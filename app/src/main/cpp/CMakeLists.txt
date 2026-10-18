# ============================================================
# Radare2 JNI 桥接（dlsym 版）
#
# 关键设计：
#   · 不再 #include 任何 r2 头文件（它们嵌套太深、还依赖 sdb 子项目）
#   · 改为运行时 dlopen("libr_core.so") + dlsym 取函数地址
#   · 因此编译期不需要 r2 头文件，链接期也不直接链 libr_*.so
#
# 为什么可行：
#   libr_*.so 已经在 jniLibs/arm64-v8a/ 里，会被一起打进 APK；
#   运行时它们就在应用的 lib 目录下，dlopen 能直接找到。
# ============================================================
cmake_minimum_required(VERSION 3.18)
project(radare_bridge C)

add_library(radare_bridge SHARED radare_bridge.c)

# 只需要系统库：dl（dlopen/dlsym）与 log
find_library(dl-lib dl)
find_library(log-lib log)
target_link_libraries(radare_bridge ${dl-lib} ${log-lib})

