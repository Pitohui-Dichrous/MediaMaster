# 映序 / MediaMaster

个人使用的安卓本地视频播放器，离线运行，无账号、无广告。当前正式版 **1.3.0**，最低支持 Android 8.0，主要面向 Android 14。

竖屏模式采用三页预载：上下滑动时直接露出已准备的视频，松手后完成切换。列表入口仍为原有全屏，手机图标切入竖屏，展开图标切回全屏；左右滑动继续调进度。模式切换保留当前播放器、位置、倍速和历史。1.3.0 将已获用户实机认可的 1.3.0-preview.2 转为正式版，本轮不再更改播放功能。

## 安装

前往 [1.3.0 Release](https://github.com/Pitohui-Dichrous/MediaMaster/releases/tag/v1.3.0) 下载 `MediaMaster-1.3.0.apk`，传到手机后打开安装。已有正式版或预览版映序时，**直接覆盖更新，不要先卸载**；同一签名保留原有设置和添加的文件夹记录。本机交付目录为 `dist/1.3.0/`。

Release 同时附有源码包 `MediaMaster-1.3.0-source.zip`、安装说明 `INSTALL.txt`、验证记录 `VERIFICATION.txt` 和校验清单 `SHA256SUMS.txt`。

首次打开允许读取视频；需要扫描整个共享存储空间时，在文件夹菜单中授予全盘访问权限。完整说明见 [安装与使用](安装与使用.txt)。

## 功能

- 播放 MP4、MKV、AVI 等常见本地视频；不支持 M3U8 播放列表。
- 自动扫描、手动刷新、添加文件夹、文件名搜索及重命名/删除。
- 修改时间、保存时间、名称、时长、大小排序，使用统一倒序开关。
- 非线性横滑定位：短滑精细调整、长滑加速，进度条与人物控制点连续跟手。
- 拖动进度条或横滑时显示附近画面预览；透明控件，长按临时两倍速。
- 全屏模式左侧上下滑动调整亮度，右侧调整音量。
- 顺序连播、随机播放、单集循环；自动适配或手动切换横竖屏。
- 竖屏三页预载：上滑下一条，下滑回上一条，画面连续跟手；随机模式保留实际观看历史，单集循环也可手动滑走。
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

1.3.0 的功能基于用户已实机确认的 1.3.0-preview.2。该预览版历史记录包含 24 项单元测试和 Android 14 模拟器的 56 项设备测试；正式包的构建、签名及安装核验以本版记录为准。历史记录保留，不视为本轮重新执行的测试。

- [1.3.0 发布说明](docs/1.3.0/发布说明.md)
- [1.3.0 验证记录](docs/1.3.0/验证记录.txt)
- [三页预载设计与交互](docs/1.3.0-preview.2/设计说明.txt)
- [1.3.0-preview.2 历史验证记录](docs/1.3.0-preview.2/验证记录.txt)
- [上一轮预览版验证记录](docs/1.3.0-preview/验证记录.txt)
- [1.2.1 历史验证记录](docs/1.2.1/验证记录.txt)
- [构建说明](docs/构建说明.txt)
- [第三方组件与素材来源](docs/THIRD_PARTY_NOTICES.txt)

人物控制点素材由用户提供。第三方组件和素材遵守各自许可。

本机 SDK、构建缓存、签名私钥、测试视频与 `dist` 安装包不纳入 Git；正式安装包通过 Releases 提供。
