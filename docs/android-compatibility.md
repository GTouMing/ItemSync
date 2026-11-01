# Android API 兼容规则

`minSdk` 保持 29（见 ADR-0027）。共享核心模块（`core-domain`、`core-store`、`core-sync`、`core-transport`）会被 Android 运行时加载，因此 main 代码只能使用 API 29 已有的 API。

## 已确认不可用的 API

| Java API | Android 起始版本 | 替代 |
|----------|------------------|------|
| `Path.of` | API 34 | `Compat.path`（内部 `Paths.get`） |
| `List.of` / `Map.of` / `Set.of` | API 30 | `Compat.list` / `Compat.map` / `Compat.set` |
| `List.copyOf` / `Map.copyOf` / `Set.copyOf` | API 30 | `Compat.copyList` / `Compat.copyMap` / `Compat.copySet` |
| `String.isBlank` / `strip` | API 33 | `Compat.isBlank` / `trim` |
| `InputStream.readAllBytes` / `transferTo` | API 33 | `Compat.readAllBytes` / `Compat.copy` |
| `Optional.isEmpty` | API 33 | `!optional.isPresent()` |
| `Stream.toList` | API 34 | `collect(Collectors.toList())` |
| `HexFormat` | 不存在 | `Compat.hex` |
| `Files.readString` / `writeString` | 不存在 | `Compat.readString` / `Compat.writeString` |

## 约束

- `core-*` 的 main 代码不得直接使用上表 API。
- `Compat` 位于 `core-domain` 的 `domain.compat` 包，只使用 Java 8 / API 29 可用的 API。
- JVM 测试可以使用较新的 API；它们不运行在 Android 上。
- `platform-desktop` 与 `cli` 不受此限制。
- Android 服务接入前，需要验证 AGP 对 record / sealed / switch 表达式的 desugaring 覆盖 API 29。
