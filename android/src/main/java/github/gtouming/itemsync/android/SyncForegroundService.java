package github.gtouming.itemsync.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import github.gtouming.itemsync.transport.tls.TlsIdentity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private volatile SyncNode node;
    private AndroidSqliteMetadataStore store;
    private AndroidDirectoryWatcher watcher;
    private ScheduledFuture<?> debounce;
    private volatile boolean started;

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
        return null;
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
            Path root = Compat.path(config.rootPath());
            Files.createDirectories(root);

            store = new AndroidSqliteMetadataStore(this, "itemsync.sqlite");
            Set<String> accepted = new LinkedHashSet<>(config.expectedClients());
            if (accepted.isEmpty()) {
                accepted.add("none");
            }
            node = new SyncNode(nodeId, GroupId.parse(config.groupId()),
                    SyncRootId.of(config.rootId()), root, store,
                    new FileSystemBlobStore(new File(getFilesDir(), "data").toPath()),
                    PathResolvers.posix(), identity, deviceId, accepted, config.port(),
                    new File(getCacheDir(), "transfer").toPath());
            for (AndroidSyncConfig.Peer peer : config.peers()) {
                try {
                    node.addPeer(peerNodeId(peer), peer.host(), peer.port(), peer.fingerprint());
                } catch (Exception e) {
                    Log.w(TAG, "peer unavailable: " + peer.host() + ":" + peer.port(), e);
                }
            }

            watcher = new AndroidDirectoryWatcher(root.toFile(), this::onFilesChanged);
            watcher.start();
            int interval = config.intervalSeconds();
            if (interval > 0) {
                scheduler.scheduleWithFixedDelay(this::sync, interval, interval, TimeUnit.SECONDS);
            }
            updateNotification("已启动");
            sync();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            updateNotification("启动失败: " + message(e));
            closeNode();
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

    private static NodeId peerNodeId(AndroidSyncConfig.Peer peer) {
        String fingerprint = peer.fingerprint();
        return NodeId.of("peer-" + fingerprint.substring(0, Math.min(8, fingerprint.length())));
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
        if (node != null) {
            node.close();
            node = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
    }

    @Override
    public void onDestroy() {
        started = false;
        closeNode();
        scheduler.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }
}
