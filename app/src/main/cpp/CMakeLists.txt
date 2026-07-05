cmake_minimum_required(VERSION 3.22.1)

project(localaiggufedition)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. HARDWARE ACCELERATION: Explicitly Disable all GPU backends
set(GGML_VULKAN       OFF CACHE BOOL "Disable Vulkan" FORCE)
set(GGML_CUDA         OFF CACHE BOOL "Disable CUDA" FORCE)
set(GGML_METAL        OFF CACHE BOOL "Disable Metal" FORCE)
set(GGML_OPENCL       OFF CACHE BOOL "Disable OpenCL" FORCE)
set(GGML_HIP          OFF CACHE BOOL "Disable HIP" FORCE)
set(GGML_RPC          OFF CACHE BOOL "Disable RPC" FORCE)

# 2. OPTIMIZATIONS: Enable CPU optimizations
set(GGML_OPENMP       OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_BLAS         OFF CACHE BOOL "Disable BLAS" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "Disable KleidiAI" FORCE)

# 3. Static target configurations
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "Build static libraries" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "Disable tests" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "Disable server" FORCE)

# 4. Include paths
include_directories(llama.cpp/include)
include_directories(llama.cpp/ggml/include)

# 5. Build official repository sub-directories
add_subdirectory(llama.cpp)

# 6. Build the shared native JNI library target layer
add_library(localaiggufedition SHARED
        bridge.cpp
)

# 7. Link necessary system libraries
find_library(log-lib log)

target_link_libraries(
        localaiggufedition
        llama
        ggml
        ${log-lib}
        dl
)