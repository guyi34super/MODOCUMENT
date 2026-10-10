package com.modocument.agent.secrets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local secrets by secretRef: environment first, then a file or a per-key folder. */
class LocalSecretsTest {

    @TempDir
    Path dir;

    private LocalSecrets secrets(Map<String, String> env) {
        return new LocalSecrets(dir, env::get, new ObjectMapper());
    }

    @Test
    void environmentVariablesWinAndMayHoldJson() {
        LocalSecrets secrets = secrets(Map.of(
                "MODOCUMENT_SECRET_CORE_DB", "plain-pw",
                "MODOCUMENT_SECRET_CRM_API", "{\"token\":\"t-1\",\"apiKey\":\"k-1\"}"));
        assertThat(secrets.resolve("core-db").get("password")).isEqualTo("plain-pw");
        assertThat(secrets.resolve("crm.api").get("token")).isEqualTo("t-1");
        assertThat(secrets.resolve("crm.api").get("apiKey")).isEqualTo("k-1");
        assertThat(secrets.resolve(null).values()).isEmpty();
    }

    @Test
    void filesAndPerKeyFoldersAreReadWithoutTrailingNewlines() throws Exception {
        Files.writeString(dir.resolve("sftp-out"), "file-pw\n");
        Files.createDirectories(dir.resolve("hr-db"));
        Files.writeString(dir.resolve("hr-db").resolve("password"), "folder-pw\r\n");
        Files.writeString(dir.resolve("hr-db").resolve("privateKeyPem"), "-----BEGIN-----\n");

        LocalSecrets secrets = secrets(Map.of());
        assertThat(secrets.resolve("sftp-out").get("password")).isEqualTo("file-pw");
        LocalSecrets.Secret hr = secrets.resolve("hr-db");
        assertThat(hr.get("password")).isEqualTo("folder-pw");
        assertThat(hr.values()).containsKey("privateKeyPem");
    }

    @Test
    void refsThatAreNotPlainNamesOrDoNotExistAreRefused() {
        LocalSecrets secrets = secrets(Map.of());
        assertThatThrownBy(() -> secrets.resolve("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.resolve("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.resolve("missing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODOCUMENT_SECRET_");
    }
}
