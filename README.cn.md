# Ncmdump LSPosed

Ncmdump LSPosed 是一个 Android/Kotlin LSPosed 模块，内置一个很小的 Rust JNI crate。它会监听网易云音乐已经写入完成的 `.ncm` 下载文件，并转换为普通 `.mp3` 或 `.flac` 文件。

仓库根目录是 Android 项目。我们自己的 Rust 代码位于 `rust/crates/ncmdump-android`，上游 `ncmdump.rs` 通过 Git submodule 链接到 `rust/ncmdump.rs`。

## 功能

1. 只监听网易云音乐下载目录：
   `/storage/emulated/0/Download/netease/cloudmusic/Music`

2. 通过 `FileObserver` 和短时间 fallback scan 处理已完成写入的 `.ncm` 文件。

3. 解密任务运行在单线程低优先级后台 worker。

4. 解密时通过小 buffer 流式写入，降低内存占用。

5. 使用 Rust `lofty` 写入 MP3 ID3v2 与 FLAC Vorbis Comment 元数据，并嵌入封面。

6. 内置 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种 ABI 的 native library。

7. 保留 LSPosed 入口与 JNI bridge，兼容 R8/ProGuard。

## 目录结构

```plaintext
app/                           Android LSPosed 模块
app/src/main/jniLibs/          内嵌 Rust native libraries
rust/Cargo.toml                我们自己的 JNI crate 小 workspace
rust/crates/ncmdump-android/   我们的 Android JNI crate
rust/ncmdump.rs/               上游 ncmdump.rs Git submodule
scripts/build-android-libs.sh  构建 Rust libraries 到 app/src/main/jniLibs
```

## 构建

### 环境要求

1. Rust stable。

2. Android SDK 和 NDK。

3. `PATH` 中可用的 Gradle。

4. 已配置 `ANDROID_HOME`、`ANDROID_NDK_HOME` 或 `ANDROID_NDK_ROOT`。

安装 Rust Android targets：

```shell
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

构建 native libraries：

```shell
bash scripts/build-android-libs.sh
```

构建 Android 模块：

```shell
gradle :app:assembleRelease
```

未签名 APK 输出路径：

```plaintext
app/build/outputs/apk/release/app-release-unsigned.apk
```

## 使用

1. 在已安装 LSPosed 的设备上安装 APK。

2. 在 LSPosed 中启用模块。

3. 将作用域设置为网易云音乐：`com.netease.cloudmusic`。

4. 强制停止并重新打开网易云音乐。

5. 下载歌曲。转换后的 `.mp3` 或 `.flac` 会生成在源 `.ncm` 文件旁边。

## 实现说明

模块避免深入 Hook 混淆后的下载内部逻辑。

它只监听最终下载目录，并处理已完成写入的 `.ncm` 文件。这样更稳定，也能避免阻塞主线程。

正常扫描和转换事件默认不输出日志。只有失败路径会写入 LSPosed 日志。

## 致谢

Rust 解密 core 来自 [iqiziqi/ncmdump.rs](https://github.com/iqiziqi/ncmdump.rs) submodule。

元数据行为参考并对比了 [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump)。

## 许可

本项目使用 MIT License。

请仅在你拥有合法访问和转换权限的音乐文件上使用本项目。
