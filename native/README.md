# 重建的 arm64-v8a 原生库

`native/arm64-v8a/` 里放的是**重新编译**的两个共享库，构建时由 `build.sh` 的
`[3b]` 步骤复制进解码树，覆盖原版 APK 里的同名文件：

| 文件 | 大小 | 来源 |
| --- | --- | --- |
| `libcom.sonelli.libssh.so` | 536,720 B | libssh 0.11.3 |
| `libcrypto.so` | 5,964,880 B | OpenSSL 3.5.4 |

原版是 libssh 0.9.3 + OpenSSL 1.1.1g，两者都停在 2020–2021 年，带着
CVE-2021-3634、CVE-2020-1730、Terrapin（CVE-2023-48795）、CVE-2025-5372 等已公开漏洞，
而且 38 个 ELF 的 `p_align` 全是 4096，在 16 KB 页设备上只能靠兼容模式跑。

## 为什么可以这样替换

JuiceSSH 用的是 **JNA**，不是 JNI：`com/sonelli/libssh/SshLibrary.java` 通过
`Native.loadLibrary("com.sonelli.libssh", ...)` 按**普通 C 符号名**绑定 866 个方法
（417 处调用）。所以只要新库导出同样的符号、结构体布局一致，就不需要改一行 Java/smali。

对齐与依赖（`llvm-readelf`）：

```
libcom.sonelli.libssh.so  LOAD p_align 0x4000  NEEDED libcrypto.so libz.so libm.so libdl.so libc.so
libcrypto.so              LOAD p_align 0x4000  NEEDED libm.so libdl.so libc.so
```

`libssl.so`（786,224 B）在 arm64-v8a 下**没有任何库依赖它**，所以直接删掉，省体积也少一个
需要跟进的 CVE 面。

## 对上游源码的两处本地修改

1. **libssh `src/misc.c:324`**：`(buffer.st_mode & S_IWRITE)` → `(buffer.st_mode & S_IWUSR)`。
   bionic 不定义 `S_IWRITE`（那是 Windows 的写法），不改就编不过。属于上游对 Android 的
   兼容缺陷，语义等价。
2. 无其它功能修改；编译选项与上游默认一致，只加了
   `-Wl,-z,max-page-size=16384`。

## 怎么复现

工具链：NDK r29（`29.0.14206865`）、CMake 4.1.2、Ninja、MSYS perl 5.042003、
OpenSSL 3.5.4 源码、libssh 0.11.3 源码。

### 1. 准备

```bash
export NDK=/d/DSH/juicessh/ndk/android-ndk-r29
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/windows-x86_64
export ANDROID_NDK_ROOT=$NDK
export API=26
export PREFIX=/d/DSH/juicessh/ndkbuild/prefix-arm64
export PAGE_FLAGS="-Wl,-z,max-page-size=16384"
export PATH=$TOOLCHAIN/bin:$PATH
export TMP=/d/DSH/juicessh/.tmp TEMP='D:/DSH/juicessh/.tmp' TMPDIR=/d/DSH/juicessh/.tmp
```

Windows 上必须设 `TMP/TEMP/TMPDIR`，否则 clang 报
`unable to make temporary file: Permission denied`。

### 2. OpenSSL 3.5.4

```bash
perl Configure android-arm64 -D__ANDROID_API__=26 \
  --prefix="$PREFIX" --openssldir="$PREFIX/ssl" shared no-tests
printf '\nLDFLAGS += -Wl,-z,max-page-size=16384\n' >> Makefile
make -j8 libcrypto.a libssl.a        # 只编静态库
```

不要直接 `make`：链接 `libcrypto.so` 时几百个 `.o` 会超过 Windows 32 KB 命令行上限，
报 `命令行太长`。改用整库归档链接：

```bash
aarch64-linux-android26-clang -shared \
  -Wl,--whole-archive libcrypto.a -Wl,--no-whole-archive \
  -Wl,-soname,libcrypto.so $PAGE_FLAGS -o "$PREFIX/lib/libcrypto.so" -lm
```

（`libssl.so` 同理链接，本项目用不到，可以不产。）

### 3. libssh 0.11.3

```bash
cmake -G Ninja -S libssh -B build-libssh \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DWITH_ZLIB=ON \
  -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.so" \
  -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.so" \
  -DCMAKE_SHARED_LINKER_FLAGS="$PAGE_FLAGS" \
  -DWITH_EXAMPLES=OFF -DWITH_GSSAPI=OFF
ninja
llvm-strip --strip-unneeded build-libssh/lib/libssh.so
```

`find_package(OpenSSL)` 要求 crypto 和 ssl 两件都在，所以第 2 步的 `libssl.so` 至少要生成
一次（之后可以删）。

### 4. 替换

```bash
cp libssh.so      <dec>/lib/arm64-v8a/libcom.sonelli.libssh.so
cp libcrypto.so   <dec>/lib/arm64-v8a/libcrypto.so
rm -f             <dec>/lib/arm64-v8a/libssl.so
```

`build.sh` 已经替你做了这一步（读 `native/arm64-v8a/*.so`）。

## 兼容性核对（静态）

- 符号：旧库用版本脚本导出（`channel_free@@LIBSSH_4_5_0`），新库同名无版本号。
  去掉 `@` 后缀后旧库 424 个、新库 901 个，`old − new` 只剩 5 个版本节点名，**没有函数缺失**。
- 实际调用：从 smali 抓出的 66 个 `Lcom/sonelli/libssh/*;->xxx(` 方法名，在旧库、新库中
  都存在（含 `channel_free`、`sftp_new` 这类历史别名，由 libssh 的 legacy 层导出）。
- `ssh_channel_callbacks_struct`：前 17 个字段顺序一致；0.11.3 只在尾部追加了
  `channel_open_response_function` / `channel_request_response_function`。App 用
  `size = sizeof(JNA struct)` 初始化（`SSHAgent.java:49`），libssh 按 `size` 截断拷贝，
  新字段为 NULL → 向后兼容。
- `sftp_attributes_struct`：字段顺序与 0.11.3 的 `sftp.h` 逐项一致。
- `ssh_session_struct` / `ssh_socket_struct` 是 JNA 的 `PointerType`（不透明句柄），不读字段。

## 未验证 / 已知回归

- **没有在真机或模拟器上跑过**（构建时 `adb devices` 为空）。上述核对全是静态的，
  实际握手、PTY、SFTP、端口转发、agent 转发都还需要一次真机回归。
- **算法集合变了**：libssh 0.11.3 默认 KEX/加密/MAC 列表比 0.9.3 更严，老服务器
  （尤其只开 `diffie-hellman-group1-sha1`、`ssh-rsa`、`3des-cbc` 的设备）可能握手失败。
  这是安全改进，但对"连老旧内网设备"的用法是行为变化。
- **OpenSSL 3 的 legacy provider 没打进去**：Blowfish/CAST/RC4/IDEA 等算法在 OpenSSL 3
  里被移到 legacy provider，本库没带，只支持这些算法的服务器会失败。
- 体积：arm64-v8a 的这两个库从 3,925,168 B 涨到 6,501,600 B，APK 净增约 0.97 MB。
