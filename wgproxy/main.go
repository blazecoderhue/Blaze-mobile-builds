// wgproxy runs a WireGuard tunnel entirely in user space (no TUN device, no VPN permission) and
// serves it as an HTTP proxy on 127.0.0.1. Blaze's browser points its WebViews at that proxy, so
// only browser traffic goes through the tunnel.
//
// Protocol with the app: one JSON config line on stdin; wgproxy answers "READY <port>" on stdout
// once the proxy is listening (or "ERROR <message>" and exits). It shuts down when stdin closes,
// so it never outlives Blaze.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"time"
)

func main() {
	in := bufio.NewReader(os.Stdin)
	line, err := in.ReadBytes('\n')
	if err != nil && len(line) == 0 {
		fail("no config on stdin")
	}
	var cfg Config
	if err := json.Unmarshal(line, &cfg); err != nil {
		fail("bad config: " + err.Error())
	}
	t, err := StartTunnel(cfg)
	if err != nil {
		fail(err.Error())
	}
	p, err := ServeProxy(t, "127.0.0.1:0")
	if err != nil {
		t.Close()
		fail(err.Error())
	}
	fmt.Printf("READY %d\n", p.Port())
	// Keep running until Blaze closes our stdin (or dies).
	_, _ = io.Copy(io.Discard, in)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	p.Close(ctx)
	t.Close()
}

func fail(msg string) {
	fmt.Printf("ERROR %s\n", msg)
	os.Exit(1)
}
