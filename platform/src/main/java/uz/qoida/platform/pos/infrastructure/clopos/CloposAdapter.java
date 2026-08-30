package uz.qoida.platform.pos.infrastructure.clopos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.api.pos.PosApiCall;
import uz.qoida.platform.integration.api.pos.PosApiCall.Effect;
import uz.qoida.platform.integration.api.pos.PosApiTransport;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.pos.api.CapabilitySnapshot;
import uz.qoida.platform.pos.api.CapabilitySnapshot.Entry;
import uz.qoida.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.qoida.platform.pos.api.CapabilitySupport;
import uz.qoida.platform.pos.api.PosCapability;
import uz.qoida.platform.pos.application.port.PosAdapter;
import uz.qoida.platform.pos.domain.CatalogSnapshot;
import uz.qoida.platform.pos.domain.ExportCandidate;
import uz.qoida.platform.pos.domain.LineFingerprint;

/**
 * Clopos Open API v2.
 *
 * <p>Verified against the published OpenAPI document and all 32 documentation
 * pages on 2026-08-23; the working is in {@code docs/providers/clopos-api.md}.
 * Four properties separate this adapter from every other provider adapter in the
 * build, and each one is a way to serve a customer badly if ignored.
 *
 * <ul>
 *   <li><b>Authentication is per brand, not per venue.</b> One credential set
 *       covers every venue under a Clopos brand and the venue is chosen with an
 *       {@code x-venue} header per request. So an installation is a brand and a
 *       binding is a venue, and the header is the single value that decides which
 *       kitchen prints a customer's dinner.</li>
 *   <li><b>There is no idempotency mechanism of any kind.</b> No key, no header,
 *       no documented repeat semantics. {@link #exportOrder} is therefore an
 *       {@link Effect#UNKEYED_CREATE} and an uncertain outcome from it is never
 *       retried — see {@link #findExportedOrder}, and see why that method returns
 *       candidates rather than an answer.</li>
 *   <li><b>Clopos is an authority for acceptance and a recipient for everything
 *       after it.</b> {@code PENDING} to {@code RECEIVED} is a clerk pressing a
 *       button and we cannot make that transition, but {@code auto_order_accept}
 *       bypasses the clerk entirely — so which of the two Clopos is, is a
 *       decision Qoida makes per order rather than a property of the vendor.</li>
 *   <li><b>Nothing reports preparation.</b> The only preparation-shaped field is
 *       {@code Receipt.order_status}, which is one of the four fields
 *       {@code PATCH /receipts/{id}} lets us write and which every other receipt
 *       field is explicitly read-only against. This adapter therefore does not
 *       declare {@link PosCapability#PREPARATION_STATUS}, and
 *       {@link #writeFulfillmentStatus} exists so that the outbound direction has
 *       a name of its own and cannot be mistaken for the inbound one.</li>
 * </ul>
 */
@Component
public class CloposAdapter implements PosAdapter {

    public static final String PROVIDER_TYPE = "clopos";

    /** Bumped when a mapping changes, so a stored snapshot cannot be read as this one. */
    public static final String ADAPTER_VERSION = "clopos-v2-2026.08";

    /**
     * Clopos's products page states 100; its shared pagination page says
     * "typically 200". Taking the smaller of two contradictory documented limits
     * costs one extra request per hundred products and cannot produce a 400.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * A ceiling on pages, so a paging bug cannot walk a brand's rate-limit budget
     * to zero. Three hundred requests a minute are shared by every venue under one
     * brand, and exhausting them stops availability polling and order export as
     * well as this read.
     */
    private static final int MAX_PAGES = 200;

    private final PosApiTransport transport;
    private final CloposSession session;
    private final Clock clock;

    public CloposAdapter(PosApiTransport transport, CloposSession session, Clock clock) {
        this.transport = transport;
        this.session = session;
        this.clock = clock;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<PosCapability> declaredCapabilities() {
        // PREPARATION_STATUS is absent and CUSTOMER_UPSERT is present, for the
        // reason this method's contract gives: it is what the vendor's API can
        // ever do, before any credential and before anything of ours. Clopos
        // publishes no preparation feed, so PREPARATION_STATUS is genuinely
        // absent. Clopos does publish `POST /customers`, so CUSTOMER_UPSERT is
        // declared — and it was not, on the reasoning that exporting a phone and
        // address needs an ADR 0029 consent basis first. That is true, and it is
        // a statement about us. Withholding the declaration filed our own policy
        // as the vendor's incapability, which is the one answer a binding can
        // never override and which contradicted both ADR 0011's capability table
        // and the `integration.pos_provider_capabilities` ceiling V0036 seeds
        // PARTIAL. The consent basis gates enabling it on a binding and gates
        // there being an operation to call at all; there is no upsert method on
        // this port, so declaring it enables nothing by itself.
        return Set.of(
                PosCapability.CATALOG_READ,
                PosCapability.AVAILABILITY_READ,
                PosCapability.ORDER_APPROVAL,
                PosCapability.ORDER_EXPORT,
                PosCapability.ORDER_CANCELLATION,
                PosCapability.RECEIPT_READ,
                PosCapability.FISCAL_IDENTIFIER_WRITE_BACK,
                PosCapability.FULFILLMENT_STATUS_WRITE,
                PosCapability.CUSTOMER_UPSERT);
    }

    /**
     * Establishes what this restaurant's credential can actually do.
     *
     * <p>Every probe is a read. Clopos's credential acts as a staff user the
     * restaurant chose, and that user's permissions decide the surface — so this
     * has to be run per installation and cannot be inferred from the vendor's
     * documentation. What it cannot do is prove the write capabilities, because
     * proving {@code ORDER_EXPORT} means creating an order and a discovery run
     * that sends a kitchen a test dinner is not a discovery run. Those come back
     * {@code UNVERIFIABLE}, which is a different statement from "unsupported" and
     * is recorded as one.
     */
    @Override
    public CapabilitySnapshot discoverCapabilities(PosContext context) {
        Instant now = clock.instant();
        Map<PosCapability, Entry> entries = new EnumMap<>(PosCapability.class);

        ProviderOutcome venues = read(context, "venues", "/venues");
        boolean authenticated = venues.status() == ProviderOutcome.Status.SUCCESS;

        entries.put(PosCapability.CATALOG_READ, probe(context, PosCapability.CATALOG_READ,
                "/products" + CloposQuery.create().page(1, 1).render(),
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT, now,
                Map.of("pageSize", Integer.toString(PAGE_SIZE),
                        // Recorded on the capability because it is the fact that
                        // decides the whole shape of a sync run.
                        "incrementalFetch", "false",
                        "changeFeed", "none")));

        entries.put(PosCapability.AVAILABILITY_READ, probe(context, PosCapability.AVAILABILITY_READ,
                "/products/stop-list",
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT, now,
                Map.of("perRowTimestamp", "true", "perVenue", "false")));

        entries.put(PosCapability.RECEIPT_READ, probe(context, PosCapability.RECEIPT_READ,
                "/receipts" + CloposQuery.create().page(1, 1).render(),
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT, now, Map.of()));

        // Order approval is real and it is polled. Recorded as PARTIAL with the
        // reason attached rather than SUPPORTED, so that a control plane offering
        // "the POS decides" also shows how late the decision arrives.
        entries.put(PosCapability.ORDER_APPROVAL, new Entry(
                authenticated ? CapabilitySupport.PARTIAL : CapabilitySupport.UNSUPPORTED,
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT,
                false,
                ADAPTER_VERSION,
                Map.of("decisionLatency", "one poll interval", "push", "none"),
                "Acceptance is a clerk's decision at the terminal. Clopos publishes no webhooks, "
                        + "so the decision is discovered by polling and never pushed.",
                now));

        entries.put(PosCapability.ORDER_EXPORT, new Entry(
                authenticated ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                IdempotencyBehaviour.NONE,
                false,
                ADAPTER_VERSION,
                Map.of("correlationEchoVerified",
                        context.config(CloposConfig.CORRELATION_ECHO_VERIFIED, "false")),
                // Not verified by exercising it, and the snapshot says so.
                "Not probed: proving an order export means creating an order, and Clopos offers no "
                        + "idempotency key with which to undo the proof.",
                now));

        entries.put(PosCapability.ORDER_CANCELLATION, new Entry(
                authenticated ? CapabilitySupport.PARTIAL : CapabilitySupport.UNSUPPORTED,
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT,
                false,
                ADAPTER_VERSION,
                Map.of("beforeAcceptance", "true", "afterAcceptance", "false"),
                "PUT /orders/{id} status IGNORE works while the order is PENDING. After a clerk "
                        + "accepts it there is no documented order-level cancel.",
                now));

        entries.put(PosCapability.FISCAL_IDENTIFIER_WRITE_BACK, new Entry(
                authenticated ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT, false, ADAPTER_VERSION,
                Map.of("afterClose", "true"),
                "PATCH /receipts/{id} writes fiscal_id even after the receipt is closed.", now));

        entries.put(PosCapability.FULFILLMENT_STATUS_WRITE, new Entry(
                authenticated ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                IdempotencyBehaviour.NATURALLY_IDEMPOTENT, false, ADAPTER_VERSION,
                Map.of("direction", "outbound"),
                "PATCH /receipts/{id} order_status. Outbound only; it is not a kitchen report.", now));

        // Stated rather than omitted. An absent entry reads as "not discovered";
        // this reads as "discovered, and the answer is no".
        entries.put(PosCapability.PREPARATION_STATUS, Entry.unsupported(
                "Clopos publishes no endpoint that reports preparation. Receipt.order_status is one "
                        + "of the four fields we write, so reading it back would report our own writes."));

        // PARTIAL, not UNSUPPORTED, and the distinction is the whole point of the
        // middle value. UNSUPPORTED means "the provider does not do this" and is
        // the one answer a binding can never override; Clopos does do it —
        // `POST /customers` exists and `CreateOrderRequest` requires a customer
        // id. What is missing is ours: an ADR 0029 consent basis for exporting a
        // named person's phone and address to a third party. Recording our own
        // policy as the vendor's incapability puts a false statement in front of
        // whoever configures the branch, and it would disagree with the provider
        // ceiling in `integration.pos_provider_capabilities`, which V0036 seeds
        // PARTIAL for exactly this reason.
        entries.put(PosCapability.CUSTOMER_UPSERT, new Entry(
                CapabilitySupport.PARTIAL,
                // No key, no header, no dedupe window — the same absence the order
                // create has, so a lost response leaves a customer that may or may
                // not exist.
                IdempotencyBehaviour.NONE,
                false,
                ADAPTER_VERSION,
                Map.of("consentBasisRequired", "true", "guestPath", "false"),
                "POST /customers exists and an order export needs a Clopos customer first, so the "
                        + "provider can do this. Not enabled: exporting a phone and address needs an "
                        + "ADR 0029 consent basis, there is no documented guest path, and the create "
                        + "has the same absence of idempotency as the order create.",
                now));

        return new CapabilitySnapshot(entries, now, ADAPTER_VERSION);
    }

    private Entry probe(PosContext context, PosCapability capability, String path,
            IdempotencyBehaviour idempotency, Instant now, Map<String, String> limits) {

        ProviderOutcome outcome = read(context, "probe." + capability.code().toLowerCase(
                java.util.Locale.ROOT), path);

        CapabilitySupport support = switch (outcome.status()) {
            case SUCCESS -> CapabilitySupport.SUPPORTED;
            // A refusal is the staff user's permissions answering, which is
            // exactly what this probe exists to discover.
            case REJECTED -> CapabilitySupport.UNSUPPORTED;
            // Clopos was unreachable. Not evidence about the capability at all,
            // and recording UNSUPPORTED here would suspend a working integration
            // because of a bad afternoon.
            case RETRYABLE, UNCERTAIN -> CapabilitySupport.PARTIAL;
        };

        return new Entry(support, idempotency, false, ADAPTER_VERSION, limits,
                outcome.status() == ProviderOutcome.Status.SUCCESS
                        ? "Probed successfully."
                        : "Probe returned %s (%s)".formatted(outcome.status(), outcome.errorCode()),
                now);
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    /**
     * Reads the whole menu, because Clopos offers no other way.
     *
     * <p>{@code GET /products} accepts no date range, no {@code updated_at}
     * filter, no cursor and no sort; there is no ETag, no conditional request and
     * no change feed. Every filterable field is structural and not one is
     * temporal. So change detection is a full re-read and a client-side diff.
     *
     * <p>The walk is by page number, and it is reported as unstable for that
     * reason. Offset paging over a catalog the restaurant is editing can skip a
     * row, and a skipped row is indistinguishable downstream from a deleted one —
     * which is why the difference engine requires two agreeing runs before it will
     * call an absence a removal. If Clopos turns out to be sortable by id, paging
     * by {@code id > last_seen} would make the walk stable and one absence would
     * then be evidence; the flag is carried rather than assumed so that day is a
     * change here and nowhere else.
     */
    @Override
    public CatalogRead readCatalog(PosContext context) {
        PagedRead products = readAllPages(context, "products", page -> "/products"
                + CloposQuery.create()
                        .page(page, PAGE_SIZE)
                        .with(0, "category")
                        .with(1, "modifications")
                        .with(2, "modificator_groups")
                        .with(3, "codes")
                        .render());
        if (products.failed()) {
            return new CatalogRead(products.outcome(), null);
        }

        PagedRead categories = readAllPages(context, "categories", page -> "/categories"
                + CloposQuery.create().page(page, PAGE_SIZE).param("include_inactive", "1").render());
        if (categories.failed()) {
            return new CatalogRead(categories.outcome(), null);
        }

        ProviderOutcome stopList = read(context, "stop-list", "/products/stop-list");
        if (stopList.status() != ProviderOutcome.Status.SUCCESS) {
            // A catalog without availability is still a catalog, and refusing the
            // whole run because the stop list was briefly unavailable would stop a
            // reviewed import for a feed that is polled separately every minute
            // anyway. The empty list is the honest reading: absence from a stop
            // list means unconstrained.
            stopList = ProviderOutcome.success(Map.of("data", List.of()), null);
        }

        CatalogSnapshot snapshot = new CloposCatalogNormalizer(
                context.config(CloposConfig.CURRENCY, "UZS"))
                .normalize(products.rows(), categories.rows(),
                        CloposEnvelope.dataList(stopList.normalized()),
                        clock.instant(),
                        // Offset paging. See the method note.
                        false,
                        products.pageCount() + categories.pageCount());

        return new CatalogRead(ProviderOutcome.success(Map.of(), null), snapshot);
    }

    /**
     * The one genuinely incremental surface Clopos has.
     *
     * <p>{@code GET /products/stop-list} carries a per-row change timestamp, in
     * milliseconds while the rest of the API is in seconds. It is small, it is the
     * fastest-changing data, and it is the one read where staleness has an
     * immediate consequence for a customer — selling a dish that ran out. It is
     * therefore polled on its own cadence rather than folded into the daily
     * catalog run, where it would be useless.
     */
    @Override
    public AvailabilityRead readAvailability(PosContext context) {
        ProviderOutcome outcome = read(context, "stop-list", "/products/stop-list");
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return new AvailabilityRead(outcome, List.of());
        }
        CatalogSnapshot snapshot = new CloposCatalogNormalizer(
                context.config(CloposConfig.CURRENCY, "UZS"))
                .normalize(List.of(), List.of(), CloposEnvelope.dataList(outcome.normalized()),
                        clock.instant(), true, 1);
        return new AvailabilityRead(outcome, snapshot.availability());
    }

    // ------------------------------------------------------------------
    // Order export
    // ------------------------------------------------------------------

    /**
     * Sends the order. The one call in this adapter that can go wrong expensively.
     *
     * <p>Classified {@link Effect#UNKEYED_CREATE}, which makes every ambiguous
     * transport failure {@code UNCERTAIN} at the gateway rather than retryable.
     * That classification is the whole safety property: there is no key on this
     * request, so a repeat is a second kitchen ticket and Clopos's own retry
     * guidance says to check the server state first rather than send it again.
     *
     * <p>{@code order_number} carries our correlation reference. Clopos's prose
     * documents the field with a twenty-character limit and its OpenAPI schema
     * omits it, so it may be silently dropped — but if it is honoured the recovery
     * read becomes deterministic, and if it is not we have lost nothing.
     */
    @Override
    public ExportResult exportOrder(PosContext context, OrderExport order) {
        CloposSession.Token token = session.token(context);
        if (!token.usable()) {
            return new ExportResult(token.outcome(), null, false);
        }

        boolean requireClerk = order.requireProviderApproval()
                && Boolean.parseBoolean(context.config(CloposConfig.REQUIRE_CLERK_APPROVAL, "true"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("venue_id", numeric(context.externalVenueReference()));
        body.put("sale_type_id", numeric(context.config(CloposConfig.SALE_TYPE_ID, null)));
        // Setting this true converts Clopos from an authority into a recipient for
        // this order: the clerk's decision is bypassed and the ticket is printed.
        // Defaulting to false is the safe failure and the inconvenient one.
        body.put("auto_order_accept", !requireClerk);
        body.put("auto_order_sent_to_station", !requireClerk);
        if (order.correlationReference() != null) {
            body.put("order_number", order.correlationReference());
        }
        body.put("customer", Map.of(
                "id", numericOrNull(order.customer().externalCustomerId()),
                "name", nullToEmpty(order.customer().name()),
                "phone", nullToEmpty(order.customer().phone()),
                "address", nullToEmpty(order.customer().address())));

        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderExport.Line line : order.lines()) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("product_id", numeric(line.externalProductId()));
            product.put("product_name", line.nameSnapshot());
            product.put("count", line.quantity());
            product.put("price", line.unitAmountMinor());
            product.put("status", "new");
            // Required by the schema, undocumented in every source: no derivation,
            // no statement of what it hashes, and an example of "abc123" on the
            // most important call in the integration. A per-line digest of the
            // fields we do understand at least makes it stable for one line and
            // different between two, which is the only property anything could
            // plausibly want from it. Question Q12 to Clopos.
            product.put("product_hash", LineFingerprint.of(List.of(new LineFingerprint.Line(
                    line.externalProductId(), line.quantity(), line.unitAmountMinor()))));
            if (!line.externalModifierIds().isEmpty()) {
                product.put("modificators", line.externalModifierIds().stream()
                        .map(CloposAdapter::numeric).toList());
            }
            lines.add(product);
        }
        body.put("products", lines);

        PosApiCall call = new PosApiCall(
                context.tenantId(), context.installationId(), PROVIDER_TYPE,
                "order.create", "POST", "/orders",
                PosApiCall.fixedBody(body),
                Effect.UNKEYED_CREATE,
                PosApiCall.fixedHeaders(headers(token.value(), context)),
                context.correlationId(),
                // Comfortably beyond Clopos's own eight-second upstream budget, so
                // that we observe their 504 rather than our own client timeout. A
                // client timeout says nothing about whether the order landed; a 504
                // at least says their gateway gave up on their upstream.
                Duration.ofSeconds(25));

        ProviderOutcome outcome = CloposEnvelope.read(transport.exchange(call), Effect.UNKEYED_CREATE);
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return new ExportResult(outcome, null, false);
        }

        Map<String, Object> created = CloposEnvelope.dataObject(outcome.normalized());
        String externalId = CloposEnvelope.string(created, "id");
        String status = CloposEnvelope.string(created, "status");
        return new ExportResult(
                ProviderOutcome.success(created, externalId),
                externalId,
                "PENDING".equalsIgnoreCase(status));
    }

    /**
     * Searches Clopos for an order that might be one we may have sent.
     *
     * <p>The recovery read, and it is unpleasant on purpose. Clopos offers no
     * filter on any correlation field, so the only available query is the day's
     * orders at this venue, matched by inspecting each one's echoed payload for
     * the customer's phone, the creation time, and the line composition.
     *
     * <p><strong>The status filter is deliberately not applied.</strong> Filtering
     * on {@code PENDING} would be the natural thing to do — an order we just
     * created should be pending — and it is exactly wrong: if the clerk accepted
     * it in the seconds between our timeout and this read, the order has already
     * moved to {@code RECEIVED} and the filtered read would report it absent. An
     * export reported absent is an export somebody may send again.
     *
     * <p>What comes back is candidates, never a verdict. Two identical baskets
     * from one telephone number ninety seconds apart are a customer who ordered
     * twice and a double export, and no field in this API separates them.
     */
    @Override
    public RecoveryRead findExportedOrder(PosContext context, ExportProbe probe) {
        String day = LocalDate.ofInstant(probe.windowStart(), ZoneOffset.UTC).toString();
        String until = LocalDate.ofInstant(probe.windowEnd(), ZoneOffset.UTC).toString();

        PagedRead orders = readAllPages(context, "order.search", page -> "/orders"
                + CloposQuery.create()
                        .page(page, PAGE_SIZE)
                        .dateRange(day, until)
                        .render());
        if (orders.failed()) {
            return new RecoveryRead(orders.outcome(), List.of());
        }

        String wantedPhone = LineFingerprint.phoneHash(probe.customerPhone());
        List<ExportCandidate> candidates = new ArrayList<>();

        for (Map<String, Object> row : orders.rows()) {
            Map<String, Object> payload = payloadOf(row);
            Map<String, Object> customer = mapOf(payload, "customer");

            boolean phoneMatches = wantedPhone.equals(
                    LineFingerprint.phoneHash(CloposEnvelope.string(customer, "phone")));
            String fingerprint = fingerprintOf(payload);
            boolean fingerprintMatches = probe.lineFingerprint() != null
                    && probe.lineFingerprint().equals(fingerprint);

            if (!phoneMatches && !fingerprintMatches) {
                continue;
            }

            String echoed = firstNonNull(
                    CloposEnvelope.string(row, "integration_uuid"),
                    CloposEnvelope.string(row, "integration_id"),
                    CloposEnvelope.string(payload, "order_number"));

            Instant createdAt = CloposTime.parse(CloposEnvelope.string(row, "created_at"));

            candidates.add(new ExportCandidate(
                    CloposEnvelope.string(row, "id"),
                    CloposEnvelope.string(row, "status"),
                    createdAt,
                    probe.correlationReference() != null && probe.correlationReference().equals(echoed),
                    phoneMatches,
                    fingerprintMatches,
                    createdAt == null ? null
                            : (int) Duration.between(probe.windowStart(), createdAt).toSeconds()));
        }

        return new RecoveryRead(ProviderOutcome.success(Map.of(), null), List.copyOf(candidates));
    }

    /**
     * Withdraws an order the clerk has not yet accepted.
     *
     * <p>Only that. Clopos documents exactly one transition through this
     * endpoint, and once the order is accepted a receipt exists with no documented
     * order-level cancel. The nearest thing — patching the receipt's
     * {@code order_status} to {@code CANCELLED} — sets a label and is not
     * documented to void the receipt, reverse the stock deduction, or stop the
     * kitchen, so this adapter does not do it and does not describe it as
     * cancellation. Telling a customer their order is cancelled while a kitchen
     * cooks it is worse than telling them it cannot be.
     */
    @Override
    public ProviderOutcome cancelExportedOrder(PosContext context, String externalOrderId, String reason) {
        CloposSession.Token token = session.token(context);
        if (!token.usable()) {
            return token.outcome();
        }
        PosApiCall call = new PosApiCall(
                context.tenantId(), context.installationId(), PROVIDER_TYPE,
                "order.ignore", "PUT", "/orders/" + externalOrderId,
                PosApiCall.fixedBody(Map.of("status", "IGNORE")),
                // Setting a terminal state. Repeating it converges, so a lost
                // response here really is safe to send again.
                Effect.IDEMPOTENT_WRITE,
                PosApiCall.fixedHeaders(headers(token.value(), context)),
                context.correlationId(), null);

        return CloposEnvelope.read(transport.exchange(call), Effect.IDEMPOTENT_WRITE);
    }

    @Override
    public ProviderOutcome writeFiscalIdentifier(PosContext context, String externalReceiptId,
            String fiscalId) {
        return patchReceipt(context, "receipt.fiscal-id", externalReceiptId,
                Map.of("fiscal_id", fiscalId));
    }

    /**
     * Writes a fulfilment label onto the Clopos receipt.
     *
     * <p>Outbound. The field Clopos calls {@code order_status} is one we write and
     * nothing else writes, so a value here is Qoida telling the restaurant where
     * the order is. It is emphatically not a place to read a kitchen's progress
     * from, and the method name says so where the vendor's field name does not.
     */
    @Override
    public ProviderOutcome writeFulfillmentStatus(PosContext context, String externalReceiptId,
            String status) {
        return patchReceipt(context, "receipt.order-status", externalReceiptId,
                Map.of("order_status", status));
    }

    private ProviderOutcome patchReceipt(PosContext context, String operation,
            String externalReceiptId, Map<String, Object> body) {
        CloposSession.Token token = session.token(context);
        if (!token.usable()) {
            return token.outcome();
        }
        PosApiCall call = new PosApiCall(
                context.tenantId(), context.installationId(), PROVIDER_TYPE,
                operation, "PATCH", "/receipts/" + externalReceiptId,
                PosApiCall.fixedBody(body),
                // Sets a named field to a named value. Idempotent by construction
                // whatever Clopos guarantees, which is nothing.
                Effect.IDEMPOTENT_WRITE,
                PosApiCall.fixedHeaders(headers(token.value(), context)),
                context.correlationId(), null);

        return CloposEnvelope.read(transport.exchange(call), Effect.IDEMPOTENT_WRITE);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private ProviderOutcome read(PosContext context, String operation, String path) {
        CloposSession.Token token = session.token(context);
        if (!token.usable()) {
            return token.outcome();
        }
        PosApiCall call = new PosApiCall(
                context.tenantId(), context.installationId(), PROVIDER_TYPE,
                operation, "GET", path, null, Effect.READ,
                PosApiCall.fixedHeaders(headers(token.value(), context)),
                context.correlationId(), null);

        ProviderOutcome outcome = CloposEnvelope.read(transport.exchange(call), Effect.READ);
        if ("CLOPOS_TOKEN_EXPIRED".equals(outcome.errorCode())) {
            // Once, not in a loop. A token that expired between our margin check
            // and Clopos's read is ordinary; a second expiry on the same call is a
            // clock problem somebody needs to know about rather than retry past.
            session.invalidate(context);
            CloposSession.Token fresh = session.token(context);
            if (!fresh.usable()) {
                return fresh.outcome();
            }
            PosApiCall retry = new PosApiCall(
                    context.tenantId(), context.installationId(), PROVIDER_TYPE,
                    operation, "GET", path, null, Effect.READ,
                    PosApiCall.fixedHeaders(headers(fresh.value(), context)),
                    context.correlationId(), null);
            return CloposEnvelope.read(transport.exchange(retry), Effect.READ);
        }
        return outcome;
    }

    private PagedRead readAllPages(PosContext context, String operation,
            java.util.function.IntFunction<String> pathForPage) {

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ProviderOutcome outcome = read(context, operation, pathForPage.apply(page));
            if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
                return new PagedRead(outcome, List.of(), page - 1);
            }
            List<Map<String, Object>> data = CloposEnvelope.dataList(outcome.normalized());
            rows.addAll(data);
            if (data.size() < PAGE_SIZE) {
                return new PagedRead(outcome, List.copyOf(rows), page);
            }
        }
        // The ceiling was reached, which means either a brand larger than anything
        // seen or a paging bug. Either way this is not a snapshot to diff against:
        // a truncated read reports everything beyond the ceiling as removed.
        return new PagedRead(ProviderOutcome.rejected("CLOPOS_PAGE_CEILING",
                "Stopped after %d pages; a truncated catalog cannot be compared safely"
                        .formatted(MAX_PAGES)),
                List.of(), MAX_PAGES);
    }

    /**
     * The two headers every authenticated Clopos call carries.
     *
     * <p>{@code x-token} takes the bare token despite the auth response saying
     * {@code token_type: Bearer}; sending {@code Authorization: Bearer} earns a
     * 401 that reads "Headers are missing". {@code x-venue} is what selects the
     * restaurant, and it is the reason a binding is a venue.
     */
    private static Map<String, String> headers(String token, PosContext context) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-token", token);
        if (context.externalVenueReference() != null) {
            headers.put("x-venue", context.externalVenueReference());
        }
        return Map.copyOf(headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> row) {
        Object payload = row.get("payload");
        return payload instanceof Map<?, ?> map ? (Map<String, Object>) map : row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static String fingerprintOf(Map<String, Object> payload) {
        Object products = payload.get("products");
        if (!(products instanceof List<?> list)) {
            return null;
        }
        List<LineFingerprint.Line> lines = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> raw) {
                Map<String, Object> line = (Map<String, Object>) raw;
                java.math.BigDecimal price = CloposEnvelope.decimal(line, "price");
                Object productId = line.get("product_id");
                Object count = line.get("count");
                if (productId == null || !(count instanceof Number quantity)) {
                    continue;
                }
                lines.add(new LineFingerprint.Line(
                        productId instanceof Number number
                                ? Long.toString(number.longValue())
                                : String.valueOf(productId),
                        quantity.intValue(),
                        price == null ? 0L : CloposCatalogNormalizer.minor(price)));
            }
        }
        return lines.isEmpty() ? null : LineFingerprint.of(lines);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !"null".equals(value) && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Object numeric(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notANumber) {
            // Clopos ids are integers, but a misconfigured venue reference should
            // reach Clopos and be refused with its own message rather than throw
            // here and be reported as an adapter fault.
            return value;
        }
    }

    private static Object numericOrNull(String value) {
        return value == null ? null : numeric(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** @param pageCount how many requests the walk took, for the run's record */
    private record PagedRead(ProviderOutcome outcome, List<Map<String, Object>> rows, int pageCount) {

        boolean failed() {
            return outcome.status() != ProviderOutcome.Status.SUCCESS;
        }
    }
}
