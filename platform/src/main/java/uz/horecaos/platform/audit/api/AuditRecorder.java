package uz.horecaos.platform.audit.api;

/**
 * Records audit evidence (ADR 0027).
 *
 * <p>The write happens in the same transaction as the change it describes. That
 * means an audit failure fails the business action, which is the correct
 * direction for evidence: an action that succeeded without a record is
 * indistinguishable from one that never happened.
 */
public interface AuditRecorder {

    void record(AuditFact fact);
}
