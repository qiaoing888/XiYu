cmake_minimum_required(VERSION 3.22.1)

project("xycore")
enable_language(ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set_source_files_properties(
    xy_embedded.S
    PROPERTIES
    OBJECT_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/xy_embedded.bin"
)

add_library(
    xycore
    SHARED
    xycore.cpp
    xy_math.cpp
    xy_otp.cpp
    xy_lm.cpp
    xy_embedded.S
)

target_include_directories(
    xycore
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# 剥离无用符号与死代码
target_compile_options(
    xycore
    PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    $<$<CONFIG:Release>:-O3>
    -fexceptions
    -fno-rtti
)

target_link_libraries(
    xycore
    PRIVATE
    # 纯标准 libc++，不引用复杂外部库
)

target_link_options(
    xycore
    PRIVATE
    -Wl,--gc-sections
)

# 适配 Android 15/16 64位系统 16 KB 页面对齐要求
target_link_options(
    xycore
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
