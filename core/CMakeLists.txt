cmake_minimum_required(VERSION 3.22)
project(hyperscreen_core VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(hyperscreen_core STATIC
    src/hs_protocol.cpp
    src/hs_fec.cpp
    src/hs_bitstream.cpp
    src/hs_filter.cpp
    src/hs_ringbuffer.cpp
)

target_include_directories(hyperscreen_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(hyperscreen_core PRIVATE
    -Wall -Wextra -O3
)

if(BUILD_TESTING OR CMAKE_PROJECT_NAME STREQUAL PROJECT_NAME)
    enable_testing()
    add_executable(test_hyperscreen_core tests/test_core.cpp)
    target_link_libraries(test_hyperscreen_core PRIVATE hyperscreen_core)
    add_test(NAME test_hyperscreen_core COMMAND test_hyperscreen_core)
endif()
