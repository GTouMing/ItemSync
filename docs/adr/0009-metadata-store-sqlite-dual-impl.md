# 0009. 元数据用 SQLite，双实现 + SPI

- 状态：已接受
- 日期：2026-11-01
- 修订：[ADR-0035](0035-multi-group-single-root.md) 组 ID 作为唯一根键，schema 迁移 `root_id` → `group_id`。

## 背景

需要持久化：同步对、同步根、同步项（含 base hash）、FileVersion、Tombstone、冲突记录、触发器、信任邻居。Android 的 JDBC 驱动不可用（Xerial sqlite-jdbc 依赖不适用于 ART），但 Android 自带 SQLite。若选纯 Java 的 KV 存储，则要自己保证崩溃一致性。

## 决策

1. 共享层定义 `MetadataStore` 仓库接口（不是通用 ORM），包含：`SyncPairRepository`、`ItemRepository`、`VersionRepository`、`TombstoneRepository`、`ConflictRepository`、`TriggerRepository`、`PeerRepository`。
2. SQL 与 DDL 放在共享层的资源里，**唯一一份 schema**，带版本号与顺序迁移脚本。
3. 平台实现：
   - Windows：`sqlite-jdbc` + 连接池 + WAL 模式 + `synchronous=NORMAL`。
   - Android：`android.database.sqlite`（`SQLiteOpenHelper`）实现同一 `MetadataStore`。
4. 所有写操作以 Sync Run 为单位包在事务里；跨仓库的原子性由 `MetadataStore.transaction(Runnable)` 提供。
5. 仓储接口只暴露领域对象与查询条件，不暴露 `ResultSet`/`Cursor` 或 SQL 字符串。

## 后果

- 需要两份实现与一套共享契约测试（contract test：同一组用例跑在两个实现上）。
- Android 与 Windows 的 SQL 方言差异（如 `INSERT OR REPLACE`、列类型亲和性）必须被限制在允许集内。
- Blob 不进数据库，只存哈希与路径；数据库损坏可用 Blob 目录 + 全量重扫部分恢复。
- 迁移脚本一旦发布不可修改，只能追加。

## 备选方案

- **纯 Java 自研 KV（Bitcask 风格）**：一套实现，但崩溃一致性、compaction、范围查询全部自担。
- **H2 共用 JDBC**：两端一份实现，但体积大且 Android 兼容性风险高。
- **二进制索引文件**：最轻，但查询与修复成本高，容易演变成自制数据库。
