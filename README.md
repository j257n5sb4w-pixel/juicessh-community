# JuiceSSH Community Build

JuiceSSH 3.2.2 的社区维护版本。原开发者 Sonelli Ltd. 已停更多年，云端后端 `api.sonelli.com`
已无 DNS 解析，应用早已从 Google Play 下架。本仓库提供**补丁与构建脚本**，让你用自己的
原版 APK 在本地重新打包出一个能装进现代 Android 的版本。

> **本仓库不包含任何 APK 文件。** 也不包含原版 APK 的反编译产物。你需要自备一份
> `JuiceSSH+-+SSH+Client_3.2.2_APKPure.apk`（或任何 3.2.2 / versionCode 200 的正版副本）。

## 为什么需要这个

原版的四个致命问题：

| 问题 | 后果 |
| --- | --- |
| `targetSdkVersion=29` | 运行在现代 Android 上会走各种兼容模式；部分机型行为异常 |
| 45+ 处 `PendingIntent` 使用 `flags=0` | targetSdk ≥ 31 时直接抛 `IllegalArgumentException`，通知/快捷方式全崩 |
| 动态注册非系统广播未声明导出性 | targetSdk ≥ 33 时抛异常 |
| 后端 `api.sonelli.com` 已死 | 云同步 / 团队共享 / 插件市场 / 书城 / Pro 内购 / Google 登录 全部永久失效 |

最后一个问题最要命：**Pro 功能检查永远返回 false**，于是端口转发、桌面小部件、
插件服务这些 SSH 客户端的核心能力被永久锁死——而解锁它们的唯一途径（内购）
也跟着后端一起死了。

## 这个补丁做了什么

### 1. 解锁 Pro 功能

`Lcom/sonelli/oi0;->d(Ljava/lang/Object;)Z` 是全部 18 处 Pro 门禁的唯一汇聚点。
补丁把它改成恒返回 `true`。这是**归还你已付费购买的东西**——原版判定 Pro 依赖
一个由服务端私钥签名的 `User.signature`，那个私钥随着公司一起消失，任何认真付费的
用户都再也拿不到它了。

如果你当初确实买过 Pro：这个改动只是恢复了你的使用权。如果你没买过：这是原作者
弃养软件之后的既成事实，请自行判断。

### 2. 删除已死功能

- `ApplicationClass.onCreate()` 里的 Google Analytics 初始化、Google Sign-In 配置、
  两个每 24 小时跑一次的 `JobScheduler` 任务（`CloudSync` / `Ec2Link`）全部移除。
  它们只会对着 NXDOMAIN 打请求然后失败。
- 主界面不再渲染「解锁 Pro」「云同步」「团队共享」「书城」卡片。
- `MainActivity.p()` 置空，首启不再向 `api.sonelli.com/bookclub` 发请求。
- `MainActivity.g()` 里指向已死 Activity 的分支改为空操作。

### 3. Android 14/15/16 兼容

- `targetSdkVersion` 29 → **35**，`versionCode` 200 → 210，`versionName` → `3.2.2-community`
- 51 处 `PendingIntent` 全部补上 `FLAG_IMMUTABLE`（`0x4000000`），并保持原 requestCode
  不变（新增独立寄存器，避免破坏 PendingIntent 的去重语义）
- `lh.smali` 里对 `com.google.analytics.RADIO_POWERED` 的动态广播注册改为
  `RECEIVER_NOT_EXPORTED`
- `ConnectionManagerService` 补 `android:foregroundServiceType="dataSync"`
- 新增 `FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS` 权限
- `MainActivity` / `PushNotificationReceiver` / `ConnectionWidgetProvider` 补
  `android:exported`（targetSdk ≥ 31 时有 intent-filter 却缺这个属性会导致装不上）
- `android:pageSizeCompat="enabled"` —— 16 KB 页设备上抑制每次启动的兼容性警告弹窗。

#### 两个必须知道的坑

**`foregroundServiceType` 不能写 `connectedDevice`。** 真机上会崩：

```
java.lang.SecurityException: Starting FGS with type connectedDevice ...
requires permissions: [android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE]
and any of [BLUETOOTH_CONNECT, CHANGE_NETWORK_STATE, NFC, USB Device, ...]
```

SSH 客户端不需要蓝牙/USB，所以只用 `dataSync`。

**`pageSizeCompat` 必须用 Android 14+ 的 framework-res.apk 编译。**
它在 AOSP 里是 **enum**（`enabled=0x20`），不是字符串。apktool 自带的
`prebuilt/android-framework.jar` 是 API 29，根本没有这个属性定义，离线编译只会
产出裸整数；更糟的是若手工把它改写成 `TYPE_STRING`，安装时会直接失败：

```
INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: Failed to read manifest ...: For input string: "enabled"
```

正确做法只有一个：把真机上的框架拉下来注册给 apktool。

```bash
adb pull //system/framework/framework-res.apk ./framework-res.apk
java -jar tools/apktool.jar if framework-res.apk
```

`build.sh` 的 `[2/5]` 步骤做的就是这件事，且框架未提供时会打印警告。
`scripts/repack_manifest.py` 是早期走弯路时的产物，**已弃用，不要调用**。

### 4. 存储路径与本地 shell

`yi0`（SFTP 下载控制器）原本把目标目录写死为公共 `Downloads`。改为
`Context.getExternalFilesDir(null)`，在分区存储下依然可写。

> 下载的文件因此落在 `Android/data/com.sonelli.juicessh/files/` 下。

本地终端原本启动包内的 `libcom_sonelli_bash.so`。这个 .so 在**每一个 ABI 目录里
都是 32 位 ARM**（原版就如此，不是构建造成的），而现代设备普遍是纯 64 位
（`abilist32` 为空），于是启动即 `exit code 255` + `EIO`，终端闪退。

补丁把 `ci0.u()` 改为返回 `/system/bin/sh`、`ci0.t()` 改为 `{"-i", NULL, NULL}`，
即改用系统自带的 64 位 shell。副作用是 bash 特性（`~/.bashrc`、补全脚本）没有了，
换来的是本地终端真能用。

**telnet 仍然不可用。** `libcom_sonelli_telnet.so` 同样是 32 位，而 Android 系统里
没有可替代的 telnet 客户端，除非自行用 NDK 交叉编译一个 aarch64 版塞进去。

## 没有做的事（以及为什么）

- **升级原生库**：`libssh 0.9.3`、`OpenSSL 1.1.1g`、`bash 4.3.27` 需要 NDK 重新交叉编译
  四个 ABI，还要适配 JNA 绑定的函数签名变化。没有真机与完整测试矩阵的情况下动这些，
  比留着已知 CVE 更危险。
- **替换 androidx / Firebase**：那是源码级重构，需要原始 Java 源码，而这里只有 smali。
- **移除 Firebase / Crashlytics**：它们的初始化在清单里由 `ContentProvider` 自动完成，
  摘除组件会牵连 `FirebaseInitProvider` 的启动顺序。留着只是不发数据（因为
  `ApplicationClass` 已经不再初始化它们），风险可控。

**仍然存在的已知漏洞**：`libssh 0.9.3` 命中 `CVE-2021-3634`（恶意 SSH 服务端可致客户端
内存破坏）、`CVE-2020-1730`、Terrapin `CVE-2023-48795`、`CVE-2025-5372`（OpenSSL < 3.0）。
只连接你信任的服务器。

## 怎么构建

需要：**JDK 17**、`apktool.jar` 3.x、`apksig.jar`、`patch`，以及**一台 Android 14+ 设备**
（或对应的 `framework-res.apk`）。

```bash
# 1. 准备工具
mkdir -p tools
# apktool:      https://github.com/iBotPeaches/Apktool/releases
# apksig:       https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.0.0/apksig-9.0.0.jar
#               （Maven Central 上没有，只有 Google Maven）

# 2. 拉真机框架（必需，否则装不上）
adb pull //system/framework/framework-res.apk ./framework-res.apk

# 3. 构建
./build.sh /path/to/JuiceSSH+-+SSH+Client_3.2.2_APKPure.apk ./framework-res.apk
```

产物在 `dist/JuiceSSH-3.2.2-community.apk`。首次构建会自动生成
`keystore/community.jks`（口令 `community`）。

Windows 上用 Git Bash 执行；`patch` 由 Git for Windows 提供。

### 已验证的参考产物

一次完整跑通的产物（Android 16 / SDK 36 / arm64 真机上安装并启动验证通过）：

```
size    24,914,048 bytes
sha256  53539dd334f5db39a1d0d01d644c80219cf6517f5fd04871dd2c4320a89987e4
```

注意：你自己构建出来的哈希不会与上面相同——签名用的是构建时自动生成的新 keystore，
证书不同则整个 APK 的字节就不同。这也意味着**你的构建产物不能覆盖安装我的**，
反之亦然。

## 安装

由于签名证书与原版不同，**不能覆盖安装**。必须先卸载原版——这会清掉本地数据库里的
主机、凭据和已知主机密钥。

```bash
adb uninstall com.sonelli.juicessh
adb install dist/JuiceSSH-3.2.2-community.apk
```

## 回归检查清单

侧载后建议手工确认（**下列前五项已在 Android 16 / XT2601-1 上实测通过**）：

- [x] 首次启动不弹「此应用不兼容 16 KB 页大小」
- [x] 新建 SSH 连接能连上（`ssh://` 链接也能唤起）
- [x] 主机密钥指纹弹出且与服务器官方指纹一致
- [x] 终端里本地 shell 可用（`/system/bin/sh`，提示符正常）
- [x] 主界面不再出现「解锁 Pro」「云同步」「团队共享」「书城」卡片
- [ ] SFTP 下载文件落在 `Android/data/com.sonelli.juicessh/files/`
- [ ] 端口转发可用（Pro 门禁已解锁）
- [ ] 通知点击能回到对应会话（`PendingIntent` 补丁生效）
- [ ] 冷启动时没有长时间卡顿（不会再有指向死域名的 10 秒超时）
- [ ] telnet 连接（**已知不可用**，见上）

## 目录结构

```
patches/0001-juicessh-community.patch   全部 smali + 清单改动（统一 diff，保留 CRLF）
scripts/Sign.java                       apksig v1+v2+v3 签名器
scripts/repack_manifest.py              已弃用，见上文「两个必须知道的坑」
build.sh                                端到端构建
```

## 许可与免责

原应用版权归 Sonelli Ltd. 所有。本仓库只包含**针对该二进制的修改补丁**，不包含
原版或修改版的可执行文件，也不构成对原作品的再分发。请在你合法持有的原版副本上
应用这些补丁。

补丁按现状提供，无任何担保。SSH 客户端是安全敏感软件，请自行审阅
`patches/0001-juicessh-community.patch` 后再使用。
