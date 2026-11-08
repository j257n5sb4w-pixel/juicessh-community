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

- `targetSdkVersion` 29 → **36**（Android 16），`compileSdkVersion` 29 → 36，
  `versionCode` 200 → 211，`versionName` → `3.2.2-community.2`
- 51 处 `PendingIntent` 全部补上 `FLAG_IMMUTABLE`（`0x4000000`），并保持原 requestCode
  不变（新增独立寄存器，避免破坏 PendingIntent 的去重语义）
- `lh.smali` 里对 `com.google.analytics.RADIO_POWERED` 的动态广播注册改为
  `RECEIVER_NOT_EXPORTED`（其余动态广播都是 `CONNECTIVITY_CHANGE` /
  `BATTERY_CHANGED` / GCM 这类受保护广播，系统对其豁免导出性检查）
- `ConnectionManagerService` 的 `foregroundServiceType` 由 `dataSync` 改为
  **`specialUse`**，并补 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
  android:value="interactive_ssh_session"/>` 与 `FOREGROUND_SERVICE_SPECIAL_USE` 权限。
  原因见下面「FGS 类型」一节：`dataSync` 在 Android 15+ 有 24 小时内累计 6 小时的硬上限，
  长时间挂着的 SSH 会话会被系统掐断。
- **修复通知全部被静默丢弃**：原版 `ConnectionManagerService.onCreate()` 用
  `new NotificationChannel("juicessh", …, 0)` 建渠道，importance `0` 就是
  `IMPORTANCE_NONE`——Android 8+ 上会话通知、SFTP 进度、断开提醒全部不可见。补丁改为
  `IMPORTANCE_LOW`，并且如果已存在的渠道 importance 为 0（升级安装的场景，渠道建好之后
  程序无法直接改 importance），先 `deleteNotificationChannel()` 再重建。
- **运行时申请 `POST_NOTIFICATIONS`**：targetSdk ≥ 33 时该权限默认拒绝，
  `MainActivity` 启动时检查并在需要时 `requestPermissions()`。没有这一步，前台服务能起来，
  但通知栏什么都不显示。
- `MainActivity` / `PushNotificationReceiver` / `ConnectionWidgetProvider` 补
  `android:exported`（targetSdk ≥ 31 时有 intent-filter 却缺这个属性会导致装不上）
- `android:pageSizeCompat="enabled"` —— 16 KB 页设备上抑制每次启动的兼容性警告弹窗。

### 4. 收紧攻击面

- `android:usesCleartextTraffic` 由 `true` 改为 **`false`**，并新增
  `android:networkSecurityConfig="@xml/network_security_config"`（仅信任系统 CA、
  禁止明文）。SSH/SFTP/mosh 走原始 TCP，不受该策略影响；应用内没有 WebView。
- 删除三条已无用的权限：`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、
  `com.android.launcher.permission.INSTALL_SHORTCUT`。代码里没有任何存储权限检查，
  写文件早已改成 app-specific 目录。
- 七个只由本应用启动的 Activity 从 `exported="true"` 改为 `false`：
  `ThankYouForPurchasingActivity`、`ReauthenticateActivity`、`ChangelogActivity`、
  `AuthenticationMigrationActivity`、`PurchaseActivity`、`ManagePortForwardActivity`、
  `LogViewerActivity`。原版把它们暴露给任何应用，等于允许第三方直接拉起内购/日志界面。
- 删除 `MainActivity` 上指向早已不存在的 `com.sonelli.juicessh.exception` 的
  intent-filter。
- `SftpPluginService$Receiver` 保持 `exported="true"`（它靠隐式广播
  `sftpservice://…` 接收暂停/续传/取消，改成 false 会切断自己发的广播），但加上
  `android:permission="com.sonelli.juicessh.api.v1.permission.JUICESSH_ONLY"`
  （签名级、manifest 里本就有定义），外部应用再也无法伪造 SFTP 控制指令。

### 5. 停用遥测

原版即使后端已死，仍在连接/会话事件里调用 Google Analytics v3 与 Firebase Analytics
（`qi0.smali`，jadx 名 `Analytics.java`）。补丁把构造函数里的
`GoogleAnalytics.getInstance()…` 与 `FirebaseAnalytics.getInstance()` 全部删掉，
并把上报方法 `b()` 改成空实现——不上报、不初始化、不产生网络请求，但保留类与方法签名，
避免其它调用点 `NoSuchMethodError`。

#### 两个必须知道的坑

**FGS 类型不能用 `connectedDevice`，也不该再用 `dataSync`。**

`connectedDevice` 在真机上会崩：

```
java.lang.SecurityException: Starting FGS with type connectedDevice ...
requires permissions: [android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE]
and any of [BLUETOOTH_CONNECT, CHANGE_NETWORK_STATE, NFC, USB Device, ...]
```

SSH 客户端不需要蓝牙/USB。早先退而求其次用了 `dataSync`，但从 Android 15（API 35）起
`dataSync` 属于「有时限的前台服务类型」：应用在 24 小时内累计运行满 **6 小时**后，
系统会调用 `Service.onTimeout()` 并停止服务。一个挂着不动的 SSH 会话正好会踩到这条线。
所以现在改用 `specialUse`：

```xml
<service android:name="….ConnectionManagerService"
         android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="interactive_ssh_session"/>
</service>
```

配套需要 `FOREGROUND_SERVICE_SPECIAL_USE` 权限（普通权限，安装即授予）。
`specialUse` 只受 Play 商店人工审核约束，侧载没有额外门槛，也没有 6 小时上限。

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
java -jar tools/apktool.jar if -p work/fw framework-res.apk
```

`build.sh` 的 `[1/5]` 步骤做的就是这件事（用 `-p work/fw` 把框架装在工作区里，
不碰 `%LOCALAPPDATA%pktool`），且框架未提供时会打印警告。
`scripts/repack_manifest.py` 是早期走弯路时的产物，**已弃用，不要调用**。

### 6. 凭据加密升级（v2 格式）

原实现（`AES.java`，smali 里的 `qg0`）用 `PBKDF2WithHmacSHA1` **1000 轮**派生密钥，再走
`AES/CBC/PKCS5Padding`。两个问题：1000 轮在今天等于没有（单张显卡每秒可试上百万次），
CBC 又没有认证，密文可以被比特翻转而不被发现。

现在写出去的格式是：

```
v2#base64(salt 16B)#base64(iv 12B)#base64(密文 + 16B GCM tag)
```

- KDF：`PBKDF2WithHmacSHA256`，210000 轮，256 位密钥
- 加密：`AES-256-GCM`，128 位 tag，AAD 固定为 `"v2"`，防止把新格式降级成旧格式解析
- salt / iv 全部来自 `SecureRandom`

旧格式（`salt#iv#ct` 三段）仍然可读，所以升级安装后已存的密码、私钥、团队密钥都能照常解开；
新写入或编辑会自动转成 v2。GCM 校验失败抛 `qg0$a`，与原来的失败语义一致。

实现在 `javasrc/com/sonelli/qg0.java`：先用 javac 编译、d8 转 dex、apktool 自带的 baksmali
反汇编，再把 smali 放回 `smali/com/sonelli/qg0.smali`。`javasrc/VaultTest.java` 是 JVM 侧的
23 项断言（往返、旧格式兼容、篡改检测、错误口令、格式校验、IPv4/IPv6 判定），必须在生成
smali 之前跑通。

仍然**没有**用 AndroidKeyStore：密钥依旧由用户口令派生，口令弱则库弱；但至少不再是
1000 轮 SHA1 + 无认证 CBC。上 Keystore 需要重做密钥生命周期（改口令要重新包裹），
属于产品级改动，本次没做。

### 7. 摆脱 org.apache.http.legacy

`Connection.isValidHostname()` 和 EC2 代码用 `org.apache.http.conn.util.InetAddressUtils`
判断 IPv4/IPv6。这个库自 Android 9 起废弃，官方计划移除。新增 `com.sonelli.ipaddr`
（纯 Java 手写解析，**不触发 DNS**），替换了两处调用。`<uses-library>` 仍然保留，因为
云端/EC2 死代码里还有别的 `org.apache.http` 引用，摘掉会让那些路径抛 `NoClassDefFoundError`。

### 8. 存储路径与本地 shell

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
- **把凭据密钥托管到 AndroidKeyStore**：需要重做密钥生命周期（改口令要重新包裹），
  会改变“同一份数据库换设备也能解”的行为，属于产品级决策，见第 6 节。
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

本仓库对应的产物（`3.2.2-community.3`，targetSdk 36，`versionCode 212`）：

```
size    24,914,129 bytes
sha256  5ba0d3b381db61d956eb3cecba6f9938f12abc502b619d7c4c0ecbb0942095ad
签名证书 SHA-256
        01:EE:3E:87:E8:D9:1A:8E:81:5F:CF:13:C7:CD:04:3C:26:17:62:8F:DD:59:C5:D3:E7:0B:4F:62:A0:D9:17:E5
```

上一版 `3.2.2-community.2`（`versionCode 211`）的哈希是
`2365ce7468e63d0b75c859b37f2eb69391555e531708323be3a587be67029cff`；再上一版
`3.2.2-community`（`versionCode 210`）是 `53539dd334f5db39a1d0d01d644c80219cf6517f5fd04871dd2c4320a89987e4`。
三版同一把签名密钥。

这一版**沿用了上一版社区构建（`3.2.2-community`，`versionCode 210`）的签名密钥**，
因此可以直接覆盖升级，不需要卸载、也不会丢主机和凭据。更早的 targetSdk 35 构建
（`24,914,048` / `53539dd3…`）也是同一把密钥。

注意：你自己构建出来的哈希不会与上面相同——`build.sh` 首次运行会自动生成新 keystore，
证书不同则整个 APK 的字节就不同。这也意味着**你的构建产物不能覆盖安装我的**，反之亦然。

## 安装

由于签名证书与原版不同，**不能覆盖安装原版**。必须先卸载原版——这会清掉本地数据库里的
主机、凭据和已知主机密钥。

```bash
adb uninstall com.sonelli.juicessh
adb install dist/JuiceSSH-3.2.2-community.apk
```

已经装了上一版社区构建（`3.2.2-community` / 210 或 `3.2.2-community.2` / 211）的，直接
`adb install -r` 即可升级：签名相同、versionCode 212 更高。

## 回归检查清单

侧载后建议手工确认（**下列前五项已在 Android 16 / XT2601-1 上实测通过**）：

- [x] 首次启动不弹「此应用不兼容 16 KB 页大小」
- [x] 新建 SSH 连接能连上（`ssh://` 链接也能唤起）
- [x] 主机密钥指纹弹出且与服务器官方指纹一致
- [x] 终端里本地 shell 可用（`/system/bin/sh`，提示符正常）
- [x] 主界面不再出现「解锁 Pro」「云同步」「团队共享」「书城」卡片
- [ ] 首次启动弹出通知权限申请，允许后会话通知可见（渠道 importance 已修为 LOW）
- [ ] SFTP 下载文件落在 `Android/data/com.sonelli.juicessh/files/`
- [ ] 端口转发可用（Pro 门禁已解锁）
- [ ] 通知点击能回到对应会话（`PendingIntent` 补丁生效）
- [ ] 会话挂机超过 6 小时不被系统掐断（`specialUse` 前台服务）
- [ ] 第三方 App 无法直接拉起 `PurchaseActivity` / `LogViewerActivity`（导出已关闭）
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
