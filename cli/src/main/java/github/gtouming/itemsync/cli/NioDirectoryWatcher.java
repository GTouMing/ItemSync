package github.gtouming.itemsync.cli;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;

final class NioDirectoryWatcher implements AutoCloseable {

    private final WatchService watchService;
    private final Consumer<Path> listener;
    private final Thread thread;
    private volatile boolean running = true;

    NioDirectoryWatcher(Path root, Consumer<Path> listener) throws IOException {
        Objects.requireNonNull(root, "root");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursively(root);
        this.thread = new Thread(this::loop, "itemsync-directory-watcher");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    private void registerRecursively(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
                    throws IOException {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            Path directory = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Path child = directory.resolve((Path) event.context());
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    try {
                        registerRecursively(child);
                    } catch (IOException ignored) {
                        // the directory may have disappeared
                    }
                }
                try {
                    listener.accept(child);
                } catch (RuntimeException ignored) {
                    // a listener failure must not kill the watcher
                }
            }
            key.reset();
        }
    }
}
