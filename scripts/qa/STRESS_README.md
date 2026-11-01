# 持续定位压力测试

本测试覆盖每秒 10–12 次、持续 10–30 秒、反复跨区点击进度条的场景。它与原有六次点击、30 秒 / 25 fps 的光学基准分开记录，不混合排名。

## 公开合成样片

```powershell
python scripts/qa/generate_seek_stress_fixture.py `
  --ffmpeg "C:/Tools/ffmpeg/bin/ffmpeg.exe" `
  --output .tools/seek-stress-public
```

输出 `mm_seek_stress_720p60_gop250.mp4`：8 秒、1280×720、H.264 High、60 fps、每 250 帧一个关键帧，并包含 B 帧和 44.1 kHz AAC 音轨。画面来自 FFmpeg 合成图案，不使用私人视频。它覆盖长参考链及重复定位负载，不代表任何特定私人视频的完整编码结构。

脚本检查 480 帧时间戳、实际 I/P/B 帧数量、关键帧位置及九帧条码，记录命令、工具版本和 SHA-256 至 `stress-fixture.json`。默认使用现有 FFmpeg 的输入元数据及完整 `showinfo` 输出验证，无需安装 FFprobe；已有 FFprobe 时可额外传入 `--ffprobe` 使用其结构化记录。帧号 `n` 对应 `n × 1000 / 60 ms`，不能按原 25 fps 基准的 `n × 40 ms` 解读。不同编码器版本可能生成不同字节。

默认拒绝覆盖已有视频、编码日志和清单。`--verify-only` 只读验证；明确替换时才加 `--overwrite`。原 `generate_seek_fixtures.py` 的默认协议保持不变。不要把此 8 秒样片交给要求 30 秒视频的 `OpticalSeekBenchmark` 或 `SeekTouchCalibration`。

## 运行

先按[基准说明](README.md)安装与应用签名一致的测试辅助包。以下命令仅操作独立的合成样片目录；`$qaSerial` 必须指向已确认的设备。

```powershell
$qaApp = "io.mediamaster.app.preview"
$qaRunner = "$qaApp.test/androidx.test.runner.AndroidJUnitRunner"
$qaRun = "public-60fps-12hz-playing"
adb -s $qaSerial shell mkdir -p /sdcard/Movies/SeekStressPublic
adb -s $qaSerial push .tools/seek-stress-public/mm_seek_stress_720p60_gop250.mp4 /sdcard/Movies/SeekStressPublic/mm_seek_stress_720p60_gop250.mp4
adb -s $qaSerial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/SeekStressPublic/mm_seek_stress_720p60_gop250.mp4
adb -s $qaSerial shell am instrument -w -r `
  -e class io.mediamaster.app.player.SustainedSeekStressTest#measureSustainedSeekTaps `
  -e benchFile mm_seek_stress_720p60_gop250.mp4 `
  -e benchRelativePath Movies/SeekStressPublic/ `
  -e benchAlias public-60fps -e benchRun $qaRun `
  -e benchHz 12 -e benchSeconds 12 -e benchState playing `
  -e benchBarcodeFps 60 -e benchBarcodeFrameCount 480 `
  -e benchEngine 0 -e benchNativeDecoder 0 -e benchInput physical `
  -e benchSteadySeconds 16 -e benchRequireSameDecoder true `
  $qaRunner
adb -s $qaSerial pull "/sdcard/Android/data/$qaApp/files/test-only/seek-stress/$qaRun" ".tools/qa-results/$qaRun"
```

`benchState=paused` 测暂停定位，此时省略 `benchSteadySeconds`。`benchHz=10` / `12` 与 `benchSeconds=10` / `20` / `30` 组成持续负载矩阵。`benchInput=direct` 是直接调用同一手势接口的诊断对照，不能替代 `physical` 的真实事件送达验证。`benchEngine` 为 `0` 自动、`1` Media3、`2` LibVLC；`benchNativeDecoder` 为 `0` 自动、`1` 硬解优先、`2` 软解。覆盖值在结束时恢复。

测试只按明确文件名和文件夹查询媒体索引，或使用显式 `benchUri`。可测试已授权提供的媒体，但使用中性 `benchAlias`；不提交原始媒体。结果仅保存实际视频 Surface 的哈希、颜色差和状态，不保存画面、文件名或媒体 URI。所有主线程查询有截止时间；辅助包仍可能需要由外部自动化处理整个应用进程失去响应的情况。

## 证据与限制

- `cadence` 验证实际发送及应用窗口收到的 DOWN/UP 数量、频率、间隔、延迟。异步注入被系统接受不等于应用已处理；直接接口模式没有窗口送达证据。
- 最后一跳前固定回到较低位置，再跳向 65%，减少“忽略最后一跳但自然播放经过目标”的误判。最终画面对照同一次运行中、单独定位产生的参考画面；播放状态再检查有序画面前进，暂停状态要求连续匹配并稳定至少 300 ms。
- v3 会重新解析当前播放器的实际 Surface，记录 `surfaceTransitions` 和采样不连续点，避免解码器回退后继续读取已拆除的旧 Surface。测量中发生播放器或 Surface 替换时，原单次定位参考作废，该运行只作为诊断记录，不能因此判为通过。`nativeSeekDiagnostics` 单独记录队列在途/待提交请求、帧序号、监听状态和截止时间；旧版不存在的字段为 `null`。
- 参考图像来自同一播放器的单次定位，而非独立解码的时间戳，因此它能证明可重复的画面恢复，不能证明逐帧 PTS 准确。播放器时钟和解码统计是分开的诊断数据，不作为画面就绪证据。
- 公开合成样片可显式启用 `benchBarcodeFps=60`、`benchBarcodeFrameCount=480`。仅接受索引中名称为 `mm_seek_stress_*.mp4` 的约 8 秒文件；采样宽度提高至 256 像素，直接读取有颜色边界、奇偶校验和多行一致性验证的帧号。`decodedFrameMs = 帧号 × 1000 / 60`，与实际整数触点请求对比，允许 150 ms 的原生预滚差异。暂停要求同一目标帧连续稳定至少 300 ms；播放要求至少四个连续、向前且符合时间进度的采样，跨度至少 300 ms、帧时间前进至少 250 ms。此时 `finalTargetRecoveryMs` 和 `barcodeOracle` 是独立位置证据，不再依赖 RGB 参考匹配。私人样片默认 `benchBarcodeFps=0`，维持原 96 像素路径且不执行条码识别。
- `longestBurstUnchangedMs` 仅覆盖持续点击区间，应与最终恢复时间一起看。`longestObservedUnchangedMs` 包含松手后的观察区间；暂停后画面理应不变，不能把此值作为暂停卡顿。
- 当前“运行通过”只代表持续点击未出现至少 2 秒的画面停滞、最终参考恢复不超过指定上限、事件送达与后续状态符合断言。默认恢复上限是 1,500 ms；这不是“低于 500 ms”或“无卡顿”的证明。若产品验收要求更短，应检查原始时间线和严格目标，不能凭绿色结果宣布达成。
- 可选稳态尾测每约 80 ms 采样画面、每 2 秒记录解码/显示/丢帧统计。跨循环或播放器重建时标记计数器重置；不将负差值算作帧率。`benchRequireSameDecoder=true` 会在发生解码器替换时失败，用于排除性能改动被自动回退掩盖。

可只读汇总显式结果文件：

```powershell
python scripts/qa/summarize_seek_stress.py .tools/qa-results/public-60fps-12hz-playing/results.json
```

输出点击期间的采样画面陈旧时间 P95 / 最大值、最长画面变化间隔和采样缺口。颜色差阈值为 0.8；这些指标反映被观察到的画面内容变化，不是硬件呈现时刻或独立解码 PTS。大的采样缺口可能漏掉中间变化，必须一起报告，不应当作已确认的冻结。

结束后仅清理这次创建的样片和 `test-only/seek-stress/<benchRun>` 结果。恢复临时音量或保持唤醒设置；不清除应用数据，不卸载用户播放器，不递归删除共享目录。
