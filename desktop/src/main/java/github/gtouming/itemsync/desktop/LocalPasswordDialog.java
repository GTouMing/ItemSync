package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.platform.desktop.DesktopSecurity;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

final class LocalPasswordDialog {

    private LocalPasswordDialog() {
    }

    static boolean setup(DesktopSecurity security) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("设置本机密码");
        PasswordField password = new PasswordField();
        PasswordField confirm = new PasswordField();
        Label hint = new Label("本机密码只锁定界面与配置，后台同步不需要它。\n"
                + "忘记后只能清除本机数据重置，无法找回。");
        hint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);
        VBox content = new VBox(10, password, confirm, hint, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType ok = new ButtonType("设置", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ok ? Boolean.TRUE : null);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String first = password.getText();
            if (first.isEmpty()) {
                error.setText("密码不能为空");
                event.consume();
                return;
            }
            if (!first.equals(confirm.getText())) {
                error.setText("两次输入不一致");
                event.consume();
                return;
            }
            try {
                security.setLocalPassword(first.toCharArray());
            } catch (Exception e) {
                error.setText("设置失败: " + message(e));
                event.consume();
            }
        });
        return dialog.showAndWait().orElse(false);
    }

    static boolean unlock(DesktopSecurity security) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("输入本机密码");
        PasswordField password = new PasswordField();
        Label hint = new Label("后台同步不受影响；解锁后才能查看同步根、组与冲突等信息。");
        hint.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);
        VBox content = new VBox(10, password, hint, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType ok = new ButtonType("解锁", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ok ? Boolean.TRUE : null);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (!security.verifyLocalPassword(password.getText().toCharArray())) {
                    error.setText("密码不正确");
                    event.consume();
                }
            } catch (Exception e) {
                error.setText("校验失败: " + message(e));
                event.consume();
            }
        });
        return dialog.showAndWait().orElse(false);
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
