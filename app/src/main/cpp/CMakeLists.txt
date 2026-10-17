cmake_minimum_required(VERSION 3.22.1)
project(better_gguf LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# CPU is required. Vulkan is a separate dlopen module: never make libvulkan.so
# a DT_NEEDED dependency of the CPU runtime (some Android devices lack it).
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
if(CMAKE_HOST_WIN32)
    set(_shader_host windows-x86_64)
    set(_exe .exe)
elseif(CMAKE_HOST_APPLE)
    set(_shader_host darwin-x86_64)
else()
    set(_shader_host linux-x86_64)
endif()
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${_shader_host}/glslc${_exe}" CACHE FILEPATH "NDK shader compiler")
# Baseline ARMv8: do not require newer phone CPU instructions.
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
if(ANDROID_ABI STREQUAL "x86_64")
    # Android's x86_64 baseline does not guarantee AVX. Never load AVX kernels
    # unconditionally just because the build machine has those instructions.
    foreach(_feature AVX AVX2 AVX512 FMA F16C BMI2)
        set(GGML_${_feature} OFF CACHE BOOL "" FORCE)
    endforeach()
endif()
add_compile_options(-O3)
add_link_options("-Wl,-z,max-page-size=16384")
include(FetchContent)
# The NDK ships Vulkan C headers, but not the C++ Vulkan-Hpp headers used by ggml.
FetchContent_Declare(vulkan_headers
    URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/v1.4.357
    URL_HASH SHA256=7dc0dbcf1d49dd3d7da3761c251c6097dfbaac475321a4a8a99269d3d5abecdc
)
FetchContent_MakeAvailable(vulkan_headers)
set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "Pinned Vulkan headers" FORCE)
# SPIR-V enums are headers only. Expose the fetched target to upstream's
# find_package without requiring a machine-wide Vulkan SDK installation.
set(SPIRV_HEADERS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_HEADERS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(spirv_headers
    URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/refs/tags/vulkan-sdk-1.4.357.0
    URL_HASH SHA256=4d703067a7e06331ccb37bdfed3f9b7879cc61969a2689ae95c95db34a47ff07
)
FetchContent_MakeAvailable(spirv_headers)
file(WRITE "${spirv_headers_BINARY_DIR}/SPIRV-HeadersConfig.cmake"
    "if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\n  message(FATAL_ERROR \"Fetch SPIRV-Headers before llama.cpp\")\nendif()\n")
set(SPIRV-Headers_DIR "${spirv_headers_BINARY_DIR}" CACHE PATH "Fetched SPIR-V headers" FORCE)
# Only the OPTIONAL Vulkan module imports post-API24 Vulkan entry points.
# Link it against the API29 loader stub without raising the CPU app baseline.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_vulkan_triple aarch64-linux-android)
else()
    set(_vulkan_triple x86_64-linux-android)
endif()
set(Vulkan_LIBRARY "${ANDROID_NDK}/toolchains/llvm/prebuilt/${_shader_host}/sysroot/usr/lib/${_vulkan_triple}/29/libvulkan.so" CACHE FILEPATH "Optional Vulkan loader stub" FORCE)
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/v0.7.0
    URL_HASH SHA256=55d67acf3ad2ad747a9bbeb75f9f0e173dbcc0d1753a471038a90c819421c5e1
)
FetchContent_MakeAvailable(llama)
target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
include(cmake/AndroidCompatibility.cmake)
add_library(better_gguf SHARED gguf_jni.cpp)
target_link_libraries(better_gguf PRIVATE llama android log dl)
target_compile_options(better_gguf PRIVATE -O3)
target_link_options(better_gguf PRIVATE "-Wl,-z,max-page-size=16384")
