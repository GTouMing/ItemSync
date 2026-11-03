package github.gtouming.itemsync.sync.engine.node;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.store.blob.BlobStore;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.PhysicalPathResolver;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.group.GroupRuntime;
import github.gtouming.itemsync.sync.trust.PeerGroup;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.pairing.PairingTrustProvider;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Single group facade over one {@link SyncDevice}.
 *
 * <p>The running node is a device with a shared sync port, pairing endpoint and mDNS
 * advertisement serving every enabled group (ADR-0036); this facade creates a device with
 * exactly one group and delegates to that group's {@link GroupRuntime}, so the existing
 * desktop, Android, CLI and test callers keep working unchanged while the multi-group
 * device layer replaces the previous per-node server.
 */
public final class SyncNode implements AutoCloseable, PeerGroup {

    private final NodeId nodeId;
    private final GroupId groupId;
    private final SyncDevice device;
    private final GroupRuntime group;

    public SyncNode(NodeId nodeId, GroupId groupId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, String expectedClientFingerprint,
                    int port, Path transferTempDir) throws IOException {
        this(nodeId, groupId, syncRoot, store, blobs, paths, identity, deviceId,
                Compat.set(expectedClientFingerprint), port, transferTempDir);
    }

    public SyncNode(NodeId nodeId, GroupId groupId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, Set<String> expectedClientFingerprints,
                    int port, Path transferTempDir) throws IOException {
        this(nodeId, groupId, syncRoot, store, blobs, paths, identity, deviceId,
                expectedClientFingerprints, port, transferTempDir, null);
    }

    public SyncNode(NodeId nodeId, GroupId groupId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, Set<String> expectedClientFingerprints,
                    int port, Path transferTempDir, PairingTrustProvider pairingTrust)
            throws IOException {
        this(nodeId, groupId, syncRoot, store, blobs, paths, identity, deviceId,
                expectedClientFingerprints, port, transferTempDir, pairingTrust, null);
    }

    public SyncNode(NodeId nodeId, GroupId groupId, Path syncRoot,
                    MetadataStore store, BlobStore blobs, PhysicalPathResolver paths,
                    TlsIdentity identity, DeviceId deviceId, Set<String> expectedClientFingerprints,
                    int port, Path transferTempDir, PairingTrustProvider pairingTrust,
                    InetAddress discoveryAddress)
            throws IOException {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(syncRoot, "syncRoot");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(blobs, "blobs");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(deviceId, "deviceId");
        Set<String> pins = Objects.requireNonNull(expectedClientFingerprints,
                "expectedClientFingerprints");
        if (pins.isEmpty()) {
            throw new IllegalArgumentException("expected client fingerprints must not be empty");
        }
        Objects.requireNonNull(transferTempDir, "transferTempDir");
        this.device = new SyncDevice(nodeId, deviceId, identity, store, blobs, paths,
                List.of(new SyncDevice.GroupConfig(groupId, null, syncRoot, pins, pairingTrust)),
                port, transferTempDir, discoveryAddress);
        GroupRuntime runtime = device.group(groupId);
        if (runtime == null) {
            device.close();
            throw new IllegalStateException("the sync device did not start group "
                    + groupId.value());
        }
        this.group = runtime;
    }

    @Override
    public GroupId groupId() {
        return groupId;
    }

    @Override
    public List<PresenceTracker.Presence> onlinePeers() {
        return group.onlinePeers();
    }

    public boolean discoveryAvailable() {
        return device.discoveryAvailable();
    }

    public Exception discoveryError() {
        return device.discoveryError();
    }

    @Override
    public void allowClient(String fingerprint) {
        device.allowClient(groupId, fingerprint);
    }

    @Override
    public void revokeClient(String fingerprint) {
        device.revokeClient(groupId, fingerprint);
    }

    @Override
    public void removePeer(NodeId peerNodeId) {
        group.removePeer(peerNodeId);
    }

    public int port() {
        return device.port();
    }

    public int pairingPort() {
        return device.pairingPort();
    }

    @Override
    public boolean isConnected(NodeId peerNodeId) {
        return group.isConnected(peerNodeId);
    }

    @Override
    public boolean hasInboundConnection(String fingerprint) {
        return device.hasInboundConnection(groupId, fingerprint);
    }

    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * The error of this node's group, falling back to the inbound failures of the shared
     * sync port, which cannot be attributed to a group.
     */
    public IOException lastError() {
        IOException groupError = group.lastError();
        return groupError != null ? groupError : device.lastError();
    }

    @Override
    public void addPeer(NodeId peerNodeId, String host, int port,
                        String expectedServerFingerprint) throws IOException {
        group.addPeer(peerNodeId, host, port, expectedServerFingerprint);
    }

    public void notifyPeers() throws IOException {
        group.notifyPeers();
    }

    public List<SyncRunResult> syncNow() throws IOException {
        return group.syncNow();
    }

    public List<SyncRunResult> runSerialized(TriggerEngine.SyncRunner work) throws IOException {
        return group.runSerialized(work);
    }

    public <T> T readSerialized(java.util.function.Supplier<T> work) {
        return group.readSerialized(work);
    }

    public void refreshManifest() {
        group.refreshManifest();
    }

    @Override
    public void close() {
        device.close();
    }
}
