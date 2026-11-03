# Android 使用说明

> 当前 APK 仍是过渡实现。本机密码、Android Keystore 中的 NUK、同组发现、在线状态、短码配对和成对信任已经接入代码，但本轮变更没有做 Android 真机验证；首次真机使用前需要按本文手工确认。

## 安装

Debug APK：

```powershell
adb install -r "E:\Projects\ItemSync\android\build\outputs\apk\debug\android-debug.apk"
```

如果 `adb` 不在 PATH，使用 `E:\Android\Sdk\platform-tools\adb.exe`。

## 首次配置

1. 首次启动先设置**本机密码**（输入两次）。它只锁界面与配置，后台同步不需要它；忘记只能清除应用数据。
2. 解锁后填写：
   - **同步根**：默认是应用专属目录，可保留。
   - **组 ID**：必须与桌面端一致。组 ID 只决定能发现哪些设备，不是密码。
   - **端口**：默认 47800，两端一致。
   - **定时同步秒数**：0 表示关闭定时同步。
   - 不再填写邻居地址或证书指纹；这些旧配置会被忽略，并在保存时清除。
3. 点“保存并启动”，允许通知权限。服务启动后会自动发布 mDNS 并监听同步端口。
4. 打开“局域网设备”，同组设备会显示设备 ID、地址、最近在线和信任状态。
5. 两台设备核对同一个 6 位短码后，各自点“信任 / 配对”。双方都信任后才会同步；任意一方撤销即停止。
6. 首次配对后可以点“立即同步”或等待定时同步/文件变动触发。

已有安装升级时，旧的身份口令会自动作为 NUK 继续使用；首次打开新版只需要设置本机密码。

## 文件位置

默认同步根：

```
/sdcard/Android/data/github.gtouming.itemsync/files/root
```

可以通过 adb 放测试文件：

```powershell
adb push test.txt /sdcard/Android/data/github.gtouming.itemsync/files/root/
```

Android 11+ 的文件管理器通常不能直接访问 `Android/data`；用 adb 或应用自身。

## 命令行调试入口

Debug APK 还包含一个只在 debug 构建中存在的 `DebugAdminProvider`，可用桌面端 `itemsync-cli` 读取或修改配置/信任：

```powershell
itemsync-cli status --platform android --json
itemsync-cli config get --platform android --json
itemsync-cli config set group <组ID> --platform android --yes
itemsync-cli trust list --platform android --json
```

修改前必须停止 Android 同步服务；release APK 不包含该 provider。

## 排查

- 通知显示“启动失败”：检查端口占用、NUK 是否可解密；身份损坏时清除应用数据后重新创建身份。
- 锁屏提示“密码不正确”：本机密码没有找回，只能清除应用数据重置；后台同步不受影响。
- “局域网设备”为空：
  - 确认两台设备在同一局域网且组 ID 相同；
  - 确认没有启用 AP 隔离、访客网络或会阻断组播的路由器策略；
  - Android 服务需要 `CHANGE_WIFI_MULTICAST_STATE` 并持有 multicast lock；如果系统或网络过滤组播，发现会失败；
  - 旧版本或 CLI 不发布配对端点，会显示“无法确认对方状态”。
- 通知显示“同步失败”：确认双方已经互相信任、同一局域网、防火墙放行同步端口；未信任设备不能进入同步 mTLS。
- 观察日志：

```powershell
adb logcat -s ItemSyncService
```

- 关闭电池优化，避免系统杀后台。
- Android 15 对 `BOOT_COMPLETED` 启动 `dataSync` 前台服务有限制；开机自启失败时手动打开应用启动。

## 已知限制

- 同组发现、在线状态和短码配对已接入代码，但**未在本轮做真机验证**；不要把它描述为已通过设备验证。
- 目前只支持应用专属存储作为同步根，尚未接入 SAF/分区存储授权。
- 本机密码只锁 GUI，不加密本地数据；静态安全依赖系统文件级加密。
- Debug APK，未做正式签名。
