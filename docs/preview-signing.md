# 河图预览版固定签名

从 `0.4.0-test.87` 起，交付的测试 APK 使用河图专用的固定预览签名。
包名保持 `io.github.xgl34222220.hetu.preview`。这不是正式发布密钥，不得用于正式包或其他项目。

GitHub Actions 仍产出临时 debug 签名的编译结果。**下载 CI APK 后，必须先运行下面的重签工具，再交付给测试用户。** 直接分发 CI 原始 APK 仍会造成签名不一致。

## 固定身份与私钥保管

- 私有持久文件名：`hetu-preview.p12`。保存在项目所有者的私有持久存储，取用时放在仓库外；POSIX 权限必须为 `600`。
- 类型：PKCS12；别名：`hetu-preview`；测试用密码：`android`。文件本身必须保持私密；标准测试密码不提供额外保密保证。
- 公开证书：[signing/hetu-preview-cert.pem](signing/hetu-preview-cert.pem)。它只含公钥证书。
- 公开证书 SHA-256 固定值（对 X.509 DER 证书计算）：

```text
6cbba419309b93f031417601e19feaed3dd056a0cb06c2aaaa7694946338085d
```

该值同时固定在 `tools/sign_preview.py` 与 `docs/signing/hetu-preview-cert.sha256`。
仓库、Actions 缓存、构建产物和日志均不得保存或输出私钥。
私钥丢失时必须从私有备份恢复；工具不会自动生成另一把密钥，也不接受不匹配的证书。

## 本地重签

需要 Python 3.9+、Java JDK（含 `keytool`）及 Android SDK Build Tools 35+。
使用同一个经过 CI 验证的提交检出本仓库，下载该次 `Hetu-Debug` 产物并解压 `app-debug.apk`。
将已有私钥从私有存储恢复到仓库外，例如 `../signing/hetu-preview.p12`，然后执行：

```sh
chmod 600 ../signing/hetu-preview.p12
python3 tools/sign_preview.py \
  --input /path/to/app-debug.apk \
  --output /path/to/Hetu-0.4.0-test.87.apk \
  --keystore ../signing/hetu-preview.p12 \
  --build-tools "$ANDROID_HOME/build-tools/37.0.0"
```

输出文件必须尚不存在。工具先验证私钥对应的公开证书和预览包名，再执行 ZIP 对齐、重签、APK 验签、固定证书检查及 16 KiB ZIP 对齐检查，并逐文件确认 APK 内容与输入一致（签名元数据除外）。全部通过后才创建最终文件。它不会改动输入 APK 或私钥。

`--build-tools` 可省略，此时从 PATH、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 查找工具。
后续版本只需更改输出文件名，始终使用同一个已有私钥。交付时注明 APK 已通过此工具重签；不要将 CI 原始 APK 与最终签名包混用。

## 从旧测试版迁移

test.86 及此前部分测试版使用各次 CI 的临时密钥，旧私钥没有保留。本次固定签名不能覆盖不同证书的旧安装，也不能从旧 APK 恢复私钥。首次迁移前应保留配置、订阅与自定义设置；安装固定签名版本后，未来使用相同包名、相同预览签名且版本号更高的交付包才能保持正常覆盖升级。

正式版须另行建立正式签名流程，不应沿用这把预览测试密钥。

参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[apksigner](https://developer.android.com/tools/apksigner)、[zipalign](https://developer.android.com/tools/zipalign)。
