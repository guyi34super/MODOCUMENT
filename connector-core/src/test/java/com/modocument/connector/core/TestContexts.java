package com.modocument.connector.core;

import com.modocument.connector.core.spi.ConnectionContext;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.DestinationGuard;
import com.modocument.connector.core.spi.SecretMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Contexts and a recording guard for the core's tests. */
public final class TestContexts {

    private TestContexts() {
    }

    /** A guard that records every question ("host h:p", "table t", "folder f", "path p"). */
    public static final class RecordingGuard implements DestinationGuard {

        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final String refuse;

        public RecordingGuard() {
            this(null);
        }

        /** Refuses every question whose text starts with {@code refuse}. */
        public RecordingGuard(String refuse) {
            this.refuse = refuse;
        }

        private void ask(String question) {
            calls.add(question);
            if (refuse != null && question.startsWith(refuse)) {
                throw new SecurityException(question + " is not allowed");
            }
        }

        @Override
        public void checkHost(String host, int port) {
            ask("host " + host + ":" + port);
        }

        @Override
        public void checkTable(String qualifiedName) {
            ask("table " + qualifiedName);
        }

        @Override
        public void checkFolder(String folder) {
            ask("folder " + folder);
        }

        @Override
        public void checkPath(String path) {
            ask("path " + path);
        }
    }

    /** A server-DIRECT-like context: secret keys required at validation. */
    public static ConnectionContext direct(ConnectionType type, Map<String, String> config,
                                           Map<String, String> secret, DestinationGuard guard) {
        return new ConnectionContext("1", 7, "test", type, true, config, SecretMaterial.of(secret), guard);
    }

    /** An agent-like context: the secret lives with the bank, checked at call time only. */
    public static ConnectionContext agent(ConnectionType type, Map<String, String> config,
                                          Map<String, String> secret, DestinationGuard guard) {
        return new ConnectionContext("agent", 7, null, type, false, config, SecretMaterial.of(secret), guard);
    }
}
