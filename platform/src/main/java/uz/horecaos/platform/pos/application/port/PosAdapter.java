package uz.horecaos.platform.pos.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.ExportCandidate;

/**
 * A point of sale, behind one provider-neutral contract (ADR 0011).
 *
 * <p>An adapter declares only what its vendor actually does. The application
 * services ask for a capability and get an adapter that has it, or get none —
 * they never ask "is this Clopos?", which is the coupling that makes every new
 * till an edit to core commerce code.
 *
 * <p>Nothing here throws for a provider failure. Every method returns an
 * ADR 0007 outcome, and the caller's next move is decided from the
 * classification. That is not ceremony: on this integration the difference
 * between {@code RETRYABLE} and {@code UNCERTAIN} is the difference between one
 * dinner and two.
 */
public interface PosAdapter {

    /** Matches the ADR 0026 installation's {@code provider_type}. */
    String providerType();

    /**
     * What this vendor's API can ever do, before any credential is considered.
     *
     * <p>A ceiling. {@link #discoverCapabilities} narrows it per installation and
     * can never widen it — an adapter that appears to discover a capability it
     * does not declare has found a bug in its own probe.
     */
    Set<PosCapability> declaredCapabilities();

    /**
     * Establishes what one restaurant's credential can actually do.
     *
     * <p>Empirical rather than assumed, because on at least one vendor the
     * credential acts as a staff user the restaurant chose, and a cashier's
     * permissions and an owner's produce different surfaces from the same API
     * version.
     *
     * <p>Probes must be free of side effects. A capability that can only be
     * demonstrated by doing the thing — creating an order, for instance — is
     * reported {@code UNVERIFIABLE} rather than proved by exercising it, because
     * a discovery run that sends a kitchen a test dinner is not a discovery run.
     */
    CapabilitySnapshot discoverCapabilities(PosContext context);

    /** Reads the vendor's whole menu. See {@link CatalogRead} on why "whole". */
    CatalogRead readCatalog(PosContext context);

    /** Reads what the vendor says is limited or out of stock. */
    AvailabilityRead readAvailability(PosContext context);

    /**
     * Sends an order to the till.
     *
     * <p>The most consequential call in the module. Where the vendor offers no
     * idempotency key, an uncertain outcome from this method must never be
     * retried by the caller; see {@link #findExportedOrder}.
     */
    ExportResult exportOrder(PosContext context, OrderExport order);

    /**
     * Searches the vendor for an order that might be one we may have sent.
     *
     * <p>The recovery path after an uncertain export. It must be free of side
     * effects and safe to call at any time, and it must return candidates rather
     * than a verdict: on a vendor with no correlation field, "an order like this
     * exists" and "our order exists" are different statements and only the first
     * is available.
     */
    RecoveryRead findExportedOrder(PosContext context, ExportProbe probe);

    /** Withdraws an exported order, where the vendor permits it. */
    ProviderOutcome cancelExportedOrder(PosContext context, String externalOrderId, String reason);

    /** Writes back the fiscal identifier another system issued (ADR 0038). */
    ProviderOutcome writeFiscalIdentifier(PosContext context, String externalReceiptId, String fiscalId);

    /**
     * Tells the vendor where an order has got to.
     *
     * <p>Outbound only. Whatever the vendor's field is called, a value written
     * here is not a report from a kitchen and must never be read back as one.
     */
    ProviderOutcome writeFulfillmentStatus(PosContext context, String externalReceiptId, String status);

    /**
     * Everything an adapter needs that is not the business request.
     *
     * @param externalVenueReference which of the vendor's venues this binding
     *                               points at. On a vendor whose credential is
     *                               brand-scoped this is a per-request header, and
     *                               getting it wrong exports a customer's dinner
     *                               to a restaurant in another city
     * @param configuration          the installation's non-sensitive configuration
     *                               merged with the binding's override. Never a
     *                               credential: the credential is resolved inside
     *                               the transport and exists only for the duration
     *                               of one call
     */
    record PosContext(
            UUID tenantId,
            UUID installationId,
            @Nullable UUID bindingId,
            @Nullable String externalVenueReference,
            Map<String, String> configuration,
            String correlationId) {

        public PosContext {
            configuration = Map.copyOf(configuration == null ? Map.of() : configuration);
        }

        public @Nullable String config(String key, @Nullable String fallback) {
            return configuration.getOrDefault(key, fallback);
        }
    }

    /**
     * The outcome of a whole-catalog read, and the catalog itself on success.
     *
     * @param snapshot null unless the outcome succeeded. A partial catalog is not
     *                 a catalog: staging half a menu and diffing it would report
     *                 the unread half as removals
     */
    record CatalogRead(ProviderOutcome outcome, @Nullable CatalogSnapshot snapshot) {}

    record AvailabilityRead(ProviderOutcome outcome, List<CatalogSnapshot.Availability> entries) {

        public AvailabilityRead {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    /**
     * The outcome of sending one order, and the vendor's own reference for it.
     *
     * @param externalOrderId the vendor's identifier, present only on success
     * @param approvalPending whether the till still has to accept this order. True
     *                        where the vendor is a genuine authority and we did
     *                        not bypass it, which is the safer pilot posture: an
     *                        order waiting for a clerk is recoverable and visible,
     *                        while an order auto-accepted and auto-sent to a
     *                        station is already food
     */
    record ExportResult(ProviderOutcome outcome, @Nullable String externalOrderId, boolean approvalPending) {}

    /**
     * What to look for when discovering whether an uncertain export landed.
     *
     * @param correlationReference the value we asked the vendor to carry. Matched
     *                             first, because a match on it is an identity and
     *                             not a resemblance
     * @param customerPhone        the customer's number, needed to search and
     *                             never stored by the caller — ADR 0029 keeps only
     *                             a hash on the export row
     * @param windowStart          bounds the search. Wide enough to survive a slow
     *                             till, narrow enough that yesterday's identical
     *                             order is not a candidate
     */
    record ExportProbe(
            @Nullable String correlationReference,
            @Nullable String customerPhone,
            String lineFingerprint,
            List<uz.horecaos.platform.pos.domain.LineFingerprint.Line> lines,
            Instant windowStart,
            Instant windowEnd) {

        public ExportProbe {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record RecoveryRead(ProviderOutcome outcome, List<ExportCandidate> candidates) {

        public RecoveryRead {
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
        }
    }

    /**
     * One order, as a till needs to receive it.
     *
     * <p>Carries personal data — a name, a telephone number, an address — because
     * a courier cannot deliver to a hash. It is a transient value passed to one
     * call and it is never logged, never put on an event, and never stored by
     * this module; ADR 0029 governs all three, and the export row keeps a hash.
     *
     * @param requireProviderApproval whether the till's clerk must accept this
     *                                order. False turns the vendor from an
     *                                authority into a recipient for this order,
     *                                which is a decision HorecaOS makes per order
     *                                rather than a fixed property of the vendor
     * @param currency                asserted from installation configuration. At
     *                                least one vendor has no currency field
     *                                anywhere in its API, so it cannot be read
     *                                from the wire and must not be guessed
     */
    record OrderExport(
            UUID orderId,
            @Nullable String correlationReference,
            String publicOrderNumber,
            Customer customer,
            List<Line> lines,
            long totalMinor,
            String currency,
            String fulfillmentMode,
            boolean requireProviderApproval,
            @Nullable Instant placedAt) {

        public OrderExport {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }

        /** Never logged, never stored, never put on an event (ADR 0029). */
        @Override
        public String toString() {
            return "OrderExport[order=" + orderId + ", lines=" + lines.size() + "]";
        }

        public record Customer(
                @Nullable String externalCustomerId,
                @Nullable String name,
                @Nullable String phone,
                @Nullable String address) {

            @Override
            public String toString() {
                return "Customer[external=" + externalCustomerId + "]";
            }
        }

        /**
         * One line of the order, as the till needs to receive it.
         *
         * @param externalProductId the vendor's own identifier for the thing. The
         *                          only stable key at least one vendor offers, and
         *                          the reason a mapping exists at all
         * @param unitAmountMinor   whole minor units. For UZS a minor unit is a
         *                          whole som
         */
        public record Line(
                String externalProductId,
                String nameSnapshot,
                int quantity,
                long unitAmountMinor,
                List<String> externalModifierIds) {

            public Line {
                externalModifierIds = List.copyOf(externalModifierIds == null ? List.of() : externalModifierIds);
            }
        }
    }
}
