# 0002. 共享核心锁定 Java 17 语言级别

- 状态：已接受
- 日期：2026-11-01

## 背景

`pom.xml` 当前声明 Java 25。Android 的 ART 不是 JVM，无法运行 JDK 25 字节码，也没有完整的 `java.*` 实现。而需求是“一套代码完成发送端和接收端”，共享核心必须能同时被 Windows 桌面端与 Android 端引用。

## 决策

1. 所有共享模块设 `maven.compiler.release=17`，产出 class 文件版本 61。
2. 共享模块**只允许**使用两端都存在的 API 子集：`java.nio`、`java.net`、`java.util`、`java.util.concurrent`、`java.time`、`javax.crypto`、`java.security`。
3. 禁止在共享模块使用：虚拟线程（Java 21+）、`java.lang.management`、`java.awt`/`javax.swing`、`ProcessHandle`、`java.nio.file.WatchService`（Android 无实现）、任何 `com.sun.*`。
4. 平台差异通过 SPI 接口隔离，由各平台模块实现：
   - `PathProvider`（路径与 SAF URI 解析）
   - `FileWatcher`（Windows ReadDirectoryChangesW / Android FileObserver）
   - `AutostartManager`
   - `Keystore`（DPAPI / Android Keystore）
   - `NetworkInterfaceProvider`
5. 桌面端运行时可以是 JDK 21+，但代码不使用 17 以上的 API；Android 端 `minSdk` 由 desugaring 能力决定。

## 后果

- 放弃虚拟线程，并发使用平台线程池 + 异步 NIO。同步引擎的阻塞点（文件 IO、哈希）必须显式线程池化。
- 需要构建期防线：Animal Sniffer 签名检查 + 自定义 forbidden-API 规则，否则半年后一定有人引入 21+ API。
- `FileWatcher` 无法用 NIO 统一，必须自写两套实现并共用事件模型。

## 备选方案

- **Kotlin Multiplatform**：共享度最高，但引入 Kotlin 工具链，偏离“用 Java”的要求。
- **只共享协议与领域模型**：传输/存储两端各写一遍，“一套代码”名不副实。
- **保持 Java 25 + 双实现**：享受新特性，代价是两份逻辑与两倍缺陷。
