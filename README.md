# 映序 / MediaMaster

个人使用的安卓本地视频播放器，离线运行，无账号、无广告。当前版本 **1.2.1**，最低支持 Android 8.0，主要面向 Android 14。

## 安装

前往 [Releases 下载 APK](https://github.com/Pitohui-Dichrous/MediaMaster/releases/latest)，传到手机后打开安装。已有映序时直接覆盖更新，不要先卸载。

首次打开允许读取视频；需要扫描整个共享存储空间时，在文件夹菜单中授予全盘访问权限。完整说明见 [安装与使用](安装与使用.txt)。

## 功能

- 播放 MP4、MKV、AVI 等常见本地视频；不支持 M3U8 播放列表。
- 自动扫描、手动刷新、添加文件夹、文件名搜索及重命名/删除。
- 修改时间、保存时间、名称、时长、大小排序，使用统一倒序开关。
- 非线性横滑定位：短滑精细调整、长滑加速，进度条与人物控制点连续跟手。
- 拖动进度条或横滑时显示附近画面预览；透明控件，长按临时两倍速。
- 左侧上下滑动调整亮度，右侧调整音量。
- 顺序连播、随机播放、单集循环；自动适配或手动切换横竖屏。
- 目录菜单随滚动收起，保留搜索与目录栏，提供可拖动快滚条。
- 仅冷启动自动扫描；播放页返回和后台恢复不扫描。缩略图使用有界内存缓存，不保存图片文件。

具体容器的播放和循环表现取决于内部编码与设备解码器。

## 构建

Java 17、Android SDK 35、Gradle Wrapper 8.11.1、Android Gradle Plugin 8.9.2。使用 Java 原生 Android Views、Media3 和 LibVLC。

用 Android Studio 打开项目，或在已配置 `JAVA_HOME` 与 `ANDROID_HOME` 的环境执行：

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

Windows 可使用 `gradlew.bat`，当前本机还可运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 assembleRelease
```

发布签名不在仓库中。没有本机发布签名配置时，构建使用调试密钥，无法覆盖已安装的正式版。后续正式更新必须保留并使用同一发布签名。

## 验证与许可

1.2.1 已通过 9 项单元测试、Android 14 模拟器的 15 项播放相关设备测试，并在 Android 15 模拟器完成从 1.2.0 的正式覆盖升级。

- [本版验证记录](docs/1.2.1/验证记录.txt)
- [构建说明](docs/构建说明.txt)
- [第三方组件与素材来源](docs/THIRD_PARTY_NOTICES.txt)

人物控制点素材由用户提供。第三方组件和素材遵守各自许可。

本机 SDK、构建缓存、签名私钥、测试视频与 `dist` 安装包不纳入 Git；安装包保存在 Releases。
