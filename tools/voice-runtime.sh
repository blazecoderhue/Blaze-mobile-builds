#!/bin/sh
# Rebuilds the My voice runtime files published on the `voice-runtime-1.13.8` release (downloaded by the app,
# never bundled in the APK). They are libsherpa-onnx-jni.so copied unchanged from the official sherpa-onnx AAR
# (onnxruntime statically linked), gzipped per ABI. The app pins the SHA-256 of the *uncompressed* .so
# (voice/myvoice/VoicePack.kt), so any gzip produces a valid download.
#   sh tools/voice-runtime.sh && gh release upload voice-runtime-1.13.8 libsherpa-onnx-jni-1.13.8-*.so.gz --clobber
set -e
V=1.13.8
curl -sSL -o s.aar "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-static-link-onnxruntime-$V.aar"
echo "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471  s.aar" | sha256sum -c
unzip -q -o s.aar 'jni/arm64-v8a/*' 'jni/armeabi-v7a/*'
for a in arm64-v8a armeabi-v7a; do gzip -9 -n -c "jni/$a/libsherpa-onnx-jni.so" > "libsherpa-onnx-jni-$V-$a.so.gz"; done
sha256sum jni/*/libsherpa-onnx-jni.so libsherpa-onnx-jni-$V-*.so.gz
# expected: arm64-v8a b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8
#           armeabi-v7a 2c0350f9c23dda60318c0d195fcc02d505f05cdbfba89492a4468d2c4276be28
