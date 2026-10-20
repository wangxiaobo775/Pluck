# 摩托罗拉国行系统应用分身机制

逆向分析样本为官方固件（国行 razr 60 Ultra，型号 XT2551-3，代号 `leap`，渠道 `RETCN`）：

- 来源：`mirrors.lolinet.com`，`lenomola/2025/leap_retcn/official/RETCN/`
- 版本：`leap_cn-user 16 W1VL36H.59-53-11-4`
- 指纹：`motorola/leap_cn/leap:16/W1VL36H.59-53-11-4/dba88-bc3535:user/release-keys`
- 构建日期：2026-04-28

该实现基于 Android 标准 `android.os.usertype.profile.CLONE` 用户类型（非旧版联想私有 `FLAG_MULTIPLE_SPACE`），核心逻辑位于 `com.motorola.server.pm.AppCloneHelper`。

## 创建分身应用

入口为 `AppCloneHelper.addAppClone(包名, 显示名)`，仅限主用户调用：

1. 查找未安装目标应用的现有克隆用户，若存在则复用。
2. 若现有克隆用户均已安装，调用 `createProfileForUserEvenWhenDisallowed` 新建 `profile.CLONE`。
3. 调用 `startProfile` 启动用户，写入 `user_setup_complete`。
4. 隐藏克隆用户自身的启动器图标（`disableLauncherIcon`）。
5. 调用 `installExistingPackageAsUser` 将目标应用安装至该克隆用户；失败则回滚。
6. 若传入自定义显示名，通过 `AppSettingsInternal.setForUser(包名, "appclone_lable", 显示名, 用户号)` 存储（原厂键名拼写为 `lable`），并发送包变更广播刷新桌面标题。

同一应用的第 N 个分身分配至第 N 个克隆用户，显示名按用户存储在系统设置中，不修改 APK 资源。

每个克隆用户按 `/system/system/etc/sysconfig/whitelist-app-clone.xml` 安装基础白名单包：权限控制器、打印服务、WebView、GMS、软件包安装器、shell、联系人、外屏设置。

## 跨用户复用浏览器、支付与输入法

跨用户 Intent 过滤器定义于 `DefaultCrossProfileIntentFiltersUtils.getDefaultCloneProfileFilters`：
- 分身至主用户：网页、普通 `VIEW`、分享、拍照、拨号、短信彩信、选图。
- 主用户至分身：分享、网页、选图、拨号、NFC。

分身内网页与支付宝等隐式 `VIEW` 通过 `CLONE_TO_PARENT_WEB_VIEW_ACTION` / `CLONE_TO_PARENT_VIEW_ACTION` 转发至主用户。

- `ContentProviderHelper.resolveParentUserIdForCloneProfile`：目标为克隆用户时，特定内容提供者解析回父用户（user 0）。
- `AppCloneHelper.shouldReplaceWithSystemUser`：已有分身、标志位含 2、目标为系统或分身且调用方属于分身用户时，改由主用户启动。
- `ActivityStarter.attachIntentExtrasForAppClone`：跨用户启动 Activity 时，改写 Intent 中的 Uri、剪贴板与 extra 以授权目标用户读取。
- 输入法：`MotoAppCloneHelper.getSystemInputMethods` 读取主用户（user 0）的输入法列表。

## 三星 One UI 差异与本项目补齐方案

三星 One UI 保留了 `android.os.usertype.profile.CLONE` 与基础跨用户过滤器，但存在三项限制，由本项目补齐：

1. **跨用户过滤器访问控制**：三星将克隆用户的 `mCrossProfileIntentFilterAccessControl` 设为 `10`（仅限系统 uid），普通应用无法触发跨用户网页与分享过滤器。
   - **补齐方案**：SysGuard `cross-profile` 钩子放开访问限制，`parent-launch` 钩子将分身无法处理的显式/隐式跳转（含 `content://` 文件授权）转发至主用户。
2. **分身分配与系统包精简**：三星新建克隆用户默认安装约 410 个系统包。
   - **补齐方案**：「毫毛」管理端按需复用或新建分身，按白名单卸载冗余系统包，并关闭分身内 GMS 后台进程。
3. **分身图标改名与角标区分**：三星无 `appclone_lable` 设置项，所有克隆用户共用同一蓝点角标。
   - **补齐方案**：「毫毛」写入 One UI Home 单图标 `customLabel` 实现独立改名，并通过 `Settings.Global.cloneui_badges` 配合 `clone-badge` 钩子绘制各分身专属颜色与文字角标。
