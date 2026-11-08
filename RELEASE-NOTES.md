# JuiceSSH 3.2.2 Community Build

基于原版 JuiceSSH 3.2.2（`versionCode 200`，`com.sonelli.juicessh`）的社区维护补丁集。

当前版本：`3.2.2-community.2`（`versionCode 211`，`targetSdk 36`）

## 本版（community.2 / 211）相对上一版（community / 210）的改动

上一版已经把 targetSdk 抬到 35、补完 `PendingIntent` 与 `exported`，能在 Android 16
上安装运行。但它仍然留着几处「装得上、用得不对劲」的问题，本版逐一处理：

- **通知渠道 importance=0 修正**：原版 `ConnectionManagerService.onCreate()` 用
  `new NotificationChannel("juicessh", …, 0)` 建渠道，`0` 即 `IMPORTANCE_NONE`。
  Android 8+ 上所有会话/SFTP/断开通知都被静默丢弃。改为 `IMPORTANCE_LOW`；若升级安装
  时渠道已存在且 importance 为 0，则 `deleteNotificationChannel()` 后重建（渠道的
  importance 建好之后程序无法直接修改）。
- **运行时申请 `POST_NOTIFICATIONS`**：`MainActivity` 在 `onCreate` 里按 SDK_INT ≥ 33
  检查并申请。此前 targetSdk 33+ 默认拒绝，前台服务能起但通知栏空白。
- **FGS 类型 `dataSync` → `specialUse`**：Android 15 起 `dataSync` 是限时前台服务类型，
  24 小时内累计 6 小时后系统停止服务——长时间挂机的 SSH 会话会被掐断。改用
  `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="interactive_ssh_session"`，
  并补 `FOREGROUND_SERVICE_SPECIAL_USE` 权限（普通权限，安装即授予）。
- **关闭明文流量**：`usesCleartextTraffic=false` + 新增
  `res/xml/network_security_config.xml`（仅信任系统 CA）。应用内无 WebView，SSH/SFTP
  走原始 TCP 不受影响；仅有的 `http://` 字面量都是交给外部浏览器打开的链接。
- **删除无用权限**：`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、
  `com.android.launcher.permission.INSTALL_SHORTCUT`。
- **关闭过度导出的组件**：`ThankYouForPurchasingActivity`、`ReauthenticateActivity`、
  `ChangelogActivity`、`AuthenticationMigrationActivity`、`PurchaseActivity`、
  `ManagePortForwardActivity`、`LogViewerActivity` 七个 Activity 改为
  `exported="false"`（原本任何应用都能直接拉起内购/日志界面）。
- **SFTP 控制接收器加签名权限**：`SftpPluginService$Receiver` 仍须 `exported="true"`
  （它靠隐式广播 `sftpservice://` 接收控制指令），但加上了
  `android:permission="com.sonelli.juicessh.api.v1.permission.JUICESSH_ONLY"`，
  第三方无法再伪造暂停/续传/取消。
- **删除死 intent-filter**：`MainActivity` 上指向早已不存在的
  `com.sonelli.juicessh.exception` 的过滤器。
- **彻底停用遥测**：`qi0.smali`（`Analytics.java`）构造函数不再初始化
  Google Analytics v3 与 Firebase Analytics，上报方法改为空实现，保留签名以免
  `NoSuchMethodError`。
- **targetSdk 35 → 36 / compileSdk 36**，`versionCode` 210 → 211，
  `versionName` → `3.2.2-community.2`。

## 累计改动（含上一版）

- **解锁 Pro 功能门禁**（`oi0.d()` 恒真）——端口转发、桌面小部件、插件服务恢复可用
- **删除已死功能**：云同步、团队共享、书城、插件市场、Google 登录、内购校验
- **51 处 `PendingIntent` 补 `FLAG_IMMUTABLE`**（保留原 requestCode 语义）
- **动态广播注册改 `RECEIVER_NOT_EXPORTED`**
- **补 `android:exported`**（3 个有 intent-filter 却缺失的组件）
- **`android:pageSizeCompat="enabled"`**（16 KB 页设备上抑制启动警告）
- **SFTP 下载目录改 `getExternalFilesDir`**（分区存储下可写）
- **本地终端改用 `/system/bin/sh`**（原版内置的是 32 位 bash，纯 64 位设备上必崩）

## 构建

见 `README.md`。产物由本地生成，本仓库不含 APK。

## 未修复

原生库 `libssh 0.9.3` / `OpenSSL 1.1.1g` 的已知漏洞（`CVE-2021-3634`、`CVE-2020-1730`、
Terrapin `CVE-2023-48795`、`CVE-2025-5372`）依然存在——重编四个 ABI 有超出补丁范围的风险。
只连接你信任的 SSH 服务器。

**telnet 不可用**：`libcom_sonelli_telnet.so` 同样是 32 位 ARM，系统里没有可替代的客户端。

**16 KB 页对齐**：39 个真实 ELF 里 38 个 `p_align=4096`，只有
`arm64-v8a/libjnidispatch.so` 是 65536。`pageSizeCompat="enabled"` 只是抑制警告，
不是真正的兼容。要彻底解决必须重编所有 `.so`。

**凭据加密仍是 PBKDF2-HmacSHA1 / 1000 轮 + AES-CBC**，且没有使用 AndroidKeyStore；
默认账户哈希是 `MD5("New User")` 这个全网常量。改动它需要迁移已存的密码库，属于
源码级工作，本补丁集不碰。

## 验证状态

本版（community.2）验证：

- 补丁可从干净解码树零冲突重现（`diff -rq` 0 差异，无 `.rej`）
- 端到端：`build.sh <原版APK> framework-res.apk` 五阶段全过，产物可被 aapt2 解析
- `aapt2 dump badging`：`versionCode=211`、`targetSdkVersion=36`、
  `compileSdkVersion=36`，权限表含 `FOREGROUND_SERVICE_SPECIAL_USE` 与
  `POST_NOTIFICATIONS`，不再含存储/快捷方式权限
- 反编译产物核对：`MainActivity` 的权限申请、`ConnectionManagerService` 的渠道重建、
  `qi0` 的空构造器都已编译进 dex
- 签名：v1+v2+v3，证书 SHA-256
  `01:EE:3E:87:E8:D9:1A:8E:81:5F:CF:13:C7:CD:04:3C:26:17:62:8F:DD:59:C5:D3:E7:0B:4F:62:A0:D9:17:E5`
  ——与上一版社区构建同一把密钥，可直接覆盖升级，无需卸载

**未做真机验证**：本次构建时没有可用的 Android 设备（`adb devices` 为空），
通知、前台服务、导出性这些改动只有静态与构建级证据。请在装好后按 README 的
「回归检查清单」逐项确认。

## 真机验证（上一版 community / 210，Android 16 / SDK 36 / arm64）

测试机：Motorola XT2601-1，`ro.product.cpu.abilist=arm64-v8a`（无 32 位），
`getconf PAGE_SIZE=4096`。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 安装 | ✅ | `adb install -r` → Success，`dumpsys package` 显示 targetSdk=35 |
| 启动 | ✅ | 主界面正常，仅剩 5 张卡（快速连接/常用/管理连接/设置/帮助） |
| 死功能移除 | ✅ | Pro/云同步/团队共享/书城卡片全部消失 |
| 前台服务 | ✅ | 不再抛 `SecurityException: Starting FGS with type connectedDevice` |
| 本地终端 | ✅ | 提示符 `: / $`，`ls`/`cd` 可用，无 exit 255 |
| SSH 握手 | ✅ | `Negotiated curve25519-sha256,ssh-ed25519,aes256-gcm@openssh.com` |
| 主机密钥 | ✅ | GitHub 指纹 `SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU` 逐字符一致 |
| 公钥认证 | ✅ | `Access denied for 'none'` + `SSH_AUTH_PARTIAL`（未配密钥，行为正确） |
| 16 KB 警告 | ✅ | 本机 4 KB 页，不触发；`pageSizeCompat` 编码已确认正确 |
| SFTP / 端口转发 / 通知点击 | ⚠️ 未测 | 需要外部服务器与已保存会话 |

参考产物（上一版，targetSdk 35）：

```
size    24,914,048 bytes
sha256  53539dd334f5db39a1d0d01d644c80219cf6517f5fd04871dd2c4320a89987e4
```

本版参考产物（targetSdk 36）：

```
size    24,914,129 bytes
sha256  2365ce7468e63d0b75c859b37f2eb69391555e531708323be3a587be67029cff
```
