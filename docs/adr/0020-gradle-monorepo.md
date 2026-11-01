# 0020. 全仓统一 Gradle 多项目构建

- 状态：已接受
- 日期：2026-11-01

## 背景

Android 只能由 Gradle + AGP 构建；桌面与共享核心目前是 Maven（`pom.xml` 已存在）。混用两套构建会让 CI、IDE 导入与依赖版本管理都变得别扭。

## 决策

1. 整个仓库改用 **Gradle 多项目**，删除 `pom.xml`。**已执行：`pom.xml` 已于 2026-11-01 移除。**
2. 模块划分（名称暂定）：
   - `:core-domain` — 领域模型、词汇表对象、用例接口
   - `:core-sync` — 扫描、差异、冲突、版本、状态机
   - `:core-store` — `MetadataStore` 契约 + SQL schema + 契约测试
   - `:core-transport` — 帧协议、TLS、分块传输、mDNS
   - `:core-viewstate` — 不可变 ViewState + Reducer（见 ADR-0015）
   - `:platform-spi` — `PathProvider`/`FileWatcher`/`AutostartManager`/`Keystore` 接口
   - `:cli` — `itemsync-cli`
   - `:desktop` — JavaFX 托盘应用 + Windows 平台实现
   - `:android` — Android 应用 + Android 平台实现
3. 共享模块使用 `java-library` 插件，`options.release = 17`；Android 模块以 `implementation project(':core-sync')` 等引用。
4. 版本目录（`gradle/libs.versions.toml`）统一管理依赖版本；Toolchain 固定 JDK 17 编译，桌面运行时可打包 21+ 运行时（不改变语言级别）。
5. CI 单一入口：`./gradlew build`；Android 构建与桌面构建同仓库同一次流水线。

## 后果

- 需要一次性迁移成本，并同步更新 IDE 配置与文档。
- `:core-*` 模块不得依赖任何 Android 插件或 AGP 类型，否则会被 Android 反向污染。
- 需要在 CI 加一条规则：`core-*` 的依赖树中不得出现 `androidx.*`。

## 备选方案

- **Maven 核心 + Gradle 安卓**：两端标准工具链，但双构建、双依赖声明、CI 两段。
- **Maven Android 插件**：已废弃，不可取。
- **核心独立仓库**：版本清晰，但改核心必须发版，早期迭代成本高。
