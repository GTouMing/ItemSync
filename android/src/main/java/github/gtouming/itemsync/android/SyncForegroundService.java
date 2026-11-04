package github.gtouming.itemsync.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.DeviceId;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.GroupDevice;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.model.TriggerType;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.group.GroupRuntime;
import github.gtouming.itemsync.sync.engine.node.SyncDevice;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trigger.TriggerEngine;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class SyncForegroundService extends Service {

    private static final String TAG = "ItemSyncService";
    private static final String CHANNEL_ID = "itemsync";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private volatile SyncDevice device;
    private final Map<GroupId, PeerTrustCoordinator> peerTrusts = new ConcurrentHashMap<>();
    private final Map<GroupId, AndroidDirectoryWatcher> watchers = new ConcurrentHashMap<>();
    private final Map<GroupId, TriggerEngine> triggerEngines = new ConcurrentHashMap<>();
    private final Map<GroupId, ScheduledFuture<?>> debounces = new ConcurrentHashMap<>();
    private final ReentrantLock storeLock = new ReentrantLock();
    private AndroidSqliteMetadataStore store;
    private volatile AndroidTrustStore trustStore;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean started;
    private volatile int lastOnlineCount = -1;
    private volatile String lastTrustWarning;

    public static boolean isRunning() {
        return running;
    }

    private static volatile boolean running;
    private static volatile SyncForegroundService instance;

    // Used only by the debug-only admin provider.
    static SyncForegroundService instance() {
        return instance;
    }

    public final class LocalBinder extends Binder {
        public SyncForegroundService service() {
            return SyncForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ItemSync 同步", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        startForeground(NOTIFICATION_ID, notification("正在启动"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (!started) {
            started = true;
            executor.execute(this::startNode);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean isReady() {
        return device != null && !peerTrusts.isEmpty();
    }

    /**
     * The ids of the groups this running service serves.
     */
    public List<GroupId> groupIds() {
        SyncDevice current = device;
        return current == null ? Compat.list() : Compat.copyList(current.groups().keySet());
    }

    /**
     * Registers a listener that fires when a peer appears or disappears in one group. The
     * listener runs on the discovery thread; callers must post to their UI thread.
     */
    public AutoCloseable addPresenceListener(GroupId groupId, Runnable listener) {
        SyncDevice current = device;
        GroupRuntime runtime = current == null ? null : current.group(groupId);
        if (runtime == null) {
            throw new IllegalStateException("同步组尚未启动: " + groupId.value());
        }
        return runtime.presenceTracker().addListener(listener);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews() {
        GroupId first = firstGroupId();
        return first == null ? Compat.list() : peerViews(first, true);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(boolean forceRefresh) {
        GroupId first = firstGroupId();
        return first == null ? Compat.list() : peerViews(first, forceRefresh);
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId, boolean forceRefresh) {
        PeerTrustCoordinator current = peerTrusts.get(groupId);
        if (current == null) {
            return Compat.list();
        }
        return mergeKnownDevices(groupId, current.peerViews(forceRefresh));
    }

    public List<PeerTrustCoordinator.PeerView> peerViews(GroupId groupId) {
        return peerViews(groupId, true);
    }

    private List<PeerTrustCoordinator.PeerView> mergeKnownDevices(
            GroupId groupId, List<PeerTrustCoordinator.PeerView> online) {
        AndroidSqliteMetadataStore current = store;
        AndroidTrustStore trust = trustStore;
        if (current == null || trust == null) {
            return online;
        }
        storeLock.lock();
        try {
            Set<DeviceId> onlineIds = new HashSet<>();
            for (PeerTrustCoordinator.PeerView view : online) {
                DiscoveredPeer peer = view.presence().peer();
                onlineIds.add(peer.deviceId());
                rememberDeviceIfChangedLocked(current, groupId, peer,
                        view.presence().lastSeen());
            }
            List<PeerTrustCoordinator.PeerView> merged = new ArrayList<>(online);
            for (GroupDevice device : current.devices().findByGroup(groupId)) {
                if (onlineIds.contains(device.deviceId())) {
                    continue;
                }
                boolean trusted = trust.isTrusted(groupId, device.certFingerprint());
                Instant lastSeen = device.lastSeenAt() == null
                        ? device.knownAt()
                        : device.lastSeenAt();
                merged.add(PeerTrustCoordinator.PeerView.offline(groupId, device.deviceId(),
                        device.certFingerprint(), lastSeen, trusted));
            }
            merged.sort(Comparator.comparing(
                    view -> view.presence().peer().deviceId().value()));
            return Compat.copyList(merged);
        } finally {
            storeLock.unlock();
        }
    }

    private void rememberOnlineDevices(GroupId groupId,
                                       List<PresenceTracker.Presence> presences) {
        AndroidSqliteMetadataStore current = store;
        if (current == null) {
            return;
        }
        storeLock.lock();
        try {
            for (PresenceTracker.Presence presence : presences) {
                rememberDeviceLocked(current, groupId, presence.peer(), presence.lastSeen());
            }
        } finally {
            storeLock.unlock();
        }
    }

    private static void rememberDeviceLocked(AndroidSqliteMetadataStore store, GroupId groupId,
                                             DiscoveredPeer peer, Instant lastSeen) {
        GroupDevice existing = store.devices().find(groupId, peer.deviceId()).orElse(null);
        Instant seen = lastSeen == null ? Instant.EPOCH : lastSeen;
        Instant knownAt = existing == null || existing.knownAt() == null
                ? seen
                : existing.knownAt();
        store.devices().upsert(new GroupDevice(groupId, peer.deviceId(),
                existing == null ? null : existing.displayName(), peer.fingerprint(),
                seen, knownAt));
    }

    /**
     * The dialog refreshes every few seconds; only a new device or a rotated certificate
     * needs a write, the ten-second reconcile updates last-seen.
     */
    private static void rememberDeviceIfChangedLocked(AndroidSqliteMetadataStore store,
                                                      GroupId groupId, DiscoveredPeer peer,
                                                      Instant lastSeen) {
        GroupDevice existing = store.devices().find(groupId, peer.deviceId()).orElse(null);
        if (existing != null
                && existing.certFingerprint().equalsIgnoreCase(peer.fingerprint())) {
            return;
        }
        rememberDeviceLocked(store, groupId, peer, lastSeen);
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        GroupId first = firstGroupId();
        if (first == null) {
            throw new IllegalStateException("同步服务尚未启动");
        }
        trustPeer(first, peer);
    }

    public void trustPeer(GroupId groupId, DiscoveredPeer peer) throws IOException {
        PeerTrustCoordinator current = peerTrusts.get(groupId);
        if (current == null) {
            throw new IllegalStateException("同步组尚未启动: " + groupId.value());
        }
        current.trustPeer(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        GroupId first = firstGroupId();
        if (first == null) {
            throw new IllegalStateException("同步服务尚未启动");
        }
        untrustPeer(first, peer);
    }

    public void untrustPeer(GroupId groupId, DiscoveredPeer peer) throws IOException {
        PeerTrustCoordinator current = peerTrusts.get(groupId);
        if (current == null) {
            throw new IllegalStateException("同步组尚未启动: " + groupId.value());
        }
        current.untrustPeer(peer);
    }

    public void requestSync() {
        GroupId first = firstGroupId();
        if (first != null) {
            requestSync(first);
        }
    }

    public void requestSync(GroupId groupId) {
        if (device != null && peerTrusts.containsKey(groupId)) {
            executor.execute(() -> sync(groupId));
        }
    }

    public void requestSyncAll() {
        if (device != null) {
            executor.execute(this::syncAll);
        }
    }

    /**
     * The stored sync rules of one group. A rule change only reaches the running
     * {@link TriggerEngine} after the service restarts; callers must say so.
     */
    public List<Trigger> triggers(GroupId groupId) {
        AndroidSqliteMetadataStore current = requireStore();
        storeLock.lock();
        try {
            return current.triggers().findByGroup(groupId);
        } finally {
            storeLock.unlock();
        }
    }

    public Trigger addTrigger(GroupId groupId, TriggerConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        AndroidSqliteMetadataStore current = requireStore();
        SyncDevice syncDevice = device;
        if (syncDevice == null || syncDevice.group(groupId) == null) {
            throw new IllegalStateException("同步组尚未启动: " + groupId.value());
        }
        TriggerId id = new TriggerId(UUID.randomUUID().toString());
        TriggerType type = config instanceof TriggerConfig.Alarm
                ? TriggerType.ALARM
                : TriggerType.CONDITION;
        Instant nextFire = config instanceof TriggerConfig.Alarm alarm
                ? alarm.schedule().nextFire(Instant.now())
                : null;
        Trigger trigger = new Trigger(id, groupId, type, TriggerConfigCodec.encode(config),
                true, nextFire);
        storeLock.lock();
        try {
            current.triggers().upsert(trigger);
        } finally {
            storeLock.unlock();
        }
        TriggerEngine engine = triggerEngines.get(groupId);
        if (engine != null) {
            engine.register(trigger);
        }
        return trigger;
    }

    public void deleteTrigger(GroupId groupId, TriggerId id) throws IOException {
        Objects.requireNonNull(id, "id");
        AndroidSqliteMetadataStore current = requireStore();
        storeLock.lock();
        try {
            Trigger trigger = current.triggers().find(id)
                    .orElseThrow(() -> new IOException("trigger not found: " + id.value()));
            if (!trigger.groupId().equals(groupId)) {
                throw new IOException("trigger does not belong to this group: " + id.value());
            }
            current.triggers().delete(id);
        } finally {
            storeLock.unlock();
        }
        TriggerEngine engine = triggerEngines.get(groupId);
        if (engine != null) {
            engine.unregister(id);
        }
    }

    public Trigger setTriggerEnabled(GroupId groupId, TriggerId id, boolean enabled)
            throws IOException {
        Objects.requireNonNull(id, "id");
        AndroidSqliteMetadataStore current = requireStore();
        Trigger updated;
        storeLock.lock();
        try {
            Trigger trigger = current.triggers().find(id)
                    .orElseThrow(() -> new IOException("trigger not found: " + id.value()));
            if (!trigger.groupId().equals(groupId)) {
                throw new IOException("trigger does not belong to this group: " + id.value());
            }
            Instant nextFire = null;
            if (enabled && TriggerConfigCodec.decode(trigger.configJson())
                    instanceof TriggerConfig.Alarm alarm) {
                nextFire = alarm.schedule().nextFire(Instant.now());
            }
            updated = new Trigger(trigger.id(), trigger.groupId(), trigger.type(),
                    trigger.configJson(), enabled, nextFire);
            current.triggers().upsert(updated);
        } finally {
            storeLock.unlock();
        }
        TriggerEngine engine = triggerEngines.get(groupId);
        if (engine != null) {
            if (enabled) {
                engine.register(updated);
            } else {
                engine.unregister(id);
            }
        }
        return updated;
    }

    private AndroidSqliteMetadataStore requireStore() {
        AndroidSqliteMetadataStore current = store;
        if (current == null) {
            throw new IllegalStateException("同步服务尚未启动");
        }
        return current;
    }

    private void startNode() {
        try {
            AndroidSyncConfig config = AndroidSyncConfig.of(this);
            List<AndroidGroup> groups = config.groups();
            if (groups.isEmpty()) {
                updateNotification("未配置同步组，请在 ItemSync 中添加");
                return;
            }
            char[] password = config.identitySecret();
            if (password.length == 0) {
                updateNotification("无法解锁本机身份，请清除应用数据后重试");
                return;
            }
            TlsIdentity identity = ensureIdentity(password);
            config.saveFingerprint(identity.fingerprint());

            NodeId nodeId = NodeId.of(readOrCreate("node-id"));
            DeviceId deviceId = DeviceId.of(readOrCreate("device-id"));

            acquireMulticastLock();
            trustStore = new AndroidTrustStore(this);
            AndroidTrustStore trustStore = this.trustStore;
            List<GroupId> groupIds = new ArrayList<>(groups.size());
            List<SyncDevice.GroupConfig> groupConfigs = new ArrayList<>(groups.size());
            for (AndroidGroup group : groups) {
                Path root = Compat.path(group.rootPath());
                Files.createDirectories(root);
                groupIds.add(group.id());
                // Trusted peers are added dynamically through the device list of that group.
                groupConfigs.add(new SyncDevice.GroupConfig(group.id(), group.name(), root,
                        Compat.set("none"), trustStore::isTrusted));
            }
            trustStore.retainOnly(groupIds);

            store = new AndroidSqliteMetadataStore(this, "itemsync.sqlite");
            InetAddress discoveryAddress = preferredDiscoveryAddress();
            device = new SyncDevice(nodeId, deviceId, identity, store,
                    new FileSystemBlobStore(new File(getFilesDir(), "data").toPath()),
                    PathResolvers.posix(), groupConfigs, config.port(),
                    new File(getCacheDir(), "transfer").toPath(), discoveryAddress);

            for (AndroidGroup group : groups) {
                GroupRuntime runtime = device.group(group.id());
                if (runtime == null) {
                    throw new IllegalStateException(
                            "the sync device did not start group " + group.id().value());
                }
                PeerTrustCoordinator trust =
                        new PeerTrustCoordinator(identity, runtime, trustStore);
                peerTrusts.put(group.id(), trust);
                trust.reconcileTrustedPeers();

                List<Trigger> triggers = store.triggers().findByGroup(group.id());
                TriggerEngine triggerEngine = new TriggerEngine(store, group.id(), nodeId,
                        runtime::syncNow,
                        work -> {
                            storeLock.lock();
                            try {
                                return runtime.runSerialized(work);
                            } finally {
                                storeLock.unlock();
                            }
                        },
                        Instant::now, Duration.ofSeconds(30));
                triggerEngine.registerAll(triggers);
                triggerEngines.put(group.id(), triggerEngine);

                AndroidDirectoryWatcher watcher = new AndroidDirectoryWatcher(
                        new File(group.rootPath()), () -> {
                            List<TriggerId> directoryRules =
                                    triggerEngine.directoryTriggers();
                            if (directoryRules.isEmpty()) {
                                onFilesChanged(group.id());
                            } else {
                                for (TriggerId triggerId : directoryRules) {
                                    triggerEngine.onDirectoryChanged(triggerId);
                                }
                            }
                        });
                watcher.start();
                watchers.put(group.id(), watcher);
            }

            scheduler.scheduleWithFixedDelay(this::reconcile, 10, 10, TimeUnit.SECONDS);
            int interval = config.intervalSeconds();
            if (interval > 0) {
                scheduler.scheduleWithFixedDelay(this::requestSyncAll, interval, interval,
                        TimeUnit.SECONDS);
            }
            if (device.discoveryAvailable()) {
                Log.i(TAG, "mDNS discovery started for " + groups.size() + " group(s)");
                updateNotification("已启动，" + groups.size() + " 个同步组");
            } else {
                Log.e(TAG, "mDNS discovery unavailable", device.discoveryError());
                updateNotification("已启动（局域网发现不可用，请查看日志）");
            }
            requestSyncAll();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            updateNotification("启动失败: " + message(e));
            closeNode();
        }
    }

    private void reconcile() {
        int online = 0;
        String warning = null;
        for (Map.Entry<GroupId, PeerTrustCoordinator> entry : peerTrusts.entrySet()) {
            try {
                PeerTrustCoordinator trust = entry.getValue();
                trust.reconcileTrustedPeers();
                trust.refreshRemoteTrust();
                List<PresenceTracker.Presence> peers = trust.onlinePeers();
                online += peers.size();
                rememberOnlineDevices(entry.getKey(), peers);
                if (warning == null) {
                    warning = trust.lastWarning();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "peer reconcile failed for " + entry.getKey().value(), e);
            }
        }
        if (online != lastOnlineCount) {
            lastOnlineCount = online;
            Log.i(TAG, "online peers: " + online);
        }
        if (warning != null && !warning.equals(lastTrustWarning)) {
            lastTrustWarning = warning;
            Log.w(TAG, warning);
            updateNotification(warning);
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return;
        }
        multicastLock = wifi.createMulticastLock("itemsync-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private InetAddress preferredDiscoveryAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            InetAddress fallback = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String name = network.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith("wlan") || name.startsWith("wifi")) {
                        return address;
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "cannot select discovery address", e);
            return null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            if (multicastLock.isHeld()) {
                multicastLock.release();
            }
            multicastLock = null;
        }
    }

    private TlsIdentity ensureIdentity(char[] password) throws Exception {
        File file = new File(getFilesDir(), "identity.p12");
        if (file.isFile()) {
            return TlsIdentity.load(file.toPath(), password);
        }
        TlsIdentity identity = TlsIdentity.generate("itemsync", password);
        identity.save(file.toPath());
        return identity;
    }

    private String readOrCreate(String name) throws IOException {
        File file = new File(getFilesDir(), name);
        if (file.isFile()) {
            return Compat.readString(file.toPath()).trim();
        }
        String value = UUID.randomUUID().toString();
        Compat.writeString(file.toPath(), value);
        return value;
    }

    private void onFilesChanged(GroupId groupId) {
        synchronized (this) {
            ScheduledFuture<?> existing = debounces.remove(groupId);
            if (existing != null) {
                existing.cancel(false);
            }
            if (scheduler.isShutdown()) {
                return;
            }
            debounces.put(groupId, scheduler.schedule(() -> requestSync(groupId),
                    2, TimeUnit.SECONDS));
        }
    }

    private void syncAll() {
        SyncDevice current = device;
        if (current == null) {
            return;
        }
        for (GroupId groupId : current.groups().keySet()) {
            sync(groupId);
        }
    }

    private void sync(GroupId groupId) {
        SyncDevice current = device;
        if (current == null) {
            return;
        }
        GroupRuntime runtime = current.group(groupId);
        if (runtime == null) {
            return;
        }
        try {
            PeerTrustCoordinator trust = peerTrusts.get(groupId);
            if (trust != null) {
                trust.reconcileTrustedPeers();
            }
            List<SyncRunResult> results;
            storeLock.lock();
            try {
                results = runtime.syncNow();
            } finally {
                storeLock.unlock();
            }
            int pulled = 0;
            int conflicts = 0;
            for (SyncRunResult result : results) {
                pulled += result.pulled();
                conflicts += result.conflicts();
            }
            String name = runtime.displayName() == null
                    ? groupId.value()
                    : runtime.displayName();
            updateNotification(name + " 已同步: 拉取 " + pulled + " 项，冲突 " + conflicts + " 项");
        } catch (Exception e) {
            updateNotification(groupId.value() + " 同步失败: " + message(e));
        }
    }

    private GroupId firstGroupId() {
        SyncDevice current = device;
        if (current == null) {
            return null;
        }
        for (GroupId groupId : current.groups().keySet()) {
            return groupId;
        }
        return null;
    }

    private void updateNotification(String text) {
        try {
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, notification(text));
        } catch (RuntimeException ignored) {
            // notification is best effort
        }
    }

    private Notification notification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ItemSync")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private static String message(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private void closeNode() {
        synchronized (this) {
            for (ScheduledFuture<?> future : debounces.values()) {
                future.cancel(false);
            }
            debounces.clear();
        }
        for (AndroidDirectoryWatcher watcher : watchers.values()) {
            watcher.close();
        }
        watchers.clear();
        for (TriggerEngine engine : triggerEngines.values()) {
            engine.close();
        }
        triggerEngines.clear();
        for (PeerTrustCoordinator trust : peerTrusts.values()) {
            trust.close();
        }
        peerTrusts.clear();
        if (device != null) {
            device.close();
            device = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
        releaseMulticastLock();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        started = false;
        running = false;
        closeNode();
        scheduler.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }
}
