package com.modocument.infrastructure.integration.connector;

import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.ConnectionTestResult;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SFTP connector (ws25), Apache MINA SSHD client.
 * <ul>
 *   <li><b>Host key pinning</b>: the connection config carries the server's
 *       {@code hostKeyFingerprint} ({@code SHA256:<base64>}, as
 *       {@code ssh-keygen -lf} prints it); an unknown or different key is
 *       refused before authenticating. There is no trust-on-first-use.</li>
 *   <li><b>Auth</b>: {@code PASSWORD} (secret {@code password}) or
 *       {@code PRIVATE_KEY} (secret {@code privateKeyPem}, optional
 *       {@code passphrase}).</li>
 *   <li><b>Confinement</b>: every path is the configured absolute
 *       {@code folder} plus a validated relative sub-folder; listed names
 *       with a separator or dot segment are ignored; files over the size cap
 *       are skipped.</li>
 *   <li><b>Read</b> ({@code {folder, glob, afterRead:"MOVE|DELETE"}}): list,
 *       match the glob, download, then move to {@code processed/} (default)
 *       or delete.</li>
 *   <li><b>Write</b> ({@code {folder, fileNamePattern}}): upload to
 *       {@code .tmp-<uuid>} then rename to the final name (atomic on the
 *       server). An existing final name means the batch was already
 *       delivered (idempotent).</li>
 * </ul>
 * The egress guard checks the host right before connecting; connect, auth
 * and channel reads have timeouts.
 */
@Component
public class SftpIntegrationConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(SftpIntegrationConnector.class);

    static final Set<String> SOURCE_KEYS = Set.of("folder", "glob", "afterRead");
    static final Set<String> TARGET_KEYS = Set.of("folder", "fileNamePattern");
    public static final String PROCESSED_FOLDER = "processed";

    private static final Pattern ABSOLUTE_FOLDER = Pattern.compile("^/[A-Za-z0-9_.~/-]{0,500}$");
    private static final Pattern GLOB = Pattern.compile("^[A-Za-z0-9_.*?\\-\\[\\]]{1,100}$");
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,199}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^SHA256:[A-Za-z0-9+/]{43}=?$");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    enum AfterRead { MOVE, DELETE }

    private final EgressGuard egress;
    private final ConnectorLimits limits;
    private final Clock clock;

    @Autowired
    public SftpIntegrationConnector(EgressGuard egress, IntegrationConnectorProperties properties) {
        this(egress, properties.limits(), Clock.systemUTC());
    }

    public SftpIntegrationConnector(EgressGuard egress, ConnectorLimits limits, Clock clock) {
        this.egress = egress;
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

    void validateConnection(ConnectionContext connection) {
        DbDialect.requireHost(Specs.string(connection.config(), "host"));
        Specs.integer(connection.config(), "port", 1, 65_535);
        if (Specs.string(connection.config(), "username") == null) {
            throw new IllegalArgumentException("The connection setting 'username' is required.");
        }
        baseFolder(connection);
        String fingerprint = Specs.string(connection.config(), "hostKeyFingerprint");
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("'hostKeyFingerprint' must be the server's SHA256 "
                    + "host key fingerprint (SHA256:...); unknown host keys are never accepted.");
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
        if (connection.mode() == ConnectionMode.DIRECT) {
            boolean key = usesPrivateKey(connection);
            String needed = key ? "privateKeyPem" : "password";
            if (connection.secret().get(needed) == null) {
                throw new IllegalArgumentException("The connection secret has no '" + needed + "'.");
            }
        }
    }

    /** {@code sftpAuth} PRIVATE_KEY (or the legacy authType PRIVATE_KEY); otherwise password. */
    static boolean usesPrivateKey(ConnectionContext connection) {
        String sftpAuth = Specs.string(connection.config(), "sftpAuth");
        if (sftpAuth != null) {
            return sftpAuth.equalsIgnoreCase("PRIVATE_KEY");
        }
        return "PRIVATE_KEY".equalsIgnoreCase(Specs.string(connection.config(), "authType"));
    }

    static String baseFolder(ConnectionContext connection) {
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
    static String folder(ConnectionContext connection, Map<String, Object> spec) {
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
                            elapsed(started));
                }
                return ConnectionTestResult.ok("Connected; host key matched and the folder is "
                        + "reachable.", elapsed(started));
            }
        } catch (Exception e) {
            return ConnectionTestResult.failed(ConnectorErrors.safeMessage(e), elapsed(started));
        }
    }

    // ---- read ---------------------------------------------------------------------

    @Override
    public ReadBatch read(ConnectionContext connection, Map<String, Object> sourceSpec,
                          String cursor, int maxRecords) throws Exception {
        validate(connection, sourceSpec, false);
        String folder = folder(connection, sourceSpec);
        Pattern glob = globRegex(Specs.string(sourceSpec, "glob"));
        AfterRead afterRead = Specs.enumValue(sourceSpec, "afterRead", AfterRead.class, AfterRead.MOVE);
        int max = Math.max(1, maxRecords);
        List<ReadBatch.InboundFile> files = new ArrayList<>();
        boolean more = false;
        try (Session session = open(connection)) {
            SftpClient sftp = session.sftp();
            List<SftpClient.DirEntry> candidates = list(sftp, folder, glob);
            for (SftpClient.DirEntry entry : candidates) {
                if (files.size() >= max) {
                    more = true;
                    break;
                }
                String path = folder + "/" + entry.getFilename();
                long size = entry.getAttributes().getSize();
                if (size > limits.maxFileBytes()) {
                    log.warn("SFTP file skipped: larger than the {}-byte cap (connection {})",
                            limits.maxFileBytes(), connection.connectionId());
                    continue;
                }
                byte[] content;
                try (InputStream in = sftp.read(path)) {
                    content = OutboundHttp.readCapped(in, limits.maxFileBytes());
                }
                String relative = relativeTo(connection, path);
                if (afterRead == AfterRead.DELETE) {
                    sftp.remove(path);
                } else {
                    moveToProcessed(sftp, folder, entry.getFilename());
                }
                files.add(new ReadBatch.InboundFile(relative, entry.getFilename(), content));
            }
        }
        return new ReadBatch(List.of(), files, null, more);
    }

    /**
     * The matching files of a folder, by name, WITHOUT moving anything (the
     * flow preview). Returns names and sizes only.
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

    static Pattern globRegex(String glob) {
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

    /** {name} the batch file name, {key} 16 chars of the idempotency key, {timestamp} UTC. */
    String expand(String pattern, String name, String key) {
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
        egress.checkDestination(connection.bankId(), host, port);

        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, key) -> {
            String actual = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            return expected != null && actual != null && MessageDigest.isEqual(
                    expected.replaceAll("=+$", "").getBytes(StandardCharsets.US_ASCII),
                    actual.replaceAll("=+$", "").getBytes(StandardCharsets.US_ASCII));
        });
        CoreModuleProperties.IDLE_TIMEOUT.set(client, limits.readTimeout().multipliedBy(2));
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, limits.readTimeout());
        CoreModuleProperties.AUTH_TIMEOUT.set(client, limits.connectTimeout());
        client.start();
        ClientSession session = null;
        try {
            session = client.connect(username, host, port).verify(limits.connectTimeout()).getSession();
            boolean useKey = usesPrivateKey(connection);
            if (useKey) {
                String pem = connection.secret().get("privateKeyPem");
                String passphrase = connection.secret().get("passphrase");
                Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(session,
                        NamedResource.ofName("connection-" + connection.connectionId()),
                        new ByteArrayInputStream(pem == null ? new byte[0]
                                : pem.getBytes(StandardCharsets.US_ASCII)),
                        passphrase == null ? FilePasswordProvider.EMPTY
                                : FilePasswordProvider.of(passphrase));
                if (keys == null || !keys.iterator().hasNext()) {
                    throw new IllegalArgumentException("The private key could not be read.");
                }
                for (KeyPair key : keys) {
                    session.addPublicKeyIdentity(key);
                }
            } else {
                session.addPasswordIdentity(connection.secret().get("password"));
            }
            try {
                session.auth().verify(limits.connectTimeout());
            } catch (Exception e) {
                if (!session.isOpen() && e.getMessage() != null
                        && e.getMessage().toLowerCase(Locale.ROOT).contains("server key")) {
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
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("server key")) {
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

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
