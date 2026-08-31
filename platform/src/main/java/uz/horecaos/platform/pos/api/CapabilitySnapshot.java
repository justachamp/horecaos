package uz.horecaos.platform.pos.api;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What one installation's credential was actually observed to be able to do
 * (ADR 0011).
 *
 * <p>Empirical rather than declared, and that is not a stylistic preference. On
 * the first POS this platform integrates against, the restaurant generates the
 * credential themselves and chooses which staff user it acts as; every call made
 * with it runs under that user's permissions. Two restaurants on the same vendor
 * version therefore expose different surfaces, and a per-provider capability
 * table would be wrong for whichever of them picked the cashier.
 *
 * <p>A snapshot can only ever narrow the provider ceiling in
 * {@code integration.pos_provider_capabilities}. A probe that appears to find a
 * capability the vendor's API does not have has found a bug in the probe.
 *
 * @param verifiedAt     when the discovery ran. A stale snapshot is the ADR 0011
 *                       negative consequence that reconciliation must be
 *                       scheduled to address, and it can only be spotted if the
 *                       time is carried
 * @param adapterVersion the adapter that did the discovering, so a snapshot
 *                       cannot be read as though later code had produced it
 */
public record CapabilitySnapshot(
        Map<PosCapability, Entry> entries, @Nullable Instant verifiedAt, @Nullable String adapterVersion) {

    public CapabilitySnapshot {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    public static CapabilitySnapshot empty() {
        return new CapabilitySnapshot(Map.of(), null, null);
    }

    public Optional<Entry> entry(PosCapability capability) {
        return Optional.ofNullable(entries.get(capability));
    }

    /**
     * Whether this installation may be asked to do something.
     *
     * <p>PARTIAL counts as usable. Refusing it would leave a restaurant with no
     * cancellation path at all rather than one that works before the till has
     * accepted the order, and the honest answer to a caller is the entry's own
     * limits rather than a flat no.
     */
    public boolean usable(PosCapability capability) {
        return entry(capability).map(e -> e.support().configurable()).orElse(false);
    }

    public CapabilitySnapshot with(PosCapability capability, Entry entry) {
        Map<PosCapability, Entry> merged = new EnumMap<>(PosCapability.class);
        merged.putAll(entries);
        merged.put(capability, entry);
        return new CapabilitySnapshot(merged, verifiedAt, adapterVersion);
    }

    /**
     * @param idempotency    what the provider guarantees about a repeated command.
     *                       Carried on the capability rather than on the provider
     *                       because it differs per operation: on the first real
     *                       adapter, reads and value-setting writes are idempotent
     *                       by construction and the order create is not idempotent
     *                       at all
     * @param pushSupported  whether the provider tells us, or we have to ask. A
     *                       polled approval arrives one interval late, and callers
     *                       that assume a push will race against their own timer
     * @param limits         provider-stated bounds worth carrying — page sizes,
     *                       request budgets, field lengths. Free-form because they
     *                       differ per provider and a typed union of all of them
     *                       would be a schema for nothing
     * @param evidence       what the probe saw. Never a response body: a provider
     *                       error has been observed to echo request content back,
     *                       and ADR 0029 keeps that out of anything listable
     */
    public record Entry(
            CapabilitySupport support,
            IdempotencyBehaviour idempotency,
            boolean pushSupported,
            @Nullable String capabilityVersion,
            Map<String, String> limits,
            @Nullable String evidence,
            @Nullable Instant verifiedAt) {

        public Entry {
            limits = limits == null ? Map.of() : Map.copyOf(limits);
        }

        public static Entry unsupported(String evidence) {
            return new Entry(
                    CapabilitySupport.UNSUPPORTED, IdempotencyBehaviour.NONE, false, null, Map.of(), evidence, null);
        }
    }

    /** What repeating a command does at the provider. */
    public enum IdempotencyBehaviour {

        /** The provider deduplicates on a key we supply. Retrying is safe. */
        KEYED,

        /**
         * The operation sets a specific value or reads, so repeating it converges.
         * Safe to retry without any provider guarantee.
         */
        NATURALLY_IDEMPOTENT,

        /**
         * Repeating creates a second one. No key, no header, no dedupe window.
         *
         * <p>This is the value that makes an uncertain outcome unresolvable by
         * machinery, and every design decision downstream of it follows from
         * taking it literally.
         */
        NONE
    }
}
