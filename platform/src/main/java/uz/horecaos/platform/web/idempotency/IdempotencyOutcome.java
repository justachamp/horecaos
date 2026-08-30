package uz.horecaos.platform.web.idempotency;

import java.util.Optional;

/**
 * What a caller should do with a request carrying an idempotency key (ADR 0031).
 */
public sealed interface IdempotencyOutcome {

    /** No prior record: the caller proceeds and reports the result. */
    record Proceed(java.util.UUID recordId) implements IdempotencyOutcome { }

    /**
     * The same key and the same request already completed: return the stored
     * response without running the effect again.
     *
     * <p>{@code responseBody} is as it sits in the column, which for a handler
     * answering with personal data is an ADR 0029 envelope rather than JSON. The
     * record id travels with it because the ciphertext is bound to the row it
     * was written into, so nothing else can decrypt it — and the caller, not the
     * store, is what holds the key material.
     */
    record Replay(java.util.UUID recordId, int responseStatus, String responseBody,
            boolean responseBodyProtected) implements IdempotencyOutcome { }

    /**
     * The same key is in flight. The caller must not run concurrently; ADR 0031
     * maps this to 409 rather than queuing, because a queued duplicate is
     * indistinguishable from a slow original.
     */
    record InProgress() implements IdempotencyOutcome { }

    /**
     * The same key arrived with a different request body. This is a client bug
     * and must never be treated as a retry.
     */
    record Conflict() implements IdempotencyOutcome { }

    default Optional<Replay> replay() {
        return this instanceof Replay replay ? Optional.of(replay) : Optional.empty();
    }
}
