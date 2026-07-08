# Ncmdump LSPosed

Ncmdump LSPosed is an Android/Kotlin LSPosed module with a small Rust JNI crate. It watches completed NetEase Cloud Music `.ncm` downloads and converts them into normal `.mp3` or `.flac` files.

The repository root is the Android project. Our Rust code lives in `rust/crates/ncmdump-android`, while upstream `ncmdump.rs` is linked as a Git submodule at `rust/ncmdump.rs`.

## Features

1. Watches only the NetEase Cloud Music download directory:
   `/storage/emulated/0/Download/netease/cloudmusic/Music`

2. Processes completed `.ncm` writes through `FileObserver` and short fallback scans.

3. Runs decrypt work on a single low-priority background worker.

4. Streams decrypted audio through a small buffer to keep memory usage low.

5. Writes MP3 ID3v2 and FLAC Vorbis Comment metadata with embedded cover art through Rust `lofty`.

6. Includes native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

7. Keeps LSPosed entry points and JNI bridge compatible with R8/ProGuard.

## Layout

```plaintext
app/                           Android LSPosed module
app/src/main/jniLibs/          Embedded Rust native libraries
rust/Cargo.toml                Small Rust workspace for our JNI crate
rust/crates/ncmdump-android/   Our Android JNI crate
rust/ncmdump.rs/               Upstream ncmdump.rs Git submodule
scripts/build-android-libs.sh  Builds Rust libraries into app/src/main/jniLibs
```

## Build

### Requirements

1. Rust stable.

2. Android SDK and NDK.

3. Gradle available on `PATH`.

4. `ANDROID_HOME`, `ANDROID_NDK_HOME`, or `ANDROID_NDK_ROOT` configured.

Install Rust Android targets:

```shell
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

Build native libraries:

```shell
bash scripts/build-android-libs.sh
```

Build the Android module:

```shell
gradle :app:assembleRelease
```

The unsigned APK is generated at:

```plaintext
app/build/outputs/apk/release/app-release-unsigned.apk
```

## Usage

1. Install the APK on a device with LSPosed.

2. Enable the module in LSPosed.

3. Set the scope to NetEase Cloud Music: `com.netease.cloudmusic`.

4. Force stop and reopen NetEase Cloud Music.

5. Download songs. Converted `.mp3` or `.flac` files will be created beside the source `.ncm` files.

## Implementation Notes

The module avoids deep hooks into obfuscated download internals.

It watches the final download directory and handles only completed `.ncm` files. This keeps the module stable across app versions and avoids blocking the main thread.

Normal scan and conversion events are intentionally silent. Only failure paths are logged to LSPosed.

## Credits

The Rust decrypt core is provided by the [iqiziqi/ncmdump.rs](https://github.com/iqiziqi/ncmdump.rs) submodule.

Metadata behavior was compared with [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump).

## License

This project is licensed under the MIT License.

Use this project only for music files you are legally allowed to access and convert.
