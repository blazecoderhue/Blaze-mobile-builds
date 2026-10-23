# Blaze Phone DOM bridge: page-side checks (jsdom)

The bridge script (`PhoneScripts.bridge`) is JavaScript, so the JVM tests can't run it. These checks run it on jsdom:
first message is a full model with the token; value / added / removed / cloned-node diffs; secrets masked (length only);
own `data-bzp` writes don't loop; `data-bz-secret` marking is posted; fetch counted for network idle; contiguous seq;
`__bzpB.full()` resync without the token; a second injection is a no-op; SNAPSHOT / locate share the same ids; a
page-set `data-bzp` is replaced by a fresh `eN` id.

    mkdir -p tools/phone-bridge/out && python3 tools/phone-bridge/extract.py
    NODE_PATH=<dir with jsdom>/node_modules node tools/phone-bridge/bridge_test.js
