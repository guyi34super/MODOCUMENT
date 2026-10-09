package com.modocument.domain.model;

/**
 * How urgent a security alert is (ws18, SEC-032). Ordered: every channel has
 * a minimum severity ({@code modocument.alerts.*.min-severity}) and receives
 * an alert only when it is {@link #atLeast} that minimum. The defaults
 * deliver every alert on every configured channel — the shipped rule
 * catalogue is curated to be low-noise, so even INFO alerts (an over-cap
 * export with a stated reason — still a large export, gate D1) reach a
 * person.
 */
public enum AlertSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean atLeast(AlertSeverity minimum) {
        return minimum == null || compareTo(minimum) >= 0;
    }
}
