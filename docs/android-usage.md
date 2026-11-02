# Android 真机验证

> 当前 APK 仍是过渡实现：邻居/指纹仍需手填。本机密码与 Android Keystore 中的 NUK 已接入；目标模型见 ADR-0028/0029/0030。

## 安装

Debug APK：

```powershell
adb install -r "E:\Projects\ItemSync\android\build\outputs\apk\debug\android-debug.apk"
```

如果 `adb` 不在 PATH，使用 `E:\SDK\AndroidStudio\sdk\platform-tools\adb.exe`。

## 首次配置

1. 首次启动先设置**本机密码**（输入两次）。它只锁界面与配置，后台同步不需要它；忘记只能清除应用数据。
2. 解锁后填写：
   - **同步根**：默认是应用专属目录，可保留。
   - **组 ID**：必须与桌面端一致。
   - **邻居**：桌面端地址，格式 `host:port:指纹`，多个用逗号分隔（过渡实现，目标模型改为同组发现 + 成对信任）。
   - **端口**：默认 47800，两端一致。
   - 不再填写身份口令；身份由 Android Keystore 里的 NUK 静默解锁。
3. 点“保存并启动”，允许通知权限。
4. 点“刷新本机指纹”，把这串指纹填到桌面端 `peers`/`expect`。
5. 桌面端启动后，手机端需要重新启动同步服务才能重连新邻居。

已有安装升级时，旧的身份口令会自动作为 NUK 继续使用，不要求再次输入；首次打开新版只需要设置本机密码。

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

## 排查

- 通知显示“启动失败”：检查端口占用、NUK 是否可解密；身份损坏时清除应用数据后重新配对。
- 锁屏提示“密码不正确”：本机密码没有找回，只能清除应用数据重置；后台同步不受影响。
- 通知显示“同步失败”：检查桌面端是否启动、同一局域网、防火墙、指纹是否互相加入。
- 观察日志：

```powershell
adb logcat -s ItemSyncService
```

- 关闭电池优化，避免系统杀后台。
- Android 15 对 `BOOT_COMPLETED` 启动 `dataSync` 前台服务有限制；开机自启失败时手动打开应用启动。

## 已知限制

- 目前只支持应用专属存储作为同步根，尚未接入 SAF/分区存储授权。
- 邻居仍为手填/指纹过渡实现；同组发现、成对信任、短码配对和在线状态尚未接入。
- 本机密码只锁 GUI，不加密本地数据；静态安全依赖系统文件级加密。
- Debug APK，未做正式签名。
