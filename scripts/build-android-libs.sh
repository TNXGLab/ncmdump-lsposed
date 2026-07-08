#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="${ROOT_DIR}/rust"
JNI_LIBS_DIR="${ROOT_DIR}/app/src/main/jniLibs"
ANDROID_API="${ANDROID_API:-24}"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_DIR="${ANDROID_NDK_HOME}"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  NDK_DIR="${ANDROID_NDK_ROOT}"
elif [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "${ANDROID_HOME}/ndk" ]]; then
  NDK_DIR="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
else
  echo "Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
  exit 1
fi

TOOLCHAIN_DIR="$(find "${NDK_DIR}/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [[ -z "${TOOLCHAIN_DIR}" ]]; then
  echo "LLVM toolchain not found under ${NDK_DIR}." >&2
  exit 1
fi

TARGETS=(
  "arm64-v8a:aarch64-linux-android:AARCH64_LINUX_ANDROID:aarch64-linux-android"
  "armeabi-v7a:armv7-linux-androideabi:ARMV7_LINUX_ANDROIDEABI:armv7a-linux-androideabi"
  "x86:i686-linux-android:I686_LINUX_ANDROID:i686-linux-android"
  "x86_64:x86_64-linux-android:X86_64_LINUX_ANDROID:x86_64-linux-android"
)

rm -rf "${JNI_LIBS_DIR}"

for item in "${TARGETS[@]}"; do
  IFS=":" read -r abi target env_target linker_prefix <<< "${item}"
  linker="${TOOLCHAIN_DIR}/bin/${linker_prefix}${ANDROID_API}-clang"
  if [[ ! -x "${linker}" ]]; then
    echo "Android linker not found: ${linker}" >&2
    exit 1
  fi

  env "CARGO_TARGET_${env_target}_LINKER=${linker}" \
    RUSTFLAGS="-C link-arg=-Wl,--gc-sections" \
    cargo build --manifest-path "${RUST_DIR}/Cargo.toml" -p ncmdump-android --release --target "${target}"
  mkdir -p "${JNI_LIBS_DIR}/${abi}"
  cp "${RUST_DIR}/target/${target}/release/libncmdump_android.so" "${JNI_LIBS_DIR}/${abi}/libncmdump_android.so"
done
