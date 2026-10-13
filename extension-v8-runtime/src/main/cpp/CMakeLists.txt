cmake_minimum_required(VERSION 3.22.1)
project("orion_extension_v8")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(ORION_REQUIRE_NATIVE_V8 "Fail when NativeScript runtime artifact is missing" ON)
option(ORION_ALLOW_V8_STUB "Allow a development-only build without native V8" OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "Orion Native V8 is Android-only")
endif()

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(m-lib m)

set(NATIVE_SCRIPT_SO "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libNativeScript.so")

if(EXISTS "${NATIVE_SCRIPT_SO}")
    add_library(NativeScript SHARED IMPORTED)
    set_target_properties(NativeScript PROPERTIES IMPORTED_LOCATION "${NATIVE_SCRIPT_SO}")
    set(V8_LIB_TARGET NativeScript)
    add_compile_definitions(ORION_HAS_V8=1)
    message(STATUS "[CMake] Using NativeScript precompiled V8 runtime: ${NATIVE_SCRIPT_SO}")
else()
    if(ORION_REQUIRE_NATIVE_V8 OR NOT ORION_ALLOW_V8_STUB)
        message(FATAL_ERROR "Required NativeScript runtime library is missing: ${NATIVE_SCRIPT_SO}")
    endif()
    message(WARNING "[CMake] Development-only V8 stub enabled; no production runtime is available")
    set(V8_LIB_TARGET "")
    add_compile_definitions(ORION_HAS_V8=0)
endif()

add_library(orion_extension_v8 SHARED
    v8_runtime.cpp v8_isolate_manager.cpp v8_context.cpp
    v8_value_converter.cpp
    v8_host_api.cpp v8_jni.cpp)

target_link_libraries(orion_extension_v8 ${log-lib} ${android-lib} ${dl-lib} ${m-lib} ${V8_LIB_TARGET})

