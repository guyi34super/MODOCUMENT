package com.modocument.domain.model;

/**
 * Delivery state of one AlertLog row (V160, ws18).
 * <ul>
 *   <li>PENDING — persisted; at least one applicable channel has not
 *       accepted it yet (first dispatch in flight, or a failed channel the
 *       minute sweep is retrying);</li>
 *   <li>SENT — every applicable channel accepted it;</li>
 *   <li>FAILED — the retry window passed with a channel still failing
 *       (audited ALERT_DELIVERY_FAILED with {@code final=true}; the log
 *       channel — the SIEM path — carries the alert regardless).</li>
 * </ul>
 */
public enum AlertStatus {
    PENDING,
    SENT,
    FAILED
}
