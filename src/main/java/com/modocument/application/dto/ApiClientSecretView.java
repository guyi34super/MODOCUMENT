package com.modocument.application.dto;

/**
 * A client's secret, shown exactly once (ws26): the initial secret (read by
 * the maker after approval) or a rotated one. For an API_KEY client
 * {@code secret} is the whole key, {@code <prefix>.<secret>}, ready to send
 * as {@code Authorization: ApiKey <secret>}.
 */
public record ApiClientSecretView(String clientId, String secret) {

    @Override
    public String toString() {
        return "ApiClientSecretView[clientId=" + clientId + ", secret=<redacted>]";
    }
}
