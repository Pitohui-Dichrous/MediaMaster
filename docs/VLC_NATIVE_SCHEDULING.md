# VLC 原生定位调度

调查日期：2026-11-01。对象为用户提供的 VLC 3.7.2 APK 中 ARM64 原生库。以下区分安装包静态证据、官方源码解释与运行验证；没有据此认定某个引擎在所有视频上都更快。

## 证据范围

`libvlc.so` SHA-256：`4d5bb84df69c876da7dbc13555b9243bfc77666b2893a56252eb15af033774c4`；版本字符串为 `3.0.25` / `3.0.24-35-gc7e5d68ee5`。REA 导出表 Evidence：`ev_8e8f86e01a556e48024564ec5ca7f267362c1f3ab48eddb85327c921d8b7793a`。

官方构建链：[VLC Android 3.7.2](https://github.com/videolan/vlc-android/blob/e5740f713d995f6c8e7d92e64134abc3a3b6987d/buildsystem/compile.sh#L280-L289) 固定 libvlcjni `0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6`；其 [get-vlc.sh](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/get-vlc.sh) 固定 VLC `84177b2273abc5c4300234e6e18b55a4d2dd5a03`，再应用20个 Android 补丁。最终生成提交 `c7e5d68ee5` 未直接取得，未做整库可复现构建。

本地完整证据位于忽略目录 `dist/analysis/vlc-20261101/native-scheduling/`，包括来源清单、原生指令、运行日志散列与 `REPORT.md`。ARM64 ELF 深度分析使用共享 Ghidra 的只读限定区间导出，属于直接 Ghidra 补充证据，不冒充 REA Evidence。下述地址采用 Ghidra 映像基址 `0x100000`，并非手机运行时地址。

## 定位请求的处理

| 环节 | 已确认行为 | 对映序的意义 |
| --- | --- | --- |
| 输入队列 | FIFO 出队前合并队首连续、同类型的定位命令，保留这一段最后目标；不会撤回已执行请求 | 应用节流与原生合并会叠加，不能靠无限增加请求提速 |
| 缓冲保护 | ES 尚在缓冲且距上次定位不足约125 ms时，可推迟后续定位；约20 ms检查一次 | 不是每次跳转固定等待125 ms，也没有证明存在统一的500 ms延时 |
| fast / precise | Android补丁在处理定位命令时读取 `input-fast-seek`，再传给demux | 拖动可以快速近似，松手仍需一次精确定位 |
| 解码清空 | 调用端清空FIFO并唤醒解码线程；解码线程优先处理flush，MediaCodec层另有输出线程确认 | 保留播放器与输出表面，避免额外的暂停、重建和重复flush |

队列与缓冲证据：`native-input-assembly.txt` 中 `0x00f4e090–0x00f4e168`、`0x00f4e18c–0x00f4e268`，对应固定源码的 [MainLoop](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/input.c#L768-L810) 与 [ControlPop](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/input.c#L1594-L1709)。队列容量为100项，合并范围受命令类型与排列限制。

fast参数证据：已有 JNI/核心导出与新增 `0x00f4f6d0–0x00f4f70c` 指令相符；官方 [补丁0010](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/libvlc/patches/0010-media_player-backport-fast-seek-argument.patch) 删除初始化时缓存的标志，改为处理命令时读取。该值实际保存在输入对象上，不能把一次Java调用返回当作该请求已经完成精确解码。

## 精确落点与画面呈现

MP4的 [Seek](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/demux/mp4/mp4.c#L1608-L1650) 先找可解码起点；精确模式额外设置目标显示时间，借助preroll跳过较早数据。这个接口标志不等于最终显示帧逐帧精确，软件解码的时间边界见下文。长关键帧间隔会增加依赖帧工作量。早期2.2.0-test.2调查尚未取得用户的慢样本；后续匿名样本及持续输入结果见 [持续定位验证](SEEK_STRESS_TEST.md)，不能仅凭GOP长度认定全部延迟原因。

[input_DecoderFlush](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/decoder.c#L2121-L2151) 是异步清空入口；安装包精确导出范围为 `0x00f59bac–0x00f59c37`。[MediaCodec flush与输出线程](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/codec/omxil/mediacodec.c#L1240-L1320) 仍需协调旧输出的释放，不能将其理解为零成本。对应安装包指令位于 `native-scheduling-assembly.txt` 的 `0x01281660–0x012816f0`、`0x012833e4–0x01283470`。

首张可显示画面会被标记为优先显示：安装包 `0x00f5b1c4–0x00f5b1e4` 与 [DecoderPlayVideo](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/decoder.c#L1036-L1050) 一致。新画面入队会[唤醒vout](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/video_output/video_output.c#L425-L432)，强制画面跳过其常规显示日期等待；[Android输出](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/video_output/android/display.c#L1110-L1133) 仍有系统表面与定时释放路径。不能把“先看到较早的关键帧”当作“已精确到达目标”。

## OpenSLES零音量与AudioTrack

发现一个与运行中静音拖动直接相关的边界缺陷。[OpenSLES VolumeSet](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/audio_output/opensles_android.c#L206-L222) 对音量立方后取对数，再换算为millibels。本APK在 `0x011a9dfc–0x011a9e34` 将结果转成64位整数，随后使用低32位。输入0产生负无穷，通常AArch64转换饱和到64位最小值，再截断为0，最终可能传出 **0 millibels，即满增益**。可信指令与导入解析见本地 `native-volume-thunk-assembly.txt`、`volume-imports.json`；此二进制推导针对被调查APK，未逐字节比较映序3.6.5库的同一函数。

这条路径使用原生OpenSLES音量接口，不能以“软件已静音”为由忽略AudioFlinger增益异常。[aout_VolumeSet](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/audio_output/output.c#L688-L749) 还会排队音量请求，因此返回0不等于底层已经应用；`getVolume()`也不是实际PCM静音证明。

映序已改为 `--aout=android_audiotrack`。[AudioTrack VolumeSet](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/audio_output/audiotrack.c#L2077-L2122) 保存音量并直接把0.0f传给Android API，避开对数零点；轨道启动时重新应用保存值，也保留预加载的静音意图。

已核对运行记录：`native365-audiotrack-mute.txt` 的模拟器针对性检查1/1通过，`native365-audiotrack-volume10.txt` 的真机原生定位检查6/6通过。该轮真机测试时系统媒体音量为10/150；日志引用和散列见本地证据目录的 `runtime-log-references.json`。此前系统总音量为0的结果不能证明应用自身静音有效。上述检查覆盖本次设备与测试场景，不构成所有输出设备的保证。

## 真机解码路径与显示承载

一款Android 14实机上的调查改变了原先只比较“Media3与LibVLC”的判断：被调查VLC在指定H.264样片中实际使用了avcodec软件解码路径。进程线程名出现 `av:h264:df1` 至 `df5`，对应 [FFmpeg帧线程命名](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libavcodec/pthread_frame.c#L164-L172)。证据为 `.tools/qa-2.2.0-test.2/vlc-thread-names-generated.txt` 与 `video-info-v1/selection.json`；后者核对了应用、文件名及1080p/25fps轨道。`scoped-codec-metrics.txt` 中映序使用设备硬件解码器的记录是交叉证据，不单凭“没有VLC的MediaCodec记录”推断软解。

这不证明VLC的通用默认设置是软解。安装包 `setMediaOptions` 的反编译 Evidence `ev_15b9a884a4b1b73d397f8699e133425c5511f0d6be4ed92733d128b600ea969a` 显示设置值、媒体标志与自动路径会共同影响选择；未读取该手机的私有设置，不能确定这次进入软解的初始原因。

LibVLC 3.6.5与3.7.7内的版本字符串分别为 `Lavc58.134.100`、`Lavc62.28.102`，后者与被调查VLC一致。证据为 `.tools/qa-2.2.0-test.2/ffmpeg-version-comparison.json` 及安装包 `libvlc.so` 文件偏移 `0x4aa5d5`。先前仅走MediaCodec的库版本对比，不能用来否定FFmpeg软件路径升级的作用；版本字符串相同也不保证所有编译配置相同。

2.2.0-test.2阶段，本机H.264样片的软解与SurfaceView组合已经接近参照VLC的响应范围，但不能外推到所有编码或设备。该阶段硬解Surface试验没有同等收益，因此SurfaceView本身不是充分原因。当前Android 14及以上路径使用SurfaceView，首帧和预览由新表面活动及有界PixelCopy确认；旧系统保留TextureView。表面首帧可用只证明存在新画面，不能证明其PTS等于定位目标。历史比较方法、分组结果与未覆盖场景见 [定位性能验证](SEEK_PERFORMANCE_TEST.md)，原始对比保留在 `.tools/qa-2.2.0-test.2/`；2.2.0-test.3的跨手势队列与持续输入结果另见 [持续定位验证](SEEK_STRESS_TEST.md)，不混用两轮协议或以UI时钟变化替代画面测量。

## 软件解码的preroll与线程取舍

固定源码的 [es_out](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/es_out.c#L2078-L2103) 按输入包PTS与时长设置preroll标志；[DecoderUpdatePreroll](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/decoder.c#L768-L779) 对首个未标记的包优先采用DTS作为边界，随后 [DecoderPlayVideo](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/src/input/decoder.c#L1000-L1017) 按该边界过滤输出。DTS与显示PTS可因重排不同；因此“precise请求已提交”不能直接证明暂停画面已到目标帧。

avcodec还会按当前输入包的preroll标志决定是否输出，而FFmpeg帧线程可能返回较早输入的画面。[帧线程队列](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libavcodec/pthread_frame.c#L563-L618) 先填充工作线程再取结果，其配置延迟为 `thread_count - 1`；这描述帧流水线，不是每次定位固定等待相同数量的帧时长。[VLC线程配置](https://github.com/videolan/vlc/blob/84177b2273abc5c4300234e6e18b55a4d2dd5a03/modules/codec/avcodec/video.c) 默认H.264最多采用6线程。减少线程可能降低输入输出的错位，也会减少并行解码能力，不能保证长GOP定位或连续播放更快。

本地720p样片的包检查确实发现PTS=4200ms、DTS=4080ms、时长40ms的包，与暂停定位到4200ms却显示4080ms的现象吻合。这支持上述边界机制，尚非对运行时每个原生变量的跟踪。两线程试验的六次GOP10暂停定位仍有80–120ms的提前落点：`.tools/qa-2.2.0-test.2/candidate19-auto-surface-threads2-gop10-paused/results.json` 的120ms容差检查通过，不能表述为提前落点已经修复，或原100ms精度检查通过。该试验没有证明普遍的速度收益，默认配置保留原生自动线程数。

不采用固定时间补偿或盲目 `nextFrame()`：包重排、帧率及目标位置不同会造成偏移变化；Java层 `getTime()` 是播放时间信息，并非表面当前画面的PTS。真正按目标PTS过滤需要原生层支持，当前性能结果必须同时保留落点误差。

## 历史自动选择的保守范围（test.2 / test.3）

以下保留历史版本的选择规则；test.4 的用户选项见 [解码选择](DECODER_SETTINGS.md)。

`NativeDecoderSelector` 只对请求播放或相邻预载的文件读取媒体头，不扫描全库、不启动对照解码器。单后台工作线程、最多8项等待任务和128项内存缓存限制资源占用；150ms内没有结果即采用硬解优先，迟到结果只影响以后打开。缓存键含文件身份、修改时间、大小及系统指纹。

这两个历史版本的自动软解仅覆盖已验证的一款API34实机：明确的8-bit AVC配置、没有HDR标记、长边不超过1920且短边不超过1080。2.2.0-test.2原先限制为不超过30fps；2.2.0-test.3将已知帧率上限扩展到60fps，同时要求 `宽 × 高 × 帧率 ≤ 1920 × 1080 × 30`（每秒62,208,000像素）。720p60符合预算，1080p60不符合。未知轨道信息、高规格视频及其他设备当时保留硬解优先。像素率不包含全部GOP及参考帧成本；这是有证据边界的历史候选策略，不是逐文件测出“最快解码器”。60fps样本及连续播放观察见 [持续定位验证](SEEK_STRESS_TEST.md)。

软解错误会先尝试原生硬解，再按已有兼容性路径转Media3。连续播放健康检查只在前台当前页、正常1倍速、非定位/缓冲状态采样：两个连续2秒窗口中，丢帧比例超过10%或呈现率低于轨道帧率75%时允许尝试硬解。缺失或完全不变的计数不作为解码器失败证据；它不能检测所有冻结，也不能测量定位精度。只有替代硬解实际产生首帧后，才记录该文件24小时内避开软解；这份记忆与整个LibVLC的失败记忆分开，记录数量有界。`--no-stats` 已移除以允许这些计数可用。

## 实现边界

另对映序依赖的LibVLC3.7.7 Java字节码进行了表面生命周期核对：`AWindow.SurfaceHelper.surfaceDestroyed`会调用`detachViews`并移除原有holder回调。仅等候Surface再次出现不足以恢复输出。相关限定导出为本地`{surface,awindow,videohelper}-recovery-bytecode.txt`，这属于依赖库补充证据，不冒充用户APK的同一段证据。映序只在输出实际脱离时重新绑定布局，保留MediaPlayer与输入；暂停时在轨道恢复后重新确认原目标帧。最终真机检查覆盖播放、暂停及恢复期间更新定位目标。

- 保留一条最终精确定位，取消未发出的中间请求；播放时可运行并静音，原本暂停时保持暂停。
- 原生引擎更短的中间请求节流可独立比较；它主要影响持续拖动，不保证缩短每次孤立点击。
- SurfaceView承载变化仍需兼顾真实首帧、三窗口复用、后台恢复与预览，不能以单次性能结果替代这些检查。
- 引擎和软硬解选择保留保守回退；不能仅按扩展名、硬件规格或软件版本宣称最快。
- 验证应分别记录输入到提交、第一张不同画面、精确目标画面及恢复连续播放，不能用播放时钟变化替代实际呈现。
