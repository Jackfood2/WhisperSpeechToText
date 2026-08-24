cmake_minimum_required(VERSION 3.22.1)
project(whisper_android C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
add_definitions(-D_GNU_SOURCE)

# Use whisper.cpp's own build system
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Build ggml and whisper as subdirectories (handles all platform quirks automatically)
option(GGML_CCACHE    "" OFF)
option(GGML_HIP       "" OFF)
option(GGML_VULKAN    "" OFF)
option(GGML_OPENCL    "" OFF)
option(GGML_CUDA      "" OFF)
option(GGML_METAL     "" OFF)
option(GGML_SYCL      "" OFF)
option(GGML_RPC       "" OFF)
option(BUILD_SHARED_LIBS "" OFF)
option(WHISPER_BUILD_TESTS    "" OFF)
option(WHISPER_BUILD_EXAMPLES "" OFF)

# Force CPU-only backend
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Add whisper.cpp as subdirectory - this builds both ggml and whisper
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

# Find Android libs
find_library(LOG_LIB log)

# Build JNI wrapper
add_library(whisper_jni SHARED
    whisper_jni.c
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    ${LOG_LIB}
)

target_compile_options(whisper_jni PRIVATE -O3)
