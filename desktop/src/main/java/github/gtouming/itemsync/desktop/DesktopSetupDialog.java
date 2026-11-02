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

public final class DesktopSetupDialog {

    private DesktopSetupDialog() {
    }

    public static boolean show(Path dataDir) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("设置同步根");
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

        Label hint = new Label("本机密码已单独设置；身份由 Windows DPAPI 中的 NUK 静默解锁。\n"
                + "同组设备会通过局域网发现，在“局域网设备”里用 6 位短码完成信任。");
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

        VBox content = new VBox(12, grid, hint, errorLabel);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(580);

        ButtonType create = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == create ? Boolean.TRUE : null);

        Button createButton = (Button) dialog.getDialogPane().lookupButton(create);
        createButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Path root = PathSupport.expandUser(rootField.getText()).toAbsolutePath();
                GroupId group = GroupId.parse(groupField.getText().trim());
                SyncRootId rootId = SyncRootId.of(rootIdField.getText().trim());
                int port = Integer.parseInt(portField.getText().trim());
                DesktopConfig.create(dataDir, root, group, rootId, port, "",
                        Duration.ofSeconds(30));
            } catch (Exception e) {
                errorLabel.setText(e.getMessage());
                event.consume();
            }
        });

        return dialog.showAndWait().orElse(false);
    }
}
