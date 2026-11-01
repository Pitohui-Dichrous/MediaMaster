<p align="center">
  <img src="docs/images/readme-banner.jpg" alt="好模型" width="300" />
</p>

# 映序 / MediaMaster

100% vibe coding

个人使用的安卓本地视频播放器。当前正式版 **2.3.0**，最低支持 Android 8.0，主要面向 Android 14。

支持基础文件管理和视频搜索，支持横屏播放和竖屏播放。首页可以一键播放随机视频。

## 安装

下载 APK 后直接安装。应用名称为“映序”，可覆盖 2.2.0 测试版、2.1.0、2.0.x、1.4.0 和已有预览版并保留设置，与原正式版 1.3.0 并存；不会覆盖 1.3.0 或自动迁移其设置。

Release 同时附有源码包 `MediaMaster-2.3.0-source.zip`、安装说明 `INSTALL.txt`、验证记录 `VERIFICATION.txt` 和校验清单 `SHA256SUMS.txt`。

首次打开允许读取视频；需要扫描整个共享存储空间时，在文件夹菜单中授予全盘访问权限。完整说明见 [安装与使用](安装与使用.txt)。

## 功能

- 播放 MP4、MKV、AVI 等常见本地视频；不支持 M3U8 播放列表。
- 自动扫描、手动刷新、添加文件夹、文件名搜索及重命名/删除。
- 修改时间、保存时间、名称、时长、大小排序，使用统一倒序开关。
- 非线性横滑定位：短滑精细调整、长滑加速，进度条与人物控制点连续跟手。
- 拖动进度条或横滑时显示附近画面预览；透明控件，长按临时两倍速。
- 全屏模式左侧上下滑动调整亮度，右侧调整音量。
- 顺序连播、双向随机播放、单集循环；自动适配或手动切换横竖屏。随机优先少看视频，观看次数本机保存。
- 默认解码自动选择；支持优先软解和优先硬解。
- 前台监听媒体索引变化，日常刷新只核对索引；全盘和额外文件夹可手动扫描。缩略图使用有界内存缓存，不保存图片文件。

具体容器的播放和循环表现取决于内部编码与设备解码器。

## 构建

Java 17、Android SDK 36、Gradle Wrapper 8.11.1、Android Gradle Plugin 8.9.2。使用 Java 原生 Android Views、Media3 和 LibVLC。

用 Android Studio 打开项目，或在已配置 `JAVA_HOME` 与 `ANDROID_HOME` 的环境执行：

```sh
./gradlew assembleDebug -PpreviewInstall=true
./gradlew testDebugUnitTest lintDebug -PpreviewInstall=true
```

Windows 可使用 `gradlew.bat`，当前本机还可运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 assembleRelease -PpreviewInstall=true
```

2.3.0 沿用 2.1.0、2.0.x、1.4.0 及测试版的包名 `io.mediamaster.app.preview` 和签名；原 1.3.0 的发布密钥已丢失。签名密钥不在仓库中，其他机器自行构建的 APK 不一定能覆盖安装。后续更新必须保留并使用同一包名和签名密钥。

<!-- 维护约定：保持本页简洁，仅在十分必要时增加功能描述。 -->
