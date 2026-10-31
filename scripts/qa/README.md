# 播放定位基准

这组测试比较真实触摸后，视频主画面首次显示目标附近帧的时间。它可以操作映序或单独安装的 VLC，不把播放器时钟、预览缩略图、SeekBar 移动当作解码完成。

## 生成视频

需要 Python 3 和带 `libx264`、`libx265`、AAC 编码器的 FFmpeg；不需要 imageio 或 Pillow。在项目根目录运行：

```powershell
python scripts/qa/generate_seek_fixtures.py --ffmpeg "C:/Tools/ffmpeg/bin/ffmpeg.exe" --output .tools/seek-fixtures
```

默认拒绝覆盖已有视频、日志或清单；确认替换时加 `--overwrite`。`--verify-only` 只读取现有文件，重新验证，不写文件。可加 `--ffprobe "C:/Tools/ffmpeg/bin/ffprobe.exe"` 检查实际关键帧间隔。

生成三个 30 秒、25 fps 的视频，均含 440 Hz 音轨：

| 文件 | 编码 / 分辨率 | 关键帧间隔 |
| --- | --- | --- |
| `mm_seek_b2_h264_720_gop1.mp4` | H.264 / 1280×720 | 1 秒 |
| `mm_seek_b2_h264_1080_gop10.mp4` | H.264 / 1920×1080 | 10 秒 |
| `mm_seek_b2_hevc_1080_gop2.mp4` | HEVC / 1920×1080 | 2 秒 |

画面中央 B2 条码包含红色起始格、白色同步格、12 位帧号（低位在前）、奇偶校验和蓝色结束格，外侧黑底隔开背景颜色。帧号 `n` 对应 `n × 40 ms`，共 750 帧。脚本验证九个采样帧的三行条码，记录 FFmpeg 版本、编码命令、大小和 SHA-256 至 `fixtures.json`。不同编码器版本生成的字节可能不同；比较两款播放器时必须使用同一批文件。

## 安装与运行

使用已解锁、允许 USB 调试的测试设备；确认所有命令都指向指定设备。测试会打开生成视频、改变播放位置，并在测外部 VLC 前强制停止它。不要同时播放个人视频。不会清除应用数据；已有应用签名不匹配时，不要为了安装测试包卸载用户应用。

以下以独立调试包 `io.mediamaster.app.preview` 为例。它可能与本机已有预览包冲突，请先核对签名；测试 APK 必须与被测映序的包名和签名对应。

```powershell
$qaSerial = "填入设备序列号"
$qaApp = "io.mediamaster.app.preview"
$qaRunner = "$qaApp.test/androidx.test.runner.AndroidJUnitRunner"
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 assembleDebug assembleDebugAndroidTest -PpreviewInstall=true
adb -s $qaSerial install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s $qaSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $qaSerial shell mkdir -p /sdcard/Movies/SeekBench
Get-ChildItem .tools/seek-fixtures/mm_seek_b2_*.mp4 | ForEach-Object {
    adb -s $qaSerial push $_.FullName "/sdcard/Movies/SeekBench/$($_.Name)"
    adb -s $qaSerial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Movies/SeekBench/$($_.Name)"
}
```

确认三个文件已进入系统媒体索引；文件名必须唯一。先单文件试跑，再扩展矩阵。下面测试实际安装的 VLC；改 `benchPackage` 为 `$qaApp` 测映序。`benchEngine` 仅映序可用：`0` 自动、`1` Media3、`2` LibVLC。覆盖值会在测试结束后恢复。

映序还支持内部诊断参数 `benchNativeDecoder`：`0` 自动、`1` 硬解、`2` 软解，同样在结束后恢复。它不会修改独立 VLC 的设置。结果保存实际页面的 `nativeSoftware`、有效视频表面的类型和前后身份；应核对是否发生回退，而不是只看请求参数。FFmpeg 线程数若未由运行时公开，会明确记录为未知，不能凭运行名称推定实际配置。

```powershell
$qaRun = "vlc-720-playing-calibrated"
adb -s $qaSerial shell am instrument -w -r `
  -e class io.mediamaster.app.player.OpticalSeekBenchmark#measureSeekLatency `
  -e benchPackage org.videolan.vlc `
  -e benchFile mm_seek_b2_h264_720_gop1.mp4 `
  -e benchState playing -e benchRun $qaRun `
  -e benchCalibrate true -e benchTargets .8,.2,.7,.35,.25,.65 `
  $qaRunner
adb -s $qaSerial pull "/sdcard/Android/data/$qaApp/files/test-only/seek-benchmark/$qaRun" ".tools/qa-results/$qaRun"
```

连续快速点击使用 `OpticalSeekBurstBenchmark#measureRapidSeekBurst`，其余参数相同。它要求六个目标，由独立输入线程每隔 120 ms 发出一次点击，每次按下 20 ms。`benchState` 支持 `playing` / `paused`；两款播放器应使用相同状态、文件、目标列表及容差。记录应用版本、设备型号、系统、音量和运行顺序；交替执行，避免把温度、缓存或运行顺序影响当作改进。

## 校准与结果

- `benchCalibrate=true` 为默认值。测试先暂停，通过三个真实长按位置读取稳定的控件进度，验证触摸位置到请求毫秒的线性关系；中间点残差必须小于 20 ms。不会通过最终显示的帧反推或修正目标。
- 校准后以真实触摸定位到 400 ms，验证暂停画面，再开始正式准备。目标使用固定 30,000 ms 视频协议，向下对齐至 40 ms 帧边界；不使用可能包含 AAC 尾部填充的容器时长确定目标。
- 默认目标容差为 120 ms；可用 `benchToleranceMs` 显式调整。`benchCalibrate=false` 是诊断选项，使用已知内边距和较宽容差，不应与校准结果混合比较。
- `results.json` 保存校准点、请求值、整数触点量化误差、实际引擎、完整画面时间线和触摸时间。单次测试另有 `results.csv`；连续点击另有 `frames.csv`。触摸前截图和结果截图都在计时结束后写盘。
- 连续点击最多记录 300 条无效条码采样时间及原因，并保留最后一次按下后首张无效截图 `invalid-after-last-down.png`。截图先保存在内存，计时后才写盘，用于区分预览遮挡与没有新视频画面；无效采样仍不提供延迟下界或完成证据。
- `firstJump` 只表示画面发生跳变；`nearTarget` 才表示接近请求。暂停测试的 `finalStable` 和播放测试的 `finalConsistentTrajectory` 用于检查落点或后续播放。下界只由确认有效的非目标画面推进，无法识别的截图不提供下界证据。
- 暂停的单次点击至少观察 1 秒，且变更后的画面连续稳定至少 300 ms，才记录稳定落点。稳定落点的首次观测区间与误差分开报告；偏离 120 ms 容差的结果标记为 `settled-outside-tolerance`，不记作持续加载。测试继续收集全部目标后统一断言准确性，`collectionCompleted` 与 `completed` 分别表示收集完成和断言通过。稳定是有限观察窗口内的结论，不保证之后永远不再修正。

截图采样本身有耗时，结果是观测区间，不能证明零延迟。120 ms 容差仍可能包含附近关键帧；需同时检查最终落点、稳定后缀和逐帧记录。播放中允许目标后正常前进，不能用播放器时钟替代画面证据。单次测试若第一个目标与准备后的画面相距不足 2 秒，会改选远端并记录 `suppliedFraction`；比较时检查实际目标是否一致。使用示例中的 `.8` 起点可减少从 400 ms 准备期间触发该回退。

连续点击的最后一次之后出现的新画面可能来自前一次请求；只比较明确接近最后目标及后续稳定轨迹的记录。`unobserved`、`jump-only` 或校准失败不等同于“解码慢”：先检查保存的触摸前截图、控件位置、文件身份和播放状态。先前未验证文件身份或使用宽容差的历史结果，不应并入本协议矩阵。

可用标准库脚本汇总导出的目录：

```powershell
python scripts/qa/summarize_seek_results.py .tools/qa-results --output .tools/seek-summary.json
```

脚本不覆盖已有报告，排除未完成收集、校准或身份验证失败的结果，并按**实际目标序列**、文件、设备、播放状态和协议分组。完整收集后的准确性失败仍保留，单独报告稳定落点误差；不会把它换算成超时延迟。已知受到并发测试影响的运行还可用 `--exclude-run 运行名` 排除。

上下界中位数是采样观测界限，不是置信区间。播放状态的 `finalConsistentTrajectory` 允许 120 ms 相位偏差，因此只是回看时间线后得到的近似指标，可能包含短暂静止；汇总还要求该后缀中实际出现过目标附近帧。它不能替代首次目标画面的响应时间，也不能证明完全无卡顿。中断的测试进程或并发操作污染的结果不构成应用崩溃证据。

## 音频与预载回归

`NativeSeekLatencyTest` 使用 B2 样片，音频检查要求系统媒体音量非零且未静音；它核对测试进程的 AudioFlinger 状态，不能用系统静音证明应用静音。先记录原音量，必要时显式运行 `DeviceTestCleanup#setLowestAudibleMediaVolume` 并传入 `-e benchMediaVolume lowest`，设置为最大档位的约十五分之一。只有原音量确实为零时，才使用 `DeviceTestCleanup#restoreSilentMediaVolume -e restoreMediaVolume 0` 恢复；其他原值应按记录恢复。两个辅助方法默认跳过，不随普通测试运行改变音量。

`PortraitPreloadTest` 还需生成的 `01_Aurora.mp4`、`02_Horizon.mkv`、`04_Portrait_Loop.mp4`。真机使用专用目录并传入 `-e fixtureRoot /storage/emulated/0/Movies/专用目录`，查询将同时限制完整路径，避免打开用户同名视频。不要将缺少样片、静音或系统未派发同UID焦点中断的前置条件问题归为播放缺陷。

## 清理

结果可能包含设备型号、包名、路径和界面截图。导出后仅保留需要的测试证据，不提交手机原始截图或其他私人数据。清理时只删除本次运行的 `test-only/seek-benchmark/<benchRun>` 目录和上表三个明确文件；可在手机文件管理器中操作。若更改过系统音量或手动 VLC 设置，恢复原值。不要使用清除数据、卸载现有播放器或整个共享存储目录的递归删除命令。
