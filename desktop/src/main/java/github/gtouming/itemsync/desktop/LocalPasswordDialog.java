package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.platform.desktop.DesktopSecurity;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

final class LocalPasswordDialog {

    private LocalPasswordDialog() {
    }

    static boolean setup(DesktopSecurity security) {
        Dialog<Boolean> dialog = DesktopTheme.dialog();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("设置本机密码");
        Label passwordLabel = new Label("本机密码");
        PasswordField password = new PasswordField();
        passwordLabel.setLabelFor(password);
        password.setAccessibleText("本机密码");
        Label confirmLabel = new Label("确认密码");
        PasswordField confirm = new PasswordField();
        confirmLabel.setLabelFor(confirm);
        confirm.setAccessibleText("确认密码");
        Label hint = new Label("本机密码只锁定界面与配置，后台同步不需要它。\n"
                + "忘记后只能清除本机数据重置，无法找回。");
        hint.setWrapText(true);
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        VBox content = new VBox(8, passwordLabel, password, confirmLabel, confirm, hint, error);
        content.setPadding(new Insets(10));
        DesktopTheme.fill(dialog, content);

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

    static boolean unlock(DesktopSecurity security, Runnable resetAction) {
        Dialog<Boolean> dialog = DesktopTheme.dialog();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("输入本机密码");
        Label passwordLabel = new Label("本机密码");
        PasswordField password = new PasswordField();
        passwordLabel.setLabelFor(password);
        password.setAccessibleText("本机密码");
        Label hint = new Label("后台同步不受影响；解锁后才能查看同步根、组与冲突等信息。");
        hint.setWrapText(true);
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        VBox content = new VBox(8, passwordLabel, password, hint, error);
        content.setPadding(new Insets(10));
        DesktopTheme.fill(dialog, content);

        ButtonType ok = new ButtonType("解锁", ButtonBar.ButtonData.OK_DONE);
        ButtonType forgot = new ButtonType("忘记密码？清除本机数据", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(ok, forgot, ButtonType.CANCEL);
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

        Button forgotButton = (Button) dialog.getDialogPane().lookupButton(forgot);
        forgotButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (!confirmReset()) {
                return;
            }
            try {
                resetAction.run();
            } catch (Exception e) {
                showFailure("清除本机数据失败: " + message(e) + "\n请重新启动 ItemSync 后重试。");
                Platform.exit();
                System.exit(1);
                return;
            }
            showInfo("本机数据已清除。请重新启动 ItemSync，重新设置本机密码、组 ID 并配对；同步根中的用户文件已保留。");
            Platform.exit();
            System.exit(0);
        });
        return dialog.showAndWait().orElse(false);
    }

    private static boolean confirmReset() {
        Dialog<Boolean> dialog = DesktopTheme.dialog();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("清除本机数据？");
        Label warning = new Label("会删除本机身份、密码、配置、信任、元数据和版本缓存；\n"
                + "同步根中的用户文件会保留。\n"
                + "清除后需要重新设置本机密码、组 ID 并重新配对，无法撤销。");
        warning.setWrapText(true);
        Label phraseLabel = new Label("输入“清除”确认");
        TextField phrase = new TextField();
        phraseLabel.setLabelFor(phrase);
        phrase.setAccessibleText("输入清除以确认");
        phrase.setPromptText("清除");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        VBox content = new VBox(8, warning, phraseLabel, phrase, error);
        content.setPadding(new Insets(10));
        DesktopTheme.fill(dialog, content);

        ButtonType ok = new ButtonType("确认清除", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ok ? Boolean.TRUE : null);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!"清除".equals(phrase.getText().trim())) {
                error.setText("请输入“清除”确认");
                event.consume();
            }
        });
        return dialog.showAndWait().orElse(false);
    }

    private static void showInfo(String message) {
        Dialog<Void> dialog = DesktopTheme.dialog();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("本机数据已清除");
        dialog.setContentText(message);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    private static void showFailure(String message) {
        Dialog<Void> dialog = DesktopTheme.dialog();
        dialog.setTitle("ItemSync");
        dialog.setHeaderText("清除失败");
        dialog.setContentText(message);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
