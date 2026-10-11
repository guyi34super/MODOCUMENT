package com.modocument.agent.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.Protocol;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SFTP connections over Apache MINA SSHD.
 * <ul>
 *   <li>the server's host key must match the connection's pinned
 *       {@code hostKeyFingerprint} ({@code SHA256:…}, as
 *       {@code ssh-keygen -lf} prints it) — an unknown or changed key is
 *       refused, there is no trust-on-first-use;</li>
 *   <li>password or private-key auth from the local secret
 *       ({@code password} / {@code privateKeyPem});</li>
 *   <li>every path is confined to the connection's {@code folder} (and the
 *       allow-list's folders): no {@code ..}, no escaping;</li>
 *   <li>READ pulls files matching the glob (≤ 50 MB each) as records
 *       {@code {fileName, remotePath, size, contentBase64}}, then — once
 *       MoDocument accepted them — moves them to {@code processed/} or
 *       deletes them;</li>
 *   <li>WRITE uploads to a temporary {@code .name.part} and renames it
 *       (atomic for the reader).</li>
 * </ul>
 */
public class SftpConnector implements Connector {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._ -]{1,255}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private final ObjectMapper mapper;

    public SftpConnector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "SFTP";
    }

    @Override
    public void authorize(TaskContext task, AllowList allowList) {
        allowList.checkDestination(host(task), task.port(22));
        String operation = task.task().operation();
        if ("READ".equals(operation) || "WRITE".equals(operation)) {
            allowList.checkFolder(folder(task));
        } else {
            allowList.checkFolder(baseFolder(task));
        }
    }

    @Override
    public void test(TaskContext task) throws IOException {
        try (Session session = open(task)) {
            session.sftp().stat(baseFolder(task));
        }
    }

    @Override
    public ReadPage read(TaskContext task, String cursor, int max) throws IOException {
        String folder = folder(task);
        Pattern glob = glob(task.specText("glob") == null ? "*" : task.specText("glob"));
        String afterRead = task.specText("afterRead") == null ? "MOVE"
                : task.specText("afterRead").toUpperCase(Locale.ROOT);
        if (!"MOVE".equals(afterRead) && !"DELETE".equals(afterRead)) {
            throw new IllegalArgumentException("afterRead must be MOVE or DELETE.");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> pulled = new ArrayList<>();
        try (Session session = open(task)) {
            List<String> names = new ArrayList<>();
            for (SftpClient.DirEntry entry : session.sftp().readDir(folder)) {
                String name = entry.getFilename();
                if (entry.getAttributes().isRegularFile() && glob.matcher(name).matches()
                        && SEGMENT.matcher(name).matches()) {
                    if (entry.getAttributes().getSize() > MAX_FILE_BYTES) {
                        throw new IOException("File " + name + " exceeds 50 MB.");
                    }
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            for (String name : names) {
                if (records.size() >= Math.max(1, max)) {
                    break;
                }
                byte[] content;
                try (InputStream in = session.sftp().read(folder + "/" + name)) {
                    content = in.readNBytes((int) MAX_FILE_BYTES + 1);
                }
                if (content.length > MAX_FILE_BYTES) {
                    throw new IOException("File " + name + " exceeds 50 MB.");
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("fileName", name);
                record.put("remotePath", name);
                record.put("size", content.length);
                record.put("contentBase64", Base64.getEncoder().encodeToString(content));
                records.add(record);
                pulled.add(name);
            }
        }
        Runnable afterAccepted = () -> {
            try (Session session = open(task)) {
                if ("MOVE".equals(afterRead)) {
                    String processed = folder + "/processed";
                    try {
                        session.sftp().stat(processed);
                    } catch (SftpException missing) {
                        session.sftp().mkdir(processed);
                    }
                    for (String name : pulled) {
                        session.sftp().rename(folder + "/" + name, processed + "/" + name);
                    }
                } else {
                    for (String name : pulled) {
                        session.sftp().remove(folder + "/" + name);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Pulled files could not be " + ("MOVE".equals(afterRead)
                        ? "moved to processed/" : "deleted") + " (" + e.getClass().getSimpleName() + ").");
            }
        };
        return new ReadPage(records, cursor, false, afterAccepted);
    }

    @Override
    public WriteOutcome write(TaskContext task, Protocol.OutboundBatch batch) throws IOException {
        String folder = folder(task);
        String name = fileName(task, batch);
        byte[] content = batch.fileContent() != null ? batch.fileContent()
                : mapper.writeValueAsBytes(batch.records());
        String target = folder + "/" + name;
        String temp = folder + "/." + name + ".part";
        try (Session session = open(task)) {
            try (OutputStream out = session.sftp().write(temp)) {
                new ByteArrayInputStream(content).transferTo(out);
            }
            try {
                session.sftp().rename(temp, target);
            } catch (SftpException exists) {
                // Deterministic name: a retry of the same batch replaces it.
                session.sftp().remove(target);
                session.sftp().rename(temp, target);
            }
        }
        int count = batch.fileContent() != null ? Math.max(1, batch.records().size()) : batch.records().size();
        return new WriteOutcome(count, target);
    }

    // ---------------------------------------------------------------- helpers

    private Session open(TaskContext task) throws IOException {
        String host = host(task);
        int port = task.port(22);
        String pinned = task.config("hostKeyFingerprint");
        if (pinned == null || !pinned.startsWith("SHA256:")) {
            throw new IllegalArgumentException("The SFTP connection has no pinned SHA256 host key "
                    + "fingerprint; unknown host keys are refused.");
        }
        String user = task.requireConfig("username");
        SshClient client = SshClient.setUpDefaultClient();
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setServerKeyVerifier((session, address, key) ->
                pinned.equals(KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)));
        client.start();
        try {
            ClientSession session = client.connect(user, host, port).verify(TIMEOUT).getSession();
            String password = task.secret().values().get("password");
            String privateKey = task.secret().values().get("privateKeyPem");
            if (privateKey != null) {
                for (KeyPair pair : loadKeys(privateKey)) {
                    session.addPublicKeyIdentity(pair);
                }
            } else if (password != null || task.secret().get("password") != null) {
                session.addPasswordIdentity(password != null ? password : task.secret().get("password"));
            } else {
                throw new IllegalArgumentException("The local secret has no password or privateKeyPem.");
            }
            session.auth().verify(TIMEOUT);
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            return new Session(client, session, sftp);
        } catch (IOException | RuntimeException e) {
            client.stop();
            if (e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IOException("SFTP connection to " + host + ":" + port + " failed ("
                    + e.getClass().getSimpleName() + ").", e);
        }
    }

    private static Iterable<KeyPair> loadKeys(String pem) throws IOException {
        try {
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null,
                    NamedResource.ofName("local-secret"),
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)),
                    FilePasswordProvider.EMPTY);
            if (keys == null) {
                throw new IllegalArgumentException("The private key could not be read.");
            }
            return keys;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The private key could not be read.");
        }
    }

    private static String host(TaskContext task) {
        String host = task.requireConfig("host");
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("The SFTP host is not a host name.");
        }
        return host;
    }

    /** The connection's absolute base folder, normalized. */
    static String baseFolder(TaskContext task) {
        String base = normalize(task.requireConfig("folder"));
        if (!base.startsWith("/")) {
            throw new IllegalArgumentException("The SFTP connection folder must be absolute.");
        }
        return base;
    }

    /** The spec's folder (relative to, or absolute under, the base folder). */
    static String folder(TaskContext task) {
        String base = baseFolder(task);
        String wanted = task.specText("folder");
        if (wanted == null || wanted.isEmpty()) {
            return base;
        }
        String effective = wanted.startsWith("/") ? normalize(wanted)
                : normalize(("/".equals(base) ? "" : base) + "/" + wanted);
        if (!effective.equals(base) && !effective.startsWith("/".equals(base) ? "/" : base + "/")) {
            throw new IllegalArgumentException("The folder leaves the connection's folder.");
        }
        return effective;
    }

    static String normalize(String path) {
        if (path.indexOf('\0') >= 0 || path.contains("\\")) {
            throw new IllegalArgumentException("The folder is not a plain path.");
        }
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || !SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("The folder is not a plain path.");
            }
            out.append('/').append(segment);
        }
        String normalized = out.length() == 0 ? "/" : out.toString();
        return path.startsWith("/") ? normalized : normalized.substring(1);
    }

    private static String fileName(TaskContext task, Protocol.OutboundBatch batch) {
        if (batch.fileName() != null && FILE_NAME.matcher(batch.fileName()).matches()) {
            return batch.fileName();
        }
        String pattern = task.specText("fileNamePattern") == null ? "modocument-{key}.json"
                : task.specText("fileNamePattern");
        String key = batch.idempotencyKey() == null ? "batch"
                : batch.idempotencyKey().replaceAll("[^A-Za-z0-9._-]", "_");
        String name = pattern.replace("{key}", key);
        if (!FILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("The target file name is not a plain file name.");
        }
        return name;
    }

    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append("[^/]*");
                case '?' -> regex.append("[^/]");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /** One SSH connection + SFTP channel, closed together. */
    private record Session(SshClient client, ClientSession session, SftpClient sftp) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                sftp.close();
                session.close();
            } finally {
                client.stop();
            }
        }
    }
}
