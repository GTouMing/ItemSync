package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.TriggerId;
import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.model.SyncRoot;
import github.gtouming.itemsync.domain.model.Trigger;
import github.gtouming.itemsync.domain.security.NodeUnlockKey;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.DesktopGroup;
import github.gtouming.itemsync.platform.desktop.DesktopSecurity;
import github.gtouming.itemsync.platform.desktop.PathSupport;
import github.gtouming.itemsync.platform.desktop.WindowsAutostart;
import github.gtouming.itemsync.store.metadata.GroupDataReset;
import github.gtouming.itemsync.store.metadata.jdbc.JdbcMetadataStore;
import github.gtouming.itemsync.sync.state.ConflictResolver;
import github.gtouming.itemsync.sync.state.VersionHistoryService;
import github.gtouming.itemsync.sync.trigger.AlarmSchedule;
import github.gtouming.itemsync.sync.trigger.TriggerConfig;
import github.gtouming.itemsync.sync.trigger.TriggerConfigCodec;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;
import github.gtouming.itemsync.sync.trust.RemoteTrust;
import github.gtouming.itemsync.sync.trust.RemoteTrustFailure;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DesktopApp extends Application {

    private DesktopConfig config;
    private DesktopSecurity security;
    private DesktopService service;
    private ScheduledExecutorService statusScheduler;
    private ExecutorService worker;
    private TrayIcon trayIcon;
    private volatile JWindow trayMenuOwner;
    private volatile boolean exiting;

    /** The group the window shows; null when this installation has no group yet. */
    private volatile GroupId selectedGroupId;
    private boolean updatingGroupSelector;

    private final Label nodeLabel = new Label();
    private final ComboBox<DesktopGroup> groupSelector = new ComboBox<>();
    private final Label groupsHintLabel = new Label("未配置同步组，请先点击“添加同步组”");
    private final Label groupIdLabel = new Label();
    private final Label groupNameLabel = new Label();
    private final Label rootLabel = new Label();
    private final Label fingerprintLabel = new Label();
    private final Label lastRunLabel = new Label();
    private final Label conflictLabel = new Label();
    private final Label errorLabel = new Label();
    private Button syncButton;
    private Button syncAllButton;
    private Button conflictsButton;
    private Button historyButton;
    private Button peersButton;
    private Button rulesButton;
    private Button addGroupButton;
    private Button groupSettingsButton;

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        Path dataDir = parseDataDir(getParameters().getRaw());
        if (dataDir == null) {
            showError("无法创建数据目录");
            Platform.exit();
            return;
        }
        security = new DesktopSecurity(dataDir);
        boolean configExists = Files.isRegularFile(dataDir.resolve(DesktopConfig.FILE_NAME));
        if (!configExists) {
            if (!security.hasLocalPassword() && !LocalPasswordDialog.setup(security)) {
                Platform.exit();
                return;
            }
            if (!DesktopSetupDialog.show(dataDir)) {
                Platform.exit();
                return;
            }
        }
        try {
            config = DesktopConfig.load(dataDir);
        } catch (Exception e) {
            showError("读取配置失败: " + e.getMessage());
            Platform.exit();
            return;
        }
        char[] secret;
        try {
            secret = identitySecret(dataDir);
        } catch (Exception e) {
            showError("解锁身份失败: " + e.getMessage());
            Platform.exit();
            return;
        }
        if (secret.length == 0) {
            showError("无法解锁本机身份，请检查 NUK 或清除本机数据");
            Platform.exit();
            return;
        }
        try {
            service = DesktopService.open(config, secret);
        } catch (Exception e) {
            showError("启动失败: " + e.getMessage());
            Platform.exit();
            return;
        }
        startBackground();
        buildUi(stage);
        installTray(stage);
        refreshAsync();
        statusScheduler.scheduleWithFixedDelay(this::queryStatus, 2, 2, TimeUnit.SECONDS);
        statusScheduler.scheduleWithFixedDelay(this::reconcileTrustedPeers, 10, 10, TimeUnit.SECONDS);
        if (!configExists || !config.hasGroups()
                || config.groups().stream().noneMatch(DesktopGroup::enabled)) {
            // A new installation, no group, or only disabled groups starts on the window so
            // the user can add or enable one.
            stage.show();
        }
    }

    private void reconcileTrustedPeers() {
        try {
            service.reconcileTrustedPeers();
            service.refreshRemoteTrust();
        } catch (RuntimeException ignored) {
            // retry on the next background pass
        }
    }

    private void startBackground() {
        worker = Executors.newSingleThreadExecutor(
                runnable -> daemon(runnable, "itemsync-desktop-worker"));
        statusScheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemon(runnable, "itemsync-desktop-status"));
    }

    private char[] identitySecret(Path dataDir) throws Exception {
        char[] nuk = security.loadNuk();
        if (nuk != null) {
            return nuk;
        }
        if (Files.isRegularFile(dataDir.resolve("identity.p12"))) {
            String legacy = migrationPassword();
            if (legacy == null || legacy.isEmpty()) {
                return new char[0];
            }
            try {
                TlsIdentity.load(dataDir.resolve("identity.p12"), legacy.toCharArray());
            } catch (Exception e) {
                throw new IOException("旧身份口令不正确", e);
            }
            security.saveNuk(legacy.toCharArray());
            return legacy.toCharArray();
        }
        char[] generated = NodeUnlockKey.generate().keystorePassword();
        security.saveNuk(generated);
        return generated;
    }

    private String migrationPassword() {
        String fromEnvironment = System.getenv("ITEMS_SYNC_PASSWORD");
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("迁移：输入现有身份密钥口令");
        PasswordField field = new PasswordField();
        Label hint = new Label("只会要求这一次；之后由 Windows DPAPI 中的 NUK 静默解锁。");
        hint.setWrapText(true);
        VBox content = new VBox(10, field, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        ButtonType ok = new ButtonType("继续", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ok ? field.getText() : null);
        return dialog.showAndWait().orElse(null);
    }

    private void buildUi(Stage stage) {
        syncButton = new Button("立即同步");
        syncAllButton = new Button("全部同步");
        conflictsButton = new Button("处理冲突");
        historyButton = new Button("版本历史");
        peersButton = new Button("局域网设备");
        rulesButton = new Button("同步规则");
        addGroupButton = new Button("添加同步组");
        groupSettingsButton = new Button("组设置");
        Button refreshButton = new Button("刷新");
        Button openDataButton = new Button("打开数据目录");
        Button hideButton = new Button("隐藏到托盘");
        Button exitButton = new Button("退出");
        CheckBox autostart = new CheckBox("开机自启");

        groupSelector.setPrefWidth(340);
        groupSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(DesktopGroup group) {
                return group == null ? "" : groupText(group);
            }

            @Override
            public DesktopGroup fromString(String text) {
                return null;
            }
        });
        groupSelector.setCellFactory(list -> groupCell());
        groupSelector.setButtonCell(groupCell());
        groupsHintLabel.setStyle("-fx-text-fill: #6a737d;");
        groupsHintLabel.setManaged(false);
        groupsHintLabel.setVisible(false);
        HBox selectorRow = new HBox(12, groupSelector, groupsHintLabel);
        selectorRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.add(new Label("节点"), 0, 0);
        grid.add(nodeLabel, 1, 0);
        grid.add(new Label("同步组"), 0, 1);
        grid.add(selectorRow, 1, 1);
        grid.add(new Label("组 ID"), 0, 2);
        grid.add(groupIdLabel, 1, 2);
        grid.add(new Label("组名"), 0, 3);
        grid.add(groupNameLabel, 1, 3);
        grid.add(new Label("同步根"), 0, 4);
        rootLabel.setWrapText(true);
        grid.add(rootLabel, 1, 4);
        grid.add(new Label("本机指纹"), 0, 5);
        fingerprintLabel.setWrapText(true);
        grid.add(fingerprintLabel, 1, 5);
        grid.add(new Label("最近运行"), 0, 6);
        lastRunLabel.setWrapText(true);
        grid.add(lastRunLabel, 1, 6);

        conflictLabel.setStyle("-fx-text-fill: #9a6700;");
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setWrapText(true);

        HBox groupButtons = new HBox(8, syncButton, syncAllButton, rulesButton, conflictsButton,
                historyButton, peersButton);
        HBox windowButtons = new HBox(8, addGroupButton, groupSettingsButton, refreshButton,
                openDataButton, hideButton, exitButton);
        VBox root = new VBox(16, grid, conflictLabel, errorLabel, groupButtons, windowButtons,
                autostart);
        root.setPadding(new Insets(20));

        stage.setScene(new Scene(root, 940, 560));
        stage.setTitle("ItemSync");
        stage.setOnCloseRequest(event -> {
            if (!exiting) {
                event.consume();
                stage.hide();
            }
        });

        groupSelector.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldGroup, group) -> {
                    if (updatingGroupSelector) {
                        return;
                    }
                    onGroupSelected(group);
                });
        syncButton.setOnAction(event -> syncAsync());
        syncAllButton.setOnAction(event -> syncAllAsync());
        conflictsButton.setOnAction(event -> showConflictsAsync());
        historyButton.setOnAction(event -> showHistoryAsync());
        peersButton.setOnAction(event -> showPeersAsync());
        rulesButton.setOnAction(event -> showRulesAsync(stage));
        addGroupButton.setOnAction(event -> addGroup(stage));
        groupSettingsButton.setOnAction(event -> groupSettings(stage));
        refreshButton.setOnAction(event -> refreshAsync());
        openDataButton.setOnAction(event -> openData());
        hideButton.setOnAction(event -> stage.hide());
        exitButton.setOnAction(event -> exit(stage));

        configureAutostart(autostart);
        reloadGroups(null);
    }

    private ListCell<DesktopGroup> groupCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DesktopGroup group, boolean empty) {
                super.updateItem(group, empty);
                setText(empty || group == null ? null : groupText(group));
            }
        };
    }

    private static String groupText(DesktopGroup group) {
        String name = group.name();
        String text = name.equals(group.id().value())
                ? group.id().value()
                : name + " (" + group.id().value() + ")";
        return group.enabled() ? text : text + "（已停用）";
    }

    /**
     * Rebuilds the selector from the persisted group list. {@code preferred} selects that
     * group again after an add, rename or delete; null falls back to the first group.
     */
    private void reloadGroups(GroupId preferred) {
        List<DesktopGroup> groups = config.groups();
        DesktopGroup selection = preferred == null ? null : config.findGroup(preferred).orElse(null);
        if (selection == null && !groups.isEmpty()) {
            selection = groups.get(0);
        }
        updatingGroupSelector = true;
        try {
            groupSelector.getItems().setAll(groups);
            groupSelector.getSelectionModel().select(selection);
            groupSelector.setPromptText(groups.isEmpty() ? "未配置同步组" : null);
        } finally {
            updatingGroupSelector = false;
        }
        selectedGroupId = selection == null ? null : selection.id();
        boolean empty = groups.isEmpty();
        groupsHintLabel.setVisible(empty);
        groupsHintLabel.setManaged(empty);
        renderSelection(selection);
        updateActions();
        refreshAsync();
    }

    private void onGroupSelected(DesktopGroup group) {
        selectedGroupId = group == null ? null : group.id();
        renderSelection(group);
        updateActions();
        refreshAsync();
    }

    private void updateActions() {
        GroupId groupId = selectedGroupId;
        boolean hasGroup = groupId != null;
        boolean served = hasGroup && serves(groupId);
        setDisabled(syncButton, !served);
        setDisabled(conflictsButton, !served);
        setDisabled(historyButton, !served);
        setDisabled(peersButton, !served);
        setDisabled(rulesButton, !served);
        setDisabled(groupSettingsButton, !hasGroup);
        setDisabled(addGroupButton, config.groups().size() >= DesktopConfig.MAX_GROUPS);
        setDisabled(syncAllButton, service == null || service.groupRuntimes().isEmpty());
    }

    private boolean serves(GroupId groupId) {
        return service != null && groupId != null && service.groupRuntimes().containsKey(groupId);
    }

    private static void setDisabled(Button button, boolean disabled) {
        if (button != null) {
            button.setDisable(disabled);
        }
    }

    private void openWindow(Stage stage) {
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        if (security.hasLocalPassword()) {
            if (!LocalPasswordDialog.unlock(security, () -> {
                closeResources();
                try {
                    // Every configured root is a user directory and survives the reset.
                    DesktopReset.reset(config.dataDir(),
                            config.groups().stream().map(DesktopGroup::root).toList());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })) {
                return;
            }
        } else if (!LocalPasswordDialog.setup(security)) {
            return;
        }
        stage.show();
        stage.toFront();
        refreshAsync();
    }

    @Override
    public void stop() {
        closeResources();
    }

    private void syncAsync() {
        GroupId groupId = selectedGroupId;
        if (groupId == null || !serves(groupId)) {
            return;
        }
        if (syncButton != null) {
            syncButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                service.syncNow(groupId);
            } catch (IOException e) {
                Platform.runLater(() -> errorLabel.setText(
                        "同步失败: " + UserFacingErrors.describe(e)));
            } finally {
                Platform.runLater(() -> {
                    updateActions();
                    refreshAsync();
                });
            }
        });
    }

    private void syncAllAsync() {
        if (syncAllButton != null) {
            syncAllButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                service.syncAllNow();
            } catch (IOException e) {
                Platform.runLater(() -> errorLabel.setText(
                        "同步失败: " + UserFacingErrors.describe(e)));
            } finally {
                Platform.runLater(() -> {
                    updateActions();
                    refreshAsync();
                });
            }
        });
    }

    private void refreshAsync() {
        statusScheduler.execute(this::queryStatus);
    }

    private void queryStatus() {
        GroupId groupId = selectedGroupId;
        if (groupId != null && !serves(groupId)) {
            // A group persisted after startup has no runtime yet; do not ask the service for it.
            Platform.runLater(this::renderSelectionState);
            return;
        }
        try {
            DesktopService.Status status = groupId == null
                    ? service.status()
                    : service.status(groupId);
            Platform.runLater(() -> render(status));
        } catch (RuntimeException e) {
            Platform.runLater(() -> {
                if (Objects.equals(groupId, selectedGroupId)) {
                    errorLabel.setText("状态读取失败: " + e.getMessage());
                }
            });
        }
    }

    private void render(DesktopService.Status status) {
        nodeLabel.setText(status.nodeId().value() + "  (port " + status.port() + ")");
        fingerprintLabel.setText(status.fingerprint());
        if (status.groupId() == null) {
            if (selectedGroupId == null) {
                renderSelection(null);
            }
            return;
        }
        if (!status.groupId().equals(selectedGroupId)) {
            // A status for a group the window no longer shows; only the window wide values apply.
            return;
        }
        DesktopGroup group = config.findGroup(status.groupId()).orElse(null);
        groupIdLabel.setText(status.groupId().value());
        groupNameLabel.setText(group == null ? status.groupId().value() : group.name());
        rootLabel.setText(status.root() == null ? "—" : status.root().toString());
        if (status.lastRun() == null) {
            lastRunLabel.setText("尚未同步");
        } else {
            lastRunLabel.setText(status.lastRun().state() + "  " + status.lastRun().statsJson());
        }
        conflictLabel.setText("未解决冲突: " + status.openConflicts());
        if (conflictsButton != null) {
            conflictsButton.setText(status.openConflicts() == 0
                    ? "处理冲突"
                    : "处理冲突 (" + status.openConflicts() + ")");
        }
        errorLabel.setText(status.trustWarning() != null
                ? "信任警告: " + status.trustWarning()
                : status.lastError() == null
                ? ""
                : "最近错误: " + UserFacingErrors.describe(status.lastError()));
    }

    private void renderSelectionState() {
        DesktopGroup group = selectedGroupId == null
                ? null
                : config.findGroup(selectedGroupId).orElse(null);
        renderSelection(group);
    }

    private void renderSelection(DesktopGroup group) {
        if (group == null) {
            groupIdLabel.setText("未配置同步组");
            groupNameLabel.setText("—");
            rootLabel.setText("—");
            lastRunLabel.setText("尚未同步");
            conflictLabel.setText("未解决冲突: —");
            errorLabel.setText("");
            if (conflictsButton != null) {
                conflictsButton.setText("处理冲突");
            }
            return;
        }
        groupIdLabel.setText(group.id().value());
        groupNameLabel.setText(group.name());
        rootLabel.setText(group.root().toString());
        if (!group.enabled()) {
            lastRunLabel.setText("该组已停用");
            conflictLabel.setText("未解决冲突: —");
            errorLabel.setText("该组已停用：在“组设置”中启用后重启 ItemSync。");
            if (conflictsButton != null) {
                conflictsButton.setText("处理冲突");
            }
            return;
        }
        if (!serves(group.id())) {
            lastRunLabel.setText("重启 ItemSync 后开始同步");
            conflictLabel.setText("未解决冲突: —");
            errorLabel.setText("该同步组尚未加载：请重启 ItemSync 使该组开始同步。");
            if (conflictsButton != null) {
                conflictsButton.setText("处理冲突");
            }
        } else {
            lastRunLabel.setText("读取中…");
            conflictLabel.setText("未解决冲突: —");
            errorLabel.setText("");
        }
    }

    private void showConflictsAsync() {
        GroupId groupId = selectedGroupId;
        if (groupId == null || !serves(groupId)) {
            return;
        }
        if (conflictsButton != null) {
            conflictsButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                List<ConflictResolver.ConflictView> views = service.openConflicts(groupId);
                Platform.runLater(() -> {
                    updateActions();
                    showConflicts(views, groupId);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    updateActions();
                    showError("读取冲突失败: " + e.getMessage());
                });
            }
        });
    }

    private void showConflicts(List<ConflictResolver.ConflictView> views, GroupId groupId) {
        String title = groupTitle(groupId);
        if (views.isEmpty()) {
            showInfo(title + "：没有未解决的冲突");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("未解决冲突 — " + title + "（" + views.size() + "）");

        ListView<ConflictResolver.ConflictView> list = new ListView<>();
        list.getItems().setAll(views);
        list.setPrefHeight(280);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ConflictResolver.ConflictView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String localText;
                if (item.local() == null) {
                    localText = "本地已删除";
                } else if (item.copyPath() == null) {
                    localText = "无副本（本地文件在同步根原路径）";
                } else {
                    localText = item.copyPath().value();
                }
                setWrapText(true);
                setText(item.path().value()
                        + "\n本地副本: " + localText
                        + "\n远端版本: " + conflictSummary(item.remote())
                        + "\n冲突前本地版本: " + conflictSummary(item.local()));
            }
        });
        list.getSelectionModel().selectFirst();

        Label hint = new Label("恢复本地会把本地副本写回原路径；接受远端保留当前远端内容；"
                + "保留两者只标记冲突已解决，副本会保留。");
        hint.setWrapText(true);

        ButtonType keepRemote = new ButtonType("接受远端", ButtonBar.ButtonData.OK_DONE);
        ButtonType keepLocal = new ButtonType("恢复本地", ButtonBar.ButtonData.OTHER);
        ButtonType keepBoth = new ButtonType("保留两者", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(keepRemote, keepLocal, keepBoth, close);

        VBox content = new VBox(10, list, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(760);

        Button remoteButton = (Button) dialog.getDialogPane().lookupButton(keepRemote);
        Button localButton = (Button) dialog.getDialogPane().lookupButton(keepLocal);
        Button bothButton = (Button) dialog.getDialogPane().lookupButton(keepBoth);
        Runnable updateButtons = () -> {
            ConflictResolver.ConflictView selected = list.getSelectionModel().getSelectedItem();
            remoteButton.setDisable(selected == null);
            localButton.setDisable(selected == null || selected.local() == null);
            bothButton.setDisable(selected == null || selected.copyPath() == null);
        };
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons.run());
        updateButtons.run();

        remoteButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_REMOTE, groupId);
        });
        localButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_LOCAL, groupId);
        });
        bothButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_BOTH, groupId);
        });

        dialog.showAndWait();
    }

    private void resolveSelected(Dialog<Void> dialog,
                                 ListView<ConflictResolver.ConflictView> list,
                                 ConflictResolver.Resolution resolution,
                                 GroupId groupId) {
        ConflictResolver.ConflictView selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (resolution == ConflictResolver.Resolution.KEEP_REMOTE && selected.remote() == null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "远端已删除该文件，接受远端会删除本地文件。继续？",
                    ButtonType.OK, ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        worker.submit(() -> {
            try {
                service.resolveConflict(groupId, selected.id(), resolution);
                Platform.runLater(() -> {
                    dialog.close();
                    refreshAsync();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("处理冲突失败: " + e.getMessage()));
            }
        });
    }

    private static String conflictSummary(FileVersion version) {
        if (version == null) {
            return "已删除";
        }
        String hash = version.blobHash().value();
        return hash.substring(0, Math.min(8, hash.length())) + "  "
                + version.size() + " B  " + version.mtime();
    }

    private void showHistoryAsync() {
        GroupId groupId = selectedGroupId;
        if (groupId == null || !serves(groupId)) {
            return;
        }
        if (historyButton != null) {
            historyButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                List<VersionHistoryService.ItemView> items = service.historyItems(groupId);
                Platform.runLater(() -> {
                    updateActions();
                    showHistory(items, groupId);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    updateActions();
                    showError("读取版本历史失败: " + e.getMessage());
                });
            }
        });
    }

    private void showHistory(List<VersionHistoryService.ItemView> items, GroupId groupId) {
        String title = groupTitle(groupId);
        if (items.isEmpty()) {
            showInfo(title + "：同步根中没有已记录的文件版本");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("版本历史 — " + title);

        ListView<VersionHistoryService.ItemView> files = new ListView<>();
        files.getItems().setAll(items);
        files.setPrefWidth(320);
        files.setPrefHeight(320);
        files.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(VersionHistoryService.ItemView item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.path().value());
            }
        });

        ListView<FileVersion> versions = new ListView<>();
        versions.setPrefWidth(460);
        versions.setPrefHeight(320);
        versions.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(FileVersion version, boolean empty) {
                super.updateItem(version, empty);
                if (empty || version == null) {
                    setText(null);
                    return;
                }
                setWrapText(true);
                setText(versionSummary(version));
            }
        });

        Label hint = new Label("恢复某个版本会用该版本内容覆盖当前文件，并记录为一个新版本。");
        hint.setWrapText(true);

        ButtonType restore = new ButtonType("恢复此版本", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(restore, close);

        HBox lists = new HBox(10,
                new VBox(4, new Label("文件"), files),
                new VBox(4, new Label("版本"), versions));
        VBox content = new VBox(10, lists, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(840);

        Button restoreButton = (Button) dialog.getDialogPane().lookupButton(restore);
        restoreButton.setDisable(true);
        long[] request = {0};
        files.getSelectionModel().selectedItemProperty().addListener((observable, oldItem, item) -> {
            versions.getItems().clear();
            restoreButton.setDisable(true);
            if (item == null) {
                return;
            }
            long current = ++request[0];
            worker.submit(() -> {
                try {
                    List<FileVersion> loaded = service.versions(groupId, item.id());
                    Platform.runLater(() -> {
                        if (request[0] == current
                                && files.getSelectionModel().getSelectedItem() == item) {
                            versions.getItems().setAll(loaded);
                            if (!loaded.isEmpty()) {
                                versions.getSelectionModel().selectFirst();
                            }
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showError("读取版本失败: " + e.getMessage()));
                }
            });
        });
        versions.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVersion, version) -> restoreButton.setDisable(version == null));
        files.getSelectionModel().selectFirst();

        restoreButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            VersionHistoryService.ItemView item = files.getSelectionModel().getSelectedItem();
            FileVersion version = versions.getSelectionModel().getSelectedItem();
            if (item == null || version == null) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "用 " + version.mtime() + " 的版本覆盖当前文件？",
                    ButtonType.OK, ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            worker.submit(() -> {
                try {
                    service.restoreVersion(groupId, item.id(), version.id());
                    Platform.runLater(() -> {
                        dialog.close();
                        refreshAsync();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showError("恢复版本失败: " + e.getMessage()));
                }
            });
        });

        dialog.showAndWait();
    }

    private static String versionSummary(FileVersion version) {
        String hash = version.blobHash().value();
        return version.mtime() + "  " + version.size() + " B\n"
                + hash.substring(0, Math.min(8, hash.length())) + "  "
                + version.sourceNodeId().value()
                + "  创建于 " + version.createdAt();
    }

    private void showPeersAsync() {
        GroupId groupId = selectedGroupId;
        if (groupId == null || !serves(groupId)) {
            return;
        }
        worker.submit(() -> {
            try {
                List<PeerTrustCoordinator.PeerView> peers = service.peerViews(groupId, true);
                Platform.runLater(() -> showPeers(peers, groupId));
            } catch (RuntimeException e) {
                Platform.runLater(() -> showError("读取在线设备失败: " + e.getMessage()));
            }
        });
    }

    private void showPeers(List<PeerTrustCoordinator.PeerView> peers, GroupId groupId) {
        String title = groupTitle(groupId);
        if (peers.isEmpty()) {
            showInfo(title + "：该组暂无已知设备");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("组内设备 — " + title + "（" + peers.size() + "）");
        ListView<PeerTrustCoordinator.PeerView> list = new ListView<>();
        list.getItems().setAll(peers);
        list.setPrefHeight(300);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(PeerTrustCoordinator.PeerView view, boolean empty) {
                super.updateItem(view, empty);
                if (empty || view == null) {
                    setText(null);
                    return;
                }
                PresenceTracker.Presence presence = view.presence();
                StringBuilder addresses = new StringBuilder();
                for (InetAddress address : presence.peer().addresses()) {
                    if (addresses.length() > 0) {
                        addresses.append(", ");
                    }
                    addresses.append(address.getHostAddress());
                }
                setWrapText(true);
                setText(presence.peer().deviceId().value()
                        + "\n地址: " + (addresses.length() == 0 ? "未知" : addresses)
                        + "  端口: " + presence.peer().port()
                        + "\n指纹: " + presence.peer().fingerprint()
                        + "\n最近在线: " + presence.lastSeen()
                        + "\n状态: " + trustState(view));
            }
        });
        list.getSelectionModel().selectFirst();

        Label hint = new Label("信任需要双方操作：两台设备都开启信任后才会同步；"
                + "首次开启信任会固定当前发现的证书指纹，无法防止首次中间人；请核对设备名。"
                + "组 ID 只决定能发现哪些设备；本窗口只显示并信任当前同步组的设备。");
        hint.setWrapText(true);
        Label deviceError = new Label();
        deviceError.setStyle("-fx-text-fill: #b00020;");
        deviceError.setWrapText(true);

        ButtonType pair = new ButtonType("开启信任", ButtonBar.ButtonData.OTHER);
        ButtonType revoke = new ButtonType("撤销信任", ButtonBar.ButtonData.OTHER);
        ButtonType refreshButtonType = new ButtonType("刷新", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(pair, revoke, refreshButtonType, close);
        VBox content = new VBox(10, list, hint, deviceError);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(780);

        Button pairButton = (Button) dialog.getDialogPane().lookupButton(pair);
        Button revokeButton = (Button) dialog.getDialogPane().lookupButton(revoke);
        Button refreshButton = (Button) dialog.getDialogPane().lookupButton(refreshButtonType);
        AtomicBoolean busy = new AtomicBoolean();
        Runnable updateButtons = () -> {
            PeerTrustCoordinator.PeerView selected = list.getSelectionModel().getSelectedItem();
            boolean locked = busy.get();
            pairButton.setDisable(locked || selected == null
                    || selected.locallyTrusted()
                    || PeerTrustCoordinator.PeerView.isOffline(selected));
            revokeButton.setDisable(locked || selected == null || !selected.locallyTrusted());
        };
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons.run());
        updateButtons.run();

        AtomicReference<CoalescingRefresher<PeerTrustCoordinator.PeerView>> refresherRef =
                new AtomicReference<>();
        CoalescingRefresher<PeerTrustCoordinator.PeerView> refresher = new CoalescingRefresher<>(
                "itemsync-device-refresh",
                force -> service.peerViews(groupId, force),
                (generation, refreshed) -> Platform.runLater(() -> {
                    CoalescingRefresher<PeerTrustCoordinator.PeerView> current = refresherRef.get();
                    if (!dialog.isShowing() || current == null || !current.isCurrent(generation)) {
                        return;
                    }
                    applyPeerViews(list, refreshed);
                    deviceError.setText("");
                    updateButtons.run();
                }),
                failure -> Platform.runLater(() -> {
                    if (dialog.isShowing()) {
                        deviceError.setText("刷新在线设备失败: " + failure.getMessage());
                    }
                    updateButtons.run();
                }));
        refresherRef.set(refresher);

        pairButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            PeerTrustCoordinator.PeerView selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            busy.set(true);
            updateButtons.run();
            refresher.submit(() -> {
                try {
                    service.trustPeer(groupId, selected.presence().peer());
                    Platform.runLater(() -> {
                        busy.set(false);
                        refresher.request(true);
                        updateButtons.run();
                        syncAsync();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        deviceError.setText("已记录本机信任，但连接失败；等待对方信任后重试: "
                                + e.getMessage());
                        refresher.request(true);
                        updateButtons.run();
                    });
                }
            });
        });
        revokeButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            PeerTrustCoordinator.PeerView selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            busy.set(true);
            updateButtons.run();
            refresher.submit(() -> {
                try {
                    service.untrustPeer(groupId, selected.presence().peer());
                    Platform.runLater(() -> {
                        busy.set(false);
                        refresher.request(true);
                        updateButtons.run();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        deviceError.setText("撤销信任失败: " + e.getMessage());
                        updateButtons.run();
                    });
                }
            });
        });
        refreshButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            refresher.request(true);
        });

        AtomicReference<AutoCloseable> presenceRef = new AtomicReference<>();
        try {
            presenceRef.set(service.addPresenceListener(groupId,
                    () -> refresher.request(false)));
        } catch (RuntimeException e) {
            deviceError.setText("订阅设备变化失败: " + e.getMessage());
        }
        // Presence events refresh the list immediately; the slow pass still picks up remote
        // trust and connection changes that have no discovery event.
        Timeline autoRefresh = new Timeline(
                new KeyFrame(Duration.seconds(30), event -> refresher.request(false)));
        autoRefresh.setCycleCount(Animation.INDEFINITE);
        autoRefresh.play();
        dialog.setOnShown(event -> refresher.request(true));
        dialog.setOnHidden(event -> {
            autoRefresh.stop();
            AutoCloseable presence = presenceRef.getAndSet(null);
            if (presence != null) {
                try {
                    presence.close();
                } catch (Exception ignored) {
                    // the group runtime is already closed
                }
            }
            refresher.close();
        });

        dialog.showAndWait();
    }

    private static void applyPeerViews(ListView<PeerTrustCoordinator.PeerView> list,
                                       List<PeerTrustCoordinator.PeerView> refreshed) {
        PeerTrustCoordinator.PeerView selected = list.getSelectionModel().getSelectedItem();
        String keep = selected == null ? null : selected.presence().peer().deviceId().value();
        list.getItems().setAll(refreshed);
        if (keep != null) {
            for (PeerTrustCoordinator.PeerView view : refreshed) {
                if (view.presence().peer().deviceId().value().equals(keep)) {
                    list.getSelectionModel().select(view);
                    return;
                }
            }
        }
        if (list.getSelectionModel().getSelectedItem() == null && !refreshed.isEmpty()) {
            list.getSelectionModel().selectFirst();
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

    private String groupTitle(GroupId groupId) {
        DesktopGroup group = config.findGroup(groupId).orElse(null);
        return group == null ? groupId.value() : groupText(group);
    }

    private void showRulesAsync(Stage stage) {
        GroupId groupId = selectedGroupId;
        if (groupId == null || !serves(groupId)) {
            return;
        }
        worker.submit(() -> {
            try {
                List<Trigger> triggers = service.triggers(groupId);
                Platform.runLater(() -> showRules(triggers, groupId, stage));
            } catch (RuntimeException e) {
                Platform.runLater(() -> showError("读取同步规则失败: " + e.getMessage()));
            }
        });
    }

    private void showRules(List<Trigger> triggers, GroupId groupId, Stage stage) {
        String title = groupTitle(groupId);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("同步规则 — " + title);

        ListView<Trigger> list = new ListView<>();
        list.getItems().setAll(triggers);
        list.setPrefHeight(260);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Trigger trigger, boolean empty) {
                super.updateItem(trigger, empty);
                if (empty || trigger == null) {
                    setText(null);
                    return;
                }
                setWrapText(true);
                setText(triggerText(trigger));
            }
        });
        list.getSelectionModel().selectFirst();

        Label hint = new Label("规则变更立即生效。闹钟规则使用本机时区；"
                + "目录变动规则监视该组的同步根。");
        hint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);

        ButtonType addAlarm = new ButtonType("添加闹钟规则", ButtonBar.ButtonData.OTHER);
        ButtonType addDirectory = new ButtonType("添加目录变动规则", ButtonBar.ButtonData.OTHER);
        ButtonType toggle = new ButtonType("启用/停用", ButtonBar.ButtonData.OTHER);
        ButtonType delete = new ButtonType("删除规则", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(addAlarm, addDirectory, toggle, delete,
                close);
        VBox content = new VBox(10, list, hint, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(760);

        Button addAlarmButton = (Button) dialog.getDialogPane().lookupButton(addAlarm);
        Button addDirectoryButton = (Button) dialog.getDialogPane().lookupButton(addDirectory);
        Button toggleButton = (Button) dialog.getDialogPane().lookupButton(toggle);
        Button deleteButton = (Button) dialog.getDialogPane().lookupButton(delete);
        AtomicBoolean busy = new AtomicBoolean();

        Runnable[] updateButtons = new Runnable[1];
        Runnable[] reload = new Runnable[1];
        updateButtons[0] = () -> {
            Trigger selected = list.getSelectionModel().getSelectedItem();
            boolean locked = busy.get();
            addAlarmButton.setDisable(locked);
            addDirectoryButton.setDisable(locked || hasDirectoryRule(list.getItems()));
            toggleButton.setDisable(locked || selected == null);
            deleteButton.setDisable(locked || selected == null);
        };
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons[0].run());

        reload[0] = () -> {
            busy.set(true);
            updateButtons[0].run();
            worker.submit(() -> {
                try {
                    List<Trigger> refreshed = service.triggers(groupId);
                    Platform.runLater(() -> {
                        list.getItems().setAll(refreshed);
                        busy.set(false);
                        error.setText("");
                        updateButtons[0].run();
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        error.setText("读取规则失败: " + e.getMessage());
                        updateButtons[0].run();
                    });
                }
            });
        };

        addAlarmButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            TriggerConfig config = showAlarmRuleDialog(groupId);
            if (config == null) {
                return;
            }
            addRuleAsync(groupId, config, error, busy, reload[0], updateButtons[0]);
        });
        addDirectoryButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (hasDirectoryRule(list.getItems())) {
                showInfo("该组已有目录变动规则，不需要重复添加。");
                return;
            }
            addRuleAsync(groupId, new TriggerConfig.DirectoryChange(), error, busy,
                    reload[0], updateButtons[0]);
        });
        toggleButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Trigger selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            busy.set(true);
            updateButtons[0].run();
            worker.submit(() -> {
                try {
                    service.setTriggerEnabled(groupId, selected.id(), !selected.enabled());
                    Platform.runLater(() -> {
                        busy.set(false);
                        reload[0].run();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        error.setText("修改规则失败: " + e.getMessage());
                        updateButtons[0].run();
                    });
                }
            });
        });
        deleteButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Trigger selected = list.getSelectionModel().getSelectedItem();
            if (selected == null || !confirmDeleteRule(selected)) {
                return;
            }
            busy.set(true);
            updateButtons[0].run();
            worker.submit(() -> {
                try {
                    service.deleteTrigger(groupId, selected.id());
                    Platform.runLater(() -> {
                        busy.set(false);
                        reload[0].run();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        error.setText("删除规则失败: " + e.getMessage());
                        updateButtons[0].run();
                    });
                }
            });
        });

        updateButtons[0].run();
        dialog.showAndWait();
    }

    private void addRuleAsync(GroupId groupId, TriggerConfig config, Label error,
                              AtomicBoolean busy, Runnable reload, Runnable updateButtons) {
        busy.set(true);
        updateButtons.run();
        worker.submit(() -> {
            try {
                service.addTrigger(groupId, config);
                Platform.runLater(() -> {
                    busy.set(false);
                    reload.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    busy.set(false);
                    error.setText("添加规则失败: " + e.getMessage());
                    updateButtons.run();
                });
            }
        });
    }

    private TriggerConfig showAlarmRuleDialog(GroupId groupId) {
        Dialog<TriggerConfig> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("添加闹钟规则 — " + groupTitle(groupId));

        DayOfWeek[] order = {
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        };
        CheckBox[] boxes = new CheckBox[order.length];
        HBox days = new HBox(6);
        for (int index = 0; index < order.length; index++) {
            boxes[index] = new CheckBox(dayLabel(order[index]));
            boxes[index].setSelected(index < 5);
            days.getChildren().add(boxes[index]);
        }
        TextField timeField = new TextField("23:00");
        Label zone = new Label("时区: " + ZoneId.systemDefault().getId());
        Label hint = new Label("选择星期和时间；到点后触发一次同步。");
        hint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);

        VBox content = new VBox(10, days, new HBox(8, new Label("时间"), timeField), zone, hint,
                error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(430);

        ButtonType add = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        AtomicReference<TriggerConfig> result = new AtomicReference<>();
        Button addButton = (Button) dialog.getDialogPane().lookupButton(add);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Set<DayOfWeek> selected = EnumSet.noneOf(DayOfWeek.class);
                for (int index = 0; index < boxes.length; index++) {
                    if (boxes[index].isSelected()) {
                        selected.add(order[index]);
                    }
                }
                if (selected.isEmpty()) {
                    throw new IllegalArgumentException("至少选择一天");
                }
                LocalTime time;
                try {
                    time = LocalTime.parse(timeField.getText().trim(),
                            DateTimeFormatter.ofPattern("HH:mm"));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("时间格式应为 HH:mm", e);
                }
                result.set(new TriggerConfig.Alarm(
                        new AlarmSchedule(selected, time, ZoneId.systemDefault())));
            } catch (RuntimeException e) {
                error.setText(e.getMessage() == null ? "规则无效" : e.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == add ? result.get() : null);
        return dialog.showAndWait().orElse(null);
    }

    private static boolean hasDirectoryRule(List<Trigger> triggers) {
        for (Trigger trigger : triggers) {
            try {
                if (TriggerConfigCodec.decode(trigger.configJson())
                        instanceof TriggerConfig.DirectoryChange) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // An unreadable rule is left for the user to delete; it is not a directory rule.
            }
        }
        return false;
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

    private boolean confirmDeleteRule(Trigger trigger) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "删除规则“" + triggerText(trigger) + "”？\n删除后立即生效。",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("ItemSync");
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Adds a group to the persisted list. The running backend is built at startup, so the new
     * group only starts syncing after a restart; the window says so instead of pretending.
     */
    private void addGroup(Stage stage) {
        if (config.groups().size() >= DesktopConfig.MAX_GROUPS) {
            showInfo("最多只能有 " + DesktopConfig.MAX_GROUPS + " 个同步组。");
            return;
        }
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("添加同步组");
        dialog.setResizable(true);

        TextField nameField = new TextField();
        nameField.setPromptText("例如 工作（留空则使用组 ID）");
        TextField idField = new TextField(GroupId.random().value());
        idField.setPromptText("8 位 [0-9a-z]；加入已有组时填写对方的组 ID");
        TextField rootField = new TextField(nextGroupRoot().toString());
        Button browse = new Button("浏览...");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择同步根");
            Path current = null;
            try {
                current = PathSupport.expandUser(rootField.getText()).toAbsolutePath();
            } catch (RuntimeException ignored) {
                // Keep the data directory as the starting point.
            }
            chooser.setInitialDirectory(current != null && Files.isDirectory(current)
                    ? current.toFile()
                    : config.dataDir().toFile());
            File selected = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null) {
                rootField.setText(selected.getAbsolutePath());
            }
        });
        HBox rootBox = new HBox(8, rootField, browse);
        HBox.setHgrow(rootField, Priority.ALWAYS);

        Label hint = new Label("组 ID 是本机的发现键，添加后不可修改；加入已有组时输入同一个组 ID，"
                + "并选择本机自己的同步根。\n每组一个同步根，目录不能与其他组相同或嵌套；"
                + "组名只在本机显示，不会同步给其他设备。");
        hint.setWrapText(true);
        Label restartHint = new Label("新组在重启 ItemSync 后开始同步。");
        restartHint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("组名"), nameField);
        grid.addRow(1, new Label("组 ID"), idField);
        grid.addRow(2, new Label("同步根"), rootBox);

        VBox content = new VBox(12, grid, hint, restartHint, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(620);

        ButtonType add = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == add ? Boolean.TRUE : null);

        AtomicReference<GroupId> added = new AtomicReference<>();
        Button addButton = (Button) dialog.getDialogPane().lookupButton(add);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Path root = PathSupport.expandUser(rootField.getText()).toAbsolutePath().normalize();
                if (Files.exists(root) && !Files.isDirectory(root)) {
                    throw new IllegalArgumentException("同步根不是目录: " + root);
                }
                GroupId groupId = GroupId.parse(idField.getText());
                if (config.findGroup(groupId).isPresent()) {
                    throw new IllegalArgumentException("本机已有该组 ID，不能重复添加");
                }
                if (rootTaken(root)) {
                    throw new IllegalArgumentException(
                            "同步根与其他同步组重叠：每个目录只能属于一个同步组");
                }
                Files.createDirectories(root);
                config = config.addGroup(new DesktopGroup(groupId, nameField.getText(), root));
                added.set(groupId);
            } catch (Exception e) {
                error.setText("添加失败: " + addGroupFailure(e));
                event.consume();
            }
        });

        if (dialog.showAndWait().orElse(false)) {
            reloadGroups(added.get());
            showRestartNotice("已添加，请重启 ItemSync 使该组开始同步", stage);
        }
    }

    private String addGroupFailure(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        if (message.contains("duplicate sync group")) {
            return "本机已有该组 ID，不能重复添加";
        }
        if (message.contains("overlap")) {
            return "同步根与其他同步组重叠：每个目录只能属于一个同步组";
        }
        if (message.contains("at most")) {
            return "最多只能有 " + DesktopConfig.MAX_GROUPS + " 个同步组";
        }
        return message;
    }

    private Path nextGroupRoot() {
        // The default must live outside the data directory: DesktopConfig rejects a sync
        // root that contains (or is contained by) the data directory.
        Path base = Path.of(System.getProperty("user.home", "."), "ItemSync")
                .toAbsolutePath().normalize();
        for (int index = 1; index <= DesktopConfig.MAX_GROUPS + 1; index++) {
            Path candidate = base.resolve("root-" + index);
            if (!rootTaken(candidate)) {
                return candidate;
            }
        }
        return base.resolve("root-new");
    }

    private boolean rootTaken(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        for (DesktopGroup group : config.groups()) {
            Path root = group.root().toAbsolutePath().normalize();
            if (normalized.startsWith(root) || root.startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renames the group, changes its sync root or deletes it. The group id is immutable.
     */
    private void groupSettings(Stage stage) {
        DesktopGroup group = selectedGroupId == null
                ? null
                : config.findGroup(selectedGroupId).orElse(null);
        if (group == null) {
            showInfo("本机还没有同步组。");
            return;
        }
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("组设置 — " + groupText(group));

        TextField idField = new TextField(group.id().value());
        idField.setEditable(false);
        TextField rootField = new TextField(group.root().toString());
        rootField.setEditable(false);
        TextField nameField = new TextField(group.name());

        Label hint = new Label("组 ID 添加后不可修改；需要更换组 ID 请删除该组后重新添加。\n"
                + "更换同步根会清空该组的版本、冲突、同步游标与运行记录，"
                + "保留组 ID、信任、规则和设备列表；旧目录中的文件不会被删除。\n"
                + "组名只在本机显示，不会同步给其他设备。");
        hint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("组 ID"), idField);
        grid.addRow(1, new Label("组名"), nameField);
        grid.addRow(2, new Label("同步根"), rootField);

        VBox content = new VBox(12, grid, hint, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(640);

        ButtonType save = new ButtonType("保存名称", ButtonBar.ButtonData.OK_DONE);
        ButtonType toggle = new ButtonType(group.enabled() ? "停用此组" : "启用此组",
                ButtonBar.ButtonData.OTHER);
        ButtonType changeRoot = new ButtonType("更换同步根", ButtonBar.ButtonData.OTHER);
        ButtonType delete = new ButtonType("删除此组", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save, toggle, changeRoot, delete, close);
        dialog.setResultConverter(button -> button == save ? Boolean.TRUE : null);

        AtomicBoolean deleted = new AtomicBoolean();
        AtomicBoolean rootChanged = new AtomicBoolean();
        AtomicBoolean toggled = new AtomicBoolean();
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                config = config.renameGroup(group.id(), nameField.getText());
            } catch (Exception e) {
                error.setText("保存失败: " + e.getMessage());
                event.consume();
            }
        });
        Button toggleButton = (Button) dialog.getDialogPane().lookupButton(toggle);
        toggleButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                config = config.setGroupEnabled(group.id(), !group.enabled());
                toggled.set(true);
                dialog.close();
            } catch (Exception e) {
                error.setText("切换失败: " + e.getMessage());
            }
        });
        Button changeRootButton = (Button) dialog.getDialogPane().lookupButton(changeRoot);
        changeRootButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择新的同步根");
            chooser.setInitialDirectory(group.root().toFile());
            File selected = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected == null) {
                return;
            }
            Path newRoot = selected.toPath().toAbsolutePath().normalize();
            if (newRoot.equals(group.root().toAbsolutePath().normalize())) {
                showInfo("同步根没有变化。");
                return;
            }
            if (!confirmChangeRoot(group, newRoot)) {
                return;
            }
            try {
                Files.createDirectories(newRoot);
                resetGroupRoot(group.id(), newRoot);
                config = config.changeRoot(group.id(), newRoot);
                rootChanged.set(true);
                dialog.close();
            } catch (Exception e) {
                error.setText("更换失败: " + e.getMessage());
            }
        });
        Button deleteButton = (Button) dialog.getDialogPane().lookupButton(delete);
        deleteButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (!confirmDeleteGroup(group)) {
                return;
            }
            try {
                deleteGroupData(group.id());
                config = config.removeGroup(group.id());
                deleted.set(true);
                dialog.close();
            } catch (Exception e) {
                error.setText("删除失败: " + e.getMessage());
            }
        });

        boolean saved = dialog.showAndWait().orElse(false);
        if (deleted.get()) {
            showRestartRequired("已删除同步组 " + groupText(group)
                    + "。\n为避免已删除的组继续同步，ItemSync 将立即退出；重启后该组不再运行。\n"
                    + "本地文件保留；该组的规则、设备列表、版本与同步元数据已清理。", stage);
        } else if (rootChanged.get()) {
            showRestartRequired("已更换同步组 " + groupText(group) + " 的同步根。\n"
                    + "旧目录中的文件保留；该组的版本、冲突、游标与运行记录已清空。\n"
                    + "重启 ItemSync 后从新目录重新扫描。", stage);
        } else if (toggled.get()) {
            boolean enabled = !group.enabled();
            showRestartRequired("已" + (enabled ? "启用" : "停用") + "同步组 "
                    + groupText(group) + "。\n"
                    + "重启 ItemSync 后" + (enabled ? "开始" : "停止")
                    + "该组的发现、同步与连接。", stage);
        } else if (saved) {
            reloadGroups(group.id());
            refreshAsync();
        }
    }

    private boolean confirmChangeRoot(DesktopGroup group, Path newRoot) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "更换同步根会停止该组同步，并清空该组的版本、冲突、同步游标与运行记录。\n"
                        + "组 ID、信任、规则和设备列表保留；旧目录中的文件不会被删除。\n\n"
                        + "旧根: " + group.root() + "\n新根: " + newRoot,
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("ItemSync");
        alert.setHeaderText("更换 " + groupText(group) + " 的同步根？");
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private boolean confirmDeleteGroup(DesktopGroup group) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("删除同步组 " + groupText(group) + "？");
        Label warning = new Label("· 本地同步根中的文件会保留，不会被删除。\n"
                + "· 本机不再显示该组；重启 ItemSync 后停止该组的发现、同步与信任连接。\n"
                + "· 该组的规则、设备列表、版本与同步元数据会立即清理。\n"
                + "此操作不可撤销；需要恢复时请重新添加该组 ID 与同步根。");
        warning.setWrapText(true);
        VBox content = new VBox(10, warning);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType("确认删除", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == confirm ? Boolean.TRUE : null);
        return dialog.showAndWait().orElse(false);
    }

    /**
     * Reports a configuration change that the running backend cannot pick up until a restart.
     */
    private void showRestartNotice(String message, Stage stage) {
        String executable = packagedExecutable();
        ButtonType restart = new ButtonType(executable == null ? "退出 ItemSync" : "立即重启",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("稍后", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                executable == null ? message + "\n\n请手动重新启动 ItemSync。" : message,
                restart, later);
        alert.setTitle("ItemSync");
        alert.setHeaderText(null);
        if (alert.showAndWait().orElse(later) == restart) {
            restart(stage, executable);
        }
    }

    /**
     * Stops the service before touching the database: its group runtime still writes the
     * metadata of the group being deleted. The caller must restart after this.
     */
    private void deleteGroupData(GroupId groupId) throws Exception {
        closeResources();
        try (JdbcMetadataStore store = openMetadataStore()) {
            GroupDataReset.delete(store, groupId);
        }
    }

    private void resetGroupRoot(GroupId groupId, Path newRoot) throws Exception {
        closeResources();
        try (JdbcMetadataStore store = openMetadataStore()) {
            GroupDataReset.reset(store, groupId,
                    new SyncRoot(newRoot.toString(), null, null));
        }
    }

    private JdbcMetadataStore openMetadataStore() throws Exception {
        Path dbDirectory = Files.createDirectories(config.dataDir().resolve("db"));
        return JdbcMetadataStore.open(
                "jdbc:sqlite:" + dbDirectory.resolve("itemsync.sqlite").toAbsolutePath());
    }

    /**
     * A group deletion cannot be undone in the running backend: it would keep serving the
     * group until the process exits. This notice therefore exits even if restarting fails.
     */
    private void showRestartRequired(String message, Stage stage) {
        String executable = packagedExecutable();
        ButtonType exit = new ButtonType(executable == null ? "退出 ItemSync" : "退出并重启",
                ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING,
                executable == null ? message + "\n\n请手动重新启动 ItemSync。" : message, exit);
        alert.setTitle("ItemSync");
        alert.setHeaderText(null);
        alert.showAndWait();
        restart(stage, executable);
    }

    /**
     * Closes the service (and therefore its listening ports) before launching the replacement
     * process; launching first would race the old process for the sync port.
     */
    private void restart(Stage stage, String executable) {
        exiting = true;
        closeResources();
        if (executable != null) {
            try {
                new ProcessBuilder(executable, "--data",
                        config.dataDir().toAbsolutePath().toString()).start();
            } catch (IOException e) {
                showError("无法重新启动: " + e.getMessage() + "\n请手动启动 ItemSync。");
            }
        }
        stage.close();
        Platform.exit();
        System.exit(0);
    }

    private String packagedExecutable() {
        String configured = config.launchCommand();
        if (isExecutable(configured)) {
            return configured.trim();
        }
        String current = AutostartCommand.currentExecutable();
        return isExecutable(current) ? current.trim() : null;
    }

    private static boolean isExecutable(String candidate) {
        return candidate != null && !candidate.isBlank()
                && candidate.trim().toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private void configureAutostart(CheckBox checkBox) {
        if (!WindowsAutostart.isSupported()) {
            checkBox.setDisable(true);
            checkBox.setTooltip(new Tooltip("仅 Windows 支持"));
            return;
        }
        try {
            checkBox.setSelected(WindowsAutostart.isEnabled());
        } catch (IOException ignored) {
            // the registry may be unreadable; leave the checkbox off
        }
        String command = AutostartCommand.resolve(config.launchCommand(),
                AutostartCommand.currentExecutable(), config.dataDir());
        checkBox.setDisable(command == null);
        if (command == null) {
            checkBox.setTooltip(new Tooltip(
                    "需要打包后的 ItemSync.exe，或在 desktop.properties 中配置 launch-command"));
            return;
        }
        checkBox.setTooltip(new Tooltip("登录后自动启动 ItemSync"));
        checkBox.setOnAction(event -> {
            try {
                if (checkBox.isSelected()) {
                    WindowsAutostart.enable(command);
                } else {
                    WindowsAutostart.disable();
                }
            } catch (Exception e) {
                checkBox.setSelected(!checkBox.isSelected());
                showError("设置开机自启失败: " + e.getMessage());
            }
        });
    }

    private void installTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(45, 108, 223));
        graphics.fillRoundRect(0, 0, 16, 16, 4, 4);
        graphics.dispose();

        trayIcon = new TrayIcon(image, "ItemSync");
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showTrayMenu(stage, event);
                }
            }
        });
        trayIcon.addActionListener(event -> Platform.runLater(() -> openWindow(stage)));
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            trayIcon = null;
        }
    }

    private void showTrayMenu(Stage stage, MouseEvent event) {
        JPopupMenu popup = new JPopupMenu();
        popup.setLightWeightPopupEnabled(false);
        Font font = trayFont();
        popup.setFont(font);
        JMenuItem open = new JMenuItem("打开");
        JMenuItem sync = new JMenuItem("立即同步");
        JMenuItem exitItem = new JMenuItem("退出");
        open.setFont(font);
        sync.setFont(font);
        exitItem.setFont(font);
        open.addActionListener(action -> Platform.runLater(() -> openWindow(stage)));
        sync.addActionListener(action -> Platform.runLater(this::syncAsync));
        exitItem.addActionListener(action -> Platform.runLater(() -> exit(stage)));
        popup.add(open);
        popup.add(sync);
        popup.addSeparator();
        popup.add(exitItem);
        popup.show(trayMenuOwner(), event.getXOnScreen(), event.getYOnScreen());
    }

    private JWindow trayMenuOwner() {
        JWindow owner = trayMenuOwner;
        if (owner == null) {
            owner = new JWindow();
            owner.setAlwaysOnTop(true);
            owner.setFocusableWindowState(false);
            owner.setSize(1, 1);
            owner.setLocation(0, 0);
            try {
                owner.setOpacity(0f);
            } catch (RuntimeException ignored) {
                // translucency may be unsupported
            }
            owner.setVisible(true);
            trayMenuOwner = owner;
        }
        return owner;
    }

    private static Font trayFont() {
        String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Noto Sans CJK SC"};
        for (String name : candidates) {
            Font font = new Font(name, Font.PLAIN, 12);
            if (font.canDisplay('中')) {
                return font;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, 12);
    }

    private void openData() {
        try {
            Desktop.getDesktop().open(config.dataDir().toFile());
        } catch (Exception e) {
            showError("无法打开目录: " + e.getMessage());
        }
    }

    private void exit(Stage stage) {
        exiting = true;
        closeResources();
        stage.close();
        Platform.exit();
        System.exit(0);
    }

    private void closeResources() {
        if (statusScheduler != null) {
            statusScheduler.shutdownNow();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        if (service != null) {
            service.close();
            service = null;
        }
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException ignored) {
                // the tray may already be gone
            }
            trayIcon = null;
        }
        JWindow menuOwner = trayMenuOwner;
        trayMenuOwner = null;
        if (menuOwner != null) {
            SwingUtilities.invokeLater(menuOwner::dispose);
        }
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle("ItemSync");
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("ItemSync");
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static Path parseDataDir(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if ("--data".equals(args.get(i)) && i + 1 < args.size()) {
                Path data = PathSupport.expandUser(args.get(i + 1)).toAbsolutePath();
                try {
                    Files.createDirectories(data);
                } catch (IOException e) {
                    return null;
                }
                return data;
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(localAppData);
        Path data = base.resolve("ItemSync").toAbsolutePath();
        try {
            Files.createDirectories(data);
        } catch (IOException e) {
            return null;
        }
        return data;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
