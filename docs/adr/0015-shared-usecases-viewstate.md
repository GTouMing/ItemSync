# 0015. 共享用例与 ViewState，UI 各自实现

- 状态：已接受
- 日期：2026-11-01

## 背景

“一套代码”不能延伸到 UI：JavaFX 无法在 Android 运行，Compose Multiplatform 需要 Kotlin（与 ADR-0002 冲突），WebView 复用会牺牲体验。但业务交互逻辑（向导步骤、冲突裁决流程、同步状态展示）如果两端各写一遍，会以两份不一致告终。

## 决策

1. 共享层包含：领域模型、同步引擎、用例（UseCase）、以及**不可变 ViewState + 单向事件流**（`Event → Reducer → ViewState`）。
2. 共享层**禁止**出现任何 UI 框架类型：不得 import `javafx.*`、`android.*`、`javax.swing.*`。
3. Windows UI：JavaFX + FXML，订阅 ViewState 渲染，把用户操作映射为 Event。
4. Android UI：原生 Views + RecyclerView，同样订阅 ViewState、派发 Event。
5. 共享层提供 ViewState 的序列化（JSON），用于两端调试回放与快照测试：同一事件序列必须产生同一 ViewState。

## 后果

- UI 代码仍然是两份，但**逻辑只有一份**；每个交互 bug 只需在共享层修一次。
- ViewState 设计要克制：只放渲染需要的数据，不塞 UI 框架对象或回调。
- 快照测试（golden file）成为共享层回归的主力手段。
- 平台特有交互（托盘菜单、通知、权限引导）不进 ViewState，留在各平台。

## 备选方案

- **WebView 共享 UI**：UI 一份，但两套壳、体验与调试差，且与“原生桌面应用”的定位不符。
- **Compose Multiplatform**：共享度最高，但引入 Kotlin 与新工具链。
- **Swing + 最小 UI**：开发快，桌面体验粗糙。
