package github.gtouming.itemsync.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.security.LocalPassword;
import github.gtouming.itemsync.store.metadata.GroupDataReset;
import github.gtouming.itemsync.sync.trigger.AlarmSchedule;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.sync.trust.RemoteTrust;
import github.gtouming.itemsync.sync.trust.RemoteTrustFailure;

import java.io.File;
import java.net.InetAddress;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {

    private AndroidSyncConfig config;
    private EditText portField;
    private EditText intervalField;
    private TextView fingerprintLabel;
    private TextView groupIdLabel;
    private TextView groupNameLabel;
    private TextView groupRootLabel;
    private TextView serviceStateLabel;
    private View serviceStateDot;
    private final List<AndroidGroup> groups = new ArrayList<>();
    private final List<TextView> groupChips = new ArrayList<>();
    private GroupId selectedGroupId;

    private SyncForegroundService boundService;
    private boolean bound;
    private DeviceDialog deviceDialog;
    private RuleDialog ruleDialog;
    private boolean pendingRules;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
            boundService = ((SyncForegroundService.LocalBinder) binder).service();
            renderServiceState();
            if (!boundService.isReady()) {
                pendingRules = false;
                Toast.makeText(MainActivity.this, "同步服务尚未就绪", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pendingRules) {
                pendingRules = false;
                loadRules();
            } else {
                openDeviceDialog();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            boundService = null;
            pendingRules = false;
            renderServiceState();
            if (deviceDialog != null) {
                deviceDialog.dismiss();
                deviceDialog = null;
            }
            if (ruleDialog != null) {
                ruleDialog.dismiss();
                ruleDialog = null;
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
    protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        configureSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderServiceState();
    }

    @Override
    protected void onDestroy() {
        if (deviceDialog != null) {
            deviceDialog.dismiss();
            deviceDialog = null;
        }
        if (ruleDialog != null) {
            ruleDialog.dismiss();
            ruleDialog = null;
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        boundService = null;
        super.onDestroy();
    }

    private void showSetup() {
        LinearLayout layout = screen();
        layout.addView(brandHeader(false));
        layout.addView(spacer());
        LinearLayout card = card();
        card.addView(eyebrow("首次启动"));
        card.addView(title("设置本机密码"));
        card.addView(hint("本机密码只锁定界面与配置，后台同步不需要它。忘记后只能清除应用数据。"));
        EditText password = passwordField(card, "本机密码");
        EditText confirm = passwordField(card, "确认密码");
        TextView error = errorLabel();
        error.setVisibility(View.GONE);
        card.addView(error);
        Button create = primaryButton("设置并继续");
        create.setOnClickListener(view -> {
            String first = password.getText().toString();
            if (first.isEmpty()) {
                setError(error, "密码不能为空");
                return;
            }
            if (!first.equals(confirm.getText().toString())) {
                setError(error, "两次输入不一致");
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
                        setError(error, "设置失败: " + message(e));
                    });
                }
            }).start();
        });
        card.addView(create);
        layout.addView(card);
        layout.addView(spacer());
        layout.addView(footerNote());
        showScreen(layout);
    }

    private void showUnlock() {
        LinearLayout layout = screen();
        layout.addView(brandHeader(false));
        layout.addView(spacer());
        LinearLayout card = card();
        card.addView(eyebrow("已锁定"));
        card.addView(title("输入本机密码"));
        card.addView(hint("后台同步不受影响。解锁后才能查看同步组、同步根与设备信任。"));
        EditText password = passwordField(card, "本机密码");
        TextView error = errorLabel();
        error.setVisibility(View.GONE);
        card.addView(error);
        Button unlock = primaryButton("解锁");
        unlock.setOnClickListener(view -> {
            String value = password.getText().toString();
            if (value.isEmpty()) {
                setError(error, "密码不能为空");
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
                        setError(error, "密码不正确");
                    }
                });
            }).start();
        });
        card.addView(unlock);
        layout.addView(card);
        layout.addView(spacer());
        layout.addView(footerNote());
        showScreen(layout);
    }

    private void showConfig() {
        LinearLayout layout = screen();
        layout.addView(brandHeader(true));
        groupChips.clear();
        try {
            groups.clear();
            groups.addAll(config.groups());
        } catch (RuntimeException e) {
            groups.clear();
            Toast.makeText(this, "读取同步组失败: " + message(e), Toast.LENGTH_LONG).show();
        }
        if (selectedGroupId == null
                || groups.stream().noneMatch(group -> group.id().equals(selectedGroupId))) {
            selectedGroupId = groups.isEmpty() ? null : groups.get(0).id();
        }

        if (groups.isEmpty()) {
            layout.addView(emptyGroupsCard());
        } else {
            layout.addView(sectionLabel("同步组"));
            layout.addView(groupChipStrip());
            View status = statusCard();
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.topMargin = dp(8);
            layout.addView(status, statusParams);
            layout.addView(sectionLabel("操作"));
            layout.addView(actionCard());
        }

        layout.addView(sectionLabel("连接设置"));
        layout.addView(settingsCard());
        layout.addView(sectionLabel("本机身份"));
        layout.addView(identityCard());
        layout.addView(footerNote());

        showScreen(layout);
        renderSelectedGroup();
        renderServiceState();
    }

    private View brandHeader(boolean withLock) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = horizontal();
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("ItemSync", 20, Typeface.BOLD, R.color.itemsync_ink);
        TextView subtitle = text("局域网双向同步", 12, Typeface.NORMAL, R.color.itemsync_muted);
        brand.addView(title);
        brand.addView(subtitle);
        row.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (withLock) {
            TextView lock = textButton("锁定", R.color.itemsync_copper_text);
            lock.setOnClickListener(view -> showUnlock());
            row.addView(lock);
        }
        header.addView(row);
        header.addView(divider());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        header.setLayoutParams(params);
        return header;
    }

    private View groupChipStrip() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (AndroidGroup group : groups) {
            TextView chip = new TextView(this);
            chip.setText(groupChipText(group));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setTextColor(getColorStateList(R.color.chip_text));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(44));
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setSelected(group.id().equals(selectedGroupId));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            row.addView(chip, params);
            chip.setOnClickListener(view -> selectGroup(group.id()));
            groupChips.add(chip);
        }
        scroll.addView(row);
        return scroll;
    }

    private View statusCard() {
        LinearLayout card = card();
        card.addView(eyebrow("当前同步组"));
        groupNameLabel = text("", 24, Typeface.BOLD, R.color.itemsync_ink);
        groupNameLabel.setLineSpacing(dp(2), 1f);
        card.addView(groupNameLabel);

        LinearLayout state = horizontal();
        serviceStateDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(8);
        serviceStateDot.setLayoutParams(dotParams);
        serviceStateDot.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        serviceStateDot.setBackground(circle(getColor(R.color.itemsync_idle)));
        serviceStateLabel = text("", 14, Typeface.NORMAL, R.color.itemsync_muted);
        state.addView(serviceStateDot);
        state.addView(serviceStateLabel);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(4);
        card.addView(state, stateParams);

        card.addView(fieldLabel("组 ID"));
        groupIdLabel = monoValue();
        card.addView(groupIdLabel);

        card.addView(fieldLabel("同步根"));
        groupRootLabel = text("", 14, Typeface.NORMAL, R.color.itemsync_ink);
        groupRootLabel.setTextIsSelectable(true);
        groupRootLabel.setLineSpacing(dp(2), 1f);
        card.addView(groupRootLabel);

        Button syncNow = primaryButton("立即同步");
        syncNow.setOnClickListener(view -> requestSync());
        addWithTop(card, syncNow, 16);
        return card;
    }

    private View actionCard() {
        LinearLayout card = cardNoPadding();
        addActionRow(card, "局域网设备", "在线状态、地址与信任开关", this::showDevices, false);
        addActionRow(card, "同步规则", "闹钟、目录变动与启停", this::showRules, false);
        addActionRow(card, "组设置", "改名、更换同步根、停用或删除",
                this::showGroupSettings, false);
        addActionRow(card, "添加同步组", "创建新组或加入已有组", this::showAddGroup, true);
        return card;
    }

    private void addActionRow(LinearLayout card, String titleText, String subtitleText,
                              Runnable action, boolean last) {
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.bg_row);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleText, 15, Typeface.BOLD, R.color.itemsync_ink);
        TextView subtitle = text(subtitleText, 14, Typeface.NORMAL, R.color.itemsync_muted);
        subtitle.setLineSpacing(dp(2), 1f);
        textBlock.addView(title);
        textBlock.addView(subtitle);
        row.addView(textBlock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text("›", 22, Typeface.NORMAL, R.color.itemsync_muted);
        chevron.setPadding(dp(8), 0, 0, 0);
        row.addView(chevron);
        row.setOnClickListener(view -> action.run());
        card.addView(row);
        if (!last) {
            card.addView(insetDivider());
        }
    }

    private View settingsCard() {
        LinearLayout card = card();
        portField = field(card, "端口（所有组共用）", String.valueOf(config.port()), true);
        intervalField = field(card, "定时同步秒数（0 关闭）",
                String.valueOf(config.intervalSeconds()), true);
        Button start = secondaryButton("保存并启动");
        start.setOnClickListener(view -> {
            startSync();
            refreshServiceStateSoon();
        });
        card.addView(start);
        Button stop = dangerButton("停止同步");
        stop.setOnClickListener(view -> {
            boolean wasRunning = SyncForegroundService.isRunning();
            stopService(new Intent(this, SyncForegroundService.class));
            Toast.makeText(this, wasRunning ? "已停止同步服务" : "同步服务未运行",
                    Toast.LENGTH_SHORT).show();
            refreshServiceStateSoon();
        });
        addWithTop(card, stop, 8);
        card.addView(hint("保存后按当前配置启动前台同步服务；停止不会删除配置。"));
        return card;
    }

    private View identityCard() {
        LinearLayout card = card();
        card.addView(hint("证书指纹用于在设备列表中核对身份，不是组 ID。"));
        fingerprintLabel = monoValue();
        setFingerprintText();
        card.addView(fingerprintLabel);
        Button refresh = secondaryButton("刷新本机指纹");
        refresh.setOnClickListener(view -> setFingerprintText());
        addWithTop(card, refresh, 12);
        return card;
    }

    private View emptyGroupsCard() {
        LinearLayout card = card();
        card.addView(eyebrow("同步组"));
        card.addView(title("还没有同步组"));
        card.addView(hint("添加一个组后，这里会显示当前组状态、同步根和操作入口。"));
        Button add = primaryButton("添加同步组");
        add.setOnClickListener(view -> showAddGroup());
        card.addView(add);
        return card;
    }

    private void setFingerprintText() {
        String value = AndroidSyncConfig.of(this).fingerprint();
        fingerprintLabel.setText(value.isEmpty() ? "启动服务后生成" : value);
    }

    private void requestSync() {
        AndroidGroup selected = selectedGroup();
        if (selected == null) {
            Toast.makeText(this, "请先添加同步组", Toast.LENGTH_SHORT).show();
        } else if (!selected.enabled()) {
            Toast.makeText(this, "该同步组已停用", Toast.LENGTH_SHORT).show();
        } else if (boundService != null && boundService.isReady()) {
            boundService.requestSync(selected.id());
            Toast.makeText(this, "已请求同步", Toast.LENGTH_SHORT).show();
        } else if (SyncForegroundService.requestSyncIfReady(selected.id())) {
            Toast.makeText(this, "已请求同步", Toast.LENGTH_SHORT).show();
        } else if (SyncForegroundService.isRunning()) {
            Toast.makeText(this, "同步服务尚未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "请先保存并启动同步服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void selectGroup(GroupId id) {
        selectedGroupId = id;
        for (int index = 0; index < groupChips.size() && index < groups.size(); index++) {
            groupChips.get(index).setSelected(groups.get(index).id().equals(id));
        }
        renderSelectedGroup();
        renderServiceState();
    }

    private void renderServiceState() {
        if (serviceStateLabel == null || serviceStateDot == null) {
            return;
        }
        AndroidGroup group = selectedGroup();
        String text;
        int color;
        if (group == null) {
            text = "未配置同步组";
            color = R.color.itemsync_idle;
        } else if (!group.enabled()) {
            text = "该组已停用";
            color = R.color.itemsync_idle;
        } else if (SyncForegroundService.isRunningAndReady()) {
            text = "同步服务运行中";
            color = R.color.itemsync_live;
        } else if (SyncForegroundService.isRunning()) {
            text = "同步服务正在启动";
            color = R.color.itemsync_copper;
        } else {
            text = "同步服务未启动";
            color = R.color.itemsync_idle;
        }
        serviceStateLabel.setText(text);
        serviceStateDot.setBackground(circle(getColor(color)));
    }

    private void refreshServiceStateSoon() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(this::renderServiceState, 500);
        handler.postDelayed(this::renderServiceState, 1500);
        handler.postDelayed(this::renderServiceState, 4000);
    }

    private String groupChipText(AndroidGroup group) {
        String name = group.name() == null || group.name().isBlank()
                ? group.id().value() : group.name();
        int duplicates = 0;
        for (AndroidGroup other : groups) {
            String otherName = other.name() == null || other.name().isBlank()
                    ? other.id().value() : other.name();
            if (otherName.equals(name)) {
                duplicates++;
            }
        }
        String text = duplicates > 1 ? name + " · " + group.id().value() : name;
        return group.enabled() ? text : text + "（停用）";
    }

    private LinearLayout screen() {
        LinearLayout layout = vertical();
        layout.setBackgroundColor(getColor(R.color.itemsync_paper));
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View spacer() {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = cardNoPadding();
        int padding = dp(16);
        card.setPadding(padding, padding, padding, padding);
        return card;
    }

    private LinearLayout cardNoPadding() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        return card;
    }

    private TextView eyebrow(String value) {
        TextView view = text(value, 11, Typeface.BOLD, R.color.itemsync_muted);
        view.setLetterSpacing(0.04f);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView title(String value) {
        TextView view = text(value, 22, Typeface.BOLD, R.color.itemsync_ink);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 12, Typeface.BOLD, R.color.itemsync_muted);
        view.setLetterSpacing(0.04f);
        view.setPadding(0, dp(20), 0, dp(8));
        return view;
    }

    private TextView fieldLabel(String value) {
        TextView view = text(value, 12, Typeface.BOLD, R.color.itemsync_muted);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private TextView monoValue() {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(getColor(R.color.itemsync_ink));
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private TextView footerNote() {
        TextView view = text("局域网直连 · 无中心服务器", 11, Typeface.NORMAL,
                R.color.itemsync_muted);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(24), 0, dp(28));
        return view;
    }

    private Button primaryButton(String value) {
        Button button = button(value);
        button.setTextColor(getColor(R.color.itemsync_surface));
        button.setBackground(buttonBackground(getColor(R.color.itemsync_ink),
                Color.parseColor("#2A343A"), 0));
        button.setMinHeight(dp(52));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = button(value);
        button.setTextColor(getColor(R.color.itemsync_ink));
        button.setBackground(buttonBackground(Color.TRANSPARENT,
                Color.parseColor("#EDE7DB"), getColor(R.color.itemsync_hairline)));
        return button;
    }

    private Button dangerButton(String value) {
        Button button = button(value);
        button.setTextColor(getColor(R.color.itemsync_error));
        button.setBackground(buttonBackground(Color.TRANSPARENT,
                Color.parseColor("#F7E3E0"), 0));
        return button;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private TextView textButton(String value, int color) {
        TextView button = text(value, 14, Typeface.BOLD, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        button.setLayoutParams(params);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackgroundResource(R.drawable.bg_button_ghost);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, int sizeSp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setTextColor(getColor(color));
        return view;
    }

    private void addWithTop(LinearLayout container, View child, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        container.addView(child, params);
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(getColor(R.color.itemsync_hairline));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return view;
    }

    private View insetDivider() {
        View view = divider();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        view.setLayoutParams(params);
        return view;
    }

    private Drawable circle(int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        return drawable;
    }

    private Drawable buttonBackground(int normal, int pressed, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, stroke));
        states.addState(new int[]{}, shape(normal, stroke));
        return states;
    }

    private Drawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(4));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private void showScreen(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.itemsync_paper));
        scroll.addView(content);
        setContentView(scroll);
        applySystemBarInsets(scroll);
    }

    private void applySystemBarInsets(View root) {
        // Android 15+ enforces edge-to-edge; older versions still reserve the system bars.
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            WindowInsetsController controller =
                    getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
        } else {
            getWindow().setStatusBarColor(getColor(R.color.itemsync_paper));
            getWindow().setNavigationBarColor(getColor(R.color.itemsync_paper));
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void renderSelectedGroup() {
        AndroidGroup group = selectedGroup();
        if (groupIdLabel == null || groupNameLabel == null || groupRootLabel == null) {
            return;
        }
        if (group == null) {
            groupIdLabel.setText("—");
            groupNameLabel.setText("—");
            groupRootLabel.setText("—");
            return;
        }
        groupIdLabel.setText(group.id().value());
        groupNameLabel.setText(group.name());
        groupRootLabel.setText(group.rootPath());
    }

    private void startSync() {
        try {
            if (!config.hasEnabledGroups()) {
                Toast.makeText(this, "请先启用至少一个同步组", Toast.LENGTH_SHORT).show();
                return;
            }
            int port = Integer.parseInt(portField.getText().toString().trim());
            int interval = Integer.parseInt(intervalField.getText().toString().trim());
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口必须在 1-65535 之间");
            }
            if (interval < 0) {
                throw new IllegalArgumentException("定时秒数不能为负");
            }
            config.saveGroups(new ArrayList<>(config.groups()), port, interval);
            if (config.identitySecret().length == 0) {
                Toast.makeText(this, "无法初始化身份解锁密钥", Toast.LENGTH_LONG).show();
                return;
            }
            if (SyncForegroundService.isRunning()) {
                restartServiceIfRunning();
                Toast.makeText(this, "配置已保存，正在重启同步服务", Toast.LENGTH_SHORT).show();
                return;
            }
            requestNotificationPermission();
            startForegroundService(new Intent(this, SyncForegroundService.class));
            Toast.makeText(this, "正在启动同步服务", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + message(e), Toast.LENGTH_LONG).show();
        }
    }

    private void showAddGroup() {
        if (config.groups().size() >= AndroidSyncConfig.MAX_GROUPS) {
            Toast.makeText(this, "最多只能有 " + AndroidSyncConfig.MAX_GROUPS + " 个同步组",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = vertical();
        EditText name = field(content, "组名（留空则使用组 ID）", "", false);
        EditText id = field(content, "组 ID（加入已有组时填写对方的组 ID）",
                GroupId.random().value(), false);
        EditText root = field(content, "本机同步根", defaultRoot(), false);
        root.setSingleLine(false);
        root.setMaxLines(3);
        root.setHorizontallyScrolling(false);
        TextView error = errorLabel();
        content.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加同步组")
                .setView(content)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        File rootDir = new File(root.getText().toString().trim());
                        if (rootDir.exists() && !rootDir.isDirectory()) {
                            throw new IllegalArgumentException("同步根不是目录: " + rootDir);
                        }
                        if (!rootDir.exists() && !rootDir.mkdirs()) {
                            throw new IllegalArgumentException("无法创建同步根: " + rootDir);
                        }
                        AndroidGroup group = new AndroidGroup(
                                GroupId.parse(id.getText().toString().trim()),
                                name.getText().toString(), rootDir.getAbsolutePath());
                        config.addGroup(group);
                        selectedGroupId = group.id();
                        dialog.dismiss();
                        showConfig();
                        restartServiceIfRunning();
                    } catch (Exception e) {
                        error.setText("添加失败: " + message(e));
                    }
                }));
        dialog.show();
    }

    private void showGroupSettings() {
        AndroidGroup group = selectedGroup();
        if (group == null) {
            Toast.makeText(this, "请先添加同步组", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = vertical();
        TextView id = hint("组 ID: " + group.id().value());
        id.setTextIsSelectable(true);
        content.addView(id);
        TextView root = hint("同步根: " + group.rootPath());
        root.setTextIsSelectable(true);
        content.addView(root);
        EditText name = field(content, "组名（只在本机显示）", group.name(), false);
        AlertDialog[] settingsDialog = new AlertDialog[1];
        Button toggleEnabled = new Button(this);
        toggleEnabled.setText(group.enabled() ? "停用此组" : "启用此组");
        toggleEnabled.setOnClickListener(view -> {
            try {
                config.setGroupEnabled(group.id(), !group.enabled());
                settingsDialog[0].dismiss();
                showConfig();
                restartServiceIfRunning();
            } catch (Exception e) {
                Toast.makeText(this, "切换失败: " + message(e),
                        Toast.LENGTH_LONG).show();
            }
        });
        content.addView(toggleEnabled);
        Button changeRoot = new Button(this);
        changeRoot.setText("更换同步根");
        changeRoot.setOnClickListener(view -> showChangeRootDialog(group, settingsDialog[0]));
        content.addView(changeRoot);
        content.addView(hint("组 ID 添加后不可修改。更换同步根会清空该组的版本、冲突、"
                + "同步游标与运行记录，保留组 ID、信任、规则和设备列表；"
                + "旧目录中的文件不会被删除。删除组会保留同步根中的文件。"));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("组设置")
                .setView(content)
                .setPositiveButton("保存名称", null)
                .setNeutralButton("删除此组", null)
                .setNegativeButton("关闭", null)
                .create();
        settingsDialog[0] = dialog;
        dialog.setOnShowListener(event -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    config.renameGroup(group.id(), name.getText().toString());
                    dialog.dismiss();
                    showConfig();
                    restartServiceIfRunning();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败: " + message(e), Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    confirmDeleteGroup(group, dialog));
        });
        dialog.show();
    }

    private void showChangeRootDialog(AndroidGroup group, AlertDialog settings) {
        LinearLayout content = vertical();
        EditText root = field(content, "新同步根", group.rootPath(), false);
        root.setSingleLine(false);
        root.setMaxLines(3);
        root.setHorizontallyScrolling(false);
        TextView error = errorLabel();
        content.addView(error);
        content.addView(hint("更换同步根会停止该组同步，并清空该组的版本、冲突、"
                + "同步游标与运行记录；组 ID、信任、规则和设备列表保留。"
                + "旧目录中的文件不会被删除。"));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("更换同步根：" + groupText(group))
                .setView(content)
                .setPositiveButton("更换", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        File newRoot = new File(root.getText().toString().trim());
                        if (newRoot.exists() && !newRoot.isDirectory()) {
                            throw new IllegalArgumentException("同步根不是目录: " + newRoot);
                        }
                        if (newRoot.getAbsolutePath()
                                .equals(new File(group.rootPath()).getAbsolutePath())) {
                            throw new IllegalArgumentException("同步根没有变化");
                        }
                        ensureRootOutsideOtherGroups(group.id(), newRoot);
                        if (!newRoot.exists() && !newRoot.mkdirs()) {
                            throw new IllegalArgumentException("无法创建同步根: " + newRoot);
                        }
                        stopSyncServiceForMaintenance();
                        try (AndroidSqliteMetadataStore store =
                                     new AndroidSqliteMetadataStore(this, "itemsync.sqlite")) {
                            GroupDataReset.reset(store, group.id(),
                                    new SyncRoot(newRoot.getAbsolutePath(), null, null));
                        }
                        config.changeRoot(group.id(), newRoot.getAbsolutePath());
                        dialog.dismiss();
                        settings.dismiss();
                        showConfig();
                        restartServiceAfterMaintenance();
                        Toast.makeText(this, "同步根已更换，正在重启同步服务",
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        error.setText("更换失败: " + message(e));
                    }
                }));
        dialog.show();
    }

    private void ensureRootOutsideOtherGroups(GroupId current, File candidate) {
        String normalized = canonical(candidate);
        for (AndroidGroup other : config.groups()) {
            if (other.id().equals(current)) {
                continue;
            }
            String otherRoot = canonical(new File(other.rootPath()));
            String separator = File.separator;
            if (normalized.equals(otherRoot)
                    || normalized.startsWith(otherRoot + separator)
                    || otherRoot.startsWith(normalized + separator)) {
                throw new IllegalArgumentException(
                        "同步根与其他同步组重叠：每个目录只能属于一个同步组");
            }
        }
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    private void confirmDeleteGroup(AndroidGroup group, AlertDialog settings) {
        new AlertDialog.Builder(this)
                .setTitle("删除同步组 " + groupText(group) + "？")
                .setMessage("本地同步根中的文件会保留。\n"
                        + "本机将不再发现或同步该组；重启同步服务后停止该组的连接。\n"
                        + "此操作不可撤销，需要恢复时请重新添加组 ID 与同步根。")
                .setPositiveButton("确认删除", (dialog, which) -> {
                    try {
                        stopSyncServiceForMaintenance();
                        try (AndroidSqliteMetadataStore store =
                                     new AndroidSqliteMetadataStore(this, "itemsync.sqlite")) {
                            GroupDataReset.delete(store, group.id());
                        }
                        config.removeGroup(group.id());
                        List<GroupId> remaining = new ArrayList<>();
                        for (AndroidGroup existing : config.groups()) {
                            remaining.add(existing.id());
                        }
                        new AndroidTrustStore(this).retainOnly(remaining);
                        selectedGroupId = null;
                        settings.dismiss();
                        showConfig();
                        restartServiceAfterMaintenance();
                    } catch (Exception e) {
                        Toast.makeText(this, "删除失败: " + message(e),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restartServiceIfRunning() {
        if (!SyncForegroundService.isRunning()) {
            return;
        }
        stopSyncServiceForMaintenance();
        restartServiceAfterMaintenance();
        if (config.hasGroups()) {
            Toast.makeText(this, "同步组已变更，正在重启同步服务", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Stops the foreground service and drops the binding before the caller opens the database
     * directly (a group deletion must not race the running group runtime).
     */
    private void stopSyncServiceForMaintenance() {
        if (deviceDialog != null) {
            deviceDialog.dismiss();
            deviceDialog = null;
        }
        if (ruleDialog != null) {
            ruleDialog.dismiss();
            ruleDialog = null;
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException ignored) {
                // the service may already be gone
            }
            bound = false;
            boundService = null;
        }
        stopService(new Intent(this, SyncForegroundService.class));
    }

    private void restartServiceAfterMaintenance() {
        if (config.hasEnabledGroups()) {
            requestNotificationPermission();
            startForegroundService(new Intent(this, SyncForegroundService.class));
        } else {
            Toast.makeText(this, "没有启用的同步组，已停止同步服务",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private AndroidGroup selectedGroup() {
        if (selectedGroupId != null) {
            for (AndroidGroup group : groups) {
                if (group.id().equals(selectedGroupId)) {
                    return group;
                }
            }
        }
        return groups.isEmpty() ? null : groups.get(0);
    }

    private String defaultRoot() {
        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        return new File(base, "root-" + (config.groups().size() + 1)).getAbsolutePath();
    }

    private static String groupText(AndroidGroup group) {
        String text = group.name().equals(group.id().value())
                ? group.id().value()
                : group.name() + " (" + group.id().value() + ")";
        return group.enabled() ? text : text + "（已停用）";
    }

    private String groupLabel(GroupId groupId) {
        for (AndroidGroup group : groups) {
            if (group.id().equals(groupId)) {
                return groupText(group);
            }
        }
        return groupId.value();
    }

    private void showDevices() {
        AndroidGroup selected = selectedGroup();
        if (selected == null) {
            Toast.makeText(this, "请先添加同步组", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!selected.enabled()) {
            Toast.makeText(this, "该同步组已停用", Toast.LENGTH_SHORT).show();
            return;
        }
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
        GroupId groupId = selectedGroupId;
        if (service == null || groupId == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<PeerTrustCoordinator.PeerView> views = service.peerViews(groupId, true);
                runOnUiThread(() -> {
                    if (deviceDialog != null) {
                        deviceDialog.dismiss();
                    }
                    deviceDialog = new DeviceDialog(views, groupId);
                    deviceDialog.show();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "读取在线设备失败: " + message(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void callService(GroupId groupId, String failure, ServiceAction action) {
        new Thread(() -> {
            try {
                action.run();
                SyncForegroundService service = boundService;
                if (service != null) {
                    service.requestSync(groupId);
                }
                runOnUiThread(() -> {
                    if (deviceDialog != null) {
                        deviceDialog.reload(true);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        failure + ": " + message(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showRules() {
        AndroidGroup selected = selectedGroup();
        if (selected == null) {
            Toast.makeText(this, "请先添加同步组", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!selected.enabled()) {
            Toast.makeText(this, "该同步组已停用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (boundService != null && boundService.isReady()) {
            loadRules();
            return;
        }
        if (bound) {
            Toast.makeText(this, "同步服务尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingRules = true;
        if (!bindService(new Intent(this, SyncForegroundService.class), connection, 0)) {
            pendingRules = false;
            Toast.makeText(this, "请先保存并启动同步服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadRules() {
        GroupId groupId = selectedGroupId;
        SyncForegroundService service = boundService;
        if (groupId == null || service == null) {
            return;
        }
        new Thread(() -> {
            try {
                List<Trigger> triggers = service.triggers(groupId);
                runOnUiThread(() -> {
                    if (ruleDialog != null) {
                        ruleDialog.dismiss();
                    }
                    ruleDialog = new RuleDialog(triggers, groupId);
                    ruleDialog.show();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "读取同步规则失败: " + message(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String triggerText(Trigger trigger) {
        String state = trigger.enabled() ? "启用" : "停用";
        try {
            TriggerConfig config = TriggerConfigCodec.decode(trigger.configJson());
            if (config instanceof TriggerConfig.Alarm alarm) {
                return state + "  闹钟  " + daysText(alarm.schedule().days()) + "  "
                        + alarm.schedule().time() + "  " + alarm.schedule().zone().getId();
            }
            if (config instanceof TriggerConfig.DirectoryChange) {
                return state + "  目录变动（同步根）";
            }
            if (config instanceof TriggerConfig.ProcessEvent process) {
                return state + "  程序事件  " + String.join(", ", process.executables());
            }
        } catch (RuntimeException ignored) {
            // Fall through to the raw value so a corrupt rule is still visible and deletable.
        }
        return state + "  " + trigger.type() + "  " + trigger.configJson();
    }

    private static boolean hasDirectoryRule(List<Trigger> triggers) {
        for (Trigger trigger : triggers) {
            try {
                if (TriggerConfigCodec.decode(trigger.configJson())
                        instanceof TriggerConfig.DirectoryChange) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // An unreadable rule is not a directory rule.
            }
        }
        return false;
    }

    private static String daysText(Set<DayOfWeek> days) {
        if (days.size() == DayOfWeek.values().length) {
            return "每天";
        }
        if (days.equals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))) {
            return "工作日";
        }
        List<String> labels = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (days.contains(day)) {
                labels.add(dayLabel(day));
            }
        }
        return String.join("、", labels);
    }

    private static String dayLabel(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private final class RuleDialog {

        private final GroupId groupId;
        private final Dialog dialog;
        private final ListView list;
        private final TextView emptyLabel;
        private final TextView error;
        private final ArrayAdapter<Trigger> adapter;
        private final Button toggleButton;
        private final Button deleteButton;
        private final Button addDirectoryButton;
        private final AtomicBoolean busy = new AtomicBoolean();
        private String selectedId;

        private RuleDialog(List<Trigger> triggers, GroupId groupId) {
            this.groupId = groupId;
            dialog = new Dialog(MainActivity.this);
            dialog.setTitle("同步规则：" + groupLabel(groupId));

            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            content.setPadding(padding, padding, padding, padding);

            TextView hint = new TextView(MainActivity.this);
            hint.setText("规则变更立即生效；闹钟规则使用本机时区，"
                    + "目录变动规则监视该组的同步根。");
            hint.setTextSize(14);
            content.addView(hint);

            emptyLabel = new TextView(MainActivity.this);
            emptyLabel.setText("该组还没有同步规则");
            content.addView(emptyLabel);

            adapter = new ArrayAdapter<Trigger>(MainActivity.this,
                    android.R.layout.simple_list_item_1, new ArrayList<>()) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView text = (TextView) super.getView(position, convertView, parent);
                    text.setText(triggerText(getItem(position)));
                    text.setTextSize(14);
                    return text;
                }
            };
            list = new ListView(MainActivity.this);
            list.setAdapter(adapter);
            list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener((parent, view, position, id) -> {
                Trigger selected = adapter.getItem(position);
                selectedId = selected == null ? null : selected.id().value();
                updateButtons();
            });
            content.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

            error = errorLabel();
            content.addView(error);

            Button addAlarm = new Button(MainActivity.this);
            addAlarm.setText("添加闹钟规则");
            addAlarm.setOnClickListener(view -> showAlarmDialog());
            content.addView(addAlarm);

            addDirectoryButton = new Button(MainActivity.this);
            addDirectoryButton.setText("添加目录变动规则");
            addDirectoryButton.setOnClickListener(view -> {
                if (hasDirectoryRule(adapterItems())) {
                    Toast.makeText(MainActivity.this, "该组已有目录变动规则",
                            Toast.LENGTH_SHORT).show();
                } else {
                    addRule(new TriggerConfig.DirectoryChange());
                }
            });
            content.addView(addDirectoryButton);

            toggleButton = new Button(MainActivity.this);
            toggleButton.setText("启用/停用");
            toggleButton.setOnClickListener(view -> toggleSelected());
            content.addView(toggleButton);

            deleteButton = new Button(MainActivity.this);
            deleteButton.setText("删除规则");
            deleteButton.setOnClickListener(view -> deleteSelected());
            content.addView(deleteButton);

            Button close = new Button(MainActivity.this);
            close.setText("关闭");
            close.setOnClickListener(view -> dialog.dismiss());
            content.addView(close);

            dialog.setContentView(content);
            dialog.setOnDismissListener(event -> {
                if (ruleDialog == this) {
                    ruleDialog = null;
                }
            });
            render(triggers);
        }

        private void show() {
            dialog.show();
        }

        private void dismiss() {
            dialog.dismiss();
        }

        private void render(List<Trigger> triggers) {
            String keep = selectedId;
            adapter.clear();
            adapter.addAll(triggers);
            adapter.notifyDataSetChanged();
            emptyLabel.setVisibility(triggers.isEmpty() ? View.VISIBLE : View.GONE);
            int position = -1;
            if (keep != null) {
                for (int index = 0; index < triggers.size(); index++) {
                    if (triggers.get(index).id().value().equals(keep)) {
                        position = index;
                        break;
                    }
                }
            }
            if (position < 0 && !triggers.isEmpty()) {
                position = 0;
            }
            if (position >= 0) {
                list.setItemChecked(position, true);
                selectedId = triggers.get(position).id().value();
            } else {
                list.clearChoices();
                selectedId = null;
            }
            updateButtons();
        }

        private List<Trigger> adapterItems() {
            List<Trigger> items = new ArrayList<>(adapter.getCount());
            for (int index = 0; index < adapter.getCount(); index++) {
                items.add(adapter.getItem(index));
            }
            return items;
        }

        private Trigger selected() {
            int position = list.getCheckedItemPosition();
            if (position == ListView.INVALID_POSITION || position >= adapter.getCount()) {
                return null;
            }
            return adapter.getItem(position);
        }

        private void updateButtons() {
            Trigger selected = selected();
            boolean locked = busy.get();
            toggleButton.setEnabled(!locked && selected != null);
            deleteButton.setEnabled(!locked && selected != null);
            addDirectoryButton.setEnabled(!locked && !hasDirectoryRule(adapterItems()));
        }

        private void reload() {
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            updateButtons();
            new Thread(() -> {
                try {
                    List<Trigger> triggers = boundService.triggers(groupId);
                    runOnUiThread(() -> {
                        busy.set(false);
                        error.setText("");
                        render(triggers);
                    });
                } catch (RuntimeException e) {
                    runOnUiThread(() -> {
                        busy.set(false);
                        error.setText("读取规则失败: " + message(e));
                        updateButtons();
                    });
                }
            }).start();
        }

        private void addRule(TriggerConfig config) {
            SyncForegroundService service = boundService;
            if (service == null) {
                error.setText("同步服务已断开");
                return;
            }
            busy.set(true);
            updateButtons();
            new Thread(() -> {
                try {
                    service.addTrigger(groupId, config);
                    runOnUiThread(() -> {
                        busy.set(false);
                        reload();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy.set(false);
                        error.setText("添加规则失败: " + message(e));
                        updateButtons();
                    });
                }
            }).start();
        }

        private void toggleSelected() {
            Trigger selected = selected();
            SyncForegroundService service = boundService;
            if (selected == null || service == null) {
                return;
            }
            busy.set(true);
            updateButtons();
            new Thread(() -> {
                try {
                    service.setTriggerEnabled(groupId, selected.id(), !selected.enabled());
                    runOnUiThread(() -> {
                        busy.set(false);
                        reload();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy.set(false);
                        error.setText("修改规则失败: " + message(e));
                        updateButtons();
                    });
                }
            }).start();
        }

        private void deleteSelected() {
            Trigger selected = selected();
            if (selected == null) {
                return;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("删除规则")
                    .setMessage(triggerText(selected) + "\n\n重启同步服务后生效。")
                    .setPositiveButton("删除", (confirm, which) -> {
                        SyncForegroundService service = boundService;
                        if (service == null) {
                            error.setText("同步服务已断开");
                            return;
                        }
                        busy.set(true);
                        updateButtons();
                        new Thread(() -> {
                            try {
                                service.deleteTrigger(groupId, selected.id());
                                runOnUiThread(() -> {
                                    busy.set(false);
                                    reload();
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    busy.set(false);
                                    error.setText("删除规则失败: " + message(e));
                                    updateButtons();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        private void showAlarmDialog() {
            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(8), dp(16), dp(8));

            DayOfWeek[] order = {
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
            };
            CheckBox[] boxes = new CheckBox[order.length];
            LinearLayout dayRow = new LinearLayout(MainActivity.this);
            dayRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = 0; index < order.length; index++) {
                boxes[index] = new CheckBox(MainActivity.this);
                boxes[index].setText(dayLabel(order[index]));
                boxes[index].setChecked(index < 5);
                dayRow.addView(boxes[index]);
            }
            content.addView(dayRow);
            EditText time = field(content, "时间（HH:mm）", "23:00", false);
            TextView zone = new TextView(MainActivity.this);
            zone.setText("时区: " + ZoneId.systemDefault().getId());
            zone.setTextSize(13);
            content.addView(zone);
            TextView alarmError = errorLabel();
            content.addView(alarmError);

            AlertDialog alarm = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("添加闹钟规则")
                    .setView(content)
                    .setPositiveButton("添加", null)
                    .setNegativeButton("取消", null)
                    .create();
            alarm.setOnShowListener(event -> alarm.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        try {
                            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                            for (int index = 0; index < boxes.length; index++) {
                                if (boxes[index].isChecked()) {
                                    days.add(order[index]);
                                }
                            }
                            if (days.isEmpty()) {
                                throw new IllegalArgumentException("至少选择一天");
                            }
                            LocalTime alarmTime;
                            try {
                                alarmTime = LocalTime.parse(time.getText().toString().trim(),
                                        DateTimeFormatter.ofPattern("HH:mm"));
                            } catch (DateTimeParseException e) {
                                throw new IllegalArgumentException("时间格式应为 HH:mm", e);
                            }
                            alarm.dismiss();
                            addRule(new TriggerConfig.Alarm(
                                    new AlarmSchedule(days, alarmTime, ZoneId.systemDefault())));
                        } catch (RuntimeException e) {
                            alarmError.setText(e.getMessage() == null ? "规则无效" : e.getMessage());
                        }
                    }));
            alarm.show();
        }
    }

    private static String trustState(PeerTrustCoordinator.PeerView view) {
        if (PeerTrustCoordinator.PeerView.isOffline(view)) {
            return view.locallyTrusted()
                    ? "本机已开启信任（设备离线）"
                    : "设备离线（未信任）";
        }
        if (!view.locallyTrusted()) {
            return switch (view.remoteTrust()) {
                case TRUSTED -> "对方已信任你，等待本机确认";
                case UNTRUSTED -> "未信任";
                case UNKNOWN -> view.connected()
                        ? "未信任（已连接，等待本机确认）"
                        : "未信任（" + trustFailureText(view) + "）";
            };
        }
        return switch (view.remoteTrust()) {
            case TRUSTED -> view.connected()
                    ? "双方已开启信任，已连接"
                    : "双方已开启信任";
            case UNTRUSTED -> view.connected()
                    ? "本机已开启信任，已连接（对方未信任）"
                    : "本机已开启信任（等待对方确认）";
            case UNKNOWN -> view.connected()
                    ? "本机已开启信任，已连接（" + trustFailureText(view) + "）"
                    : "本机已开启信任（" + trustFailureText(view) + "）";
        };
    }

    private static String trustFailureText(PeerTrustCoordinator.PeerView view) {
        RemoteTrustFailure failure = view.remoteTrustFailure();
        if (failure == null) {
            return "无法确认对方状态";
        }
        return switch (failure) {
            case NO_PAIRING_ENDPOINT -> "对端未提供配对端点";
            case QUERY_TIMEOUT -> "配对状态查询超时";
            case IDENTITY_MISMATCH -> "配对端点身份校验失败";
            case CONNECTION_FAILED -> "无法连接配对端点";
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

        private final GroupId groupId;
        private final Dialog dialog;
        private final ListView list;
        private final TextView emptyLabel;
        private final TextView deviceError;
        private final ArrayAdapter<PeerTrustCoordinator.PeerView> adapter;
        private final Button pairButton;
        private final Button revokeButton;
        private final ExecutorService refresher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "itemsync-device-refresh");
            thread.setDaemon(true);
            return thread;
        });
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private final AtomicBoolean pendingForce = new AtomicBoolean();
        private Runnable periodic;
        private AutoCloseable presence;
        private String selectedDeviceId;

        private DeviceDialog(List<PeerTrustCoordinator.PeerView> views, GroupId groupId) {
            this.groupId = groupId;
            dialog = new Dialog(MainActivity.this);
            dialog.setTitle("组内设备：" + groupLabel(groupId));

            LinearLayout content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            content.setPadding(padding, padding, padding, padding);

            emptyLabel = new TextView(MainActivity.this);
            emptyLabel.setText("该组暂无已知设备");
            content.addView(emptyLabel);

            TextView hint = new TextView(MainActivity.this);
            hint.setText("首次开启信任会固定当前发现的证书指纹，无法防止首次中间人；"
                    + "请核对设备名。组 ID 只决定能发现哪些设备。");
            hint.setTextSize(14);
            content.addView(hint);

            adapter = new ArrayAdapter<PeerTrustCoordinator.PeerView>(
                    MainActivity.this, android.R.layout.simple_list_item_1,
                    new ArrayList<>()) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView text = (TextView) super.getView(position, convertView, parent);
                    text.setText(deviceLabel(getItem(position)));
                    text.setTextSize(14);
                    return text;
                }
            };
            list = new ListView(MainActivity.this);
            list.setAdapter(adapter);
            list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            list.setOnItemClickListener((parent, view, position, id) -> {
                PeerTrustCoordinator.PeerView selected = adapter.getItem(position);
                selectedDeviceId = selected == null ? null
                        : selected.presence().peer().deviceId().value();
                updateButtons();
            });
            content.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

            deviceError = errorLabel();
            content.addView(deviceError);

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
            refresh.setOnClickListener(view -> reload(true));
            content.addView(refresh);

            Button close = new Button(MainActivity.this);
            close.setText("关闭");
            close.setOnClickListener(view -> dialog.dismiss());
            content.addView(close);

            dialog.setContentView(content);
            render(views);
        }

        private void show() {
            periodic = () -> {
                if (!dialog.isShowing()) {
                    return;
                }
                reload(false);
                handler.postDelayed(periodic, 30000);
            };
            dialog.setOnDismissListener(event -> {
                handler.removeCallbacks(periodic);
                closePresence();
                refresher.shutdownNow();
            });
            dialog.show();
            reload(true);
            SyncForegroundService service = boundService;
            if (service != null) {
                try {
                    presence = service.addPresenceListener(groupId, () -> {
                        if (dialog.isShowing()) {
                            handler.post(() -> reload(false));
                        }
                    });
                } catch (RuntimeException e) {
                    deviceError.setText("订阅设备变化失败: " + message(e));
                }
            }
            // Presence events refresh immediately; the slow pass still updates remote trust
            // and connection state, which have no discovery event.
            handler.postDelayed(periodic, 30000);
        }

        private void closePresence() {
            AutoCloseable current = presence;
            presence = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {
                    // the group runtime is already closed
                }
            }
        }

        private void dismiss() {
            dialog.dismiss();
        }

        private void reload(boolean force) {
            SyncForegroundService service = boundService;
            if (service == null) {
                if (force) {
                    Toast.makeText(MainActivity.this, "同步服务已断开", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (!refreshing.compareAndSet(false, true)) {
                if (force) {
                    pendingForce.set(true);
                }
                return;
            }
            try {
                refresher.execute(() -> {
                    try {
                        List<PeerTrustCoordinator.PeerView> views = service.peerViews(groupId, force);
                        runOnUiThread(() -> {
                            if (dialog.isShowing()) {
                                render(views);
                                deviceError.setText("");
                            }
                        });
                    } catch (RuntimeException e) {
                        runOnUiThread(() -> {
                            if (dialog.isShowing()) {
                                deviceError.setText("刷新在线设备失败: " + message(e));
                            }
                        });
                    } finally {
                        refreshing.set(false);
                        if (pendingForce.compareAndSet(true, false)) {
                            reload(true);
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                refreshing.set(false);
            }
        }

        private void render(List<PeerTrustCoordinator.PeerView> views) {
            String keep = selectedDeviceId;
            adapter.clear();
            adapter.addAll(views);
            adapter.notifyDataSetChanged();
            emptyLabel.setVisibility(views.isEmpty() ? View.VISIBLE : View.GONE);
            int position = -1;
            if (keep != null) {
                for (int i = 0; i < views.size(); i++) {
                    if (views.get(i).presence().peer().deviceId().value().equals(keep)) {
                        position = i;
                        break;
                    }
                }
            }
            if (position < 0 && !views.isEmpty()) {
                position = 0;
            }
            if (position >= 0) {
                list.setItemChecked(position, true);
                selectedDeviceId = views.get(position).presence().peer().deviceId().value();
            } else {
                list.clearChoices();
                selectedDeviceId = null;
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
            pairButton.setEnabled(view != null
                    && !view.locallyTrusted()
                    && !PeerTrustCoordinator.PeerView.isOffline(view));
            revokeButton.setEnabled(view != null && view.locallyTrusted());
        }

        private void pairSelected() {
            PeerTrustCoordinator.PeerView view = selected();
            if (view == null) {
                return;
            }
            callService(groupId, "信任失败", () -> {
                SyncForegroundService service = boundService;
                if (service == null) {
                    throw new IllegalStateException("同步服务已断开");
                }
                service.trustPeer(groupId, view.presence().peer());
            });
        }

        private void revokeSelected() {
            PeerTrustCoordinator.PeerView view = selected();
            if (view == null) {
                return;
            }
            callService(groupId, "撤销信任失败", () -> {
                SyncForegroundService service = boundService;
                if (service == null) {
                    throw new IllegalStateException("同步服务已断开");
                }
                service.untrustPeer(groupId, view.presence().peer());
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

    private EditText passwordField(LinearLayout layout, String label) {
        layout.addView(fieldLabel(label));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        layout.addView(field, params);
        return field;
    }

    private EditText field(LinearLayout layout, String label, String value, boolean numeric) {
        layout.addView(fieldLabel(label));
        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (numeric) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        layout.addView(field, params);
        return field;
    }

    private TextView hint(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(getColor(R.color.itemsync_muted));
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView errorLabel() {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(getColor(R.color.itemsync_error));
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }

    private void setError(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
