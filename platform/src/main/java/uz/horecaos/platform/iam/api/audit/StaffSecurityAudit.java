package uz.horecaos.platform.iam.api.audit;

/**
 * How {@code iam} gets a fact about a staff account into the audit trail
 * (ADR 0098).
 *
 * <p>Implemented by the {@code audit} module over ADR 0027's recorder; see this
 * package's own note for why the dependency runs that way round rather than
 * {@code iam} calling {@code AuditRecorder} directly.
 *
 * <p>Called inside the transaction that made the change, exactly as
 * {@code AuditRecorder} is: a failure to record fails the change, because an
 * action that succeeded without evidence is indistinguishable from one that
 * never happened.
 */
public interface StaffSecurityAudit {

    void record(StaffSecurityFact fact);
}
