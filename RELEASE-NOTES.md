# JuiceSSH 3.2.2 Community Build

基于原版 JuiceSSH 3.2.2（`versionCode 200`，`com.sonelli.juicessh`）的社区维护补丁集。

## 改动

- **targetSdk 29 → 35**，`versionCode` 200 → 210，`versionName` → `3.2.2-community`
- **解锁 Pro 功能门禁**（`oi0.d()` 恒真）——端口转发、桌面小部件、插件服务恢复可用
- **删除已死功能**：云同步、团队共享、书城、插件市场、Google 登录、内购校验
- **51 处 `PendingIntent` 补 `FLAG_IMMUTABLE`**（保留原 requestCode 语义）
- **动态广播注册改 `RECEIVER_NOT_EXPORTED`**
- **`ConnectionManagerService` 补 `foregroundServiceType`**
- **补 `android:exported`**（3 个有 intent-filter 却缺失的组件）
- **`android:pageSizeCompat="enabled"`**（16 KB 页设备上抑制启动警告）
- **SFTP 下载目录改 `getExternalFilesDir`**（分区存储下可写）

## 构建

见 `README.md`。产物由本地生成，本仓库不含 APK。

## 未修复

原生库 `libssh 0.9.3` / `OpenSSL 1.1.1g` 的已知漏洞（`CVE-2021-3634`、`CVE-2020-1730`、
Terrapin `CVE-2023-48795`、`CVE-2025-5372`）依然存在——重编四个 ABI 有超出补丁范围的风险。
只连接你信任的 SSH 服务器。

## 验证状态

- 补丁可从干净解码树零冲突重现（`diff -rq` 0 差异、`*.rej` 0 个）
- 构建产物通过 `jarsigner -verify -strict`
- 签名块含 v1 + v2 + v3（`0x7109871a` / `0xf05368c0`）
- 最终 manifest 经二进制解析确认：`targetSdkVersion=35`、`pageSizeCompat` 为字符串类型
- **未在真机上测试**（构建环境无可用设备）
