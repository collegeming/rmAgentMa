# rmAgentMa Android 工程

本目录是 rmAgentMa 的 Android 客户端，派生自 ConnectBot。应用 ID 为 `org.rmagentma.android`（调试版后缀 `.debug`），使用说明见[项目首页](../README.md)。本工程与 Google Play 中的 ConnectBot 是不同应用；下方链接仅介绍上游产品，不用于安装 rmAgentMa。源码归属与修改范围见 [NOTICE](NOTICE)。

## 上游 ConnectBot 说明

ConnectBot is a [Secure Shell](https://en.wikipedia.org/wiki/Secure_Shell)
client for Android that lets you connect to remote servers over a
cryptographically secure link.


## How to Install

### Google Play

[![Get it on Google Play][2]][1]

  [1]: https://play.google.com/store/apps/details?id=org.connectbot
  [2]: https://developer.android.com/images/brand/en_generic_rgb_wo_60.png

The easiest way to get ConnectBot is to [install from Google Play Store][1].
If you have installed from a downloaded APK, Google Play Store can upgrade
your installed version to the latest version. However, once it has upgraded
*you can't install a version from the releases on GitHub anymore* (due to
key rotation that upgrades the package signature to a more secure algorithm).


### Download a release

ConnectBot can be downloaded from [releases](
https://github.com/connectbot/connectbot/releases) on GitHub. There are
two versions:

-  `google` &mdash; for a version that uses Google Play Services
   to handle upgrading the cryptography provider
-  `oss` &mdash; includes the cryptography provider in the APK which
   increases its size by a few megabytes.

## Architecture

### Major dependencies

The ConnectBot app that uses two other libraries to provide its functionality:
* [ConnectBot Terminal](https://github.com/connectbot/termlib) &mdash; the
  terminal emulator used by the app is also created and maintained by the
  ConnectBot author, Kenny Root.
* [ConnectBot fork of Trilead SSH-2](https://github.com/connectbot/sshlib)
  &mdash; a heavily modified fork of the original Trilead SSH-2 Java library
  written by Christian Plattner.

## Compiling

### Android Studio

ConnectBot is most easily developed in [Android Studio](
https://developer.android.com/studio/). You can import this project
directly from its project creation screen by importing from the GitHub URL.

### Command line

To compile ConnectBot using `gradlew`, you must first specify where your
Android SDK is via the `ANDROID_SDK_HOME` environment variable. Then
you can invoke the Gradle wrapper to build:

```sh
./gradlew build
```

### Continuous Integration

rmAgentMa 使用 GitHub Actions，workflow 位于仓库根目录 `.github/workflows/android.yml`，包含构建 OSS debug APK 与设备测试 APK、格式与静态检查（`spotlessCheck lint check test`）、不依赖 Android SDK 的 `:agent-core` 测试三组任务。提交前的标准校验命令见[项目首页](../README.md)。

#### Running Workflows Locally with act

In general, simply running `./gradlew build` should cover all the
checks run in the GitHub Actions continuous integration workflow, but you can
run GitHub Actions workflows locally using [`nektos/act`](https://github.com/nektos/act).
This requires Docker to be installed and running.

To run the main CI workflow (`android.yml`, from the repository root):

```sh
act -W .github/workflows/android.yml
```

### 真机验收

真机安装与验收流程见[项目首页](../README.md)：覆盖安装用 `adb install -r` 同一签名的 APK，不卸载、不清应用数据；远端对话测试经 `agentDeviceE2e=true` 与已人工核验的主机指纹显式启用，且不得通过会清除应用数据的 Orchestrator 执行。签名密钥与真机测试通道只保留在开发机上，不进入仓库、CI artifact 或日志。
