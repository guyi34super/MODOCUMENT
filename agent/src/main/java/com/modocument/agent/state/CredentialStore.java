package com.modocument.agent.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * The credentials MoDocument issued at enrolment, kept in the agent's state
 * directory as {@code credentials.json}: the directory is 0700 and the file
 * 0600 (owner-only) on POSIX file systems, written atomically (temp file +
 * rename) so a crash never leaves half a secret behind.
 */
public class CredentialStore {

    static final String FILE = "credentials.json";

    private static final Set<PosixFilePermission> DIR_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path dir;
    private final ObjectMapper mapper;

    public CredentialStore(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public Optional<Credentials> load() {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), Credentials.class));
        } catch (IOException e) {
            throw new UncheckedIOException("The stored agent credentials are unreadable ("
                    + file + "); remove the file and enrol again.", e);
        }
    }

    public void save(Credentials credentials) {
        try {
            Files.createDirectories(dir);
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            if (posix) {
                Files.setPosixFilePermissions(dir, DIR_PERMISSIONS);
            }
            Path temp = dir.resolve(FILE + ".tmp");
            Files.deleteIfExists(temp);
            if (posix) {
                Files.createFile(temp, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } else {
                Files.createFile(temp);
            }
            Files.write(temp, mapper.writeValueAsBytes(credentials), StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
            }
            if (posix) {
                Files.setPosixFilePermissions(dir.resolve(FILE), FILE_PERMISSIONS);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the agent credentials in " + dir, e);
        }
    }

    /**
     * What the agent keeps; {@link #toString()} never prints the secret.
     * {@code authMethod} is how it enrolled: OAUTH2_CLIENT_CREDENTIALS (also
     * when absent — files written before MTLS support) with the issued
     * {@code clientSecret}, or MTLS, with no secret at all (the client
     * certificate, kept in the local secret store, is the credential).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credentials(int agentId, String clientId, String clientSecret, String tokenUrl,
                              String authMethod) {

        public Credentials(int agentId, String clientId, String clientSecret, String tokenUrl) {
            this(agentId, clientId, clientSecret, tokenUrl, null);
        }

        @JsonIgnore
        public boolean mtls() {
            return "MTLS".equals(authMethod);
        }

        @Override
        public String toString() {
            return "Credentials[agentId=" + agentId + ", clientId=" + clientId
                    + (mtls() ? ", MTLS" : "") + "]";
        }
    }
}
