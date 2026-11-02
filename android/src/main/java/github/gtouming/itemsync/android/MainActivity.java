package github.gtouming.itemsync.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import github.gtouming.itemsync.domain.security.LocalPassword;

import java.util.List;

public final class MainActivity extends Activity {

    private AndroidSyncConfig config;
    private EditText rootField;
    private EditText groupField;
    private EditText peersField;
    private EditText portField;
    private EditText intervalField;
    private TextView fingerprintLabel;

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
        layout.addView(hint("同步根默认为应用专属目录。本机密码只锁界面；身份由 NUK 静默解锁。"));
        rootField = field(layout, "同步根", config.rootPath(), false);
        groupField = field(layout, "组 ID", config.groupId(), false);
        peersField = field(layout, "邻居（host:port:指纹，多个用逗号）",
                join(config.peers()), false);
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
                    peersField.getText().toString(),
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

    private static String join(List<AndroidSyncConfig.Peer> peers) {
        StringBuilder out = new StringBuilder();
        for (AndroidSyncConfig.Peer peer : peers) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(peer.host()).append(':').append(peer.port())
                    .append(':').append(peer.fingerprint());
        }
        return out.toString();
    }
}
