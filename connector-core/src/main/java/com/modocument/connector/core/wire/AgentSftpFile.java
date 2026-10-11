package com.modocument.connector.core.wire;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One file an agent pulled over SFTP, as it travels in
 * {@code POST /api/agent/v1/runs/{id}/records} (docs/integration/agent.md,
 * "SFTP file records"). An SFTP READ run sends one record per file:
 *
 * <pre>{@code {"fileName": "stmt-0131.csv", "remotePath": "inbox/stmt-0131.csv",
 *  "size": 2048, "contentBase64": "..."}}</pre>
 *
 * <ul>
 *   <li>{@code fileName} — required; a single path segment (no {@code /} or {@code \}).</li>
 *   <li>{@code remotePath} — optional; where the file was on the bank's server
 *       (logged on the run; defaults to {@code fileName}).</li>
 *   <li>{@code size} — optional; the byte count before encoding (informational:
 *       the server measures the decoded bytes itself).</li>
 *   <li>{@code contentBase64} — required; the file, standard base64.</li>
 * </ul>
 *
 * The server side ({@code AgentRunService}) parses records with
 * {@link #fromRecord}; the agent builds them with {@link #of} and
 * {@link #toRecord}. Both builds use this one class (connector-core), so the
 * shape cannot drift; a change here is still a protocol change.
 *
 * @param fileName      the file's name, one path segment
 * @param remotePath    the path on the bank's server (never null after parsing)
 * @param size          the decoded size the agent reported, or null
 * @param contentBase64 the file content, base64
 */
public record AgentSftpFile(String fileName, String remotePath, Long size, String contentBase64) {

    public static final String FILE_NAME = "fileName";
    public static final String REMOTE_PATH = "remotePath";
    public static final String SIZE = "size";
    public static final String CONTENT_BASE64 = "contentBase64";

    /**
     * Parses and checks one agent record.
     *
     * @param maxFileBytes the decoded-size cap ({@code modocument.integration.connectors.max-file-bytes})
     * @throws IllegalArgumentException (a 400 to the agent) for a missing or
     *                                  unsafe name, missing content, bad base64
     *                                  or a file over the cap
     */
    public static AgentSftpFile fromRecord(Map<String, Object> record, long maxFileBytes) {
        Object name = record == null ? null : record.get(FILE_NAME);
        Object content = record == null ? null : record.get(CONTENT_BASE64);
        if (!(name instanceof String fileName) || fileName.isBlank() || fileName.contains("/")
                || fileName.contains("\\") || !(content instanceof String base64)) {
            throw new IllegalArgumentException("An SFTP record needs '" + FILE_NAME + "' and '"
                    + CONTENT_BASE64 + "'.");
        }
        // Reject an oversized payload before decoding it.
        if ((long) base64.length() * 3 / 4 > maxFileBytes + 3) {
            throw tooLarge(fileName, maxFileBytes);
        }
        Object remote = record.get(REMOTE_PATH);
        Object size = record.get(SIZE);
        return new AgentSftpFile(fileName,
                remote == null || String.valueOf(remote).isBlank() ? fileName : String.valueOf(remote),
                size instanceof Number number ? number.longValue() : null,
                base64);
    }

    /**
     * The decoded bytes, checked against the cap.
     *
     * @throws IllegalArgumentException not base64, or over {@code maxFileBytes}
     */
    public byte[] decode(long maxFileBytes) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + CONTENT_BASE64 + "' of '" + fileName
                    + "' is not base64.");
        }
        if (bytes.length > maxFileBytes) {
            throw tooLarge(fileName, maxFileBytes);
        }
        return bytes;
    }

    /** The wire shape (what the agent sends). */
    public Map<String, Object> toRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put(FILE_NAME, fileName);
        record.put(REMOTE_PATH, remotePath);
        if (size != null) {
            record.put(SIZE, size);
        }
        record.put(CONTENT_BASE64, contentBase64);
        return record;
    }

    /** A record for {@code content}, as the agent builds it. */
    public static AgentSftpFile of(String fileName, String remotePath, byte[] content) {
        return new AgentSftpFile(fileName, remotePath, (long) content.length,
                Base64.getEncoder().encodeToString(content));
    }

    private static IllegalArgumentException tooLarge(String fileName, long maxFileBytes) {
        return new IllegalArgumentException("'" + fileName + "' is larger than the "
                + maxFileBytes + "-byte cap.");
    }
}
