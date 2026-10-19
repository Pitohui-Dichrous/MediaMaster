映序 / MediaMaster 1.3.0

直接安装：dist/1.3.0/MediaMaster-1.3.0.apk
GitHub下载：https://github.com/Pitohui-Dichrous/MediaMaster/releases/tag/v1.3.0
安装及首次授权：安装与使用.txt
本次设计：docs/1.3.0-preview.2/设计说明.txt
本次发布：docs/1.3.0/发布说明.md
本次验证：docs/1.3.0/验证记录.txt
构建说明：docs/构建说明.txt
开源许可：docs/THIRD_PARTY_NOTICES.txt 及 app/src/main/assets/licenses/

安卓原生本地视频播放器。离线，无广告，无账号。
视频扫描、五字段排序与共用倒序开关、搜索、缩略图、重命名、删除。
非线性滑动进度与画面预览，透明控件、方向人物控制点、长按临时倍速。
全屏模式保留左侧亮度与右侧音量手势，顺序/随机/单集播放，自动与手动横竖屏。
目录标题随内容收起，搜索和目录栏固定，右侧可拖快滚。
仅冷启动自动扫描；播放返回、后台恢复及同任务配置重建均不扫描。
缩略图纯内存有界缓存，不写图片文件；升级清理旧缩略图缓存。

历史版本交付物及验证记录均保留。正式签名私钥位于 .tools/signing/，请保留供后续覆盖更新。
公开源代码压缩包不包含私钥、SDK、缓存或用户视频。

保留1.2.1修复：横滑目标不再整秒取整，进度控制点按毫秒连续跟手，原加速曲线保留。

1.3.0正式版：竖屏采用三页预载，上下滑动直接露出相邻视频；左右滑动仍调进度，可随时切回原全屏模式。相邻视频尚未准备好时保持当前画面，避免滑入空白页。
本版将已获用户实机认可的1.3.0-preview.2转为正式版，不再更改播放功能。预览版历史记录保留在docs/1.3.0-preview/与docs/1.3.0-preview.2/，正式包核验见本次验证记录。
已有正式版或预览版请直接覆盖安装，不要卸载；同一签名保留原有设置和添加的文件夹记录。
Release附件：MediaMaster-1.3.0.apk、MediaMaster-1.3.0-source.zip、INSTALL.txt、VERIFICATION.txt、SHA256SUMS.txt。
