# 多端架构与数据流

```text
Android Compose ─┐
                 ├─ shared/core：模型、校验、复查、搜索、合并
iOS SwiftUI ─────┘

Harmony ArkUI ───── shared/spec：相同 JSON Schema 与测试向量

各平台适配：加密数据库 / 安全密钥 / 私有附件 / 本地通知
                         │
                         └─ .htbackup v2（加密 ZIP）
```

Android 和 iOS 链接 Kotlin Multiplatform 共享核心；HarmonyOS NEXT 使用 ArkTS 原生实现同一协议。平台数据库内部可以使用本地数字主键，但导出、导入和合并只能依赖永久 UUID。

导入分为四步：解密到临时目录、完整验证、生成预览与冲突决定、原子提交并重建提醒。任一步失败都删除临时文件并保留当前数据库。

数据库迁移与附件安装不能互相留下半成品：新附件先进入独立目录并同步到磁盘，数据库事务成功后才成为活动引用；旧文件只在提交后清理。
