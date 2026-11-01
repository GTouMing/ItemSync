package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.platform.desktop.PathSupport;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class DesktopSetupDialog {

    private DesktopSetupDialog() {
    }

    public record Result(String password) {
    }

    public static Result show(Path dataDir) {
        boolean identityExists = Files.isRegularFile(dataDir.resolve("identity.p12"));
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("设置同步根与身份");
        dialog.setResizable(true);

        TextField rootField = new TextField(dataDir.resolve("root").toString());
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
            chooser.setInitialDirectory(
                    current != null && Files.isDirectory(current) ? current.toFile() : dataDir.toFile());
            Window owner = dialog.getDialogPane().getScene().getWindow();
            File selected = chooser.showDialog(owner);
            if (selected != null) {
                rootField.setText(selected.getAbsolutePath());
            }
        });
        HBox rootBox = new HBox(8, rootField, browse);
        HBox.setHgrow(rootField, Priority.ALWAYS);

        TextField groupField = new TextField(GroupId.random().value());
        TextField rootIdField = new TextField("root-1");
        TextField portField = new TextField("47800");
        TextField peerField = new TextField();
        peerField.setPromptText("host:port:指纹，可留空");
        PasswordField passwordField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        confirmField.setDisable(identityExists);

        Label hint = new Label((identityExists
                ? "已有身份文件，请输入原密钥库口令。\n"
                : "将创建本机身份文件，并用该口令加密保存。\n")
                + "密钥库口令不是账号密码，不会通过网络传输。\n"
                + "邻居可留空；填写后会用其指纹作为本机允许连接的邻居。");
        hint.setWrapText(true);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("同步根"), rootBox);
        grid.addRow(1, new Label("组 ID"), groupField);
        grid.addRow(2, new Label("同步根 ID"), rootIdField);
        grid.addRow(3, new Label("端口"), portField);
        grid.addRow(4, new Label("邻居"), peerField);
        grid.addRow(5, new Label("密钥库口令"), passwordField);
        grid.addRow(6, new Label("确认口令"), confirmField);

        VBox content = new VBox(12, grid, hint, errorLabel);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(580);

        ButtonType create = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == create
                ? new Result(passwordField.getText()) : null);

        Button createButton = (Button) dialog.getDialogPane().lookupButton(create);
        createButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Path root = PathSupport.expandUser(rootField.getText()).toAbsolutePath();
                GroupId group = GroupId.parse(groupField.getText().trim());
                SyncRootId rootId = SyncRootId.of(rootIdField.getText().trim());
                int port = Integer.parseInt(portField.getText().trim());
                String password = passwordField.getText();
                if (password == null || password.isEmpty()) {
                    throw new IllegalArgumentException("密钥库口令不能为空");
                }
                if (!identityExists && !password.equals(confirmField.getText())) {
                    throw new IllegalArgumentException("两次输入的口令不一致");
                }
                List<DesktopConfig.PeerTarget> peers = DesktopConfig.parsePeers(peerField.getText());
                String expected = peers.isEmpty() ? "none" : peers.get(0).fingerprint();
                DesktopConfig.create(dataDir, root, group, rootId, port, expected, peers,
                        "", Duration.ofSeconds(30));
            } catch (Exception e) {
                errorLabel.setText(e.getMessage());
                event.consume();
            }
        });

        return dialog.showAndWait().orElse(null);
    }
}
