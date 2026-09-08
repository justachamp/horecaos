package uz.horecaos.platform.audit.api;

/**
 * {@link AuditArchiveStore#archiveAndVerify} could not prove durability or the
 * retention lock (ADR 0027).
 *
 * <p>A caller that catches this must not drop whatever the archive attempt was
 * standing in for. The live partition is the only copy of the evidence until an
 * archive is proven, not merely attempted.
 */
public class AuditArchivalNotVerifiedException extends RuntimeException {

    public AuditArchivalNotVerifiedException(String message) {
        super(message);
    }

    public AuditArchivalNotVerifiedException(String message, Throwable cause) {
        super(message, cause);
    }
}
