package com.modocument.domain.model;

/**
 * What kind of personal data a template field carries (ws12, PRIV-004): the
 * classification that drives the role-by-channel masking matrix (PRIV-005,
 * gate C2 — see {@code application/privacy/MaskingPolicy} and
 * {@code docs/security/masking-matrix.md}).
 *
 * <p>A field's kind is either tagged explicitly by the template owner
 * (persisted on {@code TemplateFields.PiiKind}, V100) or inferred once by
 * {@code PiiClassifier} from the field name, its validation rules and sample
 * values — the inferred kind is then persisted so the owner can see and
 * correct it. An explicit tag always wins over inference.
 */
public enum PiiKind {

    /** National identity card number (e.g. Mauritius NIC) — fully masked. */
    NIC,

    /** Bank/card/account number — masked to its last four characters. */
    ACCOUNT_NUMBER,

    /** Email address — local part masked to its first character. */
    EMAIL,

    /** Phone number — masked to its last two digits. */
    PHONE,

    /** Personal name — masked to its first letter. */
    NAME,

    /** Explicitly classified as NOT personal data; never masked. */
    NONE
}
