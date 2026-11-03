# 架构决策记录 / ADR

每条 ADR 只记录一个决策：背景、决策、后果、被否掉的备选。状态只有 已接受 / 已废弃 / 被替代。

## 身份、信任与组织

| 编号                                       | 标题                                                       | 状态   |
|--------------------------------------------|------------------------------------------------------------|--------|
| [0001](0001-local-account-and-pairing.md)  | 本机账号 + 配对授权，不设中心（被 0028/0030 替代）         | 被替代 |
| [0013](0013-node-key-auto-unlock.md)       | 节点密钥库自动解锁，登录只解锁 UI（UI 解锁被 0028 修订）   | 已接受 |
| [0024](0024-open-join-group-id-consent.md) | 开放入组（8 位组 ID）+ 双向同意的共享门禁（被 0029/0030 替代） | 被替代 |
| [0025](0025-single-local-account.md)       | 单本机账号，无应用内角色（被 0028 替代）                   | 被替代 |
| [0028](0028-local-password-ui-lock-and-nuk.md) | 本机密码只锁 GUI，NUK 静默解锁节点身份                 | 已接受 |
| [0029](0029-group-id-namespace.md)         | 组 ID 是命名空间，不是入组凭据                             | 已接受 |
| [0030](0030-pairwise-trust-and-presence.md)| 成对信任开关、短码配对与组内在线可见                       | 已接受 |
| [0031](0031-pairing-status-endpoint.md)   | 独立配对状态端点：只读远端信任查询                         | 已接受 |
| [0032](0032-incremental-manifest-change-log.md) | 增量 manifest：变更序号与拉取/推送游标              | 已接受 |
| [0033](0033-trust-without-short-code.md)   | 信任开关使用证书固定，不使用短码配对                       | 已接受 |

## 技术选型与约束

| 编号                                            | 标题                              | 状态   |
|-------------------------------------------------|-----------------------------------|--------|
| [0002](0002-java17-shared-core.md)              | 共享核心锁定 Java 17 语言级别     | 已接受 |
| [0009](0009-metadata-store-sqlite-dual-impl.md) | 元数据用 SQLite，双实现 + SPI     | 已接受 |
| [0015](0015-shared-usecases-viewstate.md)       | 共享用例与 ViewState，UI 各自实现 | 已接受 |
| [0017](0017-scale-targets.md)                   | 规模指标：10 万项 / 单文件 10 GB  | 已接受 |
| [0018](0018-rely-on-os-disk-encryption.md)      | 静态加密依赖系统全盘加密          | 已接受 |
| [0020](0020-gradle-monorepo.md)                 | 全仓统一 Gradle 多项目构建        | 已接受 |

## 同步语义

| 编号                                                    | 标题                                             | 状态   |
|---------------------------------------------------------|--------------------------------------------------|--------|
| [0004](0004-bidirectional-sync-with-conflict-copies.md) | 双向对等同步，冲突保留副本（被 0021 修订）       | 已接受 |
| [0005](0005-file-level-versioning-with-cas.md)          | 文件级版本 + 内容寻址存储                        | 已接受 |
| [0012](0012-cross-platform-path-semantics.md)           | 跨平台路径语义：可逆转义 + 碰撞改名              | 已接受 |
| [0019](0019-isignore-semantics.md)                      | .isignore：同步根级、参与同步                    | 已接受 |
| [0021](0021-sync-group-version-vectors.md)              | 同步组 + 项级版本向量 + 变更通知驱动的“先收后推”（组/信任部分被 0029/0030 修订） | 已接受 |
| [0022](0022-nested-sync-roots.md)                       | 同步根允许嵌套但强警告                           | 已接受 |

## 触发与传输

| 编号                                        | 标题                              | 状态   |
|---------------------------------------------|-----------------------------------|--------|
| [0003](0003-alarm-vs-condition-triggers.md) | 闹钟触发器与条件触发器分开实现    | 已接受 |
| [0008](0008-windows-only-process-watch.md)  | 第三方应用启停监听仅 Windows 提供 | 已接受 |
| [0011](0011-mdns-tls-chunked-transfer.md)   | mDNS 发现 + TLS 1.3 + 分块续传    | 已接受 |
| [0014](0014-whole-file-transfer-first.md)   | 第一期整文件传输，不做块级差分    | 已接受 |

## 平台与交付

| 编号                                                 | 标题                                            | 状态   |
|------------------------------------------------------|-------------------------------------------------|--------|
| [0006](0006-windows-autostart-tray.md)               | Windows 托盘应用 + 用户级注册表自启             | 已接受 |
| [0007](0007-android-keepalive-foreground-service.md) | Android 前台服务保活，失败靠闹钟恢复            | 已接受 |
| [0010](0010-android-all-files-access.md)             | Android 使用全文件访问与真实路径                | 已接受 |
| [0016](0016-delivery-order-engine-first.md)          | 交付顺序：共享核心 + CLI → Windows UI → Android | 已接受 |
| [0023](0023-observability.md)                        | 结构化日志 + 运行历史 + 诊断包                  | 已接受 |
| [0026](0026-out-of-scope-v1.md)                      | 第一期明确不做的事                              | 已接受 |
| [0027](0027-android-min-sdk-29.md)                   | Android 最低版本提升到 API 29                   | 已接受 |

术语定义见 [../glossary.md](../glossary.md)。
