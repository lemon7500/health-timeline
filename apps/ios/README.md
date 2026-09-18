# iOS / iPadOS 客户端

最低 iOS 16。界面使用 SwiftUI，本地结构化数据使用 SQLCipher，数据库密钥存放在 Keychain，附件写入受 Data Protection 保护的应用目录，本地提醒使用 `UserNotifications`。

构建需要 macOS、Xcode、CocoaPods 与 XcodeGen：

```bash
./gradlew :shared:core:linkDebugFrameworkIosSimulatorArm64
cd apps/ios
xcodegen generate
pod install
xcodebuild -workspace HealthTimeline.xcworkspace -scheme HealthTimeline -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build
```

当前 Windows 开发机不能执行以上 Xcode 构建；GitHub Actions 会在 macOS runner 上验证模拟器编译。真机签名与 TestFlight 需要 Apple Developer 账号。

当前为开发预览版，数据协议已对齐 `.htbackup v4`：

- 家庭成员切换与成员数据隔离。
- 月历、中文搜索、完整病历字段、图片/PDF 导入查看及附件单独安全删除。
- AI 整理提示词、多条模板逐条展开核对、原文与顺序校验及单事务批量保存；应用本身不联网。
- 复查默认提前 3 天、本地通知与按月锚点计算。
- 用药月历、5 分钟时间选择、补记/修正和历史计划有效期。
- SQLCipher、Keychain、Data Protection、系统身份验证锁。
- v2/v3 转 v4、附件 SHA-256 校验、导入预览、逐项冲突选择及“默认保留本机”的安全合并。
- 整体替换前自动创建加密安全备份（最多保留 3 份），并可从设置页导出最近一份。
- Xcode 单元测试覆盖复查日期、旧状态升级、加密容器、默认保留和显式采用导入版本。

尚未在本机完成 Xcode 编译或 iPhone/iPad 真机验收；CI 负责模拟器编译与测试。流式大备份、相机拍摄和通知内直接打卡仍待完成。当前为避免一次性加解密耗尽内存，导出附件总量暂限 120 MB、导入容器暂限 128 MB，所以暂不提供客户安装包。

真机发布前请逐项执行 [`docs/DEVICE_QA_CHECKLIST_IOS.md`](../../docs/DEVICE_QA_CHECKLIST_IOS.md)。
