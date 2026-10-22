package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// A real WireGuard "server" on localhost, with a web server reachable only inside the tunnel.
func startServer(t *testing.T, serverPriv, clientPub []byte, port int) func() {
	tdev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.66.0.1")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	cfg := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.66.0.2/32\n",
		hex.EncodeToString(serverPriv), port, hex.EncodeToString(clientPub))
	if err := dev.IpcSet(cfg); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.66.0.1"), Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "inside tunnel: %s from %s", r.URL.Path, r.RemoteAddr)
	})}
	go srv.Serve(ln)
	return func() { srv.Close(); dev.Close() }
}

func TestProxyThroughTunnel(t *testing.T) {
	sPriv, sPub := keypair(t)
	cPriv, cPub := keypair(t)
	port := freeUDPPort(t)
	stop := startServer(t, sPriv, cPub, port)
	defer stop()

	tun, err := StartTunnel(Config{
		PrivateKey: base64.StdEncoding.EncodeToString(cPriv),
		Addresses:  []string{"10.66.0.2/32"},
		DNS:        []string{"10.66.0.1"},
		Peers: []Peer{{
			PublicKey:  base64.StdEncoding.EncodeToString(sPub),
			Endpoint:   fmt.Sprintf("127.0.0.1:%d", port),
			AllowedIPs: []string{"0.0.0.0/0", "::/0"},
			Keepalive:  25,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer tun.Close()
	p, err := ServeProxy(tun, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close(context.Background())

	// Plain HTTP through the proxy.
	pu, _ := url.Parse("http://" + p.Addr())
	cl := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 15 * time.Second}
	resp, err := cl.Get("http://10.66.0.1/hello")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !strings.Contains(string(b), "inside tunnel: /hello from 10.66.0.2") {
		t.Fatalf("plain HTTP: %q", b)
	}

	// CONNECT (what the browser does for HTTPS), with the first request sent in the same write.
	c, err := net.Dial("tcp", p.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	fmt.Fprintf(c, "CONNECT 10.66.0.1:80 HTTP/1.1\r\nHost: 10.66.0.1:80\r\n\r\nGET /tls HTTP/1.1\r\nHost: 10.66.0.1\r\nConnection: close\r\n\r\n")
	r := bufio.NewReader(c)
	status, _ := r.ReadString('\n')
	if !strings.Contains(status, "200") {
		t.Fatalf("CONNECT status %q", status)
	}
	rest, _ := io.ReadAll(r)
	if !strings.Contains(string(rest), "inside tunnel: /tls") {
		t.Fatalf("CONNECT body %q", rest)
	}
}

func TestConfigErrors(t *testing.T) {
	_, pub := keypair(t)
	good := Config{PrivateKey: base64.StdEncoding.EncodeToString(pub), Addresses: []string{"10.0.0.2/32"},
		Peers: []Peer{{PublicKey: base64.StdEncoding.EncodeToString(pub), Endpoint: "1.2.3.4:51820", AllowedIPs: []string{"0.0.0.0/0"}}}}
	if _, err := good.UAPI(); err != nil {
		t.Fatal(err)
	}
	bad := good
	bad.PrivateKey = "nope"
	if _, err := bad.UAPI(); err == nil {
		t.Fatal("bad key accepted")
	}
	bad = good
	bad.Peers = []Peer{{PublicKey: good.Peers[0].PublicKey, Endpoint: "vpn.example.com:51820"}}
	if _, err := bad.UAPI(); err == nil {
		t.Fatal("hostname endpoint accepted")
	}
	bad = good
	bad.Peers = nil
	if _, err := bad.UAPI(); err == nil {
		t.Fatal("no peers accepted")
	}
}
