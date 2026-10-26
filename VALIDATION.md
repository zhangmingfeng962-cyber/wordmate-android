# 词伴 1.2.0 验证报告

发布检查日期：2026-10-26，Asia/Shanghai。包名 com.wordmate.app，versionCode 4，最低 Android 8.0 / API 26。

## 本地自动检查

96 项本地测试通过，0 失败、0 错误、0 跳过。

| 测试组 | 数量 | 结果 |
|---|---:|---|
| ApiCompatibilityTest | 12 | 通过 |
| ContentServiceTest | 12 | 通过 |
| EnrichmentPipelineTest | 6 | 通过 |
| LearningRulesTest | 19 | 通过 |
| QueueAndMixedTest | 19 | 通过 |
| StageRulesTest | 11 | 通过 |
| StorageAndSessionTest | 11 | 通过 |
| V12StorageTest | 6 | 通过 |

覆盖四环节顺序、当前环节仅循环错词、200 轮反复答错的有界快照、显式继续、提示计分、首次错误保留、两个复习维度独立计分、断点恢复、删除旧词不跳过当前词、缺图暂缓、旧版半途听写补入听音选词、旧版已用提示状态迁移、版本 3 录音备份及版本 1/2 导入。

## Android 模拟器与升级

Android 16 / SDK 36.1 x86_64，360×640 dp。20 项设备测试通过，无失败或跳过：

- 8 项既有首页、词本、手动输入、OCR、答案隐藏、Keystore 和语音故障检查。
- 3 项后台队列与 SVG 图片选择检查。
- 3 项账户提示、按词失败详情和本机词形查询检查。
- 1 项使用实际 1.1.1 发布版数据库的同签名覆盖升级检查：5 个测试词、手动资料、复习记录、答题历史、会话、接口配置与加密密钥哈希保持一致。
- 5 项 1.2 检查：有授权真人 Ogg 录音经 Android MediaPlayer 实际正常/慢速播放完成；答对仍显示完整词卡并等待继续；词义、听音选词、听写答前隐藏各自不应展示的资料。
- 在 1.1.1 发布版抓取只读升级基线，另外通过 1 项；没有把旧 1.0 专用夹具测试的跳过计作通过。
- 最终签名发布版覆盖安装后冷启动成功，版本 1.2.0 / code 4；启动耗时约 655 ms。

升级证据：{"all_snapshot_fields_preserved": true, "word_count": 5, "key_present_and_hash_unchanged": true}。

## 录音验证边界

设备播放测试使用从 Wikimedia Commons 下载的原始 En-us-apple.ogg：作者 Dvortygirl，CC BY-SA 3.0，正常和慢速均播放完成。测试录音只在 androidTest 资产中，正式 APK 不预置该学习词。

录音选择、口音和许可判断有自动测试；在线来源可能缺词、限流或不可达。此次没有将“所有词在线自动取得真人录音”作为已验证结果。来源不可用时必须提示，并由使用者选择系统语音备用。没有执行真实付费 AI 调用。

## 构建与文件

- JDK 17、Gradle 8.7、AGP 8.6.1、Kotlin 2.0.21、SDK 35。
- testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest、assembleRelease 成功。
- Lint：0 错误、3 项警告：既定 target SDK 35、v26 图标目录限定冗余、JVM 测试依赖 androidx.test:core 存在更新版本。
- 原签名证书 SHA-256：10e14a50ec7efb1b661f705944a95236fe9f865c6b5ff988e41b412d7b501d1f；APK v2 签名和 ZIP 4 字节/原生库 16 KB 对齐检查通过。
- APK 大小 59,791,342 字节；SHA-256：dc99243844de7d7bb2028ef9532958859e151361f7d0d2b90f3fa1fbdb8611a6。
- 1.0.0、1.1.0、1.1.1 原交付文件按各自校验清单复核未变。
- 源码包排除缓存、构建目录、local.properties、签名文件和凭据；公开应用初始词本为空、AI 默认关闭。

没有在用户实体手机安装。实体手机的音质、厂商后台限制、相机和提醒送达仍需相应设备验证。GitHub Actions 的远端结果在仓库 Actions 页面单独查看，不用本地测试结果代替远端结果。
