package github.gtouming.itemsync.sync.apply;

import github.gtouming.itemsync.domain.identity.LogicalPath;

import java.nio.file.Path;

@FunctionalInterface
public interface PhysicalPathResolver {

    Path resolve(Path syncRoot, LogicalPath logicalPath);
}
