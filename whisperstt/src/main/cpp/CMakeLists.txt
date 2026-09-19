cmake_minimum_required(VERSION 3.22.1)
project(phantom_mic_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp")
if(NOT EXISTS "${WHISPER_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp is missing at ${WHISPER_ROOT}")
endif()

add_subdirectory("${WHISPER_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/whisper-build" EXCLUDE_FROM_ALL)

add_library(whisperstt_jni SHARED whisper_jni.cpp)
set_target_properties(whisperstt_jni PROPERTIES OUTPUT_NAME whisper)
target_include_directories(whisperstt_jni PRIVATE "${WHISPER_ROOT}/include")
target_compile_options(whisperstt_jni PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)

find_library(log-lib log)
target_link_libraries(whisperstt_jni PRIVATE whisper ${log-lib} android)
