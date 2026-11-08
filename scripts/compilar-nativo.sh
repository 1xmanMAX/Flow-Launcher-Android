#!/usr/bin/env bash
# Compila el nucleo (libflowcore.so) y los plugins nativos para Android y los
# deja en android/app/src/main/jniLibs/<abi>/.
#
# Los plugins son programas, pero van como lib<nombre>.so: es la unica
# carpeta desde la que Android deja ejecutar algo (nativeLibraryDir).
#
# Requisitos: ANDROID_NDK_HOME (o ANDROID_HOME con el NDK), rustup target add
# aarch64-linux-android armv7-linux-androideabi x86_64-linux-android, y
# cargo install cargo-ndk. Y libclang (apt install libclang-dev, o la del
# NDK con LIBCLANG_PATH): con ella se generan al compilar las cabeceras de
# QuickJS, el motor de los plugins de JavaScript.
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
salida="$raiz/android/app/src/main/jniLibs"
abis="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
plugins=(flow-texto pixpin-lanzador)

if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi

declare -A triple=([arm64-v8a]=aarch64-linux-android [armeabi-v7a]=armv7-linux-androideabi [x86_64]=x86_64-linux-android [x86]=i686-linux-android)

cd "$raiz/core"
args=()
for abi in $abis; do args+=(-t "$abi"); done
# API 24 (Android 7): el minimo de la app.
cargo ndk "${args[@]}" -P 24 -o "$salida" build --release -p flow-core
for p in "${plugins[@]}"; do
  cargo ndk "${args[@]}" -P 24 build --release -p "$p"
  for abi in $abis; do
    cp "target/${triple[$abi]}/release/$p" "$salida/$abi/lib$p.so"
  done
done
ls -la "$salida"/*/
