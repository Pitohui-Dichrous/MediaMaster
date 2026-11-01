# 安装体积优化：2.5.1-dev.1

开发分支 `codex/size-optimization`，基于正式版 `v2.5.0`（`c499b4baa316d1262d89d1a175579521031c3687`）。版本代码为 32，沿用现有包名和签名。本轮不发布正式 Release，不修改播放、定位、解码调度、图案变换或原生库提取方式。

## 2.5.0 的体积组成

APK 不是安装后唯一保留的文件。当前 `useLegacyPackaging=true`，APK 中压缩的原生库还会被解出一份；Android 另生成与系统版本、编译策略、使用情况有关的 ART 文件。以下统一使用 MiB（1,048,576 字节），不混用十进制 MB。

| ARM64 2.5.0 项目 | 逻辑字节 | MiB |
| --- | ---: | ---: |
| 保留的 APK | 34,235,698 | 32.65 |
| 解出的三个原生库 | 55,263,312 | 52.70 |
| 上述两项合计 | 89,499,010 | 85.35 |

原生库中，`libvlc.so` 为 45,875,392 字节，`libc++_shared.so` 为 9,290,184 字节，`libvlcjni.so` 为 97,736 字节。

已有的 2.5.0 安装目录记录按磁盘分配块统计：APK 为 32.69 MiB，提取库为 52.77 MiB，合计 85.46 MiB。用户报告的系统界面总量为 101.71 MB；若按相同二进制口径换算，差值约 16.25 MiB。该旧记录未能读取 ART 目录，不能将差值全部认定为 ART 或缓存，也不能假定系统 UI 的统计单位。本轮没有连接实体手机，没有重新测量手机上的总量。

## 已实施

- 固定 NDK r27d（27.3.13750724），由 Android 构建工具剥离非运行时调试/符号信息。ARM64 的 C++ 运行库从 9,290,184 降到 1,374,336 字节，减少 7,915,848 字节。全部原生库的装载内容、动态符号、重定位和异常展开信息通过逐项比较；未删解码器或重编译原生实现。
- Release 启用 R8 优化及资源精简。DEX 从 11,806,392 降到 1,594,860 字节；类定义从 9,682 降到 1,368。LibVLC JNI、Media3 上游反射规则及保存状态字段得到保留，详见 [R8 保留面](R8_SIZE_AUDIT.md)。
- 移除没有代码或 XML 使用的 SwipeRefreshLayout 依赖；项目现有的下拉刷新组件不变。
- 四张图案只重压缩 PNG 的 IDAT 流。尺寸、原始像素、透明度、非 IDAT 元数据及显示变换不变。关闭 Release 的再次 PNG crunch，APK 内图片与验证后的源文件相同。相对旧 APK 的图片净节省 209,921 字节；源文件的 942,452 字节节省不能直接当作安装节省。
- R8 mapping、源码及验证记录留在构建档案中，不进入 APK。版权/许可证文本保留并修正过时的素材说明。

## 可确定的安装文件变化

这些数字是 **APK 文件 + 提取原生库的逻辑字节**，不包含 ART、应用数据、文件系统分配块取整，因此不是手机设置页的最终应用大小。

| 架构 | 2.5.0 | 开发版 | 减少 |
| --- | ---: | ---: | ---: |
| ARM64 | 89,499,010 B / 85.35 MiB | 75,676,413 B / 72.17 MiB | 13,822,597 B / **13.18 MiB** |
| ARM32 | 70,985,452 B / 67.70 MiB | 67,002,419 B / 63.90 MiB | 3,983,033 B / **3.80 MiB** |

ARM32 依赖原先已经剥离主要符号，因此该架构没有对应的 7.55 MiB C++ 库节省。不能套用 ARM64 的收益。

## 模拟器测量与验证

Android 14 x86_64 独立模拟器，用相同系统、同一安装方式及相同编译策略比较基线与精简产物。此结果仅说明精简也能减少系统生成的代码文件，不代表 ARM 手机的精确总量或定位性能。

| 实际 ART 状态 | 2.5.0 安装代码文件 | 开发版安装代码文件 | 减少 |
| --- | ---: | ---: | ---: |
| verify | 106.39 MiB | 83.49 MiB | 22.89 MiB |
| speed | 135.18 MiB | 89.51 MiB | 45.67 MiB |

按文件系统分配字节汇总 APK、提取库、oat/vdex，不包含应用数据。第一行请求 `speed-profile`，但空配置文件下系统实际选择 `verify`，故据实际状态标注。第二行为显式 `speed`。没有通过降低编译等级来制造节省。

- 同一组 192 项单元测试在 Debug、Release 下分别通过；Release lint 为 0 错误、28 警告。
- ARM64、ARM32、模拟器 x86_64 的签名、版本、ZIP 完整性、原生架构及 JNI 契约通过检查，代码与资源跨架构相同。
- DEX 定义检查覆盖 123 个原生方法、29 个回调、13 个字段和 26 个稳定类型；破坏性负例能被校验器拒绝。该检查不声称执行过每个 JNI 入口。
- Android `BitmapFactory` 对四张 PNG 在六档采样尺寸下比较，24 组 ARGB、尺寸与透明度完全相同；图片解码中位数变化约 -0.2 至 +1.9 ms，仅为模拟器测量，不是所有设备的性能保证。
- 实际混淆 APK 的黑盒检查使用公开播放 Intent 与屏幕操作，不依赖应用内部反射；确认播放画面、定位后的真实帧以及 LibVLC 和 Media3 各自的运行证据。
- 生成的 MP4、MKV、AVI、竖屏及长 GOP 样本覆盖三种解码偏好、随机入口、单击暂停、双击点赞/点踩和竖屏预载首帧。长 GOP 样本在模拟器上有两次定位超过初始 500 ms 截图窗口；2.5.0 同条件复核也出现该现象，随后均显示目标帧。没有据此宣称“零延迟”，也没有为通过检查修改播放策略。
- 同签名从 2.5.0 覆盖安装后，已有权重数据库与设置保留。没有在用户手机安装，也没有验证实体 ARM32/ARM64 硬件解码器。

## 其它方向

| 方向 | 本轮结论 |
| --- | --- |
| 改为直接从 APK 映射原生库 | 可能避免压缩库与提取库同时占空间；用户明确暂不更改，保持现状。不能只看 APK 变大/变小判断安装收益。 |
| 自行裁剪/重新编译 LibVLC | 原生播放器仍是最大组成，但删模块或改编译优化可能影响媒体、字幕、音轨、硬件路径和定位性能，本轮不做。 |
| Lossless WebP | 原始 RGBA 可相同，但 Android 实测采样存在像素/尺寸取整差异，解码也稍慢，未采用。 |
| 缩小图片、降色或有损压缩 | 会改变图案效果，未采用。 |
| 删除语言/设备密度资源或许可证 | 收益小且涉及可用性或必要信息，未采用。 |
| 清理缩略图、限制权重数据 | 缩略图已有有界内存缓存，没有图片磁盘缓存；权重库已有 5,000,000 字节上限。本轮不通过清除用户数据缩小统计量。 |

当前约束下，未发现另一个已验证且显著、同时不改变功能或运行路径的安全减重项。

## 复核

按 [Windows 构建说明](../scripts/BUILD_WINDOWS.md) 安装固定 NDK 后执行：

```powershell
.\scripts\build.ps1 assembleRelease testDebugUnitTest testReleaseUnitTest lintRelease -PpreviewInstall=true
```

以上生成开发架构产物；正式发版另加 `-PofficialRelease=true`，只交付两个独立 ARM 包。未获正式发布授权时，不创建正式标签或 Release。

```sh
python scripts/report-apk-footprint.py --baseline baseline-arm64.apk --candidate candidate-arm64.apk --output footprint.json
python scripts/verify-native-libraries.py --baseline baseline-arm64.apk --candidate candidate-arm64.apk --abi arm64-v8a --output native.json
python scripts/verify-r8-contract.py --baseline-apk baseline-arm64.apk --apk candidate-arm64.apk --mapping app/build/outputs/mapping/release/mapping.txt
```

基准需为同一 LibVLC 版本、未混淆且原生库未被本轮工具重新剥离的产物。两个体积/原生报告拒绝覆盖既有输出。图片优化工具 `scripts/optimize-png.py` 默认仅检查，写回必须明确 `--apply` 并指定新的备份目录；开发工具依赖不打入 APK。

参考：[Android 代码与资源优化](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[Android 应用体积组成与优化](https://developer.android.com/topic/performance/reduce-apk-size)。
