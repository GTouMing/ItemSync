# Android 真机验证

> 目标模型见 ADR-0028/0029/0030。当前 debug APK 仍是过渡实现：手填邻居/指纹、身份口令。以下步骤对应这个临时 APK；代码迁移后本页会更新。

## 安装

Debug APK：

```powershell
adb install -r "E:\Projects\ItemSync\android\build\outputs\apk\debug\android-debug.apk"
```

如果 `adb` 不在 PATH，使用 `E:\SDK\AndroidStudio\sdk\platform-tools\adb.exe`。

## 首次配置

1. 打开 ItemSync，填写：
   - **同步根**：默认是应用专属目录，可保留。
   - **组 ID**：必须与桌面端一致。
   - **邻居**：桌面端地址，格式 `host:port:指纹`，多个用逗号分隔。
   - **端口**：默认 47800，两端一致。
   - **身份口令（临时）**：首次设置并牢记；当前实现用 Android Keystore 加密保存，留空表示保留现有口令。目标模型改为“本机密码只锁 GUI + NUK 自动解锁”。
2. 点“保存并启动”，允许通知权限。
3. 点“刷新本机指纹”，把这串指纹填到桌面端 `peers`/`expect`。
4. 桌面端启动后，手机端需要重新启动同步服务才能重连新邻居。

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

- 通知显示“启动失败”：检查端口占用、口令是否与已存在的身份一致。
- 通知显示“同步失败”：检查桌面端是否启动、同一局域网、防火墙、指纹是否互相加入。
- 观察日志：

```powershell
adb logcat -s ItemSyncService
```

- 关闭电池优化，避免系统杀后台。
- Android 15 对 `BOOT_COMPLETED` 启动 `dataSync` 前台服务有限制；开机自启失败时手动打开应用启动。

## 已知限制

- 目前只支持应用专属存储作为同步根，尚未接入 SAF/分区存储授权。
- 修改已存在身份的口令会导致身份无法解锁；要换口令需清除应用数据后重新配对。
- Debug APK，未做正式签名。
