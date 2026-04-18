# Vendored whisper.cpp source

**Upstream:** https://github.com/ggml-org/whisper.cpp
**Tag:** `v1.7.5`
**Tarball URL:** https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.5.tar.gz
**Vendored on:** 2026-04-17

## What's here

- `src/whisper.cpp`, `src/whisper-arch.h` — main whisper inference impl
- `include/whisper.h` — public API
- `ggml/` — GGML tensor lib (CPU backend only)

## What was pruned (non-CPU backends and platform glue we don't need)

- `src/coreml/`, `src/openvino/` (Apple/Intel platform glue)
- `ggml/src/ggml-blas/`, `ggml-cann/`, `ggml-cuda/`, `ggml-hip/`, `ggml-kompute/`,
  `ggml-metal/`, `ggml-musa/`, `ggml-opencl/`, `ggml-rpc/`, `ggml-sycl/`,
  `ggml-vulkan/` — non-CPU backends (Android JNI uses CPU only)

## Re-vendor procedure

```bash
cd /tmp && rm -rf whisper-vendor && mkdir whisper-vendor && cd whisper-vendor
curl -L https://github.com/ggml-org/whisper.cpp/archive/refs/tags/<TAG>.tar.gz | tar xz
# Replace existing tree in app/src/main/cpp/whisper/ with src/, include/, ggml/
# Re-prune the non-CPU backend directories listed above.
# Update this file's tag + date.
# Verify ./gradlew assembleDebug builds the JNI lib.
```

## Why not a Maven binding

Researched four candidates during Stage 2-D design (whisper-jni,
WhisperKitAndroid, WhisperCore_Android, io.github.ggerganov:whispercpp);
all were rejected for production readiness reasons (stale upstream,
abandoned, missing ABI coverage, or kotlin-only API gaps). Vendoring
the upstream source and building via NDK + CMake is the path with the
lowest "things we have to maintain forever" cost given how thoroughly
whisper.cpp's Android example is exercised by upstream CI.
