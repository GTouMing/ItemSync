# ItemSync 设计概览

> 本文件是各 ADR 的汇总视图，不引入新决策。任何与 ADR 冲突的内容以 ADR 为准。

## 1. 定位与硬约束

局域网内、跨 Windows/Android、去中心化的双向文件同步。

硬约束：

| 约束                                                      | 来源     |
|-----------------------------------------------------------|----------|
| 无中心服务器，节点对等                                    | ADR-0001 |
| 一套代码承载发送与接收                                    | ADR-0004 |
| 共享核心 Java 17 语言级别                                 | ADR-0002 |
| 仅局域网（含 VPN 虚拟局域网），不做公网穿透               | ADR-0026 |
| 规模上限：10 万项 / 单文件 10 GB / 5 个同步根 / 10 台设备 | ADR-0017 |
| 点对点加密：TLS 1.3 自签证书 + 指纹固定                   | ADR-0011 |
| Android 最低版本：Android 10（API 29）                    | ADR-0027 |

## 2. 系统上下文

```
         局域网 (mDNS 组播 + TCP/TLS)
   ┌───────────────┬───────────────┬───────────────┐
   │               │               │               │
┌──▼───┐        ┌──▼───┐        ┌──▼───┐        ┌──▼───┐
│ Win  │◄──────►│ Win  │◄──────►│ Andr │◄──────►│ Andr │
│ 节点 │        │ 节点 │        │ 节点 │        │ 节点 │
└──────┘        └──────┘        └──────┘        └──────┘
   同步组 = 同一组 ID 的隐式集合；实际同步由成对信任开关决定（ADR-0029/0030），无协调者
```

- 发现：JmDNS 注册 `_itemsync._tcp.local`，TXT 带 `deviceId` / `protocolVersion` / `groupId` / `certificateFingerprint` / `pairingPort`。
- 连接：同组设备先开启成对信任，固定证书后进入 mTLS（ADR-0033）。首次开启信任是 TOFU，不再使用 6 位短码；未建立连接时通过独立的配对端点查询远端信任状态（ADR-0031）。已建立的同步 TLS 连接（出站或入站）直接视为对端信任本机；撤销会关闭连接，空闲连接用 PING/PONG 检测，关闭后按 presence 重连（ADR-0034）。
- 传播：变更方 `NOTIFY` → 接收方临时 `RECEIVE_ONLY` 拉取 → 立即反推（ADR-0021）。
- 端口：固定监听 TCP `47800`（ADR-0011），mDNS TXT 同时发布端口号；被占用则启动失败并提示，不自动漂移。

## 3. 模块结构（Gradle 多项目，ADR-0020）

```
:core-domain     领域模型、版本向量、逻辑时钟、错误码、平台能力枚举
       ▲
:core-store      MetadataStore 契约 + SQL schema + 迁移 + 契约测试
:core-sync       扫描 / 差异 / 冲突 / 版本 / Apply / Sync Run 状态机
:core-transport  帧协议 / TLS / 分块续传 / mDNS / 连接管理
:core-viewstate  不可变 ViewState + Reducer + 快照测试
       ▲
:cli             itemsync-cli（pair / join / sync / status / log / diag / verify）
:desktop         JavaFX 托盘应用 + Windows 平台实现（DPAPI / Run 键 / ReadDirectoryChangesW / 进程轮询）
:android         Android 应用 + 前台服务 + Android 平台实现（Keystore / FileObserver / AlarmManager）
```

依赖方向单一：`core-*` 不允许依赖 `desktop` / `android` / `androidx.*` / `javafx.*`。以 `:platform-spi` 中的接口反向注入平台实现。

## 4. 领域模型

```java
// identity
record NodeId(String value) {}
record GroupId(String value) {}          // 8 位 [0-9a-z]
record SyncRootId(String value) {}
record LogicalPath(String value) {}      // 跨平台逻辑名，ADR-0012

// replication
final class VersionVector {
    VersionVector increment(NodeId n);
    Order compare(VersionVector other);  // BEFORE | AFTER | EQUAL | CONCURRENT
}

// aggregate
final class SyncGroup {
    GroupId id;
    Map<NodeId, GroupMember> members;    // consent / blocked / lastSeen
    LogicalClock clock;                  // 持久化
}

final class SyncRoot {
    SyncRootId id;
    GroupId groupId;
    Path physicalPath;                   // Windows 路径 / Android 真实路径
    IgnoreRules ignoreRules;             // .isignore 快照
}

final class Item {
    SyncRootId rootId;
    LogicalPath logicalPath;
    ItemKind kind;                       // FILE | DIRECTORY
    VersionId currentVersion;
    VersionVector vector;
    Tombstone tombstone;                 // 可空
}

final class FileVersion {
    VersionId id;
    BlobHash blob;
    long size;
    Instant mtime;                       // 秒级精度
    NodeId source;
    VersionVector vector;
    SyncRunId producedBy;
}
```

引擎接口（共享层）：

```java
public interface SyncEngine {
    SyncRunId trigger(SyncRunRequest request);   // 防抖之外的直接入口
    void cancel(SyncRunId runId);
    SyncState state(SyncRunId runId);
}

public interface MetadataStore extends AutoCloseable {
    <T> T transaction(Function<Tx, T> work);
    ItemPage items(SyncRootId root, PageToken token, int limit);
    // ... 分页优先，禁止 findAll
}
```

## 5. 数据库表草案（唯一 schema，ADR-0009）

```sql
CREATE TABLE account      (id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                           argon2 TEXT NOT NULL, salt BLOB NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE peer         (node_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, device_name TEXT,
                           platform TEXT, cert_fingerprint TEXT NOT NULL, last_seen_at INTEGER);
CREATE TABLE sync_group   (id TEXT PRIMARY KEY, group_token TEXT NOT NULL, name TEXT,
                           logical_clock INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL);
CREATE TABLE group_member (group_id TEXT NOT NULL, node_id TEXT NOT NULL, display_name TEXT,
                           consent INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0,
                           joined_at INTEGER, PRIMARY KEY (group_id, node_id));
CREATE TABLE sync_root    (id TEXT PRIMARY KEY, group_id TEXT NOT NULL, physical_path TEXT NOT NULL,
                           display_name TEXT, ignore_snapshot TEXT, created_at INTEGER NOT NULL);
CREATE TABLE item         (id TEXT PRIMARY KEY, root_id TEXT NOT NULL, logical_path TEXT NOT NULL,
                           kind TEXT NOT NULL, current_version_id TEXT, deleted_at INTEGER,
                           UNIQUE (root_id, logical_path));
CREATE TABLE item_vector  (root_id TEXT NOT NULL, logical_path TEXT NOT NULL, node_id TEXT NOT NULL,
                           counter INTEGER NOT NULL, PRIMARY KEY (root_id, logical_path, node_id));
CREATE TABLE item_change  (seq INTEGER PRIMARY KEY AUTOINCREMENT, root_id TEXT NOT NULL,
                           logical_path TEXT NOT NULL, UNIQUE (root_id, logical_path));
CREATE TABLE sync_cursor  (root_id TEXT NOT NULL, node_id TEXT NOT NULL,
                           pull_sequence INTEGER NOT NULL DEFAULT 0,
                           push_sequence INTEGER NOT NULL DEFAULT 0,
                           PRIMARY KEY (root_id, node_id));
CREATE TABLE version      (id TEXT PRIMARY KEY, item_id TEXT NOT NULL, blob_hash TEXT NOT NULL,
                           size INTEGER NOT NULL, mtime INTEGER NOT NULL, source_node_id TEXT NOT NULL,
                           vector_json TEXT NOT NULL, run_id TEXT, created_at INTEGER NOT NULL);
CREATE TABLE tombstone    (item_id TEXT PRIMARY KEY, vector_json TEXT NOT NULL,
                           deleted_at INTEGER NOT NULL, source_node_id TEXT NOT NULL);
CREATE TABLE conflict     (id TEXT PRIMARY KEY, item_id TEXT NOT NULL, local_version_id TEXT,
                           remote_version_id TEXT, copy_logical_path TEXT, detected_at INTEGER NOT NULL,
                           resolved_at INTEGER);
CREATE TABLE trigger      (id TEXT PRIMARY KEY, group_id TEXT NOT NULL, root_id TEXT NOT NULL,
                           type TEXT NOT NULL, config_json TEXT NOT NULL, enabled INTEGER NOT NULL,
                           next_fire_at INTEGER);
CREATE TABLE sync_run     (id TEXT PRIMARY KEY, group_id TEXT NOT NULL, root_id TEXT NOT NULL,
                           trigger_id TEXT, source_node_id TEXT, state TEXT NOT NULL,
                           started_at INTEGER NOT NULL, ended_at INTEGER, stats_json TEXT, error_code TEXT);
CREATE TABLE resume       (blob_hash TEXT NOT NULL, peer_node_id TEXT NOT NULL, bytes_done INTEGER NOT NULL,
                           updated_at INTEGER NOT NULL, PRIMARY KEY (blob_hash, peer_node_id));
```

文件系统侧：

```
<data>/blobs/aa/<sha256>          内容寻址，只写一次，临时文件 + 原子 rename
<data>/tmp/transfer/<runId>/      续传暂存，24h 超时清理
<data>/logs/<yyyy-MM-dd>.jsonl    结构化日志，保留 7 天
<data>/db/itemsync.sqlite         WAL 模式
```

版本保留：每项最近 **10 个版本**（可配），按数量淘汰；仍被“当前版本”引用的 Blob 不删（ADR-0005）。

## 6. 同步算法（ADR-0021）

```
本地变更（防抖 30s 后）
  └─ newRun(source=me) → NOTIFY 给在线且已互相信任的节点

收到 NOTIFY(runId, source, summary)
  ├─ 未互相信任 → 丢弃
  ├─ 入队，按 (logicalTime, deviceId) 排序
  └─ 轮到我执行：
       ① 进入 RECEIVE_ONLY(group)
       ② 扫描同步根（流式，5,000 项/批），应用 .isignore
       ③ MANIFEST_REQUEST(sinceSequence = 拉取游标)
       ④ 逐项判定：
            remote 严格更新  → 拉 Blob → 校验 SHA-256 → 原子 Apply → 本地 counter++
            两者 CONCURRENT  → 保留本地，写冲突副本，记 conflict 表
            本地严格更新    → 跳过（稍后反推）
       ⑤ 退出 RECEIVE_ONLY → 恢复 BIDIRECTIONAL
       ⑥ 反推：本地变更序号领先于推送游标的项，立即推给 source
```

增量清单只返回 `item_change.seq > sinceSequence` 的项；客户端 apply 成功后才推进拉取游标。本地变更推送使用每个 peer 的推送游标，避免把“增量清单未返回的本地路径”误判为远端缺失。

关键不变量：

- 一次 Sync Run 只处理一个 `(syncRoot, group)`，同一对组合不允许并行。
- Apply 必须先落 Blob 再原子替换目标文件，中途失败可重入。
- 任何时刻崩溃，重跑不得产生重复版本或重复冲突副本（按 `(item, vector, blobHash)` 幂等）。
- NOTIFY 丢失不影响一致性，只影响延迟。

### 6.1 逻辑时钟（Lamport）：为什么不用墙钟

**定义**：每个同步组一个持久化的单调计数器 `logical_clock`，只用于**调度仲裁**，不用于判断版本新旧。

规则：

```
本地产生事件（变更 / 通知 / NOTIFY）
  clock = clock + 1
  与 sync_run 行在同一个事务里落盘
  发出的 NOTIFY 携带 clock

收到 NOTIFY(remoteTime)
  clock = max(clock, remoteTime) + 1
  与“入队 run”行在同一个事务里落盘

仲裁键 = (logicalTime, deviceId)   // deviceId 为稳定随机 UUID
```

**与版本向量的分工**：

| 机制           | 回答的问题                 | 权威性                     |
|----------------|----------------------------|----------------------------|
| 项级版本向量   | 两个版本谁更新？还是并发？ | **数据正确性的唯一依据**   |
| Lamport 计数器 | 两个 NOTIFY 谁先跑？       | 仅调度顺序，错了不影响数据 |

**不用墙钟的五个理由**：

1. **时钟偏差**：两台设备 `currentTimeMillis()` 差几秒是常态（无 NTP 保证）。A 实际先改、B 实际后改，但 B 的钟快 5 秒，顺序就颠倒。
2. **时钟回拨**：手动改时间、时区/DST 跳变、NTP 校正、虚拟机快照恢复、断电后从 1970 启动，都会让“后发生的时间更小”。
3. **非单调**：`currentTimeMillis()` 可跳变；`nanoTime()` 单调但**跨设备不可比**。
4. **休眠与 Doze**：Android 唤醒后墙钟一次跳很远，闹钟与通知的先后关系失真。
5. **可被操纵**：成员可以把时间调到未来，在每次仲裁中“抢跑”。用 Lamport 时间，抬时间只能抢到调度顺序，**不能覆盖或删除数据**（并发仍由向量判定 → 冲突副本）。若用墙钟做 LWW，抬时间就等于“我可以静默覆盖所有人”。

**自愈性**：收到更大的 `remoteTime` 就拉高本地计数器；即使从旧备份恢复数据库，第一次与成员通信后就会被拉到对方水平，不会长期错序。

**局限**：Lamport 时间不表达并发（两个并发事件可以有任意大小关系），所以它绝不能参与“谁的数据胜出”的判定。这是它与版本向量必须分开的原因。

**替代方案**：Hybrid Logical Clock（HLC）= 墙钟 + 逻辑计数，既保持因果单调，又接近人类可读时间；若后续需要“按人类时间展示顺序”，可升级为 HLC（新 ADR）。

## 7. 传输协议帧

```
帧头：magic(2) | version(1) | type(1) | length(4) | payload

HELLO            {protocolVersion, nodeId, certificateFingerprint}
HELLO_ACK        {protocolVersion, nodeId, certificateFingerprint}
PING / PONG      {nonce}                                      // 同步连接空闲检测（ADR-0034）
BLOB_GET         {hash, offset}
BLOB_CHUNK       {offset(8) | length(4) | data | crc32(4)}   // 1 MiB
BLOB_RESULT      {hash, ok, size, errorCode}
ERROR            {code, message}

// M0.5 计划：
NOTIFY           {runId, groupId, logicalTime, vectorSnapshot, changedRootId}
NOTIFY_ACK       {runId, accepted, queuePosition}
MANIFEST_REQUEST {rootId, sinceSequence, sinceVector, ignoreHash}
MANIFEST_PAGE    {entries[], nextCursor}
MANIFEST_END     {totalEntries, sequence}
```

## 8. Sync Run 状态机

```
IDLE → QUEUED → SCANNING → PLANNING → TRANSFERRING → APPLYING → REVERSE_PUSH → COMPLETED
                                                     │            │
                                                     └────────────┴──► FAILED / CANCELLED
```

- `QUEUED`：并发通知排队（ADR-0021）。
- `FAILED` 保留错误码与已完成进度，可重试；`CANCELLED` 由用户或关机触发。

## 9. 平台能力矩阵

| 能力               | Windows                           | Android                                     |
|--------------------|-----------------------------------|---------------------------------------------|
| 托盘 UI            | ✅ 系统托盘                       | ❌                                          |
| 开机自启           | ✅ `HKCU\...\Run`                 | ✅ `BOOT_COMPLETED`                         |
| 后台常驻           | ✅ 托盘进程                       | ✅ 前台服务 `dataSync` + 白名单引导         |
| 目录监听           | ✅ `ReadDirectoryChangesW`（JNA） | ✅ `FileObserver`                           |
| 监听第三方应用启停 | ✅ 进程轮询（默认 2s）            | ❌（ADR-0008）                              |
| 精确闹钟           | ✅ 进程内调度 + 睡眠补偿          | ✅ `AlarmManager.setExactAndAllowWhileIdle` |
| 目录访问           | ✅ 直接路径（受 ACL 限制）        | ✅ `MANAGE_EXTERNAL_STORAGE` 真实路径       |
| 密钥库             | ✅ DPAPI                          | ✅ Android Keystore                         |
| 长路径             | ✅ `\\?\` 前缀                    | ✅ 无 MAX_PATH                              |
| 大小写敏感         | ❌ 需碰撞检测                     | ✅                                          |

Android 最低版本为 Android 10（API 29），由 TLS 1.3 决定（ADR-0027）。

## 10. 权限清单

Windows：

- 用户级目录读写；不需要管理员权限。
- `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 写入（自启动）。
- 防火墙入站规则（首次监听时由用户确认或安装器添加）。

Android：

- `MANAGE_EXTERNAL_STORAGE`（所有文件访问，商店需申报）。
- `POST_NOTIFICATIONS`（前台服务通知）。
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`（闹钟触发器）。
- `RECEIVE_BOOT_COMPLETED`（开机恢复）。
- `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`（mDNS）。
- 电池优化白名单（用户手动授权）。

## 11. 风险登记册

| 风险                                | 影响                | 缓解                                                 |
|-------------------------------------|---------------------|------------------------------------------------------|
| 8 位组 ID 可枚举                    | 在线状态/设备名泄露 | 组 ID 不是秘密；成对信任 + 证书固定保护数据；关闭信任/撤销阻断（ADR-0029/0033） |
| Play 拒绝 `MANAGE_EXTERNAL_STORAGE` | 无法上架            | 申报核心用途；准备 SAF 降级方案（新 ADR）            |
| 厂商 ROM 杀后台                     | 同步延迟            | 前台服务 + 白名单引导 + 闹钟恢复；UI 明示            |
| 组播被路由器/系统限制               | 发现失败            | 手动添加 IP 兜底                                     |
| 版本向量膨胀（长期离线节点）        | 元数据膨胀          | 分量 GC 规则；成员上限 10                            |
| 墙钟不可信                          | 仲裁错误            | 使用持久化 Lamport 计数器，不用墙钟                  |
| 崩溃导致 DB 与文件系统不一致        | 误判/丢文件         | 先 Blob 后 Apply + 幂等重跑 + 定期全量校验           |
| Windows 非法文件名/长路径           | 创建失败            | PathCodec 转义 + `\\?\` + “无法同步”清单             |
| 冲突副本堆积                        | 磁盘占用            | 冲突列表 UI + 清理入口（不自动删）                   |

## 12. 未决问题

1. 冲突副本是否提供“保留最近 N 个 / N 天”之外的自动化策略（当前只列清单，不自动删）。
2. Windows 目录监听用 JNA 还是自带 JNI 封装；轮询兜底阈值。
3. 自动更新机制是否纳入 M2 之后（当前在范围外）。
4. 传输并发上限与带宽限制是否需要 UI 配置。

## 13. 里程碑

| 里程碑 | 内容                                             | 验收                                        |
|--------|--------------------------------------------------|---------------------------------------------|
| M0.1   | Gradle 骨架、`core-domain`、错误码、版本向量单测 | `./gradlew build` 通过                      |
| M0.2   | BlobStore、PathCodec、.isignore 引擎             | 边界用例全覆盖（转义往返、忽略规则矩阵）    |
| M0.3   | MetadataStore 双实现 + 契约测试                  | 同一套 contract test 双端通过               |
| M0.4   | 传输层：TLS + 帧协议 + 分块续传 + mDNS           | 双进程互通、断网续传                        |
| M0.5   | 同步引擎 + 触发器 + CLI                          | 双进程场景脚本：新增/改/删/冲突/断电/大文件 |
| M1     | Windows 托盘 UI                                  | 向导、冲突裁决、版本历史、自启、诊断包      |
| M2     | Android 应用                                     | 前台服务、权限引导、后台同步、通知          |
