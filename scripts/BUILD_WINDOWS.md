# Windows 构建环境

此项目的独立构建环境位于 `.tools`，没有修改系统 Java 或 Android 环境变量。

- Java：Eclipse Temurin 17，下载包通过 Adoptium 提供的 SHA-256 校验。
- Gradle：8.11.1，下载包及 Wrapper 均使用官方发布版本；Wrapper 固定校验 SHA-256。
- Android SDK：平台 API 36、Build Tools 35.0.0、Platform Tools。
- Android NDK：27.3.13750724（r27d），用于 Release 原生库调试符号剥离；无需重新编译 LibVLC。
- Android Gradle Plugin：由项目构建文件固定版本。

在项目目录使用 PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

默认生成调试 APK。运行其他 Gradle 任务时，例如：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 testDebugUnitTest
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 lintDebug
```

首次构建需要联网下载项目依赖。`local.properties` 是当前机器的 SDK 路径，迁移到另一台机器后应重新配置；源码不依赖固定盘符。

Release 使用 R8 精简代码和资源。通过 Android Studio 的 SDK Manager 安装上述固定版本 NDK，并接受 SDK 许可；离线构建前须完成依赖安装。构建日志若出现原生库无法剥离的警告，应停止交付并检查 NDK，而不是将未剥离产物视为已优化版本。

保留每次构建的 `app/build/outputs/mapping/release/`，特别是 `mapping.txt`，用于还原混淆后的错误堆栈。它们保存在构建档案中，不放入 APK。体积与 JNI 校验方式见 [安装体积优化](../docs/INSTALLED_SIZE_OPTIMIZATION.md)。

其他电脑可以安装 Android Studio 和 JDK 17，配置 `JAVA_HOME` 与 `ANDROID_HOME`，再运行同一脚本。`.tools`、Gradle 缓存和本机 SDK 不属于 APK，也不需要一起安装到手机。
