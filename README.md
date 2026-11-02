# ItemSync

ItemSync 是面向 Windows 和 Android 的局域网双向文件同步项目。节点通过局域网直接连接，不依赖中心服务器；共享核心使用 Java 17 语言级别，桌面界面使用 JavaFX。

> 项目仍在开发中，目前优先完善 Windows 桌面端。请先用独立的测试目录验证，并保留重要文件的独立备份；文件同步不能代替备份。

## 当前状态

| 部分 | 已接入的主要能力 |
| --- | --- |
| 共享核心 | 文件扫描、双向同步、版本历史、冲突处理、TLS 传输和元数据存储 |
| Windows 桌面端 | 主窗口与系统托盘、开机启动、本机密码、同组设备发现与在线状态、短码配对、成对信任、修改组 ID、本机数据重置 |
| Android 端 | 配置界面、本机密码、Keystore 身份解锁、前台同步服务；最低 Android 10（API 29） |

当前限制：

- Android 仍是过渡实现，邻居地址和证书指纹需要手动配置，尚未接入桌面端的同组发现、短码配对、成对信任和在线状态。
- 桌面端旧 `peers` / `expect` 配置兼容路径尚未移除，可能在启动时导入信任。桌面端体验应使用新配置和界面配对流程，不要将旧配置视为已完成信任迁移。
- 不提供公网穿透；设计文档中的规模目标和规划功能，不代表已完成相应验证或交付。

## 安全边界

- **本机密码只锁界面和界面操作**，不阻止已有后台同步，也不加密同步根中的文件。静态数据保护依赖操作系统及磁盘加密。
- **组 ID 是发现范围，不是密码**。同组可见不等于允许同步；新设备应通过双方核对配对短码、分别确认信任的流程建立信任。
- 请勿提交或公开实际同步内容、完整本机配置、密钥、身份文件、证书指纹或未脱敏诊断信息。

安全模型及迁移约束见 [本机密码与身份解锁](<docs/adr/0028-local-password-ui-lock-and-nuk.md>)、[组 ID 命名空间](<docs/adr/0029-group-id-namespace.md>)和[成对信任与在线状态](<docs/adr/0030-pairwise-trust-and-presence.md>)。

## 从源码运行

以下 PowerShell 命令均在仓库根目录执行。

### 前置条件

- Windows，以及 JDK 21；将 `JAVA_HOME` 指向该 JDK。桌面打包需要 JDK 自带的 `jpackage`。
- 使用仓库自带的 Gradle Wrapper，无需另行安装 Gradle。首次构建需要联网获取 Gradle 和依赖。
- 配置或构建 Android 模块时，还需要 Android SDK Platform 36。SDK 路径按 Android 工具链约定在本机配置，不要提交本机路径。

### 运行桌面端

```powershell
.\gradlew.bat :desktop:run
```

命令会构建并启动桌面应用。首次启动按界面提示设置本机密码、同步根和组 ID；建议使用独立的测试目录。测试两台桌面设备时，使用相同组 ID，在双方界面核对相同的 6 位短码后分别确认信任。

### 运行测试

```powershell
.\gradlew.bat test
```

该命令执行工程的测试任务。模块级测试命令和提交前检查见 [贡献说明](<CONTRIBUTING.md>)。

### 构建 Windows 应用镜像

先退出正在运行的 ItemSync，再执行：

```powershell
.\gradlew.bat :desktop:packageApp
```

任务会清理并重新生成它的打包输出目录，产物是可运行的应用镜像目录，不是安装器。入口路径为：

```text
desktop/build/jpackage/ItemSync/ItemSync.exe
```

公开分发前，还需完成 [分发前检查](<CONTRIBUTING.md#分发前检查>)，不能将本地构建成功等同于发布包已完成许可合规检查。

### 构建 Android 调试包

安装并配置 Android SDK Platform 36 后执行：

```powershell
.\gradlew.bat :android:assembleDebug
```

产物路径为：

```text
android/build/outputs/apk/debug/android-debug.apk
```

安装、过渡版配置方式和已知限制见 [Android 使用说明](<docs/android-usage.md>)。调试包不是正式签名的发布版本。

## 仓库结构

| 模块 | 职责 |
| --- | --- |
| [`core-domain`](<core-domain/>) | 领域模型、身份、安全基础能力和平台兼容辅助代码 |
| [`core-store`](<core-store/>) | 元数据存储接口和持久化基础设施 |
| [`core-store-testkit`](<core-store-testkit/>) | 元数据存储实现的共用契约测试 |
| [`core-sync`](<core-sync/>) | 同步引擎、节点编排、冲突及版本处理 |
| [`core-transport`](<core-transport/>) | 帧协议、TLS 连接和局域网发现 |
| [`platform-desktop`](<platform-desktop/>) | Windows 平台能力与本机安全支持 |
| [`desktop`](<desktop/>) | JavaFX 桌面应用及 Windows 打包 |
| [`android`](<android/>) | Android 应用及平台适配 |
| [`cli`](<cli/>) | 命令行入口 |

## 文档与贡献

- [设计概览](<docs/design.md>)：整体架构与设计目标。
- [术语表](<docs/glossary.md>)：项目概念的统一定义。
- [架构决策记录](<docs/adr/README.md>)：已接受、已替代的设计决策。
- [Android API 兼容规则](<docs/android-compatibility.md>)：共享核心的平台约束。
- [贡献说明](<CONTRIBUTING.md>)：开发、测试、提交及分发检查。
- [开发规范](<AGENTS.md>)：仓库工作约定。

## 许可证

除另有声明外，ItemSync 的原创源码和文档采用 **Mozilla Public License 2.0（MPL-2.0）**。完整条款见 [许可证](<LICENSE>)。

> This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.

MPL-2.0 允许商用；分发受许可覆盖的程序时，需要按条款向接收者提供相应源码及修改。独立且不含 MPL 代码的文件可以采用其他许可，不要求将修改提交回本仓库。以上是简要说明，以 [许可证全文](<LICENSE>)为准。

第三方依赖、构建工具和随包运行时保留各自的许可，不因本项目采用 MPL-2.0 而被重新授权。仓库内的许可声明不代表发布包已完成第三方声明收集或完整许可审计。
