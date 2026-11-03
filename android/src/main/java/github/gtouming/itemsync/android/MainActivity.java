package github.gtouming.itemsync.android;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import github.gtouming.itemsync.domain.security.LocalPassword;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.sync.trust.RemoteTrust;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {

    private AndroidSyncConfig config;
    private EditText rootField;
    private EditText groupField;
    private EditText portField;
    private EditText intervalField;
    private TextView fingerprintLabel;

    private SyncForegroundService boundService;
    private boolean bound;
    private DeviceDialog deviceDialog;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
            boundService = ((SyncForegroundService.LocalBinder) binder).service();
            if (!boundService.isReady()) {
                Toast.makeText(MainActivity.this, "同步服务尚未就绪", Toast.LENGTH_SHORT).show();
                return;
            }
            openDeviceDialog();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            boundService = null;
            if (deviceDialog != null) {
                deviceDialog.dismiss();
                deviceDialog = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = AndroidSyncConfig.of(this);
        if (config.hasLocalPassword()) {
            showUnlock();
        } else {
            showSetup();
        }
    }

    @Override
    protected void onDestroy() {
        if (deviceDialog != null) {
            deviceDialog.dismiss();
            deviceDialog = null;
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        boundService = null;
        super.onDestroy();
    }

    private void showSetup() {
        LinearLayout layout = vertical();
        layout.addView(hint("首次启动：设置本机密码。它只锁定界面与配置，后台同步不需要它；"
                + "忘记只能清除应用数据。"));
        EditText password = passwordField(layout, "本机密码");
        EditText confirm = passwordField(layout, "确认密码");
        TextView error = hint("");
        layout.addView(error);
        Button create = new Button(this);
        create.setText("设置并继续");
        create.setOnClickListener(view -> {
            String first = password.getText().toString();
            if (first.isEmpty()) {
                error.setText("密码不能为空");
                return;
            }
            if (!first.equals(confirm.getText().toString())) {
                error.setText("两次输入不一致");
                return;
            }
            create.setEnabled(false);
            new Thread(() -> {
                try {
                    String hash = LocalPassword.hash(first.toCharArray());
                    runOnUiThread(() -> {
                        config.setLocalPasswordHash(hash);
                        password.setText("");
                        confirm.setText("");
                        showConfig();
                    });
                } catch (RuntimeException e) {
                    runOnUiThread(() -> {
                        create.setEnabled(true);
                        error.setText("设置失败: " + message(e));
                    });
                }
            }).start();
        });
        layout.addView(create);
        setContentView(scroll(layout));
    }

    private void showUnlock() {
        LinearLayout layout = vertical();
        layout.addView(hint("输入本机密码以查看同步配置。后台同步已在独立服务中运行。"));
        EditText password = passwordField(layout, "本机密码");
        TextView error = hint("");
        layout.addView(error);
        Button unlock = new Button(this);
        unlock.setText("解锁");
        unlock.setOnClickListener(view -> {
            String value = password.getText().toString();
            if (value.isEmpty()) {
                error.setText("密码不能为空");
                return;
            }
            unlock.setEnabled(false);
            new Thread(() -> {
                boolean ok = config.verifyLocalPassword(value.toCharArray());
                runOnUiThread(() -> {
                    unlock.setEnabled(true);
                    if (ok) {
                        password.setText("");
                        showConfig();
                    } else {
                        error.setText("密码不正确");
                    }
                });
            }).start();
        });
        layout.addView(unlock);
        setContentView(scroll(layout));
    }

    private void showConfig() {
        LinearLayout layout = vertical();
        layout.addView(hint("同步根默认为应用专属目录。本机密码只锁界面；身份由 NUK 静默解锁。\n"
                + "同组设备会在“局域网设备”中显示，开启信任后建立连接。"));
        rootField = field(layout, "同步根", config.rootPath(), false);
        groupField = field(layout, "组 ID", config.groupId(), false);
        portField = field(layout, "端口", String.valueOf(config.port()), true);
        intervalField = field(layout, "定时同步秒数（0 关闭）",
                String.valueOf(config.intervalSeconds()), true);

        fingerprintLabel = hint(config.fingerprint().isEmpty()
                ? "本机指纹: 启动服务后生成"
                : "本机指纹: " + config.fingerprint());
        fingerprintLabel.setTextIsSelectable(true);
        layout.addView(fingerprintLabel);

        Button refresh = new Button(this);
        refresh.setText("刷新本机指纹");
        refresh.setOnClickListener(view -> fingerprintLabel.setText(
                "本机指纹: " + AndroidSyncConfig.of(this).fingerprint()));
        layout.addView(refresh);

        Button start = new Button(this);
        start.setText("保存并启动");
        start.setOnClickListener(view -> startSync());
        layout.addView(start);

        Button devices = new Button(this);
        devices.setText("局域网设备");
        devices.setOnClickListener(view -> showDevices());
        layout.addView(devices);

        Button stop = new Button(this);
        stop.setText("停止同步");
        stop.setOnClickListener(view -> {
            stopService(new Intent(this, SyncForegroundService.class));
            Toast.makeText(this, "已停止同步服务", Toast.LENGTH_SHORT).show();
        });
        layout.addView(stop);

        Button lock = new Button(this);
        lock.setText("锁定界面");
        lock.setOnClickListener(view -> showUnlock());
        layout.addView(lock);

        setContentView(scroll(layout));
    }

    private void startSync() {
        try {
            int port = Integer.parseInt(portField.getText().toString().trim());
            int interval = Integer.parseInt(intervalField.getText().toString().trim());
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口必须在 1-65535 之间");
            }
            if (interval < 0) {
                throw new IllegalArgumentException("定时秒数不能为负");
            }
            config.save(
                    rootField.getText().toString(),
                    groupField.getText().toString(),
                    port,
                    interval);
            if (config.identitySecret().length == 0) {
                Toast.makeText(this, "无法初始化身份解锁密钥", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            startForegroundService(new Intent(this, SyncForegroundService.class));
            Toast.makeText(this, "正在启动同步服务", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + message(e), Toast.LENGTH_LONG).show();
        }
    }

    private void showDevices() {
        if (boundService != null && boundService.isReady()) {
            openDeviceDialog();
            return;
        }
        if (bound) {
            Toast.makeText(this, "同步服务尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SyncForegroundService.class);
        if (!bindService(intent, connection, 0)) {
            Toast.makeText(this, "请先启动同步服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDeviceDialog() {
        SyncForegroundService service = boundService;
        if (service == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<PeerTrustCoordinator.PeerView> views = service.peerViews();
                runOnUiThread(() -> {
                    if (deviceDialog != null) {
                        deviceDialog.dismiss();
                    }
                    deviceDialog = new DeviceDialog(views);
                    deviceDialog.show();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "读取在线设备失败: " + message(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void callService(String failure, ServiceAction action) {
        new Thread(() -> {
            try {
                action.run();
                SyncForegroundService service = boundService;
                if (service != null) {
                    service.requestSync();
                }
                runOnUiThread(() -> {
                    if (deviceDialog != null) {
                        deviceDialog.reload();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        failure + ": " + message(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String trustState(PeerTrustCoordinator.PeerView view) {
        if (!view.locallyTrusted()) {
            return switch (view.remoteTrust()) {
                case TRUSTED -> "对方已信任你，等待本机确认";
                case UNTRUSTED -> "未信任";
                case UNKNOWN -> "未信任（无法确认对方状态）";
            };
        }
        if (view.remoteTrust() == RemoteTrust.TRUSTED && view.connected()) {
            return "已信任（双方，已连接）";
        }
        return switch (view.remoteTrust()) {
            case TRUSTED -> "已信任（双方）";
            case UNTRUSTED -> "已信任（等待对方确认）";
            case UNKNOWN -> "已信任（无法确认对方状态）";
        };
    }

    private static String deviceLabel(PeerTrustCoordinator.PeerView view) {
        StringBuilder addresses = new StringBuilder();
        for (InetAddress address : view.presence().peer().addresses()) {
            if (addresses.length() > 0) {
                addresses.append(", ");
            }
            addresses.append(address.getHostAddress());
        }
        return view.presence().peer().deviceId().value()
                + "\n地址: " + (addresses.length() == 0 ? "未知" : addresses)
                + "  端口: " + view.presence().peer().port()
                + "\n指纹: " + view.presence().peer().fingerprint()
                + "\n最近在线: " + view.presence().lastSeen()
                + "\n状态: " + trustState(view);
    }

    private final class DeviceDialog {

        private final Dialog dialog;
        private final ListView list;
        private final TextView emptyLabel;
        private final ArrayAdapter<PeerTrustCoordinator.PeerView> adapter;
        private final Button pairButton;
        private final Button revokeButton;

        private DeviceDialog(List<PeerTrustCoordinator.PeerView> views) {
            dialog = new Dialog(MainActivity.this);
            dialog.setTitle("局域网设备");

            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            content.setPadding(padding, padding, padding, padding);

            emptyLabel = new TextView(MainActivity.this);
            emptyLabel.setText("同组内暂无在线设备");
            content.addView(emptyLabel);

            adapter = new ArrayAdapter<PeerTrustCoordinator.PeerView>(
                    MainActivity.this, android.R.layout.simple_list_item_1,
                    new ArrayList<>()) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView text = (TextView) super.getView(position, convertView, parent);
                    text.setText(deviceLabel(getItem(position)));
                    text.setTextSize(12);
                    return text;
                }
            };
            list = new ListView(MainActivity.this);
            list.setAdapter(adapter);
            list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener((parent, view, position, id) -> updateButtons());
            content.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

            pairButton = new Button(MainActivity.this);
            pairButton.setText("开启信任");
            pairButton.setOnClickListener(view -> pairSelected());
            content.addView(pairButton);

            revokeButton = new Button(MainActivity.this);
            revokeButton.setText("撤销信任");
            revokeButton.setOnClickListener(view -> revokeSelected());
            content.addView(revokeButton);

            Button refresh = new Button(MainActivity.this);
            refresh.setText("刷新");
            refresh.setOnClickListener(view -> reload());
            content.addView(refresh);

            Button close = new Button(MainActivity.this);
            close.setText("关闭");
            close.setOnClickListener(view -> dialog.dismiss());
            content.addView(close);

            dialog.setContentView(content);
            render(views);
        }

        private void show() {
            dialog.show();
        }

        private void dismiss() {
            dialog.dismiss();
        }

        private void reload() {
            SyncForegroundService service = boundService;
            if (service == null) {
                Toast.makeText(MainActivity.this, "同步服务已断开", Toast.LENGTH_SHORT).show();
                return;
            }
            pairButton.setEnabled(false);
            revokeButton.setEnabled(false);
            new Thread(() -> {
                try {
                    List<PeerTrustCoordinator.PeerView> views = service.peerViews();
                    runOnUiThread(() -> render(views));
                } catch (RuntimeException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "刷新在线设备失败: " + message(e), Toast.LENGTH_LONG).show();
                        updateButtons();
                    });
                }
            }).start();
        }

        private void render(List<PeerTrustCoordinator.PeerView> views) {
            adapter.clear();
            adapter.addAll(views);
            adapter.notifyDataSetChanged();
            emptyLabel.setVisibility(views.isEmpty() ? View.VISIBLE : View.GONE);
            if (!views.isEmpty()) {
                list.setItemChecked(0, true);
            }
            updateButtons();
        }

        private PeerTrustCoordinator.PeerView selected() {
            int position = list.getCheckedItemPosition();
            if (position == ListView.INVALID_POSITION || position >= adapter.getCount()) {
                return null;
            }
            return adapter.getItem(position);
        }

        private void updateButtons() {
            PeerTrustCoordinator.PeerView view = selected();
            pairButton.setEnabled(view != null && !view.locallyTrusted()
                    && view.identityVerified());
            revokeButton.setEnabled(view != null && view.locallyTrusted());
        }

        private void pairSelected() {
            PeerTrustCoordinator.PeerView view = selected();
            if (view == null || !view.identityVerified()) {
                return;
            }
            callService("信任失败", () -> {
                SyncForegroundService service = boundService;
                if (service == null) {
                    throw new IllegalStateException("同步服务已断开");
                }
                service.trustPeer(view.presence().peer());
            });
        }

        private void revokeSelected() {
            PeerTrustCoordinator.PeerView view = selected();
            if (view == null) {
                return;
            }
            callService("撤销信任失败", () -> {
                SyncForegroundService service = boundService;
                if (service == null) {
                    throw new IllegalStateException("同步服务已断开");
                }
                service.untrustPeer(view.presence().peer());
            });
        }
    }

    @FunctionalInterface
    private interface ServiceAction {
        void run() throws Exception;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    private ScrollView scroll(LinearLayout layout) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        return scroll;
    }

    private EditText passwordField(LinearLayout layout, String label) {
        layout.addView(hint(label));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        layout.addView(field, params);
        return field;
    }

    private EditText field(LinearLayout layout, String label, String value, boolean numeric) {
        layout.addView(hint(label));
        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        if (numeric) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        layout.addView(field, params);
        return field;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
