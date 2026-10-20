# 操作手册 (RUNBOOK)

以下命令默认在 root 环境执行。

## 注意事项

- 勿在 chroot 终端执行软重启；仅在屏幕解锁且未连接外接显示器时，通过 KernelSU 管理器执行软重启。
- 避免 `system_server` 短时间内连续崩溃，否则会触发三星 `BootChecker` 整机重启（`rescueparty_by_bootchecker`）。
- 模块已在 `system_server` 加载时，本机更新请使用 `./deploy.sh` 原地替换 `base.apk`（见 [incidents/2026-10-07.md](incidents/2026-10-07.md)）。
- 勿删除用户 150（三星安全文件夹 `profile.PRIVATE`）。
- 勿在全局挂载命名空间（`nsenter -t 1 -m`）挂载目录，勿使用 toybox `unshare -m` 隔离（其命名空间仍为 `shared`，挂载会传播至 zygote 与所有应用）。

## 构建与部署

```sh
./build.sh                        # 编译生成 build/pluck.apk
PKG=io.github.xxx.clone ./build.sh # 指定包名编译
./deploy.sh                       # 原地替换已安装的 base.apk（旧包备份至 deployed/）
./deploy.sh deployed/<文件名>.apk   # 回滚至指定版本
```

原地替换后在 KernelSU 管理器执行软重启，并检查日志：

```sh
grep -a SysGuard $(ls -t /data/adb/lspd/log/modules_*.log | head -1) | tail -40
```

正常日志包含：
- `SysGuard: real system_server`
- 每个钩子输出 `<name> hooked <Class.method> xN`，无 `MISSING`
- `alipay forward filters ensured for users [...]`

## Xposed 钩子与开关

| 钩子名称 | 目标方法 | 作用 |
|---|---|---|
| `user-limits` | `UserManagerService.isCreationOverrideEnabled`, `canAddMoreProfilesToUser` | 解除克隆用户数量上限 |
| `cross-profile` | `UserManagerService.isCrossProfileIntentFilterAccessible` | 允许克隆用户使用跨用户 Intent 过滤器 |
| `parent-launch` | `ActivityTaskManagerService.startActivityAsUser` | 分身内无法处理的跳转转发至主用户（user 0） |
| `ime-parent` | `InputMethodManagerService.resolveImeUserIdLocked` | 分身输入法用户 ID 解析为主用户 |
| `ime-settings` | `Settings.Secure.getStringForUser` | 分身读取主用户的 `default_input_method` |
| `ime-default` | `InputMethodInfoUtils.getMostApplicableDefaultIME` | 优先选择 `/data/system/sysguard/preferred-ime`、主用户输入法或第三方输入法 |
| `ime-bind` | `ContextImpl.bindServiceAsUser` | 分身绑定输入法服务时重定向至主用户实例 |
| `ime-init-once` | `InputMethodManagerService.initializeImeLocked` | 主用户键盘已连接时跳过分身重复初始化 |
| `ime-session` | `InputMethodManagerService.requestClientSessionLocked` | `mCurMethod` 为空时跳过建会话，防止空指针崩溃 |
| `broadcast-guard` | `ActivityThread$ApplicationThread.scheduleRegisteredReceiver` | 丢弃发往已死接收器的广播，防止 `DeadObjectException` 终止 `system_server` |
| `vpn-clones` | `Vpn.createUserAndRestrictedProfilesRanges` | 主用户 VPN 包含所有分身 UID 段 |
| `alipay-filters` | (启动线程), `UserManagerService.createUserInternalUnchecked` | 开机及新建分身时配置 `alipays:`/`alipay:` 跨用户转发规则 |
| `empty-system` | `ActivityThread.installContentProviders`, `installProvider` | 空 `0:system` 进程跳过 ContentProvider 安装 |
| `clone-badge` | `ApplicationPackageManager.getUserBadgedIcon`, `getUserBadgeForDensity*` | 根据 `Settings.Global.cloneui_badges` 在 One UI Home 绘制分身角标 |

开关文件位于 `/data/system/sysguard`（属主 `system:system`，标签 `system_data_file`），`system_server` 启动时读取，软重启后生效：

```sh
touch /data/system/sysguard/disable                     # 禁用全部钩子
touch /data/system/sysguard/off/parent-launch           # 禁用单个钩子
rm    /data/system/sysguard/off/parent-launch           # 启用单个钩子
echo  com.example.ime > /data/system/sysguard/preferred-ime  # 指定首选输入法包名
```

## 状态查询与命令行工具 (`cli.sh`)

```sh
cmd user list -v | grep -E 'CLONE|PRIVATE'          # 查看分身用户
./cli.sh vpn status                                  # 查看 VPN 断网保护状态
./cli.sh vpn on                                      # 开启 VPN 断网保护并释放脚本至 /data/adb
./cli.sh vpn off                                     # 关闭 VPN 断网保护
nsenter -t 1 -m -- iptables -L clone_vpn_guard -nv   # 查看 iptables 断网保护链
python3 test-killswitch.py                           # 测试分身断网保护连通性
./cli.sh trim --dry-run 15                           # 预览分身 15 可精简的系统包
./cli.sh trim                                        # 精简所有分身
./cli.sh untrim 15                                   # 恢复分身 15 已精简的系统包
tail /data/local/tmp/clone/clone-vpn.log             # 查看 VPN 守护日志
tail /data/local/tmp/clone/clone-ui.log              # 查看管理页操作日志
```

## VPN 断网保护与路由守护

VPN 脚本（`clone-lib.sh`、`clone-vpn.sh`、`service-clone-vpn.sh`）内置于 APK `assets/`：
- 开启「分身 VPN 断网保护 (Kill Switch)」或执行 `./cli.sh vpn on` 时，释放脚本至 `/data/adb/clone-lib.sh`、`/data/adb/clone-vpn.sh` 与 `/data/adb/service.d/clone-vpn.sh` 并生效。
- 关闭开关或执行 `./cli.sh vpn off` 时，清理 `iptables`/`ip6tables` 中的 `clone_vpn_guard` 链、停止守护进程，并删除 `/data/adb/service.d/clone-vpn.sh`。
- 默认识别主用户当前连接的 VPN，可在 `/data/adb/clone-vpn.conf` 配置 `VPN_PKG=包名` 或 `TUN=接口名`。

## 故障处理

| 现象 | 处理方法 |
|---|---|
| 部署模块后系统反复软重启 | 执行 `touch /data/system/sysguard/disable`，或 `./deploy.sh deployed/<旧文件>`，随后软重启 |
| 单个钩子异常 | 执行 `touch /data/system/sysguard/off/<钩子名>`，随后软重启 |
| 分身无法联网，需排查断网保护 | 关闭管理页「分身 VPN 断网保护」开关，或执行 `./cli.sh vpn off` |
| 用户空间重启后模块未注入 | 在 KernelSU 管理器执行软重启 |
