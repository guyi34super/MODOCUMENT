package com.modocument.connector.core.sftp;

import com.modocument.connector.core.db.DbDialect;
import com.modocument.connector.core.rest.OutboundHttp;
import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionTestResult;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.Connector;
import com.modocument.connector.core.spi.Operation;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.spi.ReadAcknowledgement;
import com.modocument.connector.core.spi.ReadBatch;
import com.modocument.connector.core.spi.WriteResult;
import com.modocument.connector.core.support.ConnectorErrors;
import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.support.Specs;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * SFTP connector, Apache MINA SSHD client.
 * <ul>
 *   <li><b>Host key pinning</b>: the connection config carries the server's
 *       {@code hostKeyFingerprint} ({@code SHA256:<base64>}, as
 *       {@code ssh-keygen -lf} prints it); an unknown or different key is
 *       refused before authenticating. There is no trust-on-first-use, and the
 *       client reads no {@code ~/.ssh} config or default identities.</li>
 *   <li><b>Auth</b>: {@code PASSWORD} (secret {@code password}) or
 *       {@code PRIVATE_KEY} (secret {@code privateKeyPem}, optional
 *       {@code passphrase}), picked by {@code sftpAuth} (or the legacy
 *       {@code authType PRIVATE_KEY}); without either setting, a secret that
 *       holds only a {@code privateKeyPem} uses it, anything else the
 *       password.</li>
 *   <li><b>Confinement</b>: every path is the configured absolute
 *       {@code folder} plus a validated relative sub-folder; listed names
 *       with a separator, a leading dot or a control character, and anything
 *       that is not a regular file (a symbolic link included), are
 *       ignored.</li>
 *   <li><b>Read</b> ({@code {folder, glob, afterRead:"MOVE|DELETE"}}): list,
 *       match the glob, download (sorted by name). Files over the size cap
 *       are skipped, left in place and reported in {@link ReadBatch#notes()}.
 *       Moving to {@code processed/} (default) or deleting is the read's
 *       SECOND phase ({@link ReadBatch#acknowledgement()}): the caller runs
 *       it once the files are safely stored, so a failed upload or ingestion
 *       never loses a file.</li>
 *   <li><b>Write</b> ({@code {folder, fileNamePattern}}): upload to
 *       {@code .tmp-<uuid>} then rename to the final name (atomic on the
 *       server). An existing final name means the batch was already
 *       delivered (idempotent).</li>
 * </ul>
 * The destination guard checks the host right before connecting; connect,
 * auth and channel reads have timeouts.
 */
public class SftpConnector implements Connector {

    public static final Set<String> SOURCE_KEYS = Set.of("folder", "glob", "afterRead");
    public static final Set<String> TARGET_KEYS = Set.of("folder", "fileNamePattern");
    public static final String PROCESSED_FOLDER = "processed";

    private static final Pattern ABSOLUTE_FOLDER = Pattern.compile("^/[A-Za-z0-9_.~/-]{0,500}$");
    private static final Pattern GLOB = Pattern.compile("^[A-Za-z0-9_.*?\\-\\[\\]]{1,100}$");
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,199}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^SHA256:[A-Za-z0-9+/]{43}=?$");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** {@code afterRead}. */
    public enum AfterRead { MOVE, DELETE }

    private final ConnectorLimits limits;
    private final Clock clock;

    public SftpConnector(ConnectorLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public ConnectionType type() {
        return ConnectionType.SFTP;
    }

    // ---- validation --------------------------------------------------------------

    @Override
    public void validate(ConnectionContext connection, Map<String, Object> spec, boolean outbound) {
        validateConnection(connection);
        if (outbound) {
            Specs.allowOnly(spec, "target spec", TARGET_KEYS);
            Specs.relativePath(Specs.string(spec, "folder"), "folder", false);
            String pattern = Specs.string(spec, "fileNamePattern");
            if (pattern != null) {
                requireFileName(expand(pattern, "sample.csv", "0123456789abcdef"));
            }
            return;
        }
        Specs.allowOnly(spec, "source spec", SOURCE_KEYS);
        Specs.relativePath(Specs.string(spec, "folder"), "folder", false);
        String glob = Specs.string(spec, "glob");
        if (glob != null && !GLOB.matcher(glob).matches()) {
            throw new IllegalArgumentException("'glob' must be a file-name pattern such as *.csv "
                    + "(no path separators).");
        }
        Specs.enumValue(spec, "afterRead", AfterRead.class, AfterRead.MOVE);
    }

    /** The connection settings (and, when required, the secret's password or key). */
    public void validateConnection(ConnectionContext connection) {
        DbDialect.requireHost(Specs.string(connection.config(), "host"));
        Specs.integer(connection.config(), "port", 1, 65_535);
        if (Specs.string(connection.config(), "username") == null) {
            throw new IllegalArgumentException("The connection setting 'username' is required.");
        }
        baseFolder(connection);
        String fingerprint = Specs.string(connection.config(), "hostKeyFingerprint");
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("'hostKeyFingerprint' must be the server's pinned "
                    + "SHA256 host key fingerprint (SHA256:...); unknown host keys are never accepted.");
        }
        String auth = Specs.string(connection.config(), "authType");
        if (auth != null && !auth.equalsIgnoreCase("BASIC") && !auth.equalsIgnoreCase("PASSWORD")
                && !auth.equalsIgnoreCase("PRIVATE_KEY")) {
            throw new IllegalArgumentException("SFTP auth type must be BASIC (the 'sftpAuth' "
                    + "setting picks PASSWORD or PRIVATE_KEY).");
        }
        String sftpAuth = Specs.string(connection.config(), "sftpAuth");
        if (sftpAuth != null && !sftpAuth.equalsIgnoreCase("PASSWORD")
                && !sftpAuth.equalsIgnoreCase("PRIVATE_KEY")) {
            throw new IllegalArgumentException("'sftpAuth' must be PASSWORD or PRIVATE_KEY.");
        }
        if (connection.secretRequired()) {
            String needed = usesPrivateKey(connection) ? "privateKeyPem" : "password";
            if (connection.secret().get(needed) == null) {
                throw new IllegalArgumentException("The connection secret has no '" + needed + "'.");
            }
        }
    }

    /** Host and port, then the folder the operation touches, then the validation. */
    @Override
    public void authorize(ConnectionContext connection, Map<String, Object> spec, Operation operation) {
        String host = DbDialect.requireHost(Specs.string(connection.config(), "host"));
        Integer port = Specs.integer(connection.config(), "port", 1, 65_535);
        connection.guard().checkHost(host, port == null ? 22 : port);
        if (operation == Operation.TEST) {
            connection.guard().checkFolder(baseFolder(connection));
            validateConnection(connection);
            return;
        }
        connection.guard().checkFolder(folder(connection, spec));
        validate(connection, spec, operation == Operation.WRITE);
    }

    /**
     * {@code sftpAuth} PRIVATE_KEY (or the legacy authType PRIVATE_KEY);
     * without a setting, a secret holding a key and no password.
     */
    public static boolean usesPrivateKey(ConnectionContext connection) {
        String sftpAuth = Specs.string(connection.config(), "sftpAuth");
        if (sftpAuth != null) {
            return sftpAuth.equalsIgnoreCase("PRIVATE_KEY");
        }
        if ("PRIVATE_KEY".equalsIgnoreCase(Specs.string(connection.config(), "authType"))) {
            return true;
        }
        return connection.secret().contains("privateKeyPem") && !connection.secret().contains("password");
    }

    /** The connection's absolute base folder, normalized (no trailing slash). */
    public static String baseFolder(ConnectionContext connection) {
        String folder = Specs.string(connection.config(), "folder");
        if (folder == null || !ABSOLUTE_FOLDER.matcher(folder).matches()) {
            throw new IllegalArgumentException("'folder' must be an absolute remote path such as "
                    + "/upload/modocument.");
        }
        String relative = Specs.relativePath(folder, "folder", true);
        return relative.isEmpty() ? "/" : "/" + (relative.endsWith("/")
                ? relative.substring(0, relative.length() - 1) : relative);
    }

    /** The confined remote folder: base + validated relative sub-folder. */
    public static String folder(ConnectionContext connection, Map<String, Object> spec) {
        String base = baseFolder(connection);
        String sub = Specs.relativePath(Specs.string(spec, "folder"), "folder", false);
        if (sub.endsWith("/")) {
            sub = sub.substring(0, sub.length() - 1);
        }
        if (sub.isEmpty()) {
            return base;
        }
        return base.equals("/") ? "/" + sub : base + "/" + sub;
    }

    private static String requireFileName(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("The file name pattern must produce a plain file "
                    + "name (letters, digits, . _ -).");
        }
        return name;
    }

    // ---- test ---------------------------------------------------------------------

    @Override
    public ConnectionTestResult test(ConnectionContext connection) {
        long started = System.nanoTime();
        try {
            validateConnection(connection);
            try (Session session = open(connection)) {
                SftpClient.Attributes attributes = session.sftp().stat(baseFolder(connection));
                if (!attributes.isDirectory()) {
                    return ConnectionTestResult.failed("The configured folder is not a directory.",
                            Connector.elapsedMillis(started));
                }
                return ConnectionTestResult.ok("Connected; host key matched and the folder is "
                        + "reachable.", Connector.elapsedMillis(started));
            }
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e),
                    Connector.elapsedMillis(started));
        }
    }

    // ---- read ---------------------------------------------------------------------

    /**
     * Downloads up to {@code maxRecords} matching files; moves or deletes
     * nothing — that is the returned batch's acknowledgement.
     */
    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        validate(connection, sourceSpec, false);
        String folder = folder(connection, sourceSpec);
        Pattern glob = globRegex(Specs.string(sourceSpec, "glob"));
        AfterRead afterRead = Specs.enumValue(sourceSpec, "afterRead", AfterRead.class, AfterRead.MOVE);
        int max = Math.max(1, maxRecords);
        List<ReadBatch.InboundFile> files = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> pulled = new LinkedHashMap<>();
        boolean more = false;
        try (Session session = open(connection)) {
            SftpClient sftp = session.sftp();
            for (SftpClient.DirEntry entry : list(sftp, folder, glob)) {
                if (files.size() >= max) {
                    more = true;
                    break;
                }
                String path = folder + "/" + entry.getFilename();
                long size = entry.getAttributes().getSize();
                if (size > limits.maxFileBytes()) {
                    notes.add("Skipped " + entry.getFilename() + ": larger than the "
                            + limits.maxFileBytes() + "-byte cap; it stays in the folder.");
                    continue;
                }
                byte[] content;
                try (InputStream in = sftp.read(path)) {
                    content = OutboundHttp.readCapped(in, limits.maxFileBytes(), "file");
                }
                String relative = relativeTo(connection, path);
                pulled.put(relative, entry.getFilename());
                files.add(new ReadBatch.InboundFile(relative, entry.getFilename(), content));
            }
        }
        ReadAcknowledgement acknowledgement = remotePaths -> afterRead(connection, folder, afterRead,
                pulled, remotePaths);
        return new ReadBatch(List.of(), files, null, more, notes, acknowledgement);
    }

    /** The second phase: moves (or deletes) the acknowledged files that this read pulled. */
    private void afterRead(ConnectionContext connection, String folder, AfterRead afterRead,
                           Map<String, String> pulled, Collection<String> remotePaths) throws Exception {
        List<String> names = remotePaths.stream().map(pulled::get)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (names.isEmpty()) {
            return;
        }
        int failed = 0;
        try (Session session = open(connection)) {
            for (String name : names) {
                try {
                    if (afterRead == AfterRead.DELETE) {
                        session.sftp().remove(folder + "/" + name);
                    } else {
                        moveToProcessed(session.sftp(), folder, name);
                    }
                } catch (IOException e) {
                    failed++;
                }
            }
        }
        if (failed > 0) {
            throw new IllegalStateException(failed + " pulled file(s) could not be "
                    + (afterRead == AfterRead.DELETE ? "deleted" : "moved to " + PROCESSED_FOLDER + "/")
                    + "; they will be read again.");
        }
    }

    /**
     * The matching files of a folder, by name, WITHOUT reading or moving
     * anything (the flow preview). Returns names and sizes only.
     */
    public List<Map<String, Object>> listFiles(ConnectionContext connection,
                                               Map<String, Object> sourceSpec, int max)
            throws Exception {
        validate(connection, sourceSpec, false);
        String folder = folder(connection, sourceSpec);
        Pattern glob = globRegex(Specs.string(sourceSpec, "glob"));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Session session = open(connection)) {
            for (SftpClient.DirEntry entry : list(session.sftp(), folder, glob)) {
                if (rows.size() >= max) {
                    break;
                }
                rows.add(Map.of("fileName", entry.getFilename(),
                        "size", String.valueOf(entry.getAttributes().getSize())));
            }
        }
        return rows;
    }

    private static List<SftpClient.DirEntry> list(SftpClient sftp, String folder, Pattern glob)
            throws IOException {
        List<SftpClient.DirEntry> matches = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(folder)) {
            String name = entry.getFilename();
            if (name == null || name.isEmpty() || name.startsWith(".") || name.contains("/")
                    || name.contains("\\") || name.chars().anyMatch(c -> c < 0x20)) {
                continue;
            }
            // readDir reports lstat attributes: a symbolic link is not a regular file.
            if (!entry.getAttributes().isRegularFile() || !glob.matcher(name).matches()) {
                continue;
            }
            matches.add(entry);
        }
        matches.sort(Comparator.comparing(SftpClient.DirEntry::getFilename));
        return matches;
    }

    private void moveToProcessed(SftpClient sftp, String folder, String name) throws IOException {
        String processed = folder + "/" + PROCESSED_FOLDER;
        try {
            sftp.mkdir(processed);
        } catch (IOException exists) {
            // already there (or not creatable: the rename below reports it)
        }
        String target = processed + "/" + name;
        if (exists(sftp, target)) {
            target = target + "." + clock.millis();
        }
        sftp.rename(folder + "/" + name, target);
    }

    /** A file-name glob ({@code *}, {@code ?}) as a regular expression; null means {@code *}. */
    public static Pattern globRegex(String glob) {
        String value = glob == null ? "*" : glob;
        StringBuilder regex = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '*' -> regex.append("[^/]*");
                case '?' -> regex.append("[^/]");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static String relativeTo(ConnectionContext connection, String path) {
        String base = baseFolder(connection);
        String relative = path.startsWith(base) ? path.substring(base.length()) : path;
        return relative.startsWith("/") ? relative.substring(1) : relative;
    }

    // ---- write ----------------------------------------------------------------------

    @Override
    public WriteResult write(ConnectionContext connection, Map<String, Object> targetSpec,
                             OutboundBatch batch) throws Exception {
        validate(connection, targetSpec, true);
        if (batch.fileContent() == null) {
            throw new IllegalArgumentException("An SFTP target needs a file batch (format CSV, JSON, "
                    + "XLSX or PDF).");
        }
        if (batch.fileContent().length > limits.maxFileBytes()) {
            throw new IllegalStateException("The file is larger than the " + limits.maxFileBytes()
                    + "-byte cap.");
        }
        String folder = folder(connection, targetSpec);
        String pattern = Specs.string(targetSpec, "fileNamePattern");
        String finalName = requireFileName(expand(pattern == null ? "{name}" : pattern,
                batch.fileName() == null ? "batch.bin" : batch.fileName(),
                batch.idempotencyKey() == null ? "" : batch.idempotencyKey()));
        String finalPath = folder + "/" + finalName;
        String tempPath = folder + "/.tmp-" + UUID.randomUUID();
        try (Session session = open(connection)) {
            SftpClient sftp = session.sftp();
            if (exists(sftp, finalPath)) {
                // Same deterministic name: this batch was delivered before.
                return new WriteResult(batch.records().size(), finalName + " (already present)");
            }
            try {
                try (OutputStream out = sftp.write(tempPath);
                     InputStream in = new ByteArrayInputStream(batch.fileContent())) {
                    in.transferTo(out);
                }
                sftp.rename(tempPath, finalPath);
            } catch (IOException | RuntimeException e) {
                try {
                    sftp.remove(tempPath);
                } catch (IOException ignored) {
                    // best effort: the temp name never matches a reader's glob
                }
                throw e;
            }
        }
        return new WriteResult(Math.max(1, batch.records().size()), finalName);
    }

    /** {name} the batch file name, {base}/{ext} its parts, {key} 16 chars of the idempotency key, {timestamp} UTC. */
    public String expand(String pattern, String name, String key) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot + 1);
        }
        return pattern.replace("{name}", name)
                .replace("{base}", base)
                .replace("{ext}", ext)
                .replace("{key}", key.length() > 16 ? key.substring(0, 16) : key)
                .replace("{timestamp}", STAMP.format(clock.instant()));
    }

    private static boolean exists(SftpClient sftp, String path) {
        try {
            sftp.stat(path);
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    // ---- session ------------------------------------------------------------------------

    /** An authenticated SFTP session whose host key matched the pinned fingerprint. */
    Session open(ConnectionContext connection) throws Exception {
        String host = Specs.string(connection.config(), "host");
        Integer configuredPort = Specs.integer(connection.config(), "port", 1, 65_535);
        int port = configuredPort == null ? 22 : configuredPort;
        String username = Specs.string(connection.config(), "username");
        String expected = Specs.string(connection.config(), "hostKeyFingerprint");
        boolean useKey = usesPrivateKey(connection);
        // Call-time secret check (the agent's local secret is not validated up front).
        String credential = connection.requireSecret(useKey ? "privateKeyPem" : "password");
        connection.guard().checkHost(host, port);

        SshClient client = SshClient.setUpDefaultClient();
        // Nothing from the process's ~/.ssh: no host config, no default identities.
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        AtomicBoolean keyRefused = new AtomicBoolean();
        client.setServerKeyVerifier((session, address, key) -> {
            String actual = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            boolean match = expected != null && actual != null && MessageDigest.isEqual(
                    expected.replaceAll("=+$", "").getBytes(StandardCharsets.US_ASCII),
                    actual.replaceAll("=+$", "").getBytes(StandardCharsets.US_ASCII));
            keyRefused.set(!match);
            return match;
        });
        CoreModuleProperties.IDLE_TIMEOUT.set(client, limits.readTimeout().multipliedBy(2));
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, limits.readTimeout());
        CoreModuleProperties.AUTH_TIMEOUT.set(client, limits.connectTimeout());
        client.start();
        ClientSession session = null;
        try {
            session = client.connect(username, host, port).verify(limits.connectTimeout()).getSession();
            if (useKey) {
                String passphrase = connection.secret().get("passphrase");
                Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(session,
                        NamedResource.ofName("connection-" + connection.connectionId()),
                        new ByteArrayInputStream(credential.getBytes(StandardCharsets.US_ASCII)),
                        passphrase == null ? FilePasswordProvider.EMPTY
                                : FilePasswordProvider.of(passphrase));
                if (keys == null || !keys.iterator().hasNext()) {
                    throw new IllegalArgumentException("The private key could not be read.");
                }
                for (KeyPair key : keys) {
                    session.addPublicKeyIdentity(key);
                }
            } else {
                session.addPasswordIdentity(credential);
            }
            try {
                session.auth().verify(limits.connectTimeout());
            } catch (Exception e) {
                if (keyRefused.get()) {
                    throw new SecurityException("The server's host key does not match the pinned "
                            + "fingerprint; refused.");
                }
                throw new SecurityException("SFTP authentication failed.");
            }
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            return new Session(client, session, sftp);
        } catch (Exception e) {
            if (session != null) {
                session.close(true);
            }
            client.stop();
            if (e instanceof SecurityException || e instanceof IllegalArgumentException) {
                throw e;
            }
            if (keyRefused.get()) {
                throw new SecurityException("The server's host key does not match the pinned "
                        + "fingerprint; refused.");
            }
            throw e;
        }
    }

    /** One open client + session + SFTP channel, closed together. */
    record Session(SshClient client, ClientSession session, SftpClient sftp) implements AutoCloseable {

        @Override
        public void close() {
            try {
                sftp.close();
            } catch (IOException ignored) {
                // closing
            }
            session.close(true);
            client.stop();
        }
    }
}
