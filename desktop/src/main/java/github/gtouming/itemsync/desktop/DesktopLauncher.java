package github.gtouming.itemsync.desktop;

import javafx.application.Application;

public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(DesktopApp.class, args);
    }
}
