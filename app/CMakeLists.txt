cmake_minimum_required(VERSION 3.22.1)

project("offline_voice_translator" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp)
set(INCLUDE_DIR ${SRC_DIR}/include)
set(JNI_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI})

include_directories(
    ${SRC_DIR}
    ${INCLUDE_DIR}
    ${SRC_DIR}/llama.cpp/include
    ${SRC_DIR}/llama.cpp/ggml/include
    ${SRC_DIR}/llama.cpp
    ${SRC_DIR}/llama.cpp/ggml/src
)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION
    ${JNI_LIBS_DIR}/libonnxruntime.so
)

add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES IMPORTED_LOCATION
    ${JNI_LIBS_DIR}/libsherpa-onnx-c-api.so
)

set(LLAMA_STANDALONE OFF CACHE BOOL "disable standalone" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build static llama" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "disable openmp on android" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "disable llamafile" FORCE)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_definitions(
        __ARM_NEON
        __ARM_FEATURE_DOTPROD
        __ARM_FEATURE_FP16_VECTOR_ARITHMETIC
    )
endif()

if (EXISTS ${SRC_DIR}/llama.cpp/CMakeLists.txt)
    add_subdirectory(${SRC_DIR}/llama.cpp llama_build EXCLUDE_FROM_ALL)
endif()

add_library(native-pipeline SHARED
    ${SRC_DIR}/native-pipeline.cpp
)

target_link_libraries(native-pipeline
    PRIVATE
    llama
    ggml
    sherpa-onnx-c-api
    onnxruntime
    android
    log
)
