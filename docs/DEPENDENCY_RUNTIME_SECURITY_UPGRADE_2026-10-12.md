# Android 运行库安全升级：第二阶段

基线 `69de3ae`，分支 `feature/android-1.2-family-profiles`。本次仅提交并测试，不创建 Release、标签或合并主分支。第一阶段报告保留为历史记录。

## 改动及保持不变的内容

- Android SQLCipher binding `4.18.0 → 4.19.1`（实际原生核心为 `4.19.0`，不要混称版本）。原厂核心升级包含 `sqlcipher_export` 别名和无效 URI `hexkey` 两项低风险修补；当前应用未使用这两个入口，不代表以前病历已泄露或保存为明文。
- 普通 Java `bcprov-jdk18on 1.83 → 1.86`，保留轻量级 AES-GCM；不注册 BC Provider，不引入联网或额外算法。原核查并未确认本应用 AES-GCM 路径可达后续公告中的其他算法漏洞。
- 新增选定 debug/release 实际依赖及 SHA-256 检查，防止声明升版而实际解析为其他版本。
- `com.healthtimeline.app`、`1.3.1 / code 14`、原签名配置、Room v8、密钥保护流程、数据文件位置和备份清单 v5 不变。没有 `rekey`、`cipher_migrate`、清库、重新生成数据库密钥或破坏性迁移。
- HTBACKUP 外层版本 1、PBKDF2-HMAC-SHA256 210,000 次、AES-256-GCM 及密码规则不变。SQLCipher 自身的数据库 KDF 仍为 PBKDF2-HMAC-SHA512 256,000 次，两者不能混为一谈。

## 真实加密数据库兼容验证

`tools/sqlcipher-compat` 是独立测试应用，包名 `com.healthtimeline.compatprobe`，不会访问主应用或发布签名。仅有公开虚构资料和公开测试密码，禁止用于真实病历。

1. baseline flavor 使用真实 `4.18.0` 原生库，按生产 Room v8 JSON 建表及 identity hash；创建 9 张业务表、25 行资料，包含两个成员、病历原文及顺序、回收站、图片/PDF 校验、复查、历史用药剂量、结束疗程和打卡。
2. 分别生成正常关闭数据库和已提交的未检查点 WAL。保留 WAL 连接后强制终止合成进程，不以正常关闭冒充中断场景。
3. 导出未改动的旧引擎文件和全部字段快照，再 `adb install -r` 替换为 candidate `4.19.1`；不卸载、不清数据。检查内容、引用、索引、文件哈希、测试偏好、原生版本、KDF/页大小、完整性及写入重开。
4. 主应用仪器测试用真实生成的 `AppDatabase` 和 DAO 打开导出副本，逐表比较所有列和值，再写入重开。另一份不带 WAL 的独立副本必须无法重现完整旧资料，避免仅凭 WAL 文件存在就宣称回放成功。
5. 错误密钥测试不得删除或改写旧加密数据库，正确密钥之后仍能读取。主应用测试使用真实生产工厂；探针的只读错误密钥检查是另一个有限范围验证，不相互替代。

CI 的 API 35 执行全部现有 Room/仓库/Compose 测试及新增兼容测试；API 26 执行加密升级、生产 Room 集成和真实旧备份恢复。脚本限定模拟器并拒绝覆盖已生成夹具或清除旧探针资料。

旧版本 `1→8` 迁移测试继续执行，但它们使用明文临时数据库。不能将其描述为各历史版本旧加密文件均已实测；本轮真实旧加密文件是上一版本使用的 v8 架构。

## 备份双向兼容验证

- 提交完全虚构的 v1–v5 清单及图片/PDF、真实 BC 1.83 生成的固定黄金密文和明/密文校验值。固定盐/IV 仅用于公开测试，生产仍用安全随机数。
- JVM 测试加载实际 BC 1.86 解密旧样本；新库输出交给只带 BC 1.83 的独立 JVM 解密。检查两份 JAR 的实际版本和哈希，旧库不进入应用或测试运行时类路径。
- Android 测试执行 v1 新数据导入及安全整体恢复、v2–v5 合并及重复去重，核对成员、原文、顺序、历史剂量、回收站和附件内容。再导出当前 v5 并合并回来，数据不能变化。
- 错误密码、认证标签损坏、原有恢复事务/空间不足/取消及资源预算测试继续执行；失败必须保持现有资料和文件不变。
- 原 JCA 新旧互通、64 MiB 备份在 32 MiB 堆内流式处理、空容器、损坏头/标签和错误密码测试继续保留。

黄金样本生成脚本：`scripts/generate-bc183-backup-fixtures.ps1`。源清单均以“虚构”标识，公开密码不能用于正式备份。

## 已取得的本机证据与 CI 门槛

本机 JVM 215 项、共享核心 21 项通过（0 失败/错误/跳过）；构建策略自测 23 项通过。混淆 release、debug、仪器 APK 及三份探针 APK 编译通过。release lint 无错误，仍有 60 条 warning、2 条 hint，不声称零警告。

release 包检查：包名及 `1.3.1 / code 14` 不变，签名证书 SHA-256 为 `f22314ed12d22ec195b6528df1266e2a14c3caeea21efcb13c431c5a92cc7000`，没有 INTERNET/debuggable；16 KiB ZIP 对齐检查通过，不等于 16 KiB 系统运行验证。

客户正在测试的 `e7d8248` APK 仍保留，SHA-256 `eed973cb4b5b94f02ee9ff4c5866a13cba7805a1d579d32506709a1d1190da40`，没有覆盖。Android 原生模拟器、iOS 及鸿蒙协议结果以**本次提交对应的 GitHub Actions 实际结果**为准，不引用旧绿色运行替代；任何数据保护测试失败均不发布。

首次 CI #29 暴露新探针自身的问题：返回结果的 `wal_autocheckpoint` PRAGMA 不能交给 SQLCipher `execSQL`；API 26 也没有可供 `run-as` 执行的独立 `test` 命令。已根据实际堆栈改为消费查询结果、核对参数为 0，并用跨版本 `ls` 检查文件，补上错误堆栈和日志上传。失败发生在虚构资料生成阶段，未进入主应用兼容测试，不是现有客户数据库损坏的证据；#29 不算通过，必须重新执行完整 CI。

## 下载来源与剩余边界

实际运行依赖与官方 Maven Central 校验一致：

| 工件 | SHA-256 |
| --- | --- |
| SQLCipher Android 4.19.1 AAR | `43fc10cae8f1f1059f0933e8942a1eb328989d13426378597cd4add1b34a34fd` |
| BC 1.86 JAR | `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f` |
| 测试专用 BC 1.83 JAR | `82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb` |

这些哈希与同源校验可以检查下载不一致，但不是独立可信发布者签名证明。完整 Maven 校验元数据、可信 PGP 签名者验证及全部传递依赖锁定仍待下一阶段。

尚未验证：华为/荣耀/Redmi 真实覆盖升级与原 Keystore 状态、真实后台通知、arm64/32 位设备和 16 KiB 系统运行；iOS SQLCipher 升级不在本轮，iOS CI 仅回归。鸿蒙协议测试不等于 DevEco 原生构建。系统安全补丁、完整供应链及本机旧 JDK 管道兼容限制仍沿用上一报告，不宣布整个软件“绝对安全”。

## 官方依据

- [SQLCipher 4.19 核心发布与安全说明](https://www.zetetic.net/blog/2026/09/08/sqlcipher-4.19.0-release/)、[Android 4.19.1 发布说明](https://discuss.zetetic.net/t/sqlcipher-for-android-4-19-1/7268)。
- [SQLCipher API](https://www.zetetic.net/sqlcipher/sqlcipher-api/)、[加密设计与 v4 默认值](https://www.zetetic.net/sqlcipher/design/)。
- [BC 官方 1.86 下载与说明](https://www.bouncycastle.org/download/bouncy-castle-java/)、[维护者 1.86 发布记录](https://github.com/bcgit/bc-java/blob/r1rv86/docs/releasenotes.md)。
