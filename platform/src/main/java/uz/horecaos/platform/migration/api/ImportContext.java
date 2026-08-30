package uz.horecaos.platform.migration.api;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The flag a historical import sets so target writes suppress their external
 * effects (ADR 0024).
 *
 * <p>ADR 0024 is exact about what an import must not do: "Historical import
 * never replays customer messages, captures payments, books couriers, exports
 * POS orders, consumes benefits, or changes inventory." Importing five years of
 * completed orders through the ordering domain would otherwise send five years
 * of order confirmations to real phone numbers and re-book couriers for
 * deliveries that arrived long ago.
 *
 * <p><strong>This suppresses external effects only.</strong> It is read at the
 * boundary where the platform reaches something outside itself — outbox
 * publication to providers, notification delivery, payment capture, courier
 * booking, POS export, benefit consumption, inventory movement. It must never be
 * read by validation, by an invariant, by optimistic concurrency, or by
 * {@code AuditRecorder}. ADR 0024 requires import ports to suppress side effects
 * <em>while retaining historical evidence</em>, and an import that skips
 * validation is precisely the ad hoc target SQL the ADR rejected as an
 * alternative: it bypasses every invariant the last twenty ADRs exist to
 * enforce, and it does so silently, because bad rows arrive looking exactly like
 * good ones. A row that cannot pass validation belongs in quarantine, not in the
 * target.
 *
 * <p>Read through {@link ImportSuppression} rather than directly, in the adapters
 * that {@link ExternalEffect} enumerates. The predicate stays visible at each call
 * site; what the helper adds is that skipping and refusing are chosen by the
 * effect rather than by whoever wrote the guard, and that an effect nobody reads
 * fails a test — which is the state this flag shipped in.
 *
 * <p>A {@link java.lang.ScopedValue} rather than a {@code ThreadLocal}: the
 * binding is strictly balanced by construction, so there is no way to set the
 * flag and forget to clear it, and no way for an import to leak onto a pooled
 * request thread and silence a real customer's notification. The binding is
 * confined to the calling thread and does not follow work handed to an executor,
 * so an import port must perform its writes on the thread it was given. That
 * failure is the unsafe direction — effects escaping rather than being
 * suppressed — which is why it is stated here rather than left to be discovered.
 */
public final class ImportContext {

    private static final ScopedValue<Boolean> IMPORTING = ScopedValue.newInstance();

    private ImportContext() {
    }

    /**
     * Whether the current thread is executing a historical import.
     *
     * <p>False outside an import, including on any thread the import handed work
     * to. Adapters ask before reaching outside the platform, never before
     * validating or recording.
     */
    public static boolean isImporting() {
        return IMPORTING.orElse(Boolean.FALSE);
    }

    /**
     * Runs the import work with external effects suppressed.
     *
     * <p>Nesting is harmless: an import that calls another import is still an
     * import, and the inner binding simply rebinds the same value.
     */
    public static <T> T runAsImport(Supplier<T> work) {
        Objects.requireNonNull(work, "Import work is required");
        return ScopedValue.where(IMPORTING, Boolean.TRUE).call(work::get);
    }
}
