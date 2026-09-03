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

当前为功能原型：已包含加密本地状态、日历搜索、病历附件、复查、用药和隐私提醒。`.htbackup` v2 导入导出、完整编辑/删除流程、自动化测试及真机验收尚未完成，因此暂不作为客户发布版本。
