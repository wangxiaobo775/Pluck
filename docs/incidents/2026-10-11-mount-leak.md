# 2026-10-11 管理页连接失败与挂载命名空间泄漏

## 现象

08:11 软重启后，管理页提示「管理服务没在运行」，桌面出现 6 个「应用分身」图标（主用户 1 个，5 个分身各 1 个）。

## 原因

1. **重复图标**：`su 2000 pm install -r` 未指定 `--user 0`，导致应用安装至所有用户。已从分身用户卸载并修正命令。
2. **连接失败**：旧版 `service.d/clone-ui-daemon.sh` 通过 `chroot /proc/<Debian 进程>/root` 启动守护进程，复用 Termux 挂载命名空间。软重启终止 Termux 后，该命名空间内的 `/data`、`/system` 绑定挂载失效，守护进程读取到空 `/data` 目录，导致权限校验失败且日志未能写入 Android `/data/local/tmp/clone/`。

## 挂载泄漏事故（08:22–08:23）

调试初期使用 toybox `unshare -m` 配合 `mount --make-rslave /`。toybox `mount` 不支持该参数（报错 `bad /etc/fstab`），新命名空间仍为 `shared`，导致 rbind 至 rootfs 的约 1500 条挂载传播至 init、zygote 及所有应用命名空间（暴露 `chroot-distro` 路径）。

在 init 命名空间执行 `umount -l` 清理时，卸载事件传播至 Debian 环境并卸掉了 `/dev/pts` 与 `/vendor`。08:39 整机重启后恢复正常，无残留挂载泄漏。

## 架构演进

1. **过渡方案**：改用 `/data/adb/ksu/bin/busybox unshare -m --propagation slave` 创建独立 slave 挂载命名空间。
2. **最终方案（当前架构）**：移除常驻守护进程、socket 与 chroot，将后端合入单 APK（`Backend.java` / `RootMain.java`）。管理页通过 KernelSU 授予的 Root 权限执行 `su -M -c 'app_process … RootMain serve'`，经标准输入输出管道通信，页面关闭即退出。
