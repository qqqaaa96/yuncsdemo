// Shizuku UserService 接口。
//
// Shizuku 会让这个接口的实现运行在 shell（或 root）身份下，
// 因此通过它执行的命令天然具备 adb shell 权限。
//
// 参考 Shizuku 官方 sample（UserService）。
package com.csdemo;

interface IUserService {
    /** 以当前服务身份执行命令，返回 stdout+stderr 合并文本 */
    String exec(String command);

    /** 服务进程自身的身份信息（如 uid=2000(shell)） */
    String whoAmI();

    /** 销毁服务进程 */
    void destroy();
}
