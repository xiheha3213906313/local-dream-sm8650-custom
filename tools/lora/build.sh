#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
: "${HEXAGON_SDK_ROOT:?set HEXAGON_SDK_ROOT}"
: "${QNN_SDK_ROOT:?set QNN_SDK_ROOT to QAIRT 2.51.0.260929}"
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CPP="$TASK_ROOT/app/src/main/cpp"
BUILD_DIR="${LORA_BUILD_DIR:-$CPP/build/lora}"
JNI_DIR="$TASK_ROOT/app/src/main/jniLibs/arm64-v8a"
cmake -S "$CPP" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
  -DLOCAL_DREAM_LORA=ON -DQNN_SDK_ROOT="$QNN_SDK_ROOT"
cmake --build "$BUILD_DIR" --target libstable_diffusion_lora_core.so -j "${BUILD_JOBS:-2}"
cmake -S "$CPP/dit" -B "$CPP/dit/build/android" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.10 \
  '-DCMAKE_C_FLAGS=-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE' \
  '-DCMAKE_CXX_FLAGS=-march=armv8.7a+fp16+dotprod+i8mm -D_GNU_SOURCE' \
  -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT"
cmake --build "$CPP/dit/build/android" --target dit_lora_engine -j "${BUILD_JOBS:-2}"
mkdir -p "$JNI_DIR"
cp "$BUILD_DIR/bin/arm64-v8a/libstable_diffusion_lora_core.so" "$JNI_DIR/"
cp "$CPP/dit/build/android/libdit_lora_engine.so" "$JNI_DIR/"
