package com.modocument.application.privacy;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The masking matrix as data (ws12, gate C2, PRIV-005): WHETHER a
 * PII-classified value is masked, per delivery channel and caller role.
 * {@link Masker} is the HOW (per-{@code PiiKind} rules);
 * {@code PiiMaskingService} applies both at the channels' choke points.
 *
 * <p>Rendered verbatim into {@code docs/security/masking-matrix.md} — a test
 * ({@code MaskingMatrixDocTest}) asserts the committed table and this class
 * never drift apart, like the role-permission matrix CSV.
 *
 * <p>Stances:
 * <ul>
 *   <li><b>EXPORT</b> and <b>REPORT</b> — masked for EVERY role (the gate's
 *       wording: NIC and account numbers masked for every role in screens,
 *       reports and exports). An export that needs true values is a
 *       disclosure and belongs on the DSR channel.</li>
 *   <li><b>SCREEN_READONLY</b> — masked for every role: a read-only consumer
 *       of job data never needs the raw identifiers.</li>
 *   <li><b>SCREEN_WORKING</b> — unmasked by default: the owner's editing
 *       wizard fixes cell values, which requires seeing them (pre-release
 *       justification in the matrix doc);
 *       {@code modocument.privacy.masking.screen-working=true} force-masks
 *       it.</li>
 *   <li><b>DSR</b> — never masked: complete disclosure to the data subject
 *       is the channel's purpose (PRIV-016), and it is admin-gated and
 *       audited.</li>
 * </ul>
 */
@Component
public class MaskingPolicy {

    /** Where a PII value is about to be delivered. */
    public enum Channel {
        /** Job/table data rendered to a READ_ONLY caller. */
        SCREEN_READONLY,
        /** The owner's editing wizard (mapping/validation/row edits). */
        SCREEN_WORKING,
        /** Generated documents and report downloads. */
        REPORT,
        /** File (csv/json/xlsx/tsv/xml) and database exports + archive copies. */
        EXPORT,
        /** Data-subject disclosure (ws14): complete by design. */
        DSR
    }

    private final MaskingProperties properties;
    /** Null = the yml switch only (unit-test wiring, the canonical matrix). */
    private final SettingsReader settings;

    public MaskingPolicy(MaskingProperties properties) {
        this(properties, null);
    }

    /**
     * ws20: SCREEN_WORKING follows the setting {@code privacy.masking.screenWorking}
     * of the caller's bank ({@code TenantContext}), read per call; with
     * nothing stored, {@code modocument.privacy.masking.screen-working}.
     */
    @Autowired
    public MaskingPolicy(MaskingProperties properties, SettingsReader settings) {
        this.properties = properties;
        this.settings = settings;
    }

    /**
     * Whether PII-classified values mask on this channel for this role.
     * {@code role} may be null (no roles claim): the answer is then the
     * most restrictive one for the channel — fail closed.
     */
    public boolean mask(Channel channel, Role role) {
        return switch (channel) {
            case EXPORT, REPORT, SCREEN_READONLY -> true;
            case SCREEN_WORKING -> screenWorkingMasked();
            case DSR -> false;
        };
    }

    /** Whether the caller's bank force-masks the owner's own editing wizard. */
    public boolean screenWorkingMasked() {
        return settings == null ? properties.isScreenWorking()
                : settings.getBoolForCurrentBank(SettingKeys.MASKING_SCREEN_WORKING);
    }

    /** {@link #screenWorkingMasked()} for one bank. */
    public boolean screenWorkingMasked(int bankId) {
        return settings == null ? properties.isScreenWorking()
                : settings.getBool(SettingKeys.MASKING_SCREEN_WORKING, bankId);
    }

    /**
     * The screen channel a caller's role lands on. Fail closed: no role →
     * read-only; only the three authoring bank roles get the working view
     * (platform admins and API clients never edit a bank's rows).
     */
    public Channel screenChannel(Role role) {
        return role == Role.ADMIN || role == Role.APPROVER || role == Role.READ_WRITE
                ? Channel.SCREEN_WORKING
                : Channel.SCREEN_READONLY;
    }

    /**
     * Canonical markdown rendering of the channel × role matrix, pinned
     * byte-for-byte inside docs/security/masking-matrix.md (between the
     * GENERATED MATRIX markers). Rendered with the DEFAULT configuration —
     * the doc describes the shipped stance, not a deployment override.
     */
    public static String renderCanonicalMatrix() {
        MaskingPolicy defaults = new MaskingPolicy(new MaskingProperties());
        StringBuilder md = new StringBuilder("| channel |");
        for (Role role : Role.values()) {
            md.append(' ').append(role.name()).append(" |");
        }
        md.append('\n').append("|---|");
        md.append("---|".repeat(Role.values().length));
        md.append('\n');
        for (Channel channel : Channel.values()) {
            md.append("| ").append(channel.name()).append(" |");
            for (Role role : Role.values()) {
                md.append(' ').append(defaults.mask(channel, role) ? "masked" : "raw").append(" |");
            }
            md.append('\n');
        }
        return md.toString();
    }
}
