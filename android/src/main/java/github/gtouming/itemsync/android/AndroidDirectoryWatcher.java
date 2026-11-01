package github.gtouming.itemsync.android;

import android.os.FileObserver;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AndroidDirectoryWatcher implements AutoCloseable {

    private static final int MASK = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MODIFY
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO
            | FileObserver.CLOSE_WRITE;

    private final File root;
    private final Runnable listener;
    private final Map<String, FileObserver> observers = new ConcurrentHashMap<>();

    public AndroidDirectoryWatcher(File root, Runnable listener) {
        this.root = root;
        this.listener = listener;
    }

    public void start() {
        register(root);
    }

    private void register(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        String key = directory.getAbsolutePath();
        if (observers.containsKey(key)) {
            return;
        }
        FileObserver observer = new FileObserver(directory, MASK) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null
                        && (event & (FileObserver.CREATE | FileObserver.MOVED_TO)) != 0) {
                    File child = new File(directory, path);
                    if (child.isDirectory()) {
                        register(child);
                    }
                }
                listener.run();
            }
        };
        observers.put(key, observer);
        observer.startWatching();
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    register(child);
                }
            }
        }
    }

    @Override
    public void close() {
        for (FileObserver observer : observers.values()) {
            observer.stopWatching();
        }
        observers.clear();
    }
}
