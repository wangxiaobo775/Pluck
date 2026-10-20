# 毫毛 (Pluck)

> 拔毛变身，收毛归真 —— 面向三星 One UI（已在 Galaxy S26 Ultra SM-S9480 / Android 16 验证）的系统级应用多开方案。

对齐摩托罗拉系统分身体验：单应用多开，每份运行在独立的 `android.os.usertype.profile.CLONE` 用户中，共用安装包与签名，隔离数据与账号，与主用户同时运行。

本项目将 **Xposed 系统钩子（SysGuard）**、**桌面管理端与 Root 后端（毫毛）** 及 **VPN 断网保护脚本** 整合为**单 APK**。

- 操作手册：[docs/RUNBOOK.md](docs/RUNBOOK.md)
- 摩托罗拉（razr 60 Ultra / Android 16）分身机制笔记：[docs/motorola-leap.md](docs/motorola-leap.md)
- 故障记录：[docs/incidents/](docs/incidents/)

## 工程结构

| 路径 | 作用 |
|---|---|
| [`src/io/github/wangxiaobo775/pluck/`](src/io/github/wangxiaobo775/pluck/) | 注入 `system_server` 与 One UI Home 的 Xposed 钩子（SysGuard）：解除克隆用户数上限、跨用户 Intent 转发、共享主用户输入法、主用户 VPN 覆盖分身、One UI 桌面分身角标、拦截失效广播接收器崩溃 |
| [`src/com/local/cloneui/`](src/com/local/cloneui/) | 「毫毛」桌面管理界面（`MainActivity`）与 Root 后端（`RootMain` / `Backend`）：由 Root 进程执行（`su -M -c app_process … RootMain`），负责新建/删除分身、安装/卸载应用、精简系统包、修改分身角标与桌面名称、开关 GMS 及 VPN 断网保护 |
| [`assets/`](assets/) | 管理页前端（`ui.html`）、Xposed 入口声明（`xposed_init`）、VPN 断网保护与路由脚本（`clone-vpn.sh`、`clone-lib.sh`、`service-clone-vpn.sh`）及策略配置模板（`clone-ui.json`） |
| [`stubs/`](stubs/) | Xposed API 编译期 Stub（不打入 APK） |
| [`build.sh`](build.sh) / [`deploy.sh`](deploy.sh) / [`cli.sh`](cli.sh) | 构建脚本、本机热替换部署脚本与命令行维护工具（`trim` / `untrim` / `vpn`） |
| [`test-killswitch.py`](test-killswitch.py) | 分身 VPN 断网保护连通性测试脚本 |
| [`docs/`](docs/) | 运维手册（`RUNBOOK.md`）、摩托罗拉分身逆向笔记（`motorola-leap.md`）与故障记录（`incidents/`） |

## 核心特性

### 1. 对齐摩托罗拉系统分身
- **系统级克隆用户**：采用 Android `android.os.usertype.profile.CLONE`，非虚拟容器或工作资料（Managed Profile）。
- **原包原签名、独立数据目录**：通过 `pm install-existing --user <id>` 共享主用户 APK，分配独立 UID，数据隔离于 `/data/user/<用户号>/`。
- **动态用户发现与按需分配**：按 `profile.CLONE` 类型识别分身。「再开一份」优先安装至首个未安装该应用的分身；若现有分身均已安装，则新建分身、精简系统包并完成安装。
- **系统服务精简与 GMS 开关**：新建分身时卸载非核心系统服务（保留系统白名单、Overlay 与 GPU 驱动），关闭分身内 Google Play 服务以节省内存，支持按分身开启。
- **跨用户协同**：
  - 分身内打开网页（`http`/`https`）、分享、拨号，或调用支付宝、微信等分身未安装的应用时，转发至主用户处理。
  - 分身传出的 `content://` URI 附加跨用户读取授权。
  - 应用自定义深链（Deep Link）保留在分身内打开。
- **共享主用户输入法**：分身输入时绑定主用户当前输入法进程，共享词库与配置，无需在分身内安装输入法。

### 2. 增强特性
- **单 APK 集成**：集成桌面图标与 Xposed 模块设置入口（支持从 LSPosed / Vector 模块页点击「设置」进入），刷新列表时清理误装入分身的管理端自身。
- **主用户 VPN 覆盖与断网保护（Kill Switch）**：
  - SysGuard 的 `vpn-clones` 钩子将所有分身 UID 段纳入主用户系统 VPN。
  - 管理页提供「分身 VPN 断网保护 (Kill Switch)」开关：开启后释放脚本至 `/data/adb/` 并维护 `iptables`/`ip6tables` 规则，限制分身流量仅经由 `tun*` 或 `lo` 发出，防止 VPN 断开期间泄露真实 IP。
- **One UI 桌面分身角标与图标改名**：
  - 支持为每个分身设置专属颜色与文字（1–2 字），由 `clone-badge` 钩子在 One UI Home 绘制角标。
  - 写入 One UI Home（配合 Home Up「自定义图标标签」）单图标 `customLabel` 字段，为每个分身图标独立命名，兼容「仅主屏幕 (`HomeOnly.db`)」与「主屏幕和应用屏幕 (`OneUI.db`)」模式。

## 安装与使用

### 安装步骤
1. 下载并安装 `pluck.apk`。
2. 在 **LSPosed / Vector 管理器**中启用「毫毛」，勾选作用域：
   - `系统框架 (android)`（必选）
   - `One UI 主屏幕 (com.sec.android.app.launcher)`（可选，用于桌面分身角标）
   - 执行重启或软重启使钩子生效。
3. 在 **KernelSU / Magisk / APatch** 中为「毫毛」授予 **Root 权限**。
4. 打开「毫毛」，即可管理分身、添加应用、定制角标或开启 VPN 断网保护。

### 源码构建

依赖 Java 17 (`javac`)、`aapt2`、`zipalign`、`apksigner`，以及 `android.jar`（API 34+）与 `r8.jar`（默认路径 `/root/toolchain/{android-34.jar,r8.jar}`，可通过环境变量 `TOOLS`、`ANDROID_JAR`、`R8` 覆盖；可通过 `PKG=包名` 指定包名）。

```sh
./build.sh      # 输出 build/pluck.apk
./deploy.sh     # 本机热替换已安装的 base.apk（或安装至用户 0）
```

运维命令、Xposed 钩子列表与故障恢复见 [docs/RUNBOOK.md](docs/RUNBOOK.md)。

## 开源协议

[MIT License](LICENSE)
