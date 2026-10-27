cmake_minimum_required(VERSION 3.22)
project(edgefit-bench-jni)

# llama.cpp is owner-acquired (gitignored) so the repo stays small and
# license-clean. See docs/bench-build.md for the exact clone command.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Clone it first:\n  git clone --depth 1 --branch v0.7.0 https://github.com/ggerganov/llama.cpp.git android/third_party/llama.cpp")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama-build)

add_library(bench-jni SHARED bench_jni.cpp)
target_link_libraries(bench-jni PRIVATE llama)
target_compile_features(bench-jni PRIVATE cxx_std_17)
