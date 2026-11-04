# 命令行数据管理

`itemsync-cli` 提供一组离线配置/信任管理命令，用于排障和调试。它只改配置与信任记录，不改本机密码、NUK、身份文件、SQLite 表或同步游标。

## 前提

- 修改前必须停止 ItemSync/Android 同步服务；命令检测到运行中会拒绝写入。
- 桌面端必须显式传 `--data <数据目录>`。
- Android 只支持 **debuggable 的 debug APK**；release 包不包含调试 provider。
- Android 命令需要可用的 `adb`，多设备时传 `--serial`。

## 命令

```powershell
# 桌面
itemsync-cli config get [key] --platform desktop --data <dir> [--json]
itemsync-cli config set <key> <value> --platform desktop --data <dir> [--yes] [--json]
itemsync-cli trust list|add|remove|clear --platform desktop --data <dir> [--json]
itemsync-cli status --platform desktop --data <dir> [--json]

# Android
itemsync-cli config get|set ... --platform android [--serial <serial>] [--adb <path>] [--json]
itemsync-cli trust list|add|remove|clear ... --platform android [--serial <serial>] [--json]
itemsync-cli status --platform android [--serial <serial>] [--json]
```

`--platform` 默认 `desktop`。

## 字段

桌面：

- `group`、`root`、`port`、`root-id`、`launch-command`、`debounce-seconds`

Android：

- `group`、`root`、`port`、`root-id`、`interval-seconds`

`group` 和 `root` 变更需要 `--yes`；`group` 变更会清除旧组信任。`trust clear` 也需要 `--yes`。

## 多组配置

桌面端的组列表保存在 `desktop-groups.properties`。修改前先退出 ItemSync，避免运行中的进程覆盖配置。

```powershell
itemsync-cli group-list   --data "$env:LOCALAPPDATA\ItemSync"
itemsync-cli group-add    --data "$env:LOCALAPPDATA\ItemSync" --id <8位组ID> `
                          --root D:\ItemSync\work --name 工作
itemsync-cli group-rename --data "$env:LOCALAPPDATA\ItemSync" --id <8位组ID> --name 新名称
itemsync-cli group-remove --data "$env:LOCALAPPDATA\ItemSync" --id <8位组ID>
```

`group-remove` 只修改配置，不删除同步根中的文件；该组的信任记录会在下次启动时按剩余组清理。`serve`/`sync` 仍可用 `--group`/`--root` 操作单个组。

## 示例

```powershell
# 查看桌面状态
itemsync-cli status --platform desktop --data "$env:LOCALAPPDATA\ItemSync" --json

# 对齐 Android 组 ID（服务必须先停止）
itemsync-cli config set group 8a5xp9em --platform android --yes

# 查看/添加/移除信任指纹
itemsync-cli trust list --platform android --json
itemsync-cli trust add <64位小写十六进制指纹> --platform android
itemsync-cli trust remove <64位小写十六进制指纹> --platform android
```

## 输出与退出码

默认人类可读；`--json` 输出：

```json
{"ok":true,"command":"config.get","platform":"desktop","data":{...}}
{"ok":false,"command":"config","error":{"code":3,"message":"..."}}
```

退出码：

- `0` 成功
- `2` 参数错误
- `3` 应用/服务在运行
- `4` I/O 或 adb 错误

## Android debug provider

Android debug APK 包含 `DebugAdminProvider`，authority 为：

```text
content://github.gtouming.itemsync.debug.admin/admin
```

CLI 通过 `adb shell content call` 调用，provider 用 Base64 JSON 返回结果。该 provider 只存在于 debug 构建，不进入 release。

调试方法：

```powershell
adb shell content call --uri content://github.gtouming.itemsync.debug.admin --method status
adb shell content call --uri content://github.gtouming.itemsync.debug.admin --method trust.list
adb shell content call --uri content://github.gtouming.itemsync.debug.admin --method peers
```

`peers` 的 `result` 是 Base64 JSON。每个 peer 包含 `pairingPort`、`locallyTrusted`、`remoteTrust`、`connected`、`remoteTrustFailure` 和 `remoteTrustErrorDetail`。`remoteTrustFailure` 是结构化原因（`NO_PAIRING_ENDPOINT`、`QUERY_TIMEOUT`、`IDENTITY_MISMATCH`、`CONNECTION_FAILED`）；`remoteTrustErrorDetail` 仅供排障，不进入 UI。

## 不做什么

- 不修改本机密码、NUK、身份密钥库。
- 不修改 SQLite 表、`item_change`、`sync_cursor` 或运行历史。
- 不支持应用运行中在线修改；不要用文件编辑绕过这个限制。
