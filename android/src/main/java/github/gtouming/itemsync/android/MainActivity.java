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

import java.util.List;

public final class MainActivity extends Activity {

    private EditText rootField;
    private EditText groupField;
    private EditText peersField;
    private EditText portField;
    private EditText intervalField;
    private EditText passwordField;
    private TextView fingerprintLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidSyncConfig config = AndroidSyncConfig.of(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);

        layout.addView(hint("同步根默认为应用专属目录。密钥库口令使用 Android Keystore 加密保存。"));
        rootField = field(layout, "同步根", config.rootPath(), false);
        groupField = field(layout, "组 ID", config.groupId(), false);
        peersField = field(layout, "邻居（host:port:指纹，多个用逗号）",
                join(config.peers()), false);
        portField = field(layout, "端口", String.valueOf(config.port()), true);
        intervalField = field(layout, "定时同步秒数（0 关闭）",
                String.valueOf(config.intervalSeconds()), true);
        passwordField = field(layout, "密钥库口令（留空保留现有）", "", true);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

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

        setContentView(scroll);
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
            AndroidSyncConfig.of(this).save(
                    rootField.getText().toString(),
                    groupField.getText().toString(),
                    passwordField.getText().toString(),
                    peersField.getText().toString(),
                    port,
                    interval);
            AndroidSyncConfig saved = AndroidSyncConfig.of(this);
            if (!saved.isConfigured()) {
                Toast.makeText(this, "请填写密钥库口令", Toast.LENGTH_LONG).show();
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
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(this, "启动失败: " + message, Toast.LENGTH_LONG).show();
        }
    }

    private EditText field(LinearLayout layout, String label, String value, boolean numeric) {
        TextView caption = new TextView(this);
        caption.setText(label);
        layout.addView(caption);

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
