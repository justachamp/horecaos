package uz.qoida.platform.audit.api;

/**
 * Audit classes differ only in retention and access, never in immutability
 * (ADR 0027).
 */
public enum AuditClass {

    /** Authentication, authorization, grants, secrets, and identity changes. */
    SECURITY,

    /** State transitions, approvals, monetary actions, and operator overrides. */
    BUSINESS
}
