# ItemSync — Design Checkup

- **日期**: 2026-11-04
- **模式**: `/design checkup`（只诊断，不修改）
- **审查面**: Windows JavaFX 桌面端（主窗口、首次设置、密码、冲突、版本历史、局域网设备、同步规则、组设置）
- **注册类型**: Product（工具型界面，不是品牌页）
- **证据基础**: 源码检查。未启动 GUI，未做截图；字体渲染、原生高亮、缩放行为见“验证”一节的未验证项。
- **范围说明**: 仓库没有手写 HTML/CSS/JS，`*.html`/`*.css` 全部是 Gradle 构建产物。Android 端仅做结构阅读，未计分（无真机验证）。

---

## TL;DR

核心同步任务可用，但界面是默认 Modena 控件直接堆叠：没有主次层级、没有进行中状态，状态区把原始枚举和 JSON 直接展示给用户。更严重的是表单控件没有可访问名称，冲突对话框把破坏性操作设为回车默认项，固定宽度的弹窗在 Windows 高缩放下会超出可用区域。

**结论：阻塞（Block）。先修可访问名称与破坏性默认按钮，再处理缩放和状态表达。**

---

## 总分

**20 / 60**

| # | 生命体征 | 状态 | 分数 | 关键观察 |
|---|---|---|---|---|
| 1 | Intentionality | Watch | 5/10 | 默认 Modena 外观；11 个按钮同权重；无标题层级；状态区混入原始枚举/JSON |
| 2 | Readability | Watch | 5/10 | 默认 12–13px 字号；所有信息行同权重；`最近运行` 直接输出 `COMPLETED {json}` |
| 3 | Usability | Watch | 5/10 | 主任务可完成；但无同步进度、无取消；禁用按钮不说明原因；破坏性操作是回车默认 |
| 4 | Responsiveness | Critical | 0/10 | 主窗口固定 940×560；9 个弹窗中 7 个不可缩放且无 ScrollPane，宽度固定到 840 |
| 5 | Speed | Watch | 5/10 | 启动在 FX 线程同步完成配置/TLS/DB/端口绑定后才显示窗口；未测量耗时 |
| 6 | Accessibility | Critical | 0/10 | 表单控件无 `labelFor`/可访问名称；键盘能到达但读屏无法得知字段含义 |

---

## 生命体征细节

### 1. Intentionality — Watch

界面是 `GridPane` 标签墙加两排等宽按钮：`DesktopApp.java:284-314`。没有页面标题、没有分区、没有主操作样式。颜色只用于错误/警告文字（`#b00020`、`#9a6700`），没有形成角色系统。托盘图标是 16×16 的纯色圆角方块（`DesktopApp.java:1919-1925`）。

判断：这是“把控件摆上去”，不是“为操作员设计”。不要求艺术指导，但 Sync 工具属于 Operate 型界面，主操作和危险操作必须一眼可分。

### 2. Readability — Watch

默认字号下中文可读，换行也已处理（`setWrapText(true)`）。真正的问题是信息表达：`lastRunLabel` 直接拼接运行状态枚举和 `statsJson`（`DesktopApp.java:545`），用户看到的是 `COMPLETED {"scanned":...}` 这类实现细节。所有键值对同字号同字重，扫视时只能逐行读。

### 3. Usability — Watch

主任务路径成立：选组 → 立即同步 / 全部同步。空组状态有明确引导（`groupsHintLabel`，`DesktopApp.java:111`）。内联校验错误出现在弹窗内，而不是另弹提示。

代价在别处：同步期间按钮仅置灰，没有“同步中”、没有进度、没有取消（`DesktopApp.java:463-478`），大文件同步时界面像卡住；禁用按钮只在开机自启上有 tooltip 解释（`DesktopApp.java:1884`），其余禁用状态没有原因说明。

### 4. Responsiveness — Critical

主窗口 `new Scene(root, 940, 560)`（`DesktopApp.java:317`），未设置 `minWidth/minHeight`；`HBox` 不换行。弹窗 `prefWidth` 分别为 760、840、780、760、430、620、640、580（`DesktopApp.java:681, 825, 965, 1212, 1541, 1660`；`DesktopSetupDialog.java:80`），只有两个弹窗调用 `setResizable(true)`，且没有 `ScrollPane`。

按 Windows 缩放换算：1366×768 屏幕在 150% 下可用逻辑区域约 911×512，主窗口 940 宽已超出，840 宽弹窗加上标题栏和按钮栏也会超出；200% 时更明显。内容不会重排，也不会滚动，底部按钮可能不可达。

### 5. Speed — Watch

`start()` 在 FX 线程依次执行配置读取、身份解锁、`DesktopService.open()`（开库、绑定端口），然后才 `buildUi()` 和 `stage.show()`（`DesktopApp.java:129-188`）。启动慢时用户看到的是“双击后没反应”。同步/历史/设备列表的正确做法已经存在：后台 `worker` + `Platform.runLater`，这部分是健康的。

未测量冷启动耗时，故不判定为 Critical。

### 6. Accessibility — Critical

全仓库 JavaFX 源码中没有出现 `setLabelFor`、`setAccessibleText`、`setAccessibleRole`、`setMnemonicParsing`。所有表单一律是“视觉标签 + 输入框”，标签没有和控件关联：

- `LocalPasswordDialog.java:25-26`：两个密码框没有标签，也没有可访问名称；读屏只能听到两个“密码字段”，无法区分“密码”和“确认密码”。
- `DesktopSetupDialog.java:59-75`：同步根、组 ID、端口三个输入框同样没有关联标签。
- `DesktopApp.java:1495-1499, 1635-1639`：添加组和组设置中的文本输入框依赖相邻的 `Label`，没有 `labelFor`。

JavaFX 默认提供键盘焦点环和 Tab 顺序，没有自定义 CSS 移除它们；这部分是正常的。问题在“控件叫什么”，不在“能不能聚焦”。

---

## 发现清单

| # | 严重度 | Discipline | 位置 | 现状 | 应改为 | 为什么 |
|---|---|---|---|---|---|---|
| 1 | HIGH | Accessibility | `LocalPasswordDialog.java:25-26`；`DesktopSetupDialog.java:59-75`；`DesktopApp.java:1495-1499, 1635-1639` | 输入框只有视觉上的相邻标签，没有 `labelFor` / `setAccessibleText` | 每个输入框配可见 `Label` 并 `setLabelFor`，同时设置可访问名称；确认密码命名为“确认密码” | 读屏用户只能听到“文本字段”，首次设置和解锁无法完成 |
| 2 | HIGH | Interaction | `DesktopApp.java:672-676, 696-699` | “接受远端”使用 `ButtonData.OK_DONE`，是回车默认按钮，与“恢复本地/保留两者”同权重，且普通冲突无二次确认 | 改为 `ButtonData.OTHER`，默认按钮留给“关闭”；执行前确认，并给破坏性按钮危险样式 | 回车即覆盖本地文件，属于不可逆操作，且没有任何视觉区分 |
| 3 | HIGH | Responsive | `DesktopApp.java:317, 825, 965, 1212, 1541`；`DesktopSetupDialog.java:80` | 固定 `prefWidth` 到 840；多数弹窗不可缩放；内容没有 `ScrollPane` | 用 `maxWidth` + 可缩放窗口 + `ScrollPane` 包裹弹窗内容；设置窗口最小尺寸；按钮区固定在底部 | 1366×768 @150% 下逻辑视口约 911×512，窗口和弹窗会越界，底部按钮不可达 |
| 4 | MEDIUM | Intentionality | `DesktopApp.java:545` | `state() + "  " + statsJson()` 直接进界面 | 映射为中文摘要，例如“已完成 · 扫描 1,204 项 · 上传 3 项” | 原始枚举和 JSON 是日志格式，不是产品文案 |
| 5 | MEDIUM | Interaction | `DesktopApp.java:463-478, 481-498` | 同步时只置灰按钮，标签仍为“立即同步” | 显示 `ProgressIndicator`、改为“同步中…”，提供取消入口 | 长任务没有反馈，用户无法判断是否卡死 |
| 6 | MEDIUM | Layout | `DesktopApp.java:309-314` | 11 个按钮分两排，样式完全一致 | “立即同步”用主按钮样式；退出/删除与常用操作分离；按任务分组 | 眯眼测试失败，分不清主操作和危险操作 |
| 7 | MEDIUM | Writing | `DesktopApp.java:721-724, 865-868, 1473-1475` | 确认框使用 `ButtonType.OK` / `CANCEL` | 使用动词：“接受远端”“恢复此版本”“删除规则” | OK 不说明将发生什么，危险操作尤其需要命名 |
| 8 | MEDIUM | Usability | `DesktopApp.java:402-414, 1884` | 禁用按钮不解释原因，只有开机自启有 tooltip | 为每个禁用态提供 tooltip 或就近说明（未选组、组未加载、需要重启） | 用户看到灰按钮却不知道如何解锁 |
| 9 | MEDIUM | Accessibility | `DesktopApp.java:305-307, 525-558` | 状态和错误通过 `Label.setText` 更新，没有实时区域或焦点转移 | 错误出现时移动焦点或使用可访问通知；区分状态文本和错误文本 | 读屏用户不会得知同步失败或信任警告 |
| 10 | MEDIUM | Writing | `DesktopApp.java:1204, 526` | 规则按钮写“启用/停用”，不说明点击结果；主窗口出现英文 `port` | 按当前状态显示单一动作（“停用规则”）；中文界面统一为“端口” | “一个按钮一个动词”，中英混排增加理解成本 |
| 11 | LOW | Layout | `DesktopApp.java:318, 637, 781, 916, 1174, 1347, 1491, 1632` | 所有窗口和弹窗标题都是“ItemSync” | 标题带上任务名，例如“冲突 — 工作” | 窗口切换器中无法区分多个任务 |
| 12 | LOW | Layout | `DesktopApp.java:307` | 错误 `Label` 始终参与布局，空文本仍占一行 | 用 `managed`/`visible` 随内容切换，参照 `groupsHintLabel` | 主窗口常驻一段无意义空白 |
| 13 | LOW | Interaction | `DesktopApp.java:1927-1935, 1943-1961` | 托盘菜单只能由鼠标右键触发 | 提供键盘可达入口，或在主窗口保留等价操作（当前已有，故非阻塞） | 键盘用户无法走托盘路径，但功能未丢失 |

---

## 处方（Critical）

1. **表单没有可访问名称** — 读屏用户无法完成首次设置、解锁和组配置。用 `/design a11y` 修复：为每个输入框建立 `Label` + `labelFor`，补 `setAccessibleText`，确认密码单独命名。
2. **“接受远端”是回车默认** — 一次误按回车就会覆盖本地文件。用 `/design interaction` 修复：改为非默认按钮、执行前确认、危险样式与安全操作分离。
3. **固定宽度弹窗在缩放后越界** — 高 DPI 笔记本上底部按钮可能不可达。用 `/design responsive` 修复：弹窗内容放入 `ScrollPane`，窗口可缩放，设置最小尺寸，按钮区固定。

---

## 做得好的地方

- **诚实的破坏性文案**：删除组、更换同步根、清除本机数据都说明了后果与不可逆性；清除数据还要求输入“清除”。
- **空状态有下一步**：未配置同步组时直接提示点击“添加同步组”，不是只显示空白。
- **错误就地显示**：表单校验错误出现在当前弹窗内，不打断上下文。
- **重活不占 UI 线程**：同步、版本历史、设备刷新都在后台线程执行，再回 FX 线程更新。
- **技术信息对目标用户有意义**：组 ID、同步根、证书指纹、端口都直接可见，符合同步工具的诊断需求。

---

## 考虑过但未报告

| 位置 | 候选问题 | 不报告的原因 |
|---|---|---|
| `DesktopApp.java:278, 305-306` | 硬编码十六进制颜色 | 估算对比度约 4.8–7.1:1，在默认浅色背景上可读；当前没有主题系统，为三个颜色建 token 收益有限 |
| 全局 | 没有自定义 JavaFX 样式表，使用默认 Modena | 工具型小界面不需要艺术指导；真正的问题是层级和可访问性，已分别报告 |
| `DesktopApp.java:180-181` | 每 2 秒轮询状态 | 没有测量到卡顿或数据库压力，不能凭直觉要求改事件驱动 |
| `DesktopApp.java:1919-1925` | 托盘图标是纯色方块 | 16×16 系统托盘尺寸下，识别度优先于品牌表达 |
| `DesktopApp.java:264-276` | 用 `ComboBox` 选择同步组 | 组上限 5 个，组合框是合适控件 |
| `DesktopApp.java:633, 777, 913` | 空结果用信息弹窗提示 | 文案清楚、不误导；改成内联空状态属于优化，不是缺陷 |

---

## 验证

**已执行**

- `glob **/*.{html,css,js,ts,jsx,tsx,vue,svelte}`：97 个结果，全部位于 `build/` 报告目录；没有手写前端资源。
- 阅读 `DesktopApp.java` 全文（2,080 行）、`LocalPasswordDialog.java`、`DesktopSetupDialog.java`、`UserFacingErrors.java`。
- `grep setLabelFor|setAccessibleText|setAccessibleRole|setMnemonicParsing|setMinWidth|ScrollPane|ProgressIndicator|setResizable|getStylesheets|setDefaultButton`：仅命中 2 处 `setResizable(true)`；无样式表、无可访问名称、无滚动容器、无进度控件、无最小尺寸。
- 枚举所有弹窗 `prefWidth` 与 `ButtonData`，确认“接受远端”为 `OK_DONE` 默认按钮。
- 阅读 `MainActivity.java` 结构：Android 端使用 `ScrollView`，正文多处 `setTextSize(12)`；未计分。

**未验证**

- 未启动 JavaFX GUI（当前环境无界面捕获），因此字体渲染、焦点环外观、原生浅/深色主题对比度未实测。
- 150% / 200% Windows 缩放下的实际越界未截图验证；上文的越界判断来自固定宽度与无滚动容器的源码推算。
- 冷启动耗时、大文件同步时的进度表现未测量。
- Android 真机渲染与 TalkBack 行为未验证。

---

## 结论

**Block** — 存在 3 项 HIGH：表单无可访问名称、破坏性操作为回车默认、固定宽度弹窗在缩放后越界。

下一步建议顺序：`/design a11y` → `/design interaction` → `/design responsive` → `/design relayout`。
