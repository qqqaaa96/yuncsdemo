# ============================================================
# Radare2 JNI 桥接
#
# 头文件来源：
#   · include/         ← radare2-master/libr/include
#   · include/r_util/  ← 同上
#   · include/sdb/     ← sdb-master/include/sdb（真实的 23 个文件）
#
# 库来源：
#   · ../jniLibs/arm64-v8a/libr_*.so（从 Android Reverse APK 提取）
# ============================================================
cmake_minimum_required(VERSION 3.18)
project(radare_bridge C)

add_library(radare_bridge SHARED radare_bridge.c)

target_include_directories(radare_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

# 预编译 Radare2 库目录
set(R2_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 需要链接的库（只列实际存在的，且不重复）
set(R2_LIBS
    r_core r_anal r_arch r_asm r_bin r_util r_io r_cons
    r_config r_flag r_reg r_syscall r_search r_debug
    r_esil r_lang r_magic r_muta r_socket r_fs r_bp r_egg r_main
)

foreach(lib ${R2_LIBS})
    if(EXISTS ${R2_LIB_DIR}/lib${lib}.so)
        add_library(${lib} SHARED IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${R2_LIB_DIR}/lib${lib}.so)
        target_link_libraries(radare_bridge ${lib})
    else()
        message(WARNING "lib${lib}.so not found, skipped")
    endif()
endforeach()

find_library(log-lib log)
target_link_libraries(radare_bridge ${log-lib})

