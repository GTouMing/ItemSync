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
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.store.blob.FileSystemBlobStore;
import github.gtouming.itemsync.sync.apply.PathResolvers;
import github.gtouming.itemsync.sync.engine.SyncRunResult;
import github.gtouming.itemsync.sync.engine.node.SyncNode;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.transport.discovery.DiscoveredPeer;
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SyncForegroundService extends Service {

    private static final String TAG = "ItemSyncService";
    private static final String CHANNEL_ID = "itemsync";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private volatile SyncNode node;
    private volatile PeerTrustCoordinator peerTrust;
    private AndroidSqliteMetadataStore store;
    private AndroidDirectoryWatcher watcher;
    private WifiManager.MulticastLock multicastLock;
    private ScheduledFuture<?> debounce;
    private volatile boolean started;
    private volatile int lastOnlineCount = -1;
    private volatile String lastTrustWarning;

    public static boolean isRunning() {
        return running;
    }

    private static volatile boolean running;

    public final class LocalBinder extends Binder {
        public SyncForegroundService service() {
            return SyncForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        return node != null && peerTrust != null;
    }

    public List<PeerTrustCoordinator.PeerView> peerViews() {
        PeerTrustCoordinator current = peerTrust;
        return current == null ? Compat.list() : current.peerViews();
    }

    public void trustPeer(DiscoveredPeer peer) throws IOException {
        PeerTrustCoordinator current = peerTrust;
        if (current == null) {
            throw new IllegalStateException("同步服务尚未启动");
        }
        current.trustPeer(peer);
    }

    public void untrustPeer(DiscoveredPeer peer) throws IOException {
        PeerTrustCoordinator current = peerTrust;
        if (current == null) {
            throw new IllegalStateException("同步服务尚未启动");
        }
        current.untrustPeer(peer);
    }

    public void requestSync() {
        if (node != null) {
            executor.execute(this::sync);
        }
    }

    private void startNode() {
        try {
            AndroidSyncConfig config = AndroidSyncConfig.of(this);
            if (!config.isConfigured()) {
                updateNotification("未配置，请在 ItemSync 中填写");
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
            GroupId group = GroupId.parse(config.groupId());
            Path root = Compat.path(config.rootPath());
            Files.createDirectories(root);

            acquireMulticastLock();
            AndroidTrustStore trustStore = new AndroidTrustStore(this);
            trustStore.retainOnly(group);

            store = new AndroidSqliteMetadataStore(this, "itemsync.sqlite");
            InetAddress discoveryAddress = preferredDiscoveryAddress();
            node = new SyncNode(nodeId, group, SyncRootId.of(config.rootId()), root, store,
                    new FileSystemBlobStore(new File(getFilesDir(), "data").toPath()),
                    PathResolvers.posix(), identity, deviceId, Compat.set("none"),
                    config.port(), new File(getCacheDir(), "transfer").toPath(),
                    trustStore::isTrusted, discoveryAddress);
            peerTrust = new PeerTrustCoordinator(group, identity, node, trustStore);
            peerTrust.reconcileTrustedPeers();

            watcher = new AndroidDirectoryWatcher(root.toFile(), this::onFilesChanged);
            watcher.start();
            scheduler.scheduleWithFixedDelay(this::reconcile, 10, 10, TimeUnit.SECONDS);
            int interval = config.intervalSeconds();
            if (interval > 0) {
                scheduler.scheduleWithFixedDelay(this::sync, interval, interval, TimeUnit.SECONDS);
            }
            if (node.discoveryAvailable()) {
                Log.i(TAG, "mDNS discovery started");
                updateNotification("已启动");
            } else {
                Log.e(TAG, "mDNS discovery unavailable", node.discoveryError());
                updateNotification("已启动（局域网发现不可用，请查看日志）");
            }
            sync();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            updateNotification("启动失败: " + message(e));
            closeNode();
        }
    }

    private void reconcile() {
        PeerTrustCoordinator current = peerTrust;
        if (current == null) {
            return;
        }
        try {
            current.reconcileTrustedPeers();
            current.refreshRemoteTrust();
            int online = current.onlinePeers().size();
            if (online != lastOnlineCount) {
                lastOnlineCount = online;
                Log.i(TAG, "online peers: " + online);
            }
            String warning = current.lastWarning();
            if (warning != null && !warning.equals(lastTrustWarning)) {
                lastTrustWarning = warning;
                Log.w(TAG, warning);
                updateNotification(warning);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "peer reconcile failed", e);
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

    private void onFilesChanged() {
        synchronized (this) {
            if (debounce != null) {
                debounce.cancel(false);
            }
            if (scheduler.isShutdown()) {
                return;
            }
            debounce = scheduler.schedule(this::sync, 2, TimeUnit.SECONDS);
        }
    }

    private void sync() {
        SyncNode current = node;
        if (current == null) {
            return;
        }
        try {
            PeerTrustCoordinator trust = peerTrust;
            if (trust != null) {
                trust.reconcileTrustedPeers();
            }
            List<SyncRunResult> results = current.syncNow();
            int pulled = 0;
            int conflicts = 0;
            for (SyncRunResult result : results) {
                pulled += result.pulled();
                conflicts += result.conflicts();
            }
            updateNotification("已同步: 拉取 " + pulled + " 项，冲突 " + conflicts + " 项");
        } catch (Exception e) {
            updateNotification("同步失败: " + message(e));
        }
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
            if (debounce != null) {
                debounce.cancel(false);
                debounce = null;
            }
        }
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        PeerTrustCoordinator trust = peerTrust;
        peerTrust = null;
        if (trust != null) {
            trust.close();
        }
        if (node != null) {
            node.close();
            node = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
        releaseMulticastLock();
    }

    @Override
    public void onDestroy() {
        started = false;
        running = false;
        closeNode();
        scheduler.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }
}
