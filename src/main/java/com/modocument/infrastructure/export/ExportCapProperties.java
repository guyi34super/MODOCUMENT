package com.modocument.infrastructure.export;

import com.modocument.domain.model.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code modocument.export.*} — per-role export row caps (ws18, gate D2,
 * PRIV-002). An export (file download or database push) of MORE rows than
 * the caller's role cap is refused with 403 EXPORT_CAP_EXCEEDED unless the
 * caller states a reason (<= {@link #getReasonMaxLength()} characters); both
 * outcomes are audited and alerted (see {@link ExportGuard}).
 *
 * <p>Defaults (application.yml, env-overridable): READ_WRITE 5,000
 * ({@code MODOCUMENT_EXPORT_CAP_READ_WRITE}), ADMIN 50,000
 * ({@code MODOCUMENT_EXPORT_CAP_ADMIN}). A role with no entry gets cap 0 —
 * fail closed: every non-empty export needs a stated reason (APPROVER and
 * READ_ONLY cannot export at all; EXPORT_RUN is ADMIN + READ_WRITE only).
 */
@ConfigurationProperties(prefix = "modocument.export")
public class ExportCapProperties {

    private Map<Role, Integer> caps = defaults();
    private int reasonMaxLength = 500;

    private static Map<Role, Integer> defaults() {
        Map<Role, Integer> caps = new EnumMap<>(Role.class);
        caps.put(Role.READ_WRITE, 5_000);
        caps.put(Role.ADMIN, 50_000);
        return caps;
    }

    /** The row cap of a role; 0 (every non-empty export needs a reason) when unset. */
    public int capFor(Role role) {
        if (role == null) {
            return 0;
        }
        Integer cap = caps.get(role);
        return cap == null || cap < 0 ? 0 : cap;
    }

    public Map<Role, Integer> getCaps() {
        return caps;
    }

    public void setCaps(Map<Role, Integer> caps) {
        this.caps = caps;
    }

    public int getReasonMaxLength() {
        return reasonMaxLength;
    }

    public void setReasonMaxLength(int reasonMaxLength) {
        this.reasonMaxLength = reasonMaxLength;
    }
}
