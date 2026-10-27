cmake_minimum_required(VERSION 3.22.1)
project(hyperscreen_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HS_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core)

add_library(hyperscreen_jni SHARED
    jni_bridge.cpp
    ${HS_CORE_DIR}/src/hs_protocol.cpp
    ${HS_CORE_DIR}/src/hs_fec.cpp
    ${HS_CORE_DIR}/src/hs_bitstream.cpp
    ${HS_CORE_DIR}/src/hs_filter.cpp
    ${HS_CORE_DIR}/src/hs_ringbuffer.cpp
)

target_include_directories(hyperscreen_jni PRIVATE
    ${HS_CORE_DIR}/include
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

# Android 15+ (API 35) 16 KB Page Size Compliance:
# Align all ELF LOAD segments in libhyperscreen_jni.so to 16 KB (16384-byte) boundaries
target_link_options(hyperscreen_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_link_libraries(hyperscreen_jni
    ${log-lib}
    ${aaudio-lib}
)
