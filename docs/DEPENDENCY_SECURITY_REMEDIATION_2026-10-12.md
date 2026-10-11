# 依赖安全修复：第一阶段（构建链）

基线：`e7d8248`，分支 `feature/android-1.2-family-profiles`。本次不发布 GitHub Release，不合并主分支，不更改客户正在测试的安装包。

## 修复范围

- Gradle `8.13 → 8.14.6`，修复原公告核查中确认的五项构建链高风险问题。Wrapper JAR 与 ZIP 均核对官方 SHA-256；在 Wrapper 中固定下载校验值。
- Kotlin 的 Android、Multiplatform、Serialization、KAPT、Compose 插件统一 `2.2.0 → 2.4.20`，包含 KAPT 缓存反序列化过滤修复；另关闭 KAPT 增量缓存，作为纵深防护。
- 保留 Kotlin `2.2` 语言/API 规则以及 Android/JVM `stdlib 2.2.10`，测试等核心库也统一在 `2.2.10`。直接使用新版默认元数据会超过 Room 2.7.2 的读取上限；仅设置语言规则还会被新版标准库的 `Continuation` 元数据阻断，测试库自动升版也会超出当前 AGP lint 的元数据上限。采用官方核心库版本配置，不是伪造元数据版本，也没有降级安全修复后的编译器；Native 仍使用新版分发目录中的平台库。
- 使用 Google 官方发布的 R8 `9.1.56`，满足 Kotlin 2.4 工具链的要求；不关闭混淆、资源压缩或验证。
- GitHub Actions 固定为已核对的完整提交 SHA，保持 `contents: read`，不使用特权 PR 触发器，不上传签名密钥。
- Windows 测试与打包脚本改用受校验的 Wrapper，不再绕过配置调用本机旧 Gradle 8.13。
- 新增构建安全策略检查及失败夹具；另验证实际加载的工具与 KAPT 缓存设置。策略检查不等同于漏洞利用测试或完整供应链验证。

## 数据与测试边界

保持 `com.healthtimeline.app`、`1.3.1 / versionCode 14`、Room v8、备份 v5、原发布签名。未修改业务源码、数据库迁移、数据库密钥、备份密码算法或医疗资料。

本机受 Windows 管道兼容问题限制，仍使用已有 JDK 17.0.2 与本地管道适配；没有将旧 JDK 带入 APK。GitHub 的 Linux/macOS 测试使用 Temurin 17，另外记录 macOS 的实际 Xcode 版本。现代 JDK 的本机管道兼容性仍待独立处理，不能声称本机工具环境已全面加固。

原签名测试包 SHA-256 保持 `eed973cb4b5b94f02ee9ff4c5866a13cba7805a1d579d32506709a1d1190da40`，没有覆盖该测试包。完整 JVM、Room/Compose 仪器测试、release lint、APK 和 iOS CI 结果，以本次提交对应的 GitHub Actions 为准；旧的绿色 CI 不替代新测试。

本机最终配置验证：Android JVM 211 项、共享核心 21 项，失败/错误/跳过均为 0（Gradle 可复用输入未变化的结果）；构建安全策略自测 21 项通过，真实危险缓存覆盖负例被拒绝。release lint 无错误，仍有 62 条 warning、2 条 hint，不声称零警告。调试 APK、仪器测试 APK、开启混淆的签名 release 构建通过。实际检查了 2 个 Kotlin 插件、16 个编译任务和 5 个 KAPT worker；Windows 不包含 Native 编译结果。

本机 release APK 的签名证书 SHA-256 仍为 `f22314ed12d22ec195b6528df1266e2a14c3caeea21efcb13c431c5a92cc7000`；包名/版本检查通过，不含 `INTERNET` 或 `debuggable`。这些检查不代替覆盖安装及真机功能验收。

## 尚未关闭的问题

- Android SQLCipher `4.18.0 → 4.19.1`、BC `1.83 → 1.86` 已完成候选二进制/API 初查，但尚未改动。下一阶段必须用旧加密数据库夹具和旧备份验证兼容，不能只凭明文 Room 测试宣称升级无损。
- iOS SQLCipher/SQLite 的版本升级与锁文件单独处理；本阶段不声称修复了 iOS 运行时数据库问题。
- Maven 依赖的完整校验元数据、跨平台锁文件、可信签名者独立验证尚未完成。固定 Action SHA 和 Wrapper 校验只覆盖部分供应链。
- 华为、荣耀、Redmi 真机及其他端真机验收仍需完成；协议模拟测试不等于鸿蒙原生构建。

## 官方依据

- [Gradle 8.14.6 发布说明](https://docs.gradle.org/8.14.6/release-notes.html)、[官方校验值](https://gradle.org/release-checksums/)。原公告：GHSA-mqwm-5m85-gmcv、GHSA-w78c-w6vf-rw82、GHSA-j5m7-59rp-24f5、GHSA-xwqc-3h47-hg64、GHSA-mvvg-497x-hmj8，均来自 [Gradle 官方安全公告](https://github.com/gradle/gradle/security/advisories)。
- [JetBrains CNA：CVE-2026-53914](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/53xxx/CVE-2026-53914.json)。
- [Kotlin 编译选项兼容规则](https://kotlinlang.org/docs/compiler-reference.html)、[显式标准库依赖规则](https://kotlinlang.org/docs/gradle-configure-project.html#dependency-on-the-standard-library)、[元数据与语言版本关系](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata.jvm/-jvm-metadata-version/)。
- [Android Kotlin/R8 支持表](https://developer.android.com/build/kotlin-support)、[R8 官方替换方法](https://r8.googlesource.com/r8/+/refs/heads/main/README.md)。

不承诺零漏洞、零崩溃或任意异常下都不会丢失资料；本阶段的通过结论仅适用于实际执行并记录的项目。
