cmake_minimum_required(VERSION 3.22.1)
project(whisper_android C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
add_definitions(-D_GNU_SOURCE)

# Use whisper.cpp's own build system
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(
    NOT EXISTS
    "${WHISPER_DIR}/CMakeLists.txt"
)
    message(
        FATAL_ERROR
        "whisper.cpp source tree not found at ${WHISPER_DIR}"
    )
endif()

if(
    NOT EXISTS
    "${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.c"
)
    message(
        FATAL_ERROR
        "whisper_jni.c not found"
    )
endif()

# Reproducible CPU-only build: FORCE cache values so stale build
# folders cannot silently enable GPU/backends or tests/examples.
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Add whisper.cpp as subdirectory - this builds both ggml and whisper
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

# Find Android libs
find_library(LOG_LIB log)

# Build JNI wrapper
add_library(whisper_jni SHARED
    whisper_jni.c
)

# 16KB page-size support (Android 15+): LOAD segments must be 16384-aligned,
# otherwise the .so fails to load on 16KB-page devices. NDK r28 + this flag.
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    ${LOG_LIB}
)

set_target_properties(
    whisper_jni
    PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

target_compile_options(
    whisper_jni
    PRIVATE
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Debug>:-O0>
    $<$<CONFIG:Debug>:-g>
    -Wall
    -Wextra
    -Wformat=2
    -Wshadow
)
