# 0036. 多组共享同步端口与组 ID 路由

- 状态：已接受
- 日期：2026-11-03
- 修订：[ADR-0011](0011-mdns-tls-chunked-transfer.md)（mDNS/TLS/端口）、[ADR-0031](0031-pairing-status-endpoint.md)（配对端点）、[ADR-0033](0033-trust-without-short-code.md)（连接/信任路由）。
- 领域模型见 [ADR-0035](0035-multi-group-single-root.md)。

## 背景

多组需要同时在线。每组一个 `SyncNode`、独立端口和独立 mDNS 广播的方案被否决：端口/防火墙规则会随组数增长，同一设备的 mDNS 记录也会重复。需要在一个同步端口上隔离多个组，同时保留“同步端口只接受该组已固定证书”的安全属性。

## 决策

1. **一个同步端口**：所有组共享固定端口 `47800` 和一个 TLS 服务端；一个节点只有一个配对端点（临时端口），mDNS TXT 发布 `pairingPort`。不再为每个组启动独立 `SyncNode`/监听器。
2. **一条 mDNS 广告**：每个设备只发布一条 `_itemsync._tcp.local` 广告，TXT 用 `groupIds` 列出该设备当前启用的组（最多 5 个），端口仍是共享同步端口。停用组从列表中移除；组列表变化时原子更新广告。组 ID 本来就是命名空间，不是秘密；同一局域网能看到该设备的组列表，这是接受的可见性取舍。
3. **SNI 选组**：客户端 TLS 的 SNI 携带组 ID；服务端根据 SNI 选择该组的 `PinnedTrustManager`/`tls.Config`，在 TLS 层只接受该组已固定的证书。未知 SNI 或无 SNI 的连接直接拒绝。
4. **Hello 再确认**：`HELLO`/`HELLO_ACK` 携带 `groupId`；服务端校验 Hello 的组 ID 与 SNI 一致。一个连接只属于一个组；跨组帧或组 ID 不一致直接关闭。
5. **组内路由**：连接建立后绑定到该组的 `RemoteSyncServer`/同步会话；`MANIFEST_REQUEST`/`MANIFEST_PAGE`/`MANIFEST_END`/`BLOB_GET` 由连接所属组处理，不再使用 `rootId`。`NOTIFY` 继续携带 `groupId` 作为防御性校验。
6. **每组建连接状态**：`TlsTransportServer` 按组跟踪连接；`hasConnection(groupId, fingerprint)`、`closeConnections(groupId, fingerprint)` 只影响该组。撤销 A 组信任不能关闭 B 组的连接。
7. **共享配对端点**：配对请求携带 `groupId`，共享端点按组返回该组的信任状态；它仍接受任意客户端证书、只返回元数据，不处理同步帧，也不改变信任状态。
8. **版本兼容**：旧版本没有 `groupIds`/SNI/Hello groupId；新旧协议不兼容。两端必须同时升级；桌面先落地，Android 跟进同一模型。

## 后果

- 防火墙只需放行一个同步端口，端口/组数解耦。
- 服务端复杂度上升：SNI 选组、每组 trust manager、Hello 校验、连接按组跟踪、帧按组路由。
- 组 ID 列表在 mDNS 中可见；同一局域网能看到设备加入的所有组（组 ID 不是秘密）。
- 未建立连接时，远端信任仍通过共享配对端点查询；已连接时按 ADR-0033 的连接证据处理。
- 旧客户端无法发现/连接新客户端；混合版本会出现发现失败或握手拒绝，必须两端升级。

## 备选方案

- **每组一个 SyncNode/端口/mDNS**：隔离最简单，但端口/防火墙和广播数量随组增长，否决。
- **全局发现，不带组 ID**：实现简单，但所有设备互相可见，弱化 ADR-0029 的发现范围，否决。
- **每组一条 mDNS 广告、共享端口**：比 A 更贴近现有 `JmDnsPeerDiscovery`，但同一设备多条记录，已被“一条广告 + groupIds”替代。
- **共享一个宽松 trust manager，握手后在应用层按组拒绝**：实现简单，但同步端口不再做到“只接受该组固定证书”，否决。
- **SNI 只做路由、不做 TLS 选组**：握手阶段无法按组固定证书，等于放弃现有安全属性，否决。
