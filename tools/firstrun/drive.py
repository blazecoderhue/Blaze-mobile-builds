#!/usr/bin/env python3
"""Clean-install first-run proof (0.45.23): installs the debug APK fresh N times on an emulator and drives it with
UiAutomator dumps + adb input, through the 2-step first run: sign in -> chat (starter in the composer) -> restore the
account's chats and memory -> send -> first answer. Each run is screen-recorded (adb screenrecord).

Talks only to tools/firstrun/mock_gateway.py on the host (the APK is a debug build made with
-Pblaze.testBase=http://10.0.2.2:8787): no Blaze Cloud, no model.

Usage: drive.py --apk app-debug.apk --out results/ --runs 5 --seed seed.json
"""
import argparse, json, os, re, subprocess, sys, time, urllib.request
import xml.etree.ElementTree as ET

PKG = "com.blaze.assistant"
OLD_STEPS = ["Let me help on your phone.", "All set. Say hi.", "A few things Blaze needs.", "Almost there.", "Finish setting up"]
REPLY = "MOCK-FIRST-ANSWER-OK"


def adb(*a, check=False, timeout=120):
    r = subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(a)}: {r.stdout} {r.stderr}")
    return r.stdout


def mock(path, method="GET"):
    req = urllib.request.Request("http://127.0.0.1:8787" + path, method=method, data=b"{}" if method == "POST" else None)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b"{}")


class Node:
    def __init__(self, e):
        self.text = e.get("text", ""); self.desc = e.get("content-desc", ""); self.cls = e.get("class", "")
        self.pkg = e.get("package", ""); self.focused = e.get("focused") == "true"
        m = re.findall(r"\d+", e.get("bounds", "[0,0][0,0]")); self.b = list(map(int, m)) if len(m) == 4 else [0, 0, 0, 0]

    @property
    def center(self):
        return (self.b[0] + self.b[2]) // 2, (self.b[1] + self.b[3]) // 2


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
        x = adb("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in x:
            try:
                return [Node(e) for e in ET.fromstring(x[x.index("<hierarchy"):]).iter("node")]
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def wait(pred, timeout, what, log, dismiss=True):
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump()
        if dismiss:
            handle_system(nodes, log)
        hit = pred(nodes)
        if hit:
            return hit
        time.sleep(1.5)
    raise TimeoutError(f"timed out waiting for {what}")


def handle_system(nodes, log):
    """Records any system prompt (none is expected before the first answer) and gets ANR dialogs out of the way."""
    pk = {n.pkg for n in nodes}
    if "com.android.permissioncontroller" in pk or "com.google.android.permissioncontroller" in pk:
        log["system_prompts"].append([n.text for n in nodes if n.text][:6])
        for n in nodes:
            if n.text.lower() in ("don\u2019t allow", "don't allow", "deny"):
                tap(n); return
    for n in nodes:
        if n.text in ("Wait",) and any("isn't responding" in m.text or "isn\u2019t responding" in m.text for m in nodes):
            log["anr"] += 1; tap(n); return


def tap(n):
    x, y = n.center; adb("shell", "input", "tap", str(x), str(y))


def text_in(s):
    return lambda ns: next((n for n in ns if s.lower() in (n.text + " " + n.desc).lower()), None)


def exact(s):
    return lambda ns: next((n for n in ns if n.text.strip().lower() == s.lower() or n.desc.strip().lower() == s.lower()), None)


def edits(ns):
    return [n for n in ns if n.cls.endswith("EditText") and n.pkg == PKG]


def type_into(n, s):
    tap(n); time.sleep(0.6)
    adb("shell", "input", "text", s.replace(" ", "%s").replace("'", ""))
    time.sleep(0.4)


def hide_keyboard():
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "4"); time.sleep(0.8)


def one_run(i, apk, out, seed):
    log = {"run": i, "steps": [], "system_prompts": [], "anr": 0, "old_steps_seen": [], "ok": False, "error": ""}
    t0 = time.time()
    def step(name):
        log["steps"].append({"step": name, "t": round(time.time() - t0, 1)}); print(f"  run {i}: {name} @{log['steps'][-1]['t']}s", flush=True)

    adb("uninstall", PKG, timeout=120)
    mock("/__reset", "POST")
    adb("install", apk, check=True, timeout=600)
    step("installed (fresh)")
    rec_path = f"/sdcard/firstrun-{i}.mp4"
    adb("shell", "rm", "-f", rec_path)
    rec = subprocess.Popen(["adb", "shell", "screenrecord", "--bit-rate", "3000000", "--time-limit", "175", rec_path])
    try:
        time.sleep(1.5)
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", timeout=180)
        step("launched")
        # Step 1 of 2: sign in.
        wait(text_in("Sign in to Blaze."), 180, "the sign-in step", log)
        step("step 1: sign-in screen")
        ns = wait(lambda ns: ns if len(edits(ns)) >= 2 else None, 60, "email + password fields", log)
        email, password = edits(ns)[0], edits(ns)[1]
        type_into(email, "ci@blazeinchue.dev")
        ns = dump(); type_into(edits(ns)[1], "ci-password-1")
        hide_keyboard()
        btn = wait(exact("Sign in"), 30, "the Sign in button", log)
        tap(btn)
        step("signed in (email + password, mock account)")
        # Step 2 of 2: the chat, starter in the composer.
        comp = wait(lambda ns: next((n for n in edits(ns) if "What can you do for me" in n.text), None), 90, "the chat with the starter in the composer", log)
        step("step 2: chat, starter ready in the composer")
        # Fresh install restores: the account's copy comes back with the sync passphrase.
        wait(text_in("Bring back your chats and memory"), 90, "the restore card", log)
        step("restore card shown")
        ns = dump()
        field = next((n for n in edits(ns) if "What can you do for me" not in n.text), None)
        if field is None:
            raise RuntimeError("no passphrase field")
        type_into(field, seed["passphrase"])
        hide_keyboard()
        tap(wait(exact("Restore"), 30, "Restore", log))
        wait(text_in("CI-HISTORY-OK"), 120, "the restored chat history", log)
        step("history restored from the account (chats + memory)")
        hide_keyboard()
        send = wait(exact("Send"), 30, "the Send button", log)
        tap(send)
        step("starter sent")
        wait(text_in(REPLY), 120, "the first answer", log)
        step("first answer shown")
        time.sleep(2.5)
        ns = dump()
        all_text = " ".join(n.text for n in ns)
        log["old_steps_seen"] = [s for s in OLD_STEPS if s in all_text]
        log["ok"] = not log["old_steps_seen"] and not log["system_prompts"]
        if not log["ok"]:
            log["error"] = "old steps or system prompts appeared"
    except Exception as e:  # noqa
        log["error"] = f"{type(e).__name__}: {e}"
    finally:
        adb("shell", "screencap", "-p", f"/sdcard/firstrun-{i}.png")
        adb("pull", f"/sdcard/firstrun-{i}.png", os.path.join(out, f"firstrun-{i}-end.png"))
        adb("shell", "pkill", "-INT", "screenrecord")
        try:
            rec.wait(timeout=20)
        except Exception:
            rec.kill()
        time.sleep(2)
        adb("pull", rec_path, os.path.join(out, f"firstrun-{i}.mp4"), timeout=120)
        open(os.path.join(out, f"firstrun-{i}-ui.xml"), "w").write(adb("exec-out", "cat", "/sdcard/ui.xml"))
        try:
            json.dump(mock("/__log"), open(os.path.join(out, f"firstrun-{i}-mock.json"), "w"), indent=1)
        except Exception:
            pass
        adb("logcat", "-d", "-t", "3000", timeout=60)
        open(os.path.join(out, f"firstrun-{i}-logcat.txt"), "w").write(adb("logcat", "-d", "-t", "4000", timeout=60))
        adb("logcat", "-c")
        log["seconds"] = round(time.time() - t0, 1)
    return log


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--runs", type=int, default=5); ap.add_argument("--seed", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    seed = json.load(open(a.seed))
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    results = []
    for i in range(1, a.runs + 1):
        print(f"== run {i}/{a.runs}", flush=True)
        results.append(one_run(i, a.apk, a.out, seed))
        print(json.dumps(results[-1]), flush=True)
    ok = sum(1 for r in results if r["ok"])
    summary = {"runs": a.runs, "passed": ok, "results": results,
               "first_run_steps": ["sign in", "chat with the starter ready (first answer)"]}
    json.dump(summary, open(os.path.join(a.out, "summary.json"), "w"), indent=1)
    lines = [f"FIRSTRUN clean installs: {ok}/{a.runs} passed"]
    for r in results:
        lines.append(f"run {r['run']}: {'PASS' if r['ok'] else 'FAIL'} {r['seconds']}s " + " > ".join(s['step'] for s in r['steps']) + (f" ERROR {r['error']}" if r['error'] else ""))
    open(os.path.join(a.out, "summary.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    sys.exit(0 if ok == a.runs else 1)


if __name__ == "__main__":
    main()
