package com.modocument.application.security;

import org.springframework.stereotype.Component;

/**
 * The separation-of-duties policy (SEC-012 role catalogue + conflict table,
 * SEC-024 three-layer enforcement) as DATA: every SoD conflict the
 * application enforces is a constant of {@link Operation}, and every
 * enforcement point goes through the ONE mechanism
 * {@link #verifyDistinct(int, int, Operation)}. Prose rendering of this
 * table: docs/security/role-catalogue-and-sod.md.
 *
 * <p><b>Why the conflicts are operation-level, not role-pair-level.</b>
 * MoDocument users hold exactly ONE {@link com.modocument.domain.model.Role}
 * (a single enum-typed column on AppUsers — {@code SodPolicyTest} asserts
 * the shape), so "toxic role combinations" (e.g. a user holding both a
 * maker and a checker role) are STRUCTURALLY impossible: there is no role
 * set to combine, and a role change (itself maker/checker-gated, ws10)
 * REPLACES the role rather than adding one. That closes the assignment-time
 * layer by construction. What a single role cannot prevent is the same
 * PERSON acting on both sides of one object — an ADMIN is maker and checker
 * in one role; an author granted APPROVER later is author and releaser in
 * one role. So the conflict table binds pairs of duties on an OBJECT:
 * <ul>
 *   <li>{@link Operation#CHANGE_APPROVAL} — maker vs. checker of a
 *       PendingChange (ws10, gate B7);</li>
 *   <li>{@link Operation#DOC_RELEASE} — author (owner) and submitter vs.
 *       releaser of a DocInstance (ws11, gate B8).</li>
 * </ul>
 *
 * <p><b>The three layers (SEC-024):</b>
 * <ol>
 *   <li><b>assignment time</b> — single-role-per-user (structural, above),
 *       and role changes go through maker/checker themselves;</li>
 *   <li><b>runtime</b> — {@code PendingChangeUseCase.approve} and
 *       {@code DocReleaseUseCase.release/reject} call
 *       {@link #verifyDistinct}; a conflict is a hard 409 with a DENIED
 *       audit row ({@link Operation#auditAction()});</li>
 *   <li><b>nightly scan</b> — {@code SodScanJob} re-verifies the invariants
 *       over the persisted data (APPLIED changes with maker == checker,
 *       RELEASED docs with releaser == owner/submitter) and audits
 *       SOD_SCAN_COMPLETED / SOD_VIOLATION_FOUND per bank.</li>
 * </ol>
 */
@Component
public class SodPolicy {

    /**
     * The SoD conflict table: each constant is one operation on which two
     * duties may never be performed by the same user.
     */
    public enum Operation {

        /** ws10/B7: the maker of a PendingChange must not be its checker. */
        CHANGE_APPROVAL(
                "PendingChange", "maker", "checker", "SELF_APPROVAL_BLOCKED",
                "Self-approval is blocked: the user who requested a change "
                        + "cannot approve it — a different user must."),

        /**
         * ws11/B8: the author (owner) or submitter of a DocInstance must not
         * be its releaser — even when they also hold the Approver role.
         */
        DOC_RELEASE(
                "DocInstance", "author/submitter", "releaser", "DOC_SELF_RELEASE_BLOCKED",
                "Self-release is blocked: the author or submitter of a report "
                        + "cannot release or reject it — a different user must.");

        private final String objectType;
        private final String firstDuty;
        private final String secondDuty;
        private final String auditAction;
        private final String denialMessage;

        Operation(String objectType, String firstDuty, String secondDuty,
                  String auditAction, String denialMessage) {
            this.objectType = objectType;
            this.firstDuty = firstDuty;
            this.secondDuty = secondDuty;
            this.auditAction = auditAction;
            this.denialMessage = denialMessage;
        }

        /** The entity the conflicting duties act on. */
        public String objectType() {
            return objectType;
        }

        public String firstDuty() {
            return firstDuty;
        }

        public String secondDuty() {
            return secondDuty;
        }

        /** The DENIED audit action the runtime layer records on a conflict. */
        public String auditAction() {
            return auditAction;
        }

        /** The message of the 409 the caller receives. */
        public String denialMessage() {
            return denialMessage;
        }
    }

    /**
     * The runtime gate, shared by every enforcement point: refuses (409 via
     * {@link SodViolationException}, an {@code IllegalStateException}) when
     * the acting user IS the operation's other party. The call site records
     * the {@link Operation#auditAction()} DENIED audit row — the evidence
     * must survive the exception, and only the call site knows the entity id.
     */
    public void verifyDistinct(int actorUserId, int otherPartyUserId, Operation operation) {
        if (actorUserId == otherPartyUserId) {
            throw new SodViolationException(operation);
        }
    }

    /**
     * An SoD conflict. Extends {@link IllegalStateException} so the global
     * and per-controller handlers map it to 409, exactly like the ws10
     * self-approval refusal always answered.
     */
    public static final class SodViolationException extends IllegalStateException {

        private final Operation operation;

        private SodViolationException(Operation operation) {
            super(operation.denialMessage());
            this.operation = operation;
        }

        public Operation operation() {
            return operation;
        }
    }
}
