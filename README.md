# 病程日历

一款完全离线的个人病程记录 Android 应用，适用于支持安装 Android APK 的华为、荣耀、Redmi、小米及其他 Android 手机。最低 Android 8.0，不支持 HarmonyOS NEXT 原生系统。

## 已实现功能

- 月历中的每条病历使用独立浅色圆角标签，标题最多显示两行；每天最多显示两条和 `+N`。
- 日历支持按标题、分类、症状、诊断、治疗、用药、医院、医生和备注搜索，点击结果可定位日期。
- 记录就诊前后病情、诊断、治疗、医院、医生、用药和备注。
- 每份病历可保存多张图片和多个 PDF，并在应用内查看。
- 一次性、每 N 天、每 N 周指定星期、每 N 月锚点复查提醒。
- 每日多个服药时间、通知快捷打卡、已服/跳过记录和剂量历史快照。
- 数据库 SQLCipher 加密，密钥由 Android Keystore 保护。
- 无网络权限；附件位于应用私有目录，锁屏通知隐藏医疗详情。
- 密码加密的完整备份与事务式恢复，包含附件校验。

## 工程结构

- `data/`：Room 实体、DAO、SQLCipher 数据库、附件存储和统一仓库。
- `domain/`：复查重复规则与月末锚点计算。
- `reminders/`：精确/降级闹钟、通知、开机与时区恢复。
- `backup/`：AES-256-GCM 备份、PBKDF2 密钥派生、JSON 清单和附件校验。
- `ui/`：日历、复查、用药、设置四个 Compose 页面。

应用没有医疗决策能力，不提供诊断、处方、药物相互作用或剂量建议。

## 构建

推荐用 Android Studio 打开本目录，使用 JDK 17 和 Android SDK 37 构建。

命令行：

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
./gradlew.bat assembleRelease
```

自行发布签名版本时，可运行 `scripts/generate-release-keystore.ps1` 生成 `release-private/keystore.properties`。`release-private/` 已被 `.gitignore` 排除；发布密钥绝不能提交到 GitHub，并且必须单独安全备份。

## 开源许可

本项目采用 [Apache License 2.0](LICENSE) 开源。欢迎学习、修改和提交改进；使用本项目时请遵守许可证及所在地适用的法律法规。

## 隐私和数据安全

- 不声明 `INTERNET` 权限，不进行账号登录、云同步、统计或崩溃上报。
- Android 应用沙箱和 SQLCipher 共同保护本地数据。
- 数据库使用 WAL 与 `synchronous=FULL`，启动时执行完整性检查；不使用任何破坏性迁移或自动清库策略。
- 附件先写入临时文件、同步到存储并校验格式与 SHA-256，成功后才建立数据库引用。
- 恢复备份先完成全部验证，再安装到独立附件目录，最后用单个数据库事务切换；中断不会让当前数据引用半成品。
- 加密备份密码不会保存，也无法找回。
- 卸载应用会删除数据库和私有附件；卸载前必须导出备份。
