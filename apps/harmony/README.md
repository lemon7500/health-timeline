# HarmonyOS NEXT 客户端

这是纯血鸿蒙 ArkTS / ArkUI 工程，不是 Android APK。目标能力与 Android 一致：日历病历、附件、复查提醒、用药打卡以及 `.htbackup` v2。

当前电脑尚未安装 DevEco Studio，也没有华为开发者账号或 HarmonyOS NEXT 测试设备，因此仓库提供工程源码、加密 RDB 数据层和平台适配接口，但签名 HAP 与真机后台提醒必须在这些条件具备后验收。

当前为功能原型，`.htbackup` v2、完整附件导入查看、全部编辑/删除流程和故障注入测试尚未完成，不能直接作为客户发布包。

导入 DevEco Studio 后：

1. 安装仓库声明的 HarmonyOS SDK。
2. 登录华为开发者账号并配置调试签名。
3. 执行 `hvigorw clean assembleHap`。
4. 按 `docs/DEVICE_QA_CHECKLIST_HARMONY.md` 完成模拟器和真机检查。
