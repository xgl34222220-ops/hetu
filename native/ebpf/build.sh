#!/bin/sh
# Reproducible build of the three eBPF payloads pinned by .github/scripts/verify_packaged_runtime.py.
# Usage: native/ebpf/build.sh OUT_DIR   (needs clang 18 with the BPF target and Go 1.23.4)
# Layout written: OUT_DIR/hetu_match.o, OUT_DIR/arm64-v8a/hetu-bpf, OUT_DIR/x86_64/hetu-bpf
set -eu
out=${1:?usage: build.sh OUT_DIR}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out/arm64-v8a" "$out/x86_64"
out=$(cd "$out" && pwd)
${CLANG:-clang} -O2 -g0 -target bpf -Wall -Werror -c "$here/hetu_match.c" -o "$out/hetu_match.o"
cd "$here/loader"
for pair in arm64:arm64-v8a amd64:x86_64; do
	CGO_ENABLED=0 GOOS=linux GOARCH=${pair%%:*} GOFLAGS= GOTOOLCHAIN=local \
		go build -trimpath -buildvcs=false -ldflags='-s -w -buildid=' -o "$out/${pair#*:}/hetu-bpf" .
done
sha256sum "$out/hetu_match.o" "$out/arm64-v8a/hetu-bpf" "$out/x86_64/hetu-bpf"
