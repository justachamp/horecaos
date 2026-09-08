package uz.horecaos.platform.audit.api;

import java.time.Instant;

/**
 * Where a closed audit partition's evidence goes once it leaves the live table
 * (ADR 0027).
 *
 * <p>One method, and it does not hand back a partial result. Protected storage
 * for evidence a ten-year retention floor governs is not "the bytes are
 * probably there": {@link #archiveAndVerify} either returns a receipt proving
 * both that the exact bytes read back byte-for-byte and that the store's own
 * retention lock is confirmed in force through {@code retainUntil}, or it throws
 * {@link AuditArchivalNotVerifiedException}. There is no third outcome — a
 * caller that catches the exception must treat the archive as not having
 * happened at all, and in particular must not drop the live partition it came
 * from.
 *
 * <p>Deliberately smaller than {@code media.api.ObjectStorage}. Archival never
 * presigns — nothing outside the platform ever writes here — and it never needs
 * an ordinary, unlocked delete: the one thing this port cannot express is
 * removing an object, because retention-protected storage is the property that
 * an application role must not be able to do that on its own say-so.
 */
public interface AuditArchiveStore {

    /**
     * Writes {@code content} under {@code key} with a retention lock through
     * {@code retainUntil}, and does not return until the store has proven both
     * durability and that lock — by reading each back, not by trusting the
     * write.
     *
     * @throws AuditArchivalNotVerifiedException if the read-back bytes do not
     *     match what was written, or if the store did not honour the requested
     *     retention lock — including when the store cannot enforce a retention
     *     lock at all, which must fail loudly rather than be reported as
     *     protected
     */
    ArchiveReceipt archiveAndVerify(String key, byte[] content, Instant retainUntil);

    /**
     * Proof that an object arrived intact and locked.
     *
     * @param retainUntil the lock the store itself reported, which may be later
     *     than requested but is never earlier — {@link #archiveAndVerify} throws
     *     rather than return one that is
     */
    record ArchiveReceipt(String bucket, String key, long sizeBytes, String sha256Base64, Instant retainUntil) {}
}
