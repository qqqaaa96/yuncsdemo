# ============================================================
# Radare2 JNI 桥接
#
# 说明：
#   · libr_*.so 是预编译库（已放在 app/src/main/jniLibs/arm64-v8a/）
#   · 这里只编译一个小的 JNI 封装（radare_bridge.c）
#   · 链接时直接指向预编译的 .so
# ============================================================
cmake_minimum_required(VERSION 3.18)
project(radare_bridge C)

add_library(radare_bridge SHARED radare_bridge.c)

# 头文件（从 Radare2 源码 libr/include 拷入）
target_include_directories(radare_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/include/r_util
    ${CMAKE_SOURCE_DIR}/include/r_anal
    ${CMAKE_SOURCE_DIR}/include/r_muta
)

# 预编译库目录（由 AGP 注入）
set(R2_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 需要链接的 Radare2 库
#
# 必须与 jniLibs/arm64-v8a/ 下实际存在的 libr_*.so 一一对应。
# 列表里写了不存在的库（如 r_parse / r_bf / r_crypto），链接会失败。
# 已去重（r_util 只能出现一次）。
set(R2_LIBS
    r_core
    r_anal
    r_arch
    r_asm
    r_bin
    r_util
    r_io
    r_cons
    r_config
    r_flag
    r_reg
    r_syscall
    r_search
    r_debug
    r_esil
    r_lang
    r_magic
    r_muta
    r_socket
    r_fs
    r_bp
    r_egg
    r_main
)

foreach(lib ${R2_LIBS})
    if(EXISTS ${R2_LIB_DIR}/lib${lib}.so)
        add_library(${lib} SHARED IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${R2_LIB_DIR}/lib${lib}.so)
        target_link_libraries(radare_bridge ${lib})
    else()
        message(WARNING "lib${lib}.so not found in ${R2_LIB_DIR}, skipped")
    endif()
endforeach()

# 系统库
find_library(log-lib log)
target_link_libraries(radare_bridge ${log-lib})
