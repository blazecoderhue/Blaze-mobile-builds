package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type Dialer interface {
	DialContext(ctx context.Context, network, addr string) (net.Conn, error)
}

type Proxy struct {
	ln  net.Listener
	srv *http.Server
}

func (p *Proxy) Port() int                 { return p.ln.Addr().(*net.TCPAddr).Port }
func (p *Proxy) Close(ctx context.Context) { _ = p.srv.Shutdown(ctx) }
func (p *Proxy) Addr() string              { return p.ln.Addr().String() }

// Hop-by-hop headers a proxy must not forward.
var hop = []string{"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade"}

// ServeProxy serves an HTTP proxy (CONNECT for HTTPS/WebSockets, absolute-URI requests for plain
// HTTP) whose every outgoing connection, DNS included, goes through d.
func ServeProxy(d Dialer, listen string) (*Proxy, error) {
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return nil, err
	}
	tr := &http.Transport{
		DialContext:           d.DialContext,
		Proxy:                 nil,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
		DisableCompression:    true,
	}
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			connect(d, w, r)
			return
		}
		if !r.URL.IsAbs() {
			http.Error(w, "Blaze VPN proxy", http.StatusBadRequest)
			return
		}
		out := r.Clone(r.Context())
		out.RequestURI = ""
		for _, k := range hop {
			out.Header.Del(k)
		}
		if c := r.Header.Get("Connection"); c != "" {
			for _, k := range strings.Split(c, ",") {
				out.Header.Del(strings.TrimSpace(k))
			}
		}
		resp, err := tr.RoundTrip(out)
		if err != nil {
			http.Error(w, "VPN couldn't reach "+r.URL.Host+": "+err.Error(), http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()
		for _, k := range hop {
			resp.Header.Del(k)
		}
		for k, v := range resp.Header {
			w.Header()[k] = v
		}
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(flushWriter{w}, resp.Body)
	})
	srv := &http.Server{Handler: h, ReadHeaderTimeout: 30 * time.Second}
	go func() { _ = srv.Serve(ln) }()
	return &Proxy{ln: ln, srv: srv}, nil
}

func connect(d Dialer, w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	up, err := d.DialContext(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		http.Error(w, "VPN couldn't reach "+r.Host+": "+err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		up.Close()
		http.Error(w, "no hijack", http.StatusInternalServerError)
		return
	}
	down, buf, err := hj.Hijack()
	if err != nil {
		up.Close()
		return
	}
	_, _ = down.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n"))
	// Bytes the client sent right after CONNECT (e.g. a TLS ClientHello) may already be buffered.
	if n := buf.Reader.Buffered(); n > 0 {
		b, _ := buf.Reader.Peek(n)
		_, _ = up.Write(b)
	}
	var once sync.Once
	closeBoth := func() { once.Do(func() { up.Close(); down.Close() }) }
	go func() { _, _ = io.Copy(up, down); closeBoth() }()
	_, _ = io.Copy(down, up)
	closeBoth()
}

type flushWriter struct{ w http.ResponseWriter }

func (f flushWriter) Write(p []byte) (int, error) {
	n, err := f.w.Write(p)
	if fl, ok := f.w.(http.Flusher); ok {
		fl.Flush()
	}
	return n, err
}
