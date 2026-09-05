cmake_minimum_required(VERSION 3.22.1)
project(downpour LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Root source directory
set(SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(ROOT_DIR "${SRC_DIR}/..")

if(NOT DEFINED REXGLUE_SDK_DIR)
    if(EXISTS "${ROOT_DIR}/rexglue-sdk")
        set(REXGLUE_SDK_DIR "${ROOT_DIR}/rexglue-sdk")
    elseif(EXISTS "/home/windroid/rexglue-sdk/android-arm64")
        set(REXGLUE_SDK_DIR "/home/windroid/rexglue-sdk/android-arm64")
    else()
        message(FATAL_ERROR "rexglue-sdk directory not found. Please provide -DREXGLUE_SDK_DIR=<path>")
    endif()
endif()

list(PREPEND CMAKE_FIND_ROOT_PATH "${REXGLUE_SDK_DIR}")
list(PREPEND CMAKE_PREFIX_PATH "${REXGLUE_SDK_DIR}")
set(rexglue_DIR "${REXGLUE_SDK_DIR}/lib/cmake/rexglue")

add_compile_definitions(
    DPOUR_NO_NATIVE_RENDER=1
    __ANDROID__
    SPDLOG_COMPILED_LIB
    SPDLOG_FMT_EXTERNAL
    REXGLUE_BUILD_CONFIG="Debug"
)

find_package(rexglue 0.10.0 REQUIRED CONFIG)

if(NOT DEFINED CMAKE_ANDROID_ARCH_ABI AND DEFINED ANDROID_ABI)
    set(CMAKE_ANDROID_ARCH_ABI ${ANDROID_ABI})
endif()

# AdrenoTools library for loading Turnip and custom GPU drivers
add_subdirectory(libadrenotools)

# Include generated recompiled sources
include("${ROOT_DIR}/generated/default/sources.cmake")

set(DOWNPOUR_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/downpour_android.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/downpour_driver.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/downpour_main_android.cpp
    ${SRC_DIR}/main.cpp
    ${SRC_DIR}/sha256.cpp
    ${SRC_DIR}/downpour_iso_installer.cpp
    ${SRC_DIR}/downpour_title_update_installer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/rex_app.cpp
    ${GENERATED_SOURCES}
)

add_library(downpour SHARED ${DOWNPOUR_SOURCES})

target_include_directories(downpour PRIVATE
    ${ROOT_DIR}
    ${SRC_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libadrenotools/include
    ${ROOT_DIR}/generated/default
    ${REXGLUE_SDK_DIR}/share/rexglue
)

target_link_libraries(downpour PRIVATE
    rex::runtime
    android
    log
    adrenotools
)

add_dependencies(downpour
    main_hook
    hook_impl
    file_redirect_hook
    gsl_alloc_hook
)
