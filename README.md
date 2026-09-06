# 病程日历 / Health Timeline

[![build-and-test](https://github.com/lemon7500/health-timeline/actions/workflows/ci.yml/badge.svg)](https://github.com/lemon7500/health-timeline/actions/workflows/ci.yml)

[下载最新版 Android APK](https://github.com/lemon7500/health-timeline/releases/latest) · 升级时请直接覆盖安装，不要卸载旧版本。

一个完全离线、隐私优先的个人病程记录项目，覆盖 Android、iPhone/iPad 与 HarmonyOS NEXT。项目不提供诊断或处方建议，也不连接医院系统。

## 平台状态

| 平台 | 技术 | 当前状态 |
|---|---|---|
| Android 8+ | Kotlin、Jetpack Compose、Room、SQLCipher | 1.2.0 发布候选；必须完成华为、荣耀、Redmi 覆盖升级验收后再标记稳定版 |
| iOS/iPadOS 16+ | SwiftUI、SQLCipher、Keychain、PDFKit | 功能原型源码；尚未达到客户发布标准，需 macOS CI 与真机继续开发验证 |
| HarmonyOS NEXT | ArkTS、ArkUI、加密 RDB | 功能原型源码；尚未达到客户发布标准，需 DevEco Studio、账号和设备继续开发验证 |
| 共享核心 | Kotlin Multiplatform | 数据模型、校验、搜索、复查计算、合并规则已接入 Android |

## Android 现有功能

- 月历标题显示两行、独立浅色圆角标签，每天最多两条和 `+N`。
- 家庭多人档案，全局切换成员；搜索、日历、复查和用药严格按当前成员隔离。
- 按标题、病情分类、症状、诊断、治疗、用药、医院、医生和备注搜索。
- 保存就诊记录、多张图片和多个 PDF。
- 快速录入支持粘贴一大段带字段标签的文字；可按“日期”拆成多天、多条病历，预览后一次保存。
- 一次性、每 N 天、每 N 周和每 N 月锚点复查提醒。
- 每日多个服药时间、5 分钟步进滚轮选时、已服/跳过打卡和历史剂量快照。
- 可选系统指纹、面容或锁屏密码保护；锁屏通知隐藏姓名、病情和药名。
- 本地加密存储且无网络权限。
- `.htbackup` v3 全家庭加密备份；兼容导入 v2 单人备份，导入前完整验证并按 UUID 去重、预览冲突。

iOS 与 HarmonyOS NEXT 当前已完成原生工程、加密存储、主要页面和提醒原型；跨平台备份、完整附件流程、自动化测试和真机验收仍是待完成工作，不能把当前源码当作正式客户安装包。

## 仓库结构

- `apps/android/app`：已发布 Android 客户端。
- `apps/ios`：SwiftUI iPhone/iPad 客户端。
- `apps/harmony`：HarmonyOS NEXT 原生客户端。
- `shared/core`：Kotlin Multiplatform 共享业务规则。
- `shared/spec`：备份 JSON Schema、加密容器说明和黄金测试向量。
- `docs`：安装、隐私、安全和真机测试文档。

给客户的简明说明见 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md)。

## GitHub 下载与升级

正式发布后，从 [Releases](https://github.com/lemon7500/health-timeline/releases) 下载 `HealthTimeline-1.2.0.apk`，并用同目录的 `SHA256SUMS.txt` 校验文件。应用不联网、不会自行检查更新。

从旧版升级必须直接安装新版 APK 覆盖原应用。不要卸载旧版、不要清除应用数据；这两种操作都会删除手机里的私有资料。1.2.0 保持 `applicationId=com.healthtimeline.app`、原发布签名和正式 Room 数据迁移。

## Android 构建

使用 JDK 17 与 Android SDK 37：

```powershell
./gradlew.bat :shared:core:testDebugUnitTest :androidApp:testDebugUnitTest
./gradlew.bat :androidApp:assembleDebug
./gradlew.bat :androidApp:assembleRelease
```

Windows 环境若遇到 Java loopback/pipe 错误，可运行 `scripts/test-windows.ps1`。自行发布时使用 `scripts/generate-release-keystore.ps1`；`release-private/` 不得提交，且必须单独安全备份。

iOS 与鸿蒙构建方式分别见 `apps/ios/README.md` 和 `apps/harmony/README.md`。

## 数据安全原则

- 三端不申请网络权限，不包含账号、广告、统计或云同步。
- Android 保持 `applicationId=com.healthtimeline.app` 和原发布签名；数据库禁止破坏性迁移。
- 写入附件时先落临时文件、同步并校验 SHA-256，成功后才提交数据库引用。
- 导入备份必须先验证密码、版本、空间、引用关系和全部附件；失败不得改变当前数据。
- 合并冲突默认保留本机。整体替换属于高级操作，应用会先要求用户导出当前数据安全备份，并在私有目录保留最近三份自动安全备份。
- 卸载应用会删除应用私有数据，卸载前必须导出备份。

## 开源许可

本项目采用 [Apache License 2.0](LICENSE)。
