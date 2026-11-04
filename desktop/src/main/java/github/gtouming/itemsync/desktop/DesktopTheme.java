package github.gtouming.itemsync.desktop;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.stage.Screen;

final class DesktopTheme {

    static final String STYLESHEET = stylesheet();

    private DesktopTheme() {
    }

    static <T> Dialog<T> dialog() {
        Dialog<T> dialog = new Dialog<>();
        apply(dialog);
        return dialog;
    }

    static void apply(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (STYLESHEET != null && !pane.getStylesheets().contains(STYLESHEET)) {
            pane.getStylesheets().add(STYLESHEET);
        }
        if (!pane.getStyleClass().contains("app-dialog")) {
            pane.getStyleClass().add("app-dialog");
        }
        dialog.setResizable(true);
    }

    static void fill(Dialog<?> dialog, Node content) {
        DialogPane pane = dialog.getDialogPane();
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(Math.max(180, bounds.getHeight() * 0.55));
        pane.setContent(scroll);
        pane.setMaxWidth(bounds.getWidth() * 0.92);
    }

    private static String stylesheet() {
        var url = DesktopTheme.class.getResource("/itemsync.css");
        return url == null ? null : url.toExternalForm();
    }
}
