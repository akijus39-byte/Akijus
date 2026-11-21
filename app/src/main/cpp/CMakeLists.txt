cmake_minimum_required(VERSION 3.22.1)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Matikan target llama.cpp yang tidak perlu agar build CI cepat & hemat RAM (HP Redmi 13 8GB / runner)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp llama_build)

add_library(llama_jni SHARED native-lib.cpp)

# Header llama.cpp (antisipasi jika target llama belum mengekspor include)
target_include_directories(llama_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
)

find_library(log-lib log)

target_link_libraries(llama_jni
    llama
    ${log-lib}
)

# 16KB page-size (wajib Android 15+): NDK 27 mendukung flag ini
target_link_options(llama_jni PRIVATE "-Wl,-z,max-page-size=16384")
