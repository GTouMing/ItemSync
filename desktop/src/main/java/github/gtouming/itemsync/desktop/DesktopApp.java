package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.model.FileVersion;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.security.NodeUnlockKey;
import github.gtouming.itemsync.platform.desktop.DesktopSecurity;
import github.gtouming.itemsync.platform.desktop.PathSupport;
import github.gtouming.itemsync.platform.desktop.WindowsAutostart;
import github.gtouming.itemsync.sync.state.ConflictResolver;
import github.gtouming.itemsync.sync.state.VersionHistoryService;
import github.gtouming.itemsync.transport.discovery.PresenceTracker;
import github.gtouming.itemsync.transport.tls.TlsIdentity;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DesktopApp extends Application {

    private DesktopConfig config;
    private DesktopSecurity security;
    private DesktopService service;
    private ScheduledExecutorService statusScheduler;
    private ExecutorService worker;
    private TrayIcon trayIcon;
    private volatile JWindow trayMenuOwner;
    private volatile boolean exiting;

    private final Label nodeLabel = new Label();
    private final Label groupLabel = new Label();
    private final Label rootLabel = new Label();
    private final Label fingerprintLabel = new Label();
    private final Label lastRunLabel = new Label();
    private final Label conflictLabel = new Label();
    private final Label errorLabel = new Label();
    private Button syncButton;
    private Button conflictsButton;
    private Button historyButton;

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
        if (!configExists) {
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
        conflictsButton = new Button("处理冲突");
        historyButton = new Button("版本历史");
        Button lanButton = new Button("局域网设备");
        Button groupButton = new Button("修改组 ID");
        Button refreshButton = new Button("刷新");
        Button openDataButton = new Button("打开数据目录");
        Button hideButton = new Button("隐藏到托盘");
        Button exitButton = new Button("退出");
        CheckBox autostart = new CheckBox("开机自启");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.add(new Label("节点"), 0, 0);
        grid.add(nodeLabel, 1, 0);
        grid.add(new Label("组 ID"), 0, 1);
        grid.add(groupLabel, 1, 1);
        grid.add(new Label("同步根"), 0, 2);
        grid.add(rootLabel, 1, 2);
        grid.add(new Label("本机指纹"), 0, 3);
        fingerprintLabel.setWrapText(true);
        grid.add(fingerprintLabel, 1, 3);
        grid.add(new Label("最近运行"), 0, 4);
        grid.add(lastRunLabel, 1, 4);

        conflictLabel.setStyle("-fx-text-fill: #9a6700;");
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setWrapText(true);

        HBox buttons = new HBox(8, syncButton, conflictsButton, historyButton, lanButton,
                groupButton, refreshButton, openDataButton, hideButton, exitButton);
        VBox root = new VBox(16, grid, conflictLabel, errorLabel, buttons, autostart);
        root.setPadding(new Insets(20));

        stage.setScene(new Scene(root, 900, 480));
        stage.setTitle("ItemSync");
        stage.setOnCloseRequest(event -> {
            if (!exiting) {
                event.consume();
                stage.hide();
            }
        });

        syncButton.setOnAction(event -> syncAsync());
        conflictsButton.setOnAction(event -> showConflictsAsync());
        historyButton.setOnAction(event -> showHistoryAsync());
        lanButton.setOnAction(event -> showPeersAsync());
        groupButton.setOnAction(event -> changeGroup(stage));
        refreshButton.setOnAction(event -> refreshAsync());
        openDataButton.setOnAction(event -> openData());
        hideButton.setOnAction(event -> stage.hide());
        exitButton.setOnAction(event -> exit(stage));

        configureAutostart(autostart);
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
                    DesktopReset.reset(config.dataDir(), config.root());
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
        if (syncButton != null) {
            syncButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                service.syncNow();
            } catch (IOException e) {
                Platform.runLater(() -> errorLabel.setText("同步失败: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> {
                    if (syncButton != null) {
                        syncButton.setDisable(false);
                    }
                    refreshAsync();
                });
            }
        });
    }

    private void refreshAsync() {
        statusScheduler.execute(this::queryStatus);
    }

    private void queryStatus() {
        try {
            DesktopService.Status status = service.status();
            Platform.runLater(() -> render(status));
        } catch (RuntimeException e) {
            Platform.runLater(() -> errorLabel.setText("状态读取失败: " + e.getMessage()));
        }
    }

    private void render(DesktopService.Status status) {
        nodeLabel.setText(status.nodeId().value() + "  (port " + status.port() + ")");
        groupLabel.setText(config.group().value());
        rootLabel.setText(status.root().toString());
        fingerprintLabel.setText(status.fingerprint());
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
        errorLabel.setText(status.lastError() == null
                ? ""
                : "最近错误: " + status.lastError().getMessage());
    }

    private void showConflictsAsync() {
        if (conflictsButton != null) {
            conflictsButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                List<ConflictResolver.ConflictView> views = service.openConflicts();
                Platform.runLater(() -> {
                    if (conflictsButton != null) {
                        conflictsButton.setDisable(false);
                    }
                    showConflicts(views);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (conflictsButton != null) {
                        conflictsButton.setDisable(false);
                    }
                    showError("读取冲突失败: " + e.getMessage());
                });
            }
        });
    }

    private void showConflicts(List<ConflictResolver.ConflictView> views) {
        if (views.isEmpty()) {
            showInfo("没有未解决的冲突");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("未解决冲突（" + views.size() + "）");

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
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_REMOTE);
        });
        localButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_LOCAL);
        });
        bothButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resolveSelected(dialog, list, ConflictResolver.Resolution.KEEP_BOTH);
        });

        dialog.showAndWait();
    }

    private void resolveSelected(Dialog<Void> dialog,
                                 ListView<ConflictResolver.ConflictView> list,
                                 ConflictResolver.Resolution resolution) {
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
                service.resolveConflict(selected.id(), resolution);
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
        if (historyButton != null) {
            historyButton.setDisable(true);
        }
        worker.submit(() -> {
            try {
                List<VersionHistoryService.ItemView> items = service.historyItems();
                Platform.runLater(() -> {
                    if (historyButton != null) {
                        historyButton.setDisable(false);
                    }
                    showHistory(items);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (historyButton != null) {
                        historyButton.setDisable(false);
                    }
                    showError("读取版本历史失败: " + e.getMessage());
                });
            }
        });
    }

    private void showHistory(List<VersionHistoryService.ItemView> items) {
        if (items.isEmpty()) {
            showInfo("同步根中没有已记录的文件版本");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("版本历史");

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
                    List<FileVersion> loaded = service.versions(item.id());
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
                    service.restoreVersion(item.id(), version.id());
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
        worker.submit(() -> {
            try {
                List<DesktopService.PeerView> peers = service.peerViews();
                Platform.runLater(() -> showPeers(peers));
            } catch (RuntimeException e) {
                Platform.runLater(() -> showError("读取在线设备失败: " + e.getMessage()));
            }
        });
    }

    private void showPeers(List<DesktopService.PeerView> peers) {
        if (peers.isEmpty()) {
            showInfo("同组内暂无在线设备");
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("局域网设备（" + peers.size() + "）");
        ListView<DesktopService.PeerView> list = new ListView<>();
        list.getItems().setAll(peers);
        list.setPrefHeight(300);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DesktopService.PeerView view, boolean empty) {
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

        Label hint = new Label("信任需要双方操作：两台设备都确认同一个 6 位短码并点信任后才会同步。"
                + "组 ID 只决定能发现哪些设备。");
        hint.setWrapText(true);

        ButtonType pair = new ButtonType("信任 / 配对", ButtonBar.ButtonData.OTHER);
        ButtonType revoke = new ButtonType("撤销信任", ButtonBar.ButtonData.OTHER);
        ButtonType refreshButtonType = new ButtonType("刷新", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(pair, revoke, refreshButtonType, close);
        VBox content = new VBox(10, list, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(780);

        Button pairButton = (Button) dialog.getDialogPane().lookupButton(pair);
        Button revokeButton = (Button) dialog.getDialogPane().lookupButton(revoke);
        Button refreshButton = (Button) dialog.getDialogPane().lookupButton(refreshButtonType);
        Runnable updateButtons = () -> {
            DesktopService.PeerView selected = list.getSelectionModel().getSelectedItem();
            pairButton.setDisable(selected == null || selected.locallyTrusted()
                    || !selected.identityVerified());
            revokeButton.setDisable(selected == null || !selected.locallyTrusted());
        };
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons.run());
        updateButtons.run();

        Runnable refresh = () -> {
            pairButton.setDisable(true);
            revokeButton.setDisable(true);
            worker.submit(() -> {
                try {
                    List<DesktopService.PeerView> refreshed = service.peerViews();
                    Platform.runLater(() -> {
                        list.getItems().setAll(refreshed);
                        if (!refreshed.isEmpty()) {
                            list.getSelectionModel().selectFirst();
                        }
                        updateButtons.run();
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        showError("刷新在线设备失败: " + e.getMessage());
                        updateButtons.run();
                    });
                }
            });
        };

        pairButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            DesktopService.PeerView selected = list.getSelectionModel().getSelectedItem();
            if (selected == null || !selected.identityVerified() || !confirmPairing(selected)) {
                return;
            }
            try {
                service.trustPeer(selected.presence().peer());
                refresh.run();
                syncAsync();
            } catch (Exception e) {
                showError("已记录本机信任，但连接失败；等待对方信任后重试: " + e.getMessage());
                refresh.run();
            }
        });
        revokeButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            DesktopService.PeerView selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            try {
                service.untrustPeer(selected.presence().peer());
                refresh.run();
            } catch (Exception e) {
                showError("撤销信任失败: " + e.getMessage());
            }
        });
        refreshButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            refresh.run();
        });

        dialog.showAndWait();
    }

    private static String trustState(DesktopService.PeerView view) {
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

    private boolean confirmPairing(DesktopService.PeerView view) {
        String code = service.pairingCode(view.presence().peer());
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("核对配对短码");
        Label codeLabel = new Label(code);
        codeLabel.setStyle("-fx-font-size: 32px; -fx-font-family: monospace;");
        Label hint = new Label("在另一台设备上对 " + view.presence().peer().deviceId().value()
                + " 执行信任，并确认两边显示相同的短码。\n确认后本机才开始信任该设备。");
        hint.setWrapText(true);
        VBox content = new VBox(12, codeLabel, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        ButtonType ok = new ButtonType("已核对，信任", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ok ? Boolean.TRUE : null);
        return dialog.showAndWait().orElse(false);
    }

    private void changeGroup(Stage stage) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("修改组 ID");
        TextField field = new TextField(config.group().value());
        Label warning = new Label("改 ID 会停止当前组同步，清除旧组信任；"
                + "本机文件和版本保留。保存后应用会退出，请重新启动。");
        warning.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);
        VBox content = new VBox(10, field, warning, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType save = new ButtonType("保存并退出", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == save ? Boolean.TRUE : null);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                GroupId group = GroupId.parse(field.getText().trim());
                if (group.equals(config.group())) {
                    error.setText("组 ID 未变化");
                    event.consume();
                    return;
                }
                DesktopConfig.changeGroup(config.dataDir(), group);
                new TrustStore(config.dataDir()).clear();
            } catch (Exception e) {
                error.setText("修改失败: " + e.getMessage());
                event.consume();
            }
        });

        if (dialog.showAndWait().orElse(false)) {
            showInfo("组 ID 已修改，请重新启动 ItemSync。");
            exit(stage);
        }
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
