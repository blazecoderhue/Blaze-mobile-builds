package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Config is a parsed wg-quick file. The app resolves Endpoint to an IP before sending it, because
// Android has no /etc/resolv.conf for Go's resolver.
type Config struct {
	PrivateKey string   `json:"private_key"` // base64
	Addresses  []string `json:"addresses"`   // "10.2.0.2/32", "fd00::2/128"
	DNS        []string `json:"dns"`
	MTU        int      `json:"mtu"`
	Peers      []Peer   `json:"peers"`
}

type Peer struct {
	PublicKey    string   `json:"public_key"`
	PresharedKey string   `json:"preshared_key,omitempty"`
	Endpoint     string   `json:"endpoint"` // ip:port
	AllowedIPs   []string `json:"allowed_ips"`
	Keepalive    int      `json:"keepalive,omitempty"`
}

type Tunnel struct {
	dev *device.Device
	net *netstack.Net
}

func (t *Tunnel) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	return t.net.DialContext(ctx, network, addr)
}

func (t *Tunnel) Close() { t.dev.Close() }

func keyHex(b64 string) (string, error) {
	k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(k) != 32 {
		return "", errors.New("a key in the config isn't a valid WireGuard key")
	}
	return hex.EncodeToString(k), nil
}

// UAPI turns the config into WireGuard's configuration protocol text.
func (c Config) UAPI() (string, error) {
	var b strings.Builder
	pk, err := keyHex(c.PrivateKey)
	if err != nil {
		return "", err
	}
	fmt.Fprintf(&b, "private_key=%s\n", pk)
	if len(c.Peers) == 0 {
		return "", errors.New("the config has no [Peer]")
	}
	for _, p := range c.Peers {
		pub, err := keyHex(p.PublicKey)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&b, "public_key=%s\n", pub)
		if p.PresharedKey != "" {
			psk, err := keyHex(p.PresharedKey)
			if err != nil {
				return "", err
			}
			fmt.Fprintf(&b, "preshared_key=%s\n", psk)
		}
		if _, err := netip.ParseAddrPort(p.Endpoint); err != nil {
			return "", fmt.Errorf("endpoint %q must be ip:port", p.Endpoint)
		}
		fmt.Fprintf(&b, "endpoint=%s\n", p.Endpoint)
		if p.Keepalive > 0 {
			fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.Keepalive)
		}
		for _, a := range p.AllowedIPs {
			if _, err := netip.ParsePrefix(strings.TrimSpace(a)); err != nil {
				return "", fmt.Errorf("allowed IP %q is invalid", a)
			}
			fmt.Fprintf(&b, "allowed_ip=%s\n", strings.TrimSpace(a))
		}
	}
	return b.String(), nil
}

func StartTunnel(c Config) (*Tunnel, error) {
	var addrs []netip.Addr
	for _, a := range c.Addresses {
		a = strings.TrimSpace(a)
		if p, err := netip.ParsePrefix(a); err == nil {
			addrs = append(addrs, p.Addr())
		} else if ip, err := netip.ParseAddr(a); err == nil {
			addrs = append(addrs, ip)
		} else {
			return nil, fmt.Errorf("address %q is invalid", a)
		}
	}
	if len(addrs) == 0 {
		return nil, errors.New("the config has no Address")
	}
	var dns []netip.Addr
	for _, d := range c.DNS {
		if ip, err := netip.ParseAddr(strings.TrimSpace(d)); err == nil {
			dns = append(dns, ip)
		}
	}
	if len(dns) == 0 {
		// No DNS in the config: resolve through the tunnel with Cloudflare, never outside it.
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	mtu := c.MTU
	if mtu <= 0 {
		mtu = 1420
	}
	uapi, err := c.UAPI()
	if err != nil {
		return nil, err
	}
	tdev, tnet, err := netstack.CreateNetTUN(addrs, dns, mtu)
	if err != nil {
		return nil, err
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return nil, err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, err
	}
	return &Tunnel{dev: dev, net: tnet}, nil
}
