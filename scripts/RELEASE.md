# 正式发布

正式 Release 只附带 ARM64、ARM32 两个独立 APK。全架构、x86、x86_64 产物保留给开发和模拟器，不上传到正式 Release；不要将 APK 二进制提交进 Git。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 assembleRelease -PpreviewInstall=true -PofficialRelease=true
```

`officialRelease` 关闭全架构包，仅生成 `app-arm64-v8a-release.apk`、`app-armeabi-v7a-release.apk`。两个版本使用相同版本号、包名和签名。省略该选项时仍可生成开发用的全部架构包；构建目录可能保留早先产物，发布时不得直接使用通配符上传。

完成必要验证、合并到 `main` 并提交后，准备只描述本次已执行检查的 UTF-8 验证记录，再使用 Python 3.11 或更新版本执行打包脚本。以下变量由发布者填写，不使用历史结果替代本次证据：

```powershell
python .\scripts\package-release.py `
  --source-commit $releaseCommit `
  --arm64 .\app\build\outputs\apk\release\app-arm64-v8a-release.apk `
  --arm32 .\app\build\outputs\apk\release\app-armeabi-v7a-release.apk `
  --expected-arm64-sha256 $arm64Sha256 `
  --expected-arm32-sha256 $arm32Sha256 `
  --verification $verificationFile
```

脚本从提交中的版本号生成新的 `dist/MediaMaster-版本号/` 目录，拒绝覆盖已有目录，并校验：

- 两个 APK 的包名、版本、签名一致，原生库各自只含对应 ARM 架构。
- ARM64 与 ARM32 的 DEX、资源和素材一致。
- 源码包来自精确提交，不附带构建输出、密钥、本机工具或私人媒体。
- 附件固定为 `-arm64.apk`、`-arm32.apk`、`-source.zip`、`INSTALL.txt`、`VERIFICATION.txt`、`SHA256SUMS.txt` 六项。

脚本只打包，不创建标签或发布 Release。发布后核对标签与主线提交、正式发布状态、六项附件及公开下载文件的 SHA-256。ARM32 的静态校验不能代替对应设备上的运行验证，验证记录应如实区分。
