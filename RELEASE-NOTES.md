# JuiceSSH 3.2.2 Community Build

基于原版 JuiceSSH 3.2.2（`versionCode 200`，`com.sonelli.juicessh`）的社区维护补丁集。

当前版本：`3.2.2-community.4`（`versionCode 213`，`targetSdk 36`）

## 本版（community.4 / 213）相对上一版的改动

- **arm64-v8a 的 `libssh` 从 0.9.3 升到 0.11.3、`OpenSSL` 从 1.1.1g 升到 3.5.4**，
  两者都用 NDK r29 重新交叉编译，`p_align` 从 4096 变成 **16384**。原库带
  `CVE-2021-3634`、`CVE-2020-1730`、Terrapin `CVE-2023-48795` 与
  `CVE-2025-5372`（OpenSSL < 3.0），且 16 KB 页设备只能靠兼容模式跑；这一版
  arm64 上两个问题一起解决。
- **换了实现但不改接口**：JuiceSSH 用 JNA 按普通 C 符号名绑定，不依赖 JNI 导出名。
  旧库 424 个导出符号新库全有（新库 901 个），smali 里实际调用的 66 个方法名零缺失；
  `ssh_channel_callbacks_struct` / `sftp_attributes_struct` 的字段顺序与 0.11.3 一致，
  新增的两个回调在结构体尾部、App 按 `size` 初始化，因而不受影响。
- **删掉 arm64-v8a 的 `libssl.so`**：没有任何库 `NEEDED` 它，省 786 KB。
- **删掉 `FOREGROUND_SERVICE_DATA_SYNC` 权限**：前台服务类型已换成 `specialUse`，
  这条权限不再对应任何声明，属最小权限清理。
- **`libssh` 上游一处 bionic 兼容缺陷**：`src/misc.c:324` 用 `S_IWRITE`（Windows 写法）
  编不过，改为等价的 `S_IWUSR`。编译选项只额外加了 `-Wl,-z,max-page-size=16384`。
- `versionCode` → 213，`versionName` → `3.2.2-community.4`。

代价：arm64-v8a 这两个库从 3.9 MB 涨到 6.5 MB，APK 净增约 0.97 MB。libssh 0.11.3 与
OpenSSL 3 的默认算法列表比旧版更严，只支持 `ssh-rsa`/`3des-cbc`/`group1-sha1`/
Blowfish/CAST/RC4 的老服务器可能连不上——这是安全改进，但对老内网设备是行为变化。

复现步骤、ABI 核对与风险见 `native/README.md`。

## 上一版（community.3 / 212）相对 community.2 的改动

- **凭据库加密升级**：`qg0`（原 `AES.java`）从 `PBKDF2WithHmacSHA1` **1000 轮** +
  `AES/CBC/PKCS5Padding`，改为 `PBKDF2WithHmacSHA256` **210000 轮** + `AES-256-GCM`
  （128 位 tag，AAD 固定 `"v2"`，防降级）。写入格式变成
  `v2#base64(salt)#base64(iv)#base64(ct+tag)`；旧格式 `salt#iv#ct` 仍然可读，所以升级后
  已存的密码、私钥、团队密钥照常能解开。任何新写入或编辑都会转成 v2。
- **不再让 `org.apache.http.legacy` 承担主机校验**：新增 `com.sonelli.ipaddr`
  （纯 Java 手写 IPv4/IPv6 解析，不触发 DNS），替换 `Connection.isValidHostname()` 与
  EC2 代码中的 `InetAddressUtils` 调用。
- `versionCode` → 212，`versionName` → `3.2.2-community.3`。

代价：210000 轮 PBKDF2 在手机上约 0.1–0.3 秒，只在解密单条凭据时发生
（打开连接、编辑身份），不在列表渲染路径上。

实现方式：`javasrc/com/sonelli/{qg0,ipaddr}.java` 用 javac 编译、d8 转 dex、apktool 自带的
baksmali 反汇编，再把 smali 放回解码树。`javasrc/VaultTest.java` 的 23 项断言（往返、
旧格式兼容、篡改检测、错误口令、格式校验、IPv4/IPv6 判定）在生成 smali 之前必须全部通过。

## 上一版（community.2 / 211）相对 community / 210 的改动

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

其余 ABI（`armeabi-v7a` / `x86` / `x86_64`）的 `libssh 0.9.3` / `OpenSSL 1.1.1g` 已知漏洞
（`CVE-2021-3634`、`CVE-2020-1730`、Terrapin `CVE-2023-48795`、`CVE-2025-5372`）依然
存在——现代设备基本都是 arm64，为它们重编只会再加约 4 MB。只连接你信任的 SSH 服务器。

**telnet 不可用**：`libcom_sonelli_telnet.so` 同样是 32 位 ARM，系统里没有可替代的客户端。

**16 KB 页对齐**：arm64-v8a 的 `libcom.sonelli.libssh.so` 与 `libcrypto.so` 已改成
`p_align=16384`，但其余 36 个 ELF（含 `libjnidispatch.so` 之外的 mosh/telnet/bash、
以及另外三个 ABI）仍是 4096。`pageSizeCompat="enabled"` 只是抑制警告，不是真正的兼容；
剩下的要重编才有意义。

**凭据库仍没有 AndroidKeyStore 托管**：v2 把 KDF 和 AEAD 换掉了，但密钥依旧由用户口令
派生，口令弱则库弱；同一份数据库换设备仍可解开（这是刻意的，便于迁移）。默认账户哈希
`MD5("New User")` 也仍是全网常量。要上 Keystore 需重做密钥生命周期，属于产品级决定。

## 验证状态

本版（community.4）验证：

- 补丁可从干净解码树零冲突重现（`diff -rq` 0 差异，无 `.rej`）；补丁只含文本，
  原生库由 `native/arm64-v8a/` 复制，`dec.orig + patch + native == dec` 已逐文件核对
- 端到端：`build.sh <原版APK> framework-res.apk` 五阶段全过，产物可被 aapt2 解析
- `aapt2 dump badging`：`versionCode=213`、`targetSdkVersion=36`、
  `compileSdkVersion=36`，权限表含 `FOREGROUND_SERVICE_SPECIAL_USE` 与
  `POST_NOTIFICATIONS`，不再含存储/快捷方式权限
- 反编译产物核对：`MainActivity` 的权限申请、`ConnectionManagerService` 的渠道重建、
  `qi0` 的空构造器都已编译进 dex；`qg0` 含 `PBKDF2WithHmacSHA256` 与
  `AES/GCM/NoPadding`，旧 KDF 分支仍在
- 原生库：从 APK 解包后 `llvm-readelf` 复核 `p_align=0x4000`、
  `NEEDED` 正确、arm64 下无 `libssl.so`；旧库 424 个导出符号与新库比对无函数缺失，
  smali 实际调用的 66 个方法名零缺失
- 签名：v1+v2+v3，证书 SHA-256
  `01:EE:3E:87:E8:D9:1A:8E:81:5F:CF:13:C7:CD:04:3C:26:17:62:8F:DD:59:C5:D3:E7:0B:4F:62:A0:D9:17:E5`
  ——与最初社区构建同一把密钥，可直接覆盖升级，无需卸载

**未做真机验证**：本次构建时没有可用的 Android 设备（`adb devices` 为空），
通知、前台服务、导出性这些改动只有静态与构建级证据。**重建的 libssh/OpenSSL 尤其需要
一次真实登录**：符号与结构体只做了静态核对，握手、PTY、SFTP、端口转发、agent 转发
都还没有跑过。请在装好后按 README 的「回归检查清单」逐项确认。

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

本版参考产物（targetSdk 36，含重建原生库）：

```
size    25,884,812 bytes
sha256  a8341505347670bf48f8131c17eee2e2f9f0451fa4aa57b2b549a8fede59333f
```

上一版参考产物（community.3，纯 Java 补丁）：

```
size    24,914,129 bytes
sha256  5ba0d3b381db61d956eb3cecba6f9938f12abc502b619d7c4c0ecbb0942095ad
```
