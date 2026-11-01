# 0010. Android 使用全文件访问与真实路径

- 状态：已接受
- 日期：2026-11-01
- 修订：`minSdk` 由 26 提升到 29，见 [ADR-0027](0027-android-min-sdk-29.md)。

## 背景

Android 访问用户文件夹有两条路：
- SAF（`ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`）：商店合规，但没有真实路径、不能用 `java.nio.file`、不能用 `FileObserver`，每个文件操作都是一次跨进程 IPC。
- `MANAGE_EXTERNAL_STORAGE`（All files access）：拿到真实路径，与 Windows 共用同一套文件 IO 与监听实现。

## 决策

1. Android 端声明 `MANAGE_EXTERNAL_STORAGE`，运行时引导用户到系统设置页授权。
2. 同步引擎使用 `java.nio.file`（API 26+）与 `FileObserver`，与 Windows 的 `FileWatcher` 实现共享同一事件模型。
3. `PathProvider`/`FileWatcher` 仍保留在 SPI 后面，为将来切 SAF 留出接口，但第一期不实现 SAF 版本。
4. 商店策略：若上架 Google Play，需提交 “All files access” 声明并说明核心用途为文件同步/备份；若审核被拒，降级方案是侧载分发或切 SAF（需新 ADR）。

## 后果

- Play 上架存在审核风险，应用必须准备“被拒后的降级路径”。
- 用户必须手动授予敏感权限，首次运行引导要好，否则会直接放弃。
- 真实路径模式下，Android 11+ 对 `Android/data`、`Android/obb` 仍不可访问，UI 需明确提示。
- 分区存储下的 `MediaStore` 索引与直接文件写入可能不同步，需要 `MediaScannerConnection` 通知。

## 备选方案

- **SAF**：合规但等于再写一套文件系统适配，扫描性能差。
- **双模式**：体验最好，维护成本翻倍。
- **仅私有目录**：完全失去价值。
