package com.modocument.infrastructure.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Root directory of the LOCAL file store ({@code modocument.storage.root},
 * env MODOCUMENT_STORAGE_ROOT, default {@code wwwroot}): uploaded files live
 * under {@code <root>/uploads}, export copies under {@code <root>/exports}.
 * Code never resolves paths under it directly any more — every read, write
 * and delete goes through the {@link com.modocument.application.port.FileStore}
 * port (optimization ws29), which is this directory only when
 * {@code modocument.storage.backend=local}.
 */
@Component
public class StorageProperties {

    private final Path root;

    public StorageProperties(@Value("${modocument.storage.root:wwwroot}") String root) {
        this.root = Path.of(root);
    }

    public Path root() {
        return root;
    }
}
