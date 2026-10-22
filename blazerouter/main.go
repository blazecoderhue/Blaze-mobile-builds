// BlazeRouter for Blaze on Android: the Desktop router (internal/router, a Go port of OmniRoute,
// MIT) as a small server on 127.0.0.1. Blaze starts it inside Alpine (proot), in Termux, or
// directly, with BLAZE_HOME pointing at the phone's ~/.blaze. internal/ is copied from
// Blaze-dekstop (keep the import path; sync by copying).
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/lacksonkapapahue/blaze-desktop/internal/config"
	"github.com/lacksonkapapahue/blaze-desktop/internal/router"
)

var version = "dev"

func main() {
	home := flag.String("home", "", "Blaze data dir (BLAZE_HOME), default ~/.blaze")
	flag.Parse()
	phoneDNS()
	if *home != "" {
		_ = os.Setenv("BLAZE_HOME", *home)
	}
	_ = os.MkdirAll(config.Dir(), 0o700)
	mergeKeys(os.Getenv("BLAZE_KEYS"))
	r := router.New(router.Options{Config: config.Load, Version: version})
	if err := r.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "start:", err)
		os.Exit(1)
	}
	// One line Blaze reads: where the API and dashboard are, and the client key.
	info := map[string]any{"url": r.URL(), "dashboard": r.DashboardURL(""), "api_key": r.APIKey(), "version": version}
	b, _ := json.Marshal(info)
	fmt.Println("BLAZEROUTER " + string(b))
	_ = os.WriteFile(config.Dir()+"/blazerouter.run.json", b, 0o600)
	c := make(chan os.Signal, 1)
	signal.Notify(c, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	<-c
	r.Stop()
}

// mergeKeys adds the phone's model keys (JSON {"gemini": "...", "gemini#extra": "[...]"}) to
// ~/.blaze/keys.json, the keystore's file fallback (phones have no OS keychain). Keys the
// dashboard added stay; the phone's copy wins for the same account.
func mergeKeys(raw string) {
	if raw == "" {
		return
	}
	in := map[string]string{}
	if json.Unmarshal([]byte(raw), &in) != nil || len(in) == 0 {
		return
	}
	path := filepath.Join(config.Dir(), "keys.json")
	cur := map[string]string{}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &cur)
	}
	for k, v := range in {
		if v != "" {
			cur[k] = v
		}
	}
	b, _ := json.MarshalIndent(cur, "", "  ")
	_ = os.WriteFile(path, b, 0o600)
}

// phoneDNS: Android has no /etc/resolv.conf, so Go's resolver would ask 127.0.0.1:53 and fail.
// Outside Alpine (direct or Termux) ask public resolvers instead.
func phoneDNS() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	servers := []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"}
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		d := net.Dialer{Timeout: 3 * time.Second}
		var last error
		for _, s := range servers {
			c, err := d.DialContext(ctx, network, s)
			if err == nil {
				return c, nil
			}
			last = err
		}
		return nil, last
	}}
}
