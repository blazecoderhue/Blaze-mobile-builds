#!/usr/bin/env python3
"""Mock Blaze account server + gateway for the clean-install emulator job (0.45.23).

One local HTTP server answers both the account routes (/v1/providers, /v1/auth/*, /v1/me) and the gateway routes
(/v1/runs with SSE, /v1/chat/completions, /v1/sync/*, and harmless 200s for the rest). No model is ever called:
every reply is a fixed text. The debug APK under test is built with -Pblaze.testBase=http://10.0.2.2:<port>.

Control routes (host side only): POST /__reset re-loads the sync seed and clears runs; GET /__log returns the request log.
"""
import json, sys, threading, time, uuid, argparse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

REPLY = "MOCK-FIRST-ANSWER-OK: here are three things I can do on this phone."
USER = {"id": "u_ci_1", "email": "ci@blazeinchue.dev", "name": "CI Tester", "avatar_url": ""}
TOKEN = "ci-session-token"

lock = threading.Lock()
state = {"runs": {}, "rows": {}, "seq": 0, "meta": {"set_up": False}, "log": []}
SEED = None


def reset():
    with lock:
        state["runs"].clear(); state["rows"].clear(); state["log"].clear()
        if SEED:
            state["meta"] = dict(SEED["meta"]); state["seq"] = SEED["seq"]
            for r in SEED["rows"]:
                state["rows"][r["kind"] + "|" + r["id"]] = dict(r)
        else:
            state["meta"] = {"set_up": False}; state["seq"] = 0


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw or b"{}")
        except Exception:
            return {}

    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _sse(self, frames):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for f in frames:
            self.wfile.write(f.encode()); self.wfile.flush(); time.sleep(0.05)
        self.close_connection = True

    def _note(self, method):
        with lock:
            state["log"].append(f"{time.strftime('%H:%M:%S')} {method} {self.path} auth={'y' if self.headers.get('Authorization') else 'n'}")

    # ------------------------------------------------------------------ routes
    def do_GET(self):
        self._note("GET"); u = urlparse(self.path); p = u.path; q = parse_qs(u.query)
        if p == "/__log":
            with lock:
                return self._json(200, {"log": state["log"], "rows": len(state["rows"]), "runs": len(state["runs"])})
        if p in ("/health", "/v1/health"):
            return self._json(200, {"ok": True, "version": "mock-0.45.23"})
        if p == "/v1/providers":
            return self._json(200, {"login": {"google": False, "github": False}, "connectors": {}, "web_login": False,
                                    "password_reset": False, "two_factor": False})
        if p == "/v1/me":
            return self._json(200, {"user": USER, "plan": "free"})
        if p == "/v1/models":
            return self._json(200, {"object": "list", "data": [{"id": "blaze-auto", "object": "model"}]})
        if p == "/v1/sync/meta":
            with lock:
                m = dict(state["meta"]); m.setdefault("bytes", 0)
            return self._json(200, m)
        if p == "/v1/sync/pull":
            since = int((q.get("since") or ["0"])[0]); limit = int((q.get("limit") or ["200"])[0])
            with lock:
                after = sorted([r for r in state["rows"].values() if r["seq"] > since], key=lambda r: r["seq"])
                page = after[:limit]
                epoch = state["meta"].get("epoch", "e1")
            return self._json(200, {"items": page, "next": page[-1]["seq"] if page else since, "more": len(after) > len(page), "epoch": epoch})
        if p == "/v1/runs":
            return self._json(200, {"runs": []})
        if p.startswith("/v1/runs/"):
            parts = p.split("/"); rid = parts[3]
            with lock:
                r = state["runs"].get(rid)
            if not r:
                return self._json(404, {"error": {"code": "no_run"}})
            if len(parts) > 4 and parts[4] == "stream":
                last = int(self.headers.get("Last-Event-ID") or 0)
                return self._sse(self._frames(r, None, last))
            after = int((q.get("after") or ["0"])[0])
            return self._json(200, {"status": r["status"], "events": [e for e in r["events"] if e["seq"] > after], "reply": REPLY})
        return self._json(200, {})

    def do_PUT(self):
        self._note("PUT"); b = self._body()
        if self.path.startswith("/v1/sync/meta"):
            with lock:
                state["meta"] = {"set_up": True, "salt": b.get("salt", ""), "kdf": b.get("kdf", ""), "check": b.get("check", ""), "epoch": "e-" + uuid.uuid4().hex[:6]}
            return self._json(200, {"ok": True})
        return self._json(200, {})

    def do_DELETE(self):
        self._note("DELETE")
        if self.path.startswith("/v1/runs/"):
            with lock:
                state["runs"].pop(self.path.split("/")[3].split("?")[0], None)
            return self._json(200, {"ok": True})
        return self._json(200, {"ok": True})

    def do_POST(self):
        self._note("POST"); b = self._body(); p = urlparse(self.path).path
        if p == "/__reset":
            reset(); return self._json(200, {"ok": True})
        if p in ("/v1/auth/login", "/v1/auth/signup"):
            if not b.get("email") or not b.get("password"):
                return self._json(400, {"error": "missing"})
            return self._json(200, {"token": TOKEN, "user": USER})
        if p == "/v1/runs":
            cid = b.get("client_msg_id") or uuid.uuid4().hex
            with lock:
                ex = next((r for r in state["runs"].values() if r["cid"] == cid), None)
                r = ex or self._new_run(cid)
            head = {"run_id": r["id"], "status": r["status"], "existing": ex is not None}
            return self._sse(self._frames(r, head, 0))
        if p.endswith("/cancel") and p.startswith("/v1/runs/"):
            return self._json(200, {"ok": True})
        if p == "/v1/chat/completions":
            if b.get("stream"):
                chunk = {"id": "mock", "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {"role": "assistant", "content": REPLY}, "finish_reason": None}]}
                end = {"id": "mock", "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
                return self._sse([f"data: {json.dumps(chunk)}\n\n", f"data: {json.dumps(end)}\n\n", "data: [DONE]\n\n"])
            return self._json(200, {"id": "mock", "object": "chat.completion", "choices": [{"index": 0, "message": {"role": "assistant", "content": REPLY}, "finish_reason": "stop"}],
                                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
        if p == "/v1/sync/push":
            acc, con = [], []
            with lock:
                for i in b.get("items", []):
                    k = i["kind"] + "|" + i["id"]; r = state["rows"].get(k); rev = r["rev"] if r else 0
                    if rev != i.get("base_rev", 0):
                        con.append(r if r else {"kind": i["kind"], "id": i["id"], "seq": 0, "rev": 0, "deleted": True, "blob": None, "updated_at": 0}); continue
                    state["seq"] += 1
                    blob = i.get("blob"); deleted = bool(i.get("deleted")) or blob is None
                    state["rows"][k] = {"kind": i["kind"], "id": i["id"], "seq": state["seq"], "rev": rev + 1, "deleted": deleted, "blob": None if deleted else blob, "updated_at": int(time.time() * 1000)}
                    acc.append({"kind": i["kind"], "id": i["id"], "rev": rev + 1})
            return self._json(200, {"accepted": acc, "conflicts": con, "rejected": []})
        return self._json(200, {})

    # ------------------------------------------------------------------ runs (the FakeGateway shape from CloudRunsTest)
    def _new_run(self, cid):
        rid = "run_" + uuid.uuid4().hex[:24]
        r = {"id": rid, "cid": cid, "events": [], "status": "queued"}
        def emit(t, d):
            e = dict(d); e["seq"] = len(r["events"]) + 1; e["type"] = t; r["events"].append(e)
            if t == "status": r["status"] = d["status"]
        emit("status", {"status": "queued"}); emit("status", {"status": "running"})
        emit("delta", {"step": 0, "text": REPLY})
        emit("message", {"step": 0, "message": {"role": "assistant", "content": REPLY}})
        emit("done", {"reply": REPLY, "receipt": {"intent": "task", "summary": REPLY.split(":")[0]}})
        emit("status", {"status": "done"})
        state["runs"][rid] = r
        return r

    def _frames(self, r, head, after):
        out = []
        if head is not None:
            out.append(f"event: run\ndata: {json.dumps(head)}\n\n")
        for e in r["events"]:
            if e["seq"] > after:
                out.append(f"id: {e['seq']}\nevent: {e['type']}\ndata: {json.dumps(e)}\n\n: ping\n\n")
        out.append(f"event: end\ndata: {json.dumps({'status': r['status']})}\n\n")
        return out


def main():
    global SEED
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8787); ap.add_argument("--seed", default="")
    a = ap.parse_args()
    if a.seed:
        SEED = json.load(open(a.seed))
    reset()
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), H)
    print(f"mock gateway on :{a.port} seed={'yes' if SEED else 'no'}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
