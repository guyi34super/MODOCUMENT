package com.modocument.connector.core.support;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * Turns a connector failure into a message that is safe to show an admin,
 * store on a run and write to a log (ws25): a fixed phrase for the common
 * network/auth failures, otherwise the exception type plus a scrubbed,
 * truncated first line. Never a stack trace; driver and HTTP messages pass
 * {@link Redaction#credentials} (plus the caller's own scrub, see
 * {@link #safeMessage(Throwable, UnaryOperator)}) so credential-shaped text
 * cannot leak.
 */
public final class ConnectorErrors {

    private static final int MAX = 300;

    private ConnectorErrors() {
    }

    public static String safeMessage(Throwable error) {
        return safeMessage(error, UnaryOperator.identity());
    }

    /**
     * As {@link #safeMessage(Throwable)}, with {@code scrub} applied to the
     * message text before it is truncated (the server passes its full PII
     * scrub).
     */
    public static String safeMessage(Throwable error, UnaryOperator<String> scrub) {
        Throwable root = error;
        for (int depth = 0; depth < 8 && root.getCause() != null && root.getCause() != root; depth++) {
            if (root instanceof SecurityException || root instanceof IllegalArgumentException
                    || root instanceof IllegalStateException) {
                break;
            }
            root = root.getCause();
        }
        if (root instanceof UnknownHostException) {
            return "The host could not be resolved.";
        }
        if (root instanceof HttpConnectTimeoutException) {
            return "Connecting timed out.";
        }
        if (root instanceof HttpTimeoutException || root instanceof SocketTimeoutException
                || root instanceof TimeoutException) {
            return "The call timed out.";
        }
        if (root instanceof ConnectException) {
            return "The connection was refused or could not be opened.";
        }
        if (root instanceof SecurityException) {
            return clip(scrub, root.getMessage() == null ? "The destination is not allowed." : root.getMessage());
        }
        if (root instanceof SQLException sql) {
            String state = sql.getSQLState() == null ? "" : " (SQLState " + sql.getSQLState() + ")";
            return clip(scrub, "Database error" + state + ": " + firstLine(sql.getMessage()));
        }
        if (root instanceof IllegalArgumentException || root instanceof IllegalStateException
                || root instanceof UnsupportedOperationException) {
            return clip(scrub, firstLine(root.getMessage()));
        }
        return clip(scrub, root.getClass().getSimpleName() + ": " + firstLine(root.getMessage()));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "no detail";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String clip(UnaryOperator<String> scrub, String message) {
        String scrubbed = scrub.apply(Redaction.credentials(message));
        return scrubbed.length() <= MAX ? scrubbed : scrubbed.substring(0, MAX) + "...";
    }
}
