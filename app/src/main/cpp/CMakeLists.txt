# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(pilgrim-whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Android 15+ requires JNI .so files compiled with 16 KB max page size.
add_link_options("-Wl,-z,max-page-size=16384")

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

set(WHISPER_SOURCES
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_DIR}/ggml/src/gguf.cpp
    ${WHISPER_DIR}/ggml/src/ggml-opt.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
)

find_library(LOG_LIB log)

add_library(pilgrim-whisper SHARED
    ${WHISPER_SOURCES}
    whisper-jni.cpp
)

target_include_directories(pilgrim-whisper PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(pilgrim-whisper PRIVATE
    GGML_USE_CPU
)

# Per-ABI optimization flags borrowed from the upstream android example.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(pilgrim-whisper PRIVATE -march=armv8.2-a+fp16)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(pilgrim-whisper PRIVATE -mfpu=neon-vfpv4)
endif()

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(pilgrim-whisper PRIVATE
        -O3 -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections
    )
    target_link_options(pilgrim-whisper PRIVATE
        -Wl,--gc-sections -Wl,--exclude-libs,ALL
    )
endif()

target_link_libraries(pilgrim-whisper ${LOG_LIB} android)
