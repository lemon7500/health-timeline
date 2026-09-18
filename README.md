# 病程日历 / Health Timeline

[![build-and-test](https://github.com/lemon7500/health-timeline/actions/workflows/ci.yml/badge.svg)](https://github.com/lemon7500/health-timeline/actions/workflows/ci.yml)

[下载最新版 Android APK](https://github.com/lemon7500/health-timeline/releases/latest) · 升级时请直接覆盖安装，不要卸载旧版本。

一个完全离线、隐私优先的个人病程记录项目，覆盖 Android、iPhone/iPad 与 HarmonyOS NEXT。项目不提供诊断或处方建议，也不连接医院系统。

## 平台状态

| 平台 | 技术 | 当前状态 |
|---|---|---|
| Android 8+ | Kotlin、Jetpack Compose、Room、SQLCipher | 1.3.0 第三阶段候选；新增 30 天回收站与备份 v5，等待覆盖升级真机验收 |
| iOS/iPadOS 16+ | SwiftUI、SQLCipher、Keychain、PDFKit | 开发预览：成员隔离、主要页面、附件、提醒、用药月历、应用锁和 v4 安全合并已实现；待 CI/真机验收 |
| HarmonyOS NEXT | ArkTS、ArkUI、加密 RDB | 开发预览：真实录入、成员隔离、迁移回滚、提醒、用药月历和 v4 清单校验已实现；平台附件/加密 ZIP/应用锁待补 |
| 共享核心 | Kotlin Multiplatform | 数据模型、校验、搜索、复查计算、合并规则已接入 Android |

## Android 现有功能

- 月历标题显示两行、独立浅色圆角标签，每天最多两条和 `+N`。
- 家庭多人档案，全局切换成员；搜索、日历、复查和用药严格按当前成员隔离。
- 按标题、病情分类、症状、诊断、治疗、用药、医院、医生和备注搜索。
- 保存就诊记录、多张图片和多个 PDF；病历、附件、复查和药物删除后先进入 30 天回收站，可恢复或二次确认后永久删除。
- 快速录入支持字段模板和中文自然叙述；复杂长文可主动复制通用 AI 整理提示词，在外部完成脱敏整理后粘贴回来，逐条展开修改再保存。应用不会复制病历原文或连接 AI。
- 一次性、每 N 天、每 N 周和每 N 月锚点复查提醒；新建计划默认提前 3 天。
- 用药月历按天查看计划时间、实际时间和状态；支持补记、修正、5 分钟步进选时及历史计划/剂量快照。
- 可选系统指纹、面容或锁屏密码保护；锁屏通知隐藏姓名、病情和药名。
- 安全与提醒检查中心可核对通知类别、精确闹钟、电池优化、最近备份和数据完整性，并导出不含医疗正文的本地诊断报告。
- 本地加密存储且无网络权限。
- `.htbackup` v5 全家庭加密备份，包含回收站；兼容导入 v1、v2、v3、v4，导入前完整验证并按 UUID 去重、预览冲突。

iOS 与 HarmonyOS NEXT 的详细完成度和下一步见 [`docs/MULTIPLATFORM_STATUS.md`](docs/MULTIPLATFORM_STATUS.md)。两端目前仍是开发预览，不能把源码当作已经验收的客户安装包。

## 仓库结构

- `apps/android/app`：已发布 Android 客户端。
- `apps/ios`：SwiftUI iPhone/iPad 客户端。
- `apps/harmony`：HarmonyOS NEXT 原生客户端。
- `shared/core`：Kotlin Multiplatform 共享业务规则。
- `shared/spec`：备份 JSON Schema、加密容器说明和黄金测试向量。
- `docs`：安装、隐私、安全和真机测试文档。

给客户的简明说明见 [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md)。

## GitHub 下载与升级

正式发布后，从 [Releases](https://github.com/lemon7500/health-timeline/releases) 下载 `HealthTimeline-1.3.0.apk`，并用同目录的 `SHA256SUMS.txt` 校验文件。应用不联网、不会自行检查更新。

从旧版升级必须直接安装新版 APK 覆盖原应用。不要卸载旧版、不要清除应用数据；这两种操作都会删除手机里的私有资料。1.3.0 保持 `applicationId=com.healthtimeline.app` 和原发布签名，通过正式 Room 7→8 事务迁移增加回收站标记；原病历、附件、复查、用药计划、打卡、UUID 与附件路径均保留。

版本号遵循 `主版本.次版本.补丁版本`。同一功能线内的小修改按顺序递增最后一位，例如 `1.2.0 → 1.2.1 → 1.2.2`；每次发布同时递增 Android `versionCode`，不得复用已发布版本号。

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
