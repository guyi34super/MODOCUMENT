package com.modocument.infrastructure.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Root directory for file storage: uploaded files live under
 * {@code <root>/uploads} and export copies under {@code <root>/exports}.
 * Configured via modocument.storage.root (env MODOCUMENT_STORAGE_ROOT) so
 * deployments can point it at a mounted volume.
 */
@Component
public class StorageProperties {

    private final Path root;

    public StorageProperties(@Value("${modocument.storage.root:wwwroot}") String root) {
        this.root = Path.of(root);
    }

    public Path uploadsDir() {
        return root.resolve("uploads");
    }

    public Path exportsDir() {
        return root.resolve("exports");
    }
}
