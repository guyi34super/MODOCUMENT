package com.modocument.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON body of {@code POST /api/admin/integrations/egress-rules} (ws25): a
 * bank asks for a private-network destination. Exactly one of {@code host}
 * (DNS name or IP literal, no scheme/port/path) and {@code cidr} (e.g.
 * {@code 10.20.0.0/16}); the rule stays PENDING until two platform admins
 * approve it.
 */
public record EgressRuleRequest(@Size(max = 253) String host,
                                @Size(max = 64) String cidr,
                                @NotNull @Min(1) @Max(65535) Integer port,
                                @NotBlank @Size(max = 500) String reason) {
}
