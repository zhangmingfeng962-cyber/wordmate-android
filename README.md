# 词伴 WordMate 1.2

只学习你自己添加的英语单词。中文界面，Android 8.0 及以上，Kotlin + Jetpack Compose。

## 下载和安装

在 [Releases](https://github.com/zhangmingfeng962-cyber/wordmate-android/releases/latest) 下载正式 APK 与 SHA-256 校验文件。
已安装正式旧版时直接覆盖安装，保留词本、图片、学习进度和本机加密密钥。建议升级前在设置中导出备份。

首次安装词本为空，AI 默认关闭，无需账号。可通过本机图片识别添加单词，也可手动输入或每行一个词批量粘贴。

## 四环节过关练习

每组最多 5 个词，顺序为：**单词选图片 → 单词选意思 → 听音选单词 → 听音拼写**。

- 当前环节先练一遍本组单词，再只循环答错的词，直到无提示答对才进入下一环节。
- 每题无论对错都显示完整词卡，包括图、词、中文释义、例句和拼读分块，点击「继续」才进入下一题。
- 词义题答前只显示单词、音标和选项，句图答后展示。听音选词和听写题隐藏目标词卡。
- 提示或「不认识」表示尚未过关；重考正确不抹掉首次错误，也不重复升级复习间隔。
- 缺少图片或有效图片选项时先练文字，图片故障不计错。
- 意思与听辨/拼写分开排程，间隔为 1、3、7、14、30、60 天，错误维度次日加强。
- 每天默认 10 个未学词及最多 20 个到期词，可调整。词典及干扰选项不会自动加入词本。

## 真人录音

按准确拼写和美式/英式口音寻找有来源与许可的真人录音，缓存成功后可以离线播放。来源页可查看作者、许可证和原始录音。

有些词或口音没有可核实的录音。界面会提示重试，或由你选择「使用系统语音继续」，不会把合成声音标为真人。题前必须成功播放一次才能作答。单词慢读使用同一录音降低播放速度；逐字母拼读和例句仍使用系统语音并明确标注。

录音查询依赖 Free Dictionary API 与 Wikimedia Commons；不能保证每个词都可取得录音。变形词不会用原形词录音冒充。

## 资料补全和 AI

「我的词本」顶部提供一键补全、失败项重试、暂停/继续和逐词详情。有释义即可先练文字，句图可在后台补齐。

本机词典优先，免费例句及图片并行查询；仍有缺项时，只有开启 AI 并保存配置后才调用服务。默认同时处理 2 个词、全局最多 4 个网络请求，AI 图片最多 1 个并发。免费查询单次 6 秒、下载 8 秒、每词免费阶段最多 20 秒；AI 文字 45 秒、图片 90 秒。

在设置中分别填写文字和图片服务的 HTTPS 根地址、模型和各自的 API Key，保存后再测试：

| 服务 | 接口 | 示例根地址 |
|---|---|---|
| 文字补全 | POST /chat/completions | https://你的服务商/v1 |
| 图片生成 | POST /images/generations | https://你的服务商/v1 |

支持通用 OpenAI 兼容协议。火山方舟官方接口对 Seed 2 关闭深度思考并请求 JSON；Seedream 5.0 Pro / Flash 使用 1K 单图。文字测试会进行简短调用；图片连接检查只查模型列表，不代表已获得生成权限。

API 费用由各自的服务商收取，项目不提供共享密钥。欠费、权限、参数和网络错误会分别显示；账户欠费时暂停后续 AI 队列，可处理账户后继续，或关闭 AI 先用免费资源。

## 数据、备份与隐私

词本和进度保存在本机，提供版本 3 ZIP 备份，兼容导入版本 1、2。备份包含已引用的句图、录音及练习状态，**不包含 API Key**。恢复会替换当前词本和进度。

API Key 使用 Android Keystore 加密存储。识别照片使用随 APK 打包的 ML Kit 模型在手机本地处理。联网查询会向相应服务发送单词或检索词；AI 会收到待补词条和必要例句。详见 [隐私说明](PRIVACY.md)。

## 构建和验证

需要 JDK 17、Android SDK 35 / Build Tools 35，以及 Google Maven 和 Maven Central 网络访问：

```powershell
.\tools\build.ps1 -JavaHome '你的JDK17目录' -AndroidSdk '你的AndroidSDK目录'
```

也可直接运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`。Gradle Wrapper 8.7、AGP 8.6.1、Kotlin 2.0.21。源码位于 `app/src/wordmate/kotlin/`，本地测试位于 `app/src/wordmateTest/kotlin/`，设备测试位于 `app/src/wordmateAndroidTest/kotlin/`。

正式签名在项目外配置。将 `WORDMATE_SIGNING_PROPERTIES` 指向私有 properties 文件，字段为 storeFile、storePassword、keyAlias、keyPassword；源码中不提供发布私钥。GitHub Actions 仅测试并构建调试版。

发布检查见 [VALIDATION.md](VALIDATION.md)。设备升级测试使用专用测试词本与占位密钥；不要向真实词本导入测试数据。

## 许可和贡献

应用代码采用 [MIT](LICENSE)。第三方词典、图片、录音和依赖保留自己的许可，详见 [NOTICE.md](NOTICE.md)。欢迎提交问题和改进。
