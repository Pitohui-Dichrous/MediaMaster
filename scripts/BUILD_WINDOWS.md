# Windows 构建环境

此项目的独立构建环境位于 `.tools`，没有修改系统 Java 或 Android 环境变量。

- Java：Eclipse Temurin 17，下载包通过 Adoptium 提供的 SHA-256 校验。
- Gradle：8.11.1，下载包及 Wrapper 均使用官方发布版本；Wrapper 固定校验 SHA-256。
- Android SDK：平台 API 35、Build Tools 35.0.0、Platform Tools。
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

其他电脑可以安装 Android Studio 和 JDK 17，配置 `JAVA_HOME` 与 `ANDROID_HOME`，再运行同一脚本。`.tools`、Gradle 缓存和本机 SDK 不属于 APK，也不需要一起安装到手机。
