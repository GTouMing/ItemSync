# 0027. Android 最低版本提升到 API 29

- 状态：已接受
- 日期：2026-11-01

## 背景

ADR-0011 要求 TLS 1.3 双向认证，而 Android 平台从 Android 10（API 29）起才在 Conscrypt 中提供 TLS 1.3。此前 `minSdk = 26` 的依据是 `java.nio.file`（API 26）与 `FileObserver`，但这会让 Android 8.0–9.0 设备无法建立传输连接。

## 决策

1. `:android` 的 `minSdk` 从 26 提升到 29（Android 10）。
2. 不为 API 26–28 提供 TLS 1.2 回退；传输层固定 `TLSv1.3`。
3. 其他平台能力（`MANAGE_EXTERNAL_STORAGE`、前台服务、精确闹钟）的权限模型不变。

## 后果

- Android 8.0–9.0 不再受支持，发布说明必须写明。按 2026 年的设备分布，影响面较小。
- `java.nio.file`、`FileObserver` 仍可用；API 29 还提供完整的分区存储行为，减少兼容分支。
- 旧设备即使安装也无法加入同步组（握手阶段失败），比“能装但同步不了”更可解释。
- 构建配置、文档与测试基线（Robolectric SDK 36）需保持一致。

## 备选方案

- **保持 minSdk 26 + TLS 1.2 回退**：覆盖旧设备，但违反 ADR-0011 的 TLS 1.3 要求，并引入协议降级逻辑与双倍测试矩阵。
- **保持 minSdk 26 并放弃旧设备传输**：设备能安装但无法同步，体验最差。
- **双 APK / 双变体**：维护成本高，收益有限。
