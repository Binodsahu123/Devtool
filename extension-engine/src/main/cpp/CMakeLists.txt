cmake_minimum_required(VERSION 3.22.1)
project(swift_extension_core)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(swift_extension_core SHARED
    extension_core/isolated_world_manager.cpp
    extension_core/main_world_executor.cpp
    extension_core/extension_core_jni.cpp
)

target_include_directories(swift_extension_core PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/extension_core
)

find_library(log-lib log)
target_link_libraries(swift_extension_core ${log-lib})
