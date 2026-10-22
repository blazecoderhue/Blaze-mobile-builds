package router

import (
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Every OmniRoute auto/* preset Luke uses resolves, plans and is listed in /v1/models.
var omniPresets = strings.Fields(`auto/best-chaos auto/best-chat auto/best-coding auto/best-coding-fast auto/best-fast
auto/best-free auto/best-reasoning auto/best-vision auto/chaos auto/chat auto/cheap auto/claude-opus auto/claude-sonnet
auto/coding auto/fast auto/lkgp auto/multimodal auto/offline auto/pro-chat auto/pro-coding auto/pro-fast auto/pro-reasoning
auto/pro-vision auto/quality auto/reasoning auto/smart auto/subscription auto/thrifty auto/vision`)

func TestAutoPresetsAll(t *testing.T) {
	a, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) { okJSON(w, "a") })
	r, _ := newTestRouter(t, a)
	listed := map[string]bool{}
	for _, m := range r.ModelList() {
		listed[m.ID] = true
	}
	for _, id := range append(omniPresets, "auto/free", "auto/quota", "auto/coding:free", "auto/reasoning:pro", "auto/chat:reliable") {
		p, ok := autoPreset(id)
		if !ok || p.ID != id {
			t.Fatalf("%s doesn't resolve (%+v)", id, p)
		}
		pl, err := r.plan(id, false)
		if err != nil || pl.Combo != id {
			t.Fatalf("%s: plan %+v %v", id, pl, err)
		}
		if !strings.Contains(id, ":") || id == "auto/coding:free" || id == "auto/reasoning:pro" {
			if !listed[id] {
				t.Fatalf("%s missing from /v1/models", id)
			}
		}
	}
	if _, ok := autoPreset("auto/nonsense"); ok {
		t.Fatal("unknown auto/* should not resolve")
	}
	if _, ok := autoPreset("auto/coding:weird"); ok {
		t.Fatal("unknown tier should not resolve")
	}
	// The everyday one answers end to end.
	rec := post(t, r, "/v1/chat/completions", r.APIKey(), chatBody("auto/best-free", false))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"a"`) {
		t.Fatalf("auto/best-free: %d %s", rec.Code, rec.Body)
	}
}

func TestAutoPresetFilters(t *testing.T) {
	r, _ := newTestRouter(t)
	pool := []Target{{Provider: "x", Model: "llama-3.1-8b-instant"}, {Provider: "y", Model: "qwen2.5-vl-72b"}, {Provider: "z", Model: "qwen3-coder-480b"}}
	vis := r.orderPreset(AutoPreset{ID: "auto/vision", Category: "vision"}, pool, false)
	if len(vis) != 1 || vis[0].Provider != "y" {
		t.Fatalf("vision should keep only the vision model: %+v", vis)
	}
	code := r.orderPreset(AutoPreset{ID: "auto/coding", Variant: "coding", Category: "coding"}, pool, false)
	if code[0].Provider != "z" {
		t.Fatalf("coding should put the coder first: %+v", code)
	}
	fast := r.orderPreset(AutoPreset{ID: "auto/pro-coding", Variant: "coding", Category: "coding", Tier: "pro"}, pool, false)
	if fast[0].Model != "qwen3-coder-480b" {
		t.Fatalf("pro should put premium first: %+v", fast)
	}
}

func TestAutoLKGP(t *testing.T) {
	a, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) { okJSON(w, "a") })
	b, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) { okJSON(w, "b") })
	r, _ := newTestRouter(t, a, b)
	pl, _ := r.plan("auto/lkgp", false)
	second := pl.Targets[1]
	noteGood("auto/lkgp", second)
	pl, _ = r.plan("auto/lkgp", false)
	if pl.Targets[0] != second {
		t.Fatalf("lkgp should stick with the last good model: %+v", pl.Targets)
	}
}

func TestAutoChaosFirstAnswerWins(t *testing.T) {
	var slowHit atomic.Int32
	slow, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) {
		slowHit.Add(1)
		select {
		case <-time.After(3 * time.Second):
			okJSON(w, "slow")
		case <-r.Context().Done():
		}
	})
	fast, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) { okJSON(w, "fast") })
	bad, _ := fakeProvider(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) })
	r, _ := newTestRouter(t, slow, fast, bad)
	pl, _ := r.plan("auto/chaos", false)
	if !pl.Chaos || len(pl.Targets) != 3 {
		t.Fatalf("chaos panel: %+v", pl)
	}
	t0 := time.Now()
	rec := post(t, r, "/v1/chat/completions", r.APIKey(), chatBody("auto/chaos", false))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), "fast") {
		t.Fatalf("chaos: %d %s", rec.Code, rec.Body)
	}
	if time.Since(t0) > 2*time.Second {
		t.Fatal("chaos waited for the slow model")
	}
	_ = slowHit.Load() // the slow member may be cancelled before it is even reached
}
