cmake_minimum_required(VERSION 3.22.1)
project(downpour LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_definitions(
    DPOUR_NO_NATIVE_RENDER=1
    __ANDROID__
)

# Root source directory
set(SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

# Check if generated/rexglue.cmake exists (produced by rexglue codegen)
if(EXISTS "${SRC_DIR}/../generated/rexglue.cmake")
    include("${SRC_DIR}/../generated/rexglue.cmake")
endif()

set(DOWNPOUR_SOURCES
    ${SRC_DIR}/main.cpp
    ${SRC_DIR}/downpour_iso_installer.cpp
    ${SRC_DIR}/downpour_title_update_installer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/downpour_android.cpp
)

add_library(downpour SHARED ${DOWNPOUR_SOURCES})

target_include_directories(downpour PRIVATE
    ${SRC_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(downpour PRIVATE
    android
    log
)

if(COMMAND rexglue_setup_target)
    rexglue_setup_target(downpour)
endif()
