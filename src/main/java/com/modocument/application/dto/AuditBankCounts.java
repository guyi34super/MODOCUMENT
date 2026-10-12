package com.modocument.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * The platform console's cross-bank audit figures (ws28): COUNTS ONLY per
 * bank for {@code [from, to)} — never a row, an actor or an entity.
 */
public record AuditBankCounts(Instant from, Instant to, List<Bank> banks) {

    public record Bank(int bankId, String code, long total, long denied, long failed) {
    }
}
