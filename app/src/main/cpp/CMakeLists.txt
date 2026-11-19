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

# ============================================================
# Capstone 反汇编引擎
#
# 为什么用 FetchContent 而不是预编译 .so：
#   · 手写预编译库需要本地 NDK 环境，而我们没有；
#   · CI 环境有完整 NDK，拉源码编译最可靠。
#
# 使用 next 分支（Capstone 5.x 开发线）：
#   4.x 对 ARM64 的部分指令解码有误（如 movz/nop 的别名识别）。
#
# 只编译需要的 4 个架构，其余一律关掉，控制在体积。
# ============================================================
include(FetchContent)

set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM_SUPPORT        OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT      ON  CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT        ON  CACHE BOOL "" FORCE)
set(CAPSTONE_MIPS_SUPPORT       OFF CACHE BOOL "" FORCE)
set(CAPSTONE_PPC_SUPPORT        OFF CACHE BOOL "" FORCE)
set(CAPSTONE_SPARC_SUPPORT      OFF CACHE BOOL "" FORCE)
set(CAPSTONE_SYSZ_SUPPORT       OFF CACHE BOOL "" FORCE)
set(CAPSTONE_XCORE_SUPPORT      OFF CACHE BOOL "" FORCE)
set(CAPSTONE_M68K_SUPPORT       OFF CACHE BOOL "" FORCE)
set(CAPSTONE_TMS320C64X_SUPPORT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_M680X_SUPPORT      OFF CACHE BOOL "" FORCE)
set(CAPSTONE_EVM_SUPPORT        OFF CACHE BOOL "" FORCE)
set(CAPSTONE_WASM_SUPPORT       OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BPF_SUPPORT        OFF CACHE BOOL "" FORCE)
set(CAPSTONE_RISCV_SUPPORT      OFF CACHE BOOL "" FORCE)
set(CAPSTONE_SH_SUPPORT         OFF CACHE BOOL "" FORCE)
set(CAPSTONE_TRICORE_SUPPORT    OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL       OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_SHARED       OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    capstone
    GIT_REPOSITORY https://github.com/capstone-engine/capstone.git
    GIT_TAG        next
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(capstone)

add_library(capstone_bridge SHARED capstone_bridge.c)

target_link_libraries(capstone_bridge capstone-static ${log-lib})

# Capstone 头文件目录由 FetchContent 自动导出
# （target_include_directories 已在 capstone 目标里设置，链接后自动继承）

