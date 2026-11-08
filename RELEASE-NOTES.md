# JuiceSSH 3.2.2 Community Build

基于原版 JuiceSSH 3.2.2（`versionCode 200`，`com.sonelli.juicessh`）的社区维护补丁集。

## 改动

- **targetSdk 29 → 35**，`versionCode` 200 → 210，`versionName` → `3.2.2-community`
- **解锁 Pro 功能门禁**（`oi0.d()` 恒真）——端口转发、桌面小部件、插件服务恢复可用
- **删除已死功能**：云同步、团队共享、书城、插件市场、Google 登录、内购校验
- **51 处 `PendingIntent` 补 `FLAG_IMMUTABLE`**（保留原 requestCode 语义）
- **动态广播注册改 `RECEIVER_NOT_EXPORTED`**
- **`ConnectionManagerService` 补 `foregroundServiceType="dataSync"`**
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

## 验证状态

- 补丁可从干净解码树零冲突重现（`diff -rq` 0 差异、`*.rej` 0 个）
- **端到端复现**：把仓库复制到干净目录、只放 `tools/*.jar`，跑
  `./build.sh <原版APK> framework-res.apk`，五阶段全过并产出可安装 APK
  （与手工构建产物大小一致 `24,800,165` 字节未签名；因新生成 keystore 故签名后哈希不同）
- 构建产物通过 `jarsigner -verify`（退出码 0；`-strict` 会因 JDK 对 v1 条目的
  `not signed in JarInputStream` 提示返回 4，这是 apksig 签名的常规现象，不是篡改证据）
- 签名块含 v1 + v2 + v3（`0x7109871a` / `0xf05368c0` / `0x42726577`）
- 最终 manifest 经二进制解析确认：`targetSdkVersion=35`、`pageSizeCompat` 为
  **enum `0x20`**（不是字符串——写成字符串会导致安装失败）

## 真机验证（Android 16 / SDK 36 / arm64）

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

参考产物（本次端到端构建，真机验证通过）：

```
size    24,914,048 bytes
sha256  53539dd334f5db39a1d0d01d644c80219cf6517f5fd04871dd2c4320a89987e4
```
