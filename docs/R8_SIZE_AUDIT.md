# R8 保留面与安装体积

本次精简只移除未使用的 Java 字节码、Android 资源和非运行时符号。播放器调度、解码选项、媒体解析器、原生库打包方式与图案像素不在 R8 改造范围内。R8 会优化 Java 实现，但不重新编译或裁剪 LibVLC 中的媒体解码器。

## 构建配置

Release 启用 `minifyEnabled`、`shrinkResources`，使用 AGP 的 `proguard-android-optimize.txt` 和项目 `app/proguard-rules.pro`。Debug 保持未混淆，供已有内部状态测试使用。AGP 版本仍为 8.9.2。

安装后仍保留 APK；本项目当前还会提取原生库。因此安装占用需同时核对 APK、提取的 `.so`、系统生成的 ART 文件及应用数据，不能把 APK 的下载体积当作安装体积。R8 减少 DEX，并可能减少系统生成的 ART 文件；ART 的实际变化须在同一系统、同一编译状态下测量。

## 原生调用边界

所用 `org.videolan.android:libvlc-all:3.7.7` AAR 未附带 consumer rules。根据其 `classes.jar` 方法签名和原生库中的 JNI 导出/名称查找，保留以下契约：

| 保留项 | 原因 |
| --- | --- |
| 含 `native` 方法的 LibVLC 类、原生方法及其类型描述符 | 导出名称和 `RegisterNatives` 绑定依赖这些名称 |
| `VLCObject.mInstance`、事件分发方法 | JNI 保存原生句柄并回调 Java 事件 |
| `MediaPlayer.Equalizer.mInstance` | JNI 读取均衡器句柄 |
| Media / MediaPlayer / MediaDiscoverer / RendererDiscoverer 的 `create*FromNative` 工厂 | JNI 以名称及完整签名构造轨道、标题、章节、统计和发现结果 |
| Dialog 的 `*FromNative` 回调 | JNI 在加载库时查找这些方法 |
| AWindow 的 Surface、SurfaceTexture、布局与注册方法 | `libvlc.so` 的视频输出模块按名称调用 |

包含描述符的规则同时保护返回类型与参数类型的名称。项目规则没有使用 `-keep class org.videolan.** { *; }`，也没有新增全局 `-dontwarn` 隐藏缺失依赖。原生代码不以 Java 可见调用图为边界，因此即使当前界面不直接使用某个 JNI 入口，也不能仅依据 Java 引用删除它。

Media3 1.11.1 的 common、exoplayer、extractor、ui AAR 已提供 consumer rules，覆盖可选扩展的反射入口及相关类型；继续合并其上游规则。升级 LibVLC 或 Media3 后，必须重新核对上述边界。

## 应用持久化与资源

生产源码没有自定义类名反射，也没有通过字符串 `getIdentifier` 查找图案。Manifest 中的 Activity、XML 引用和依赖 consumer rules 由 Android 构建工具处理。

`VideoItem` 是唯一的应用 Serializable 类型，用于文件操作期间的 Activity 保存状态。保留其类名、字段名和 `serialVersionUID`，避免升级或进程重建时破坏保存状态。队列、索引和权重分别使用显式 JSON 字段或数据库列，不依赖 Java 字段名，未追加宽泛保留。

资源压缩保留代码/XML 实际引用的资源，包括进度条人物、点赞图案、图标、PlayerView 和字幕所需资源；不通过删语言、删密度、降低图案分辨率或减少媒体类型达到体积目标。

## 验证边界

- 检查 release 构建生成的 `configuration.txt`、`seeds.txt`、`usage.txt`、`mapping.txt`，确认关键 JNI 入口未改名或删除，且应用与依赖代码确实参与精简。
- `mapping.txt` 随构建证据单独留存，用于还原错误堆栈；不放入 APK。保留源码名和行号，便于诊断。
- 既有 androidTest 多次通过反射读取私有播放器状态。它们适合验证 debug 的播放逻辑，不能证明真实混淆产物可用，也不应因此扩大正式包的 keep 范围。
- 真实 release 需通过独立的公开 Intent / UI 操作验证启动、目录、设置、播放、定位、竖屏切换、图案及原生回调；如果测试工具依赖内部类或方法，必须明确其并非纯黑盒验证。
- 模拟器验证可发现 JNI 名称丢失、资源缺失和功能回归；不能证明所有实体设备的硬件解码兼容性，也不能代替特定实体设备的定位延迟测量。

`scripts/verify-r8-contract.py` 直接读取 APK 内各个 DEX 的类定义及 encoded 字段/方法，核对原生入口、回调完整签名、`native` / `static` 绑定、描述符类型和保存状态字段；再核对 mapping 中的稳定类名。它不把字符串表里出现一个方法名视为保留成功。基准 APK 必须是使用相同 LibVLC 版本的未混淆产物：

```sh
python scripts/verify-r8-contract.py --baseline-apk baseline.apk --apk candidate.apk --mapping app/build/outputs/mapping/release/mapping.txt
```

检查结果写到标准输出，失败时返回非零退出码，不修改 APK。该检查证明静态契约仍存在，不声称已执行全部原生回调。

进一步减少安装体积的高收益方向通常是原生库本身或其提取策略。本轮保持打包方式，并完整保留现有原生编解码能力；不为节省空间删除解码器、硬件路径、字幕或音轨支持。

参考：[Android keep rules](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)、[常见保留场景](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples)。
