package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.domain.Quote;

/** Pricing persistence (ADR 0018). */
@Repository
public class JdbcPricingStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPricingStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * The price book that applies at a location right now.
     *
     * <p>Ordered by scope specificity then priority then id. Every tiebreak is
     * explicit because ADR 0018 forbids row order or wall-clock timing from
     * deciding a price: without the final id tiebreak, two equally-specific books
     * with equal priority would return whichever the planner happened to emit
     * first, and the same cart could price differently on consecutive requests.
     *
     * <p><b>ADR 0036 closes the channel gap.</b> Before it, a sales channel was
     * not an entity, so there was nothing to match {@code scope_id} against: this
     * query matched {@code scope_type = 'CHANNEL' AND scope_id IS NOT NULL} — any
     * channel assignment, whichever channel it named — and ranked it above the
     * brand book, so a price book attached to the kiosk channel priced every
     * storefront order at kiosk prices. That was then narrowed to exclude the
     * scope entirely, which was honest but inert.
     *
     * <p>{@code pricingChannelId} is now bound, and it is the channel's
     * <em>price plane</em> rather than the channel itself: ADR 0036's
     * {@code price_plane_channel_id} is how "for QR and kiosk take the hall's
     * prices" becomes one column instead of a duplicated price book. A null means
     * the caller has no channel, in which case no CHANNEL assignment may match —
     * not "any of them may".
     *
     * <p>Channel outranks location. A channel-scoped book is the deliberate
     * statement "this route is priced differently", and a location book that
     * silently beat it would make an aggregator's agreed price plane depend on
     * which branch fulfilled the order.
     */
    public Optional<PriceBookRow> resolvePriceBook(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable UUID pricingChannelId, Instant at) {
        return jdbc.sql("""
                SELECT pb.id, pb.currency, pb.version, pb.priority
                FROM pricing.price_books pb
                JOIN pricing.price_book_assignments a ON a.price_book_id = pb.id
                WHERE pb.tenant_id = :tenantId AND pb.brand_id = :brandId
                  AND pb.status = 'ACTIVE'
                  AND pb.valid_from <= :at AND (pb.valid_until IS NULL OR pb.valid_until > :at)
                  AND a.valid_from <= :at AND (a.valid_until IS NULL OR a.valid_until > :at)
                  AND (
                        (a.scope_type = 'CHANNEL' AND a.scope_id = :channelId)
                     OR (a.scope_type = 'LOCATION' AND a.scope_id = :locationId)
                     OR (a.scope_type = 'BRAND')
                  )
                ORDER BY
                    CASE a.scope_type WHEN 'CHANNEL' THEN 0 WHEN 'LOCATION' THEN 1 ELSE 2 END,
                    a.priority DESC, pb.priority DESC, pb.id
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", pricingChannelId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) -> new PriceBookRow(
                        row.getObject("id", UUID.class), row.getString("currency"), row.getInt("version")))
                .optional();
    }

    public Optional<TaxProfileRow> resolveTaxProfile(UUID tenantId, UUID brandId, String jurisdictionCode, Instant at) {
        return jdbc.sql("""
                SELECT id, mode, rate_basis_points, version
                FROM pricing.tax_profiles
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND jurisdiction_code = :jurisdiction
                  AND valid_from <= :at AND (valid_until IS NULL OR valid_until > :at)
                ORDER BY valid_from DESC, id
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("jurisdiction", jurisdictionCode)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) -> new TaxProfileRow(
                        row.getObject("id", UUID.class),
                        row.getString("mode"),
                        row.getInt("rate_basis_points"),
                        row.getInt("version")))
                .optional();
    }

    /** Current amounts for a set of priceables, in one round trip. */
    public Map<UUID, Long> pricesFor(UUID priceBookId, String priceableType, Set<UUID> priceableIds, Instant at) {
        if (priceableIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> prices = new HashMap<>();
        jdbc.sql("""
                SELECT priceable_id, amount_minor FROM pricing.prices
                WHERE price_book_id = :priceBookId AND priceable_type = :type
                  AND priceable_id = ANY(:ids)
                  AND valid_from <= :at AND (valid_until IS NULL OR valid_until > :at)
                """)
                .param("priceBookId", priceBookId)
                .param("type", priceableType)
                .param("ids", priceableIds.toArray(UUID[]::new))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) ->
                        Map.entry(row.getObject("priceable_id", UUID.class), row.getLong("amount_minor")))
                .list()
                .forEach(entry -> prices.put(entry.getKey(), entry.getValue()));
        return prices;
    }

    /** Which of these variants have an active price anywhere in the brand. */
    public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds, Instant at) {
        if (variantIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT p.priceable_id
                FROM pricing.prices p
                JOIN pricing.price_books pb ON pb.id = p.price_book_id
                WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId
                  AND p.priceable_type = 'VARIANT' AND p.priceable_id = ANY(:ids)
                  AND pb.status = 'ACTIVE'
                  AND p.valid_from <= :at AND (p.valid_until IS NULL OR p.valid_until > :at)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(UUID.class)
                .list());
    }

    public void insertQuote(Quote quote, @Nullable String idempotencyKey, Map<String, Object> calculationDocument) {
        jdbc.sql("""
                INSERT INTO pricing.quotes (
                    id, tenant_id, brand_id, location_id, customer_account_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash,
                    subtotal_minor, tax_minor, fee_minor, discount_minor, total_minor,
                    calculation_document, expires_at, idempotency_key, created_at)
                VALUES (
                    :id, :tenantId, :brandId, :locationId, :customerId, :currency, :status,
                    :publicationId, :calculationVersion, :contextHash,
                    :subtotal, :tax, :fee, :discount, :total,
                    CAST(:document AS jsonb), :expiresAt, :idempotencyKey, :createdAt)
                """)
                .param("id", quote.quoteId())
                .param("tenantId", quote.tenantId())
                .param("brandId", quote.brandId())
                .param("locationId", quote.locationId())
                .param("customerId", quote.customerAccountId())
                .param("currency", quote.currency())
                .param("status", quote.status().name())
                .param("publicationId", quote.catalogPublicationId())
                .param("calculationVersion", quote.calculationVersion())
                .param("contextHash", quote.contextHash())
                .param("subtotal", quote.subtotal().minor())
                .param("tax", quote.tax().minor())
                .param("fee", quote.fees().minor())
                .param("discount", quote.discount().minor())
                .param("total", quote.total().minor())
                .param("document", objectMapper.writeValueAsString(calculationDocument))
                .param("expiresAt", OffsetDateTime.ofInstant(quote.expiresAt(), ZoneOffset.UTC))
                .param("idempotencyKey", idempotencyKey)
                .param("createdAt", OffsetDateTime.ofInstant(quote.createdAt(), ZoneOffset.UTC))
                .update();

        for (Quote.QuoteLine line : quote.lines()) {
            // A HashMap because variantId is legitimately null on the ADR 0037
            // delivery-fee line, and Map.of rejects a null value outright.
            Map<String, Object> lineParams = new HashMap<>();
            lineParams.put("quoteId", quote.quoteId());
            lineParams.put("lineId", line.lineId());
            lineParams.put("tenantId", quote.tenantId());
            lineParams.put("lineType", line.type().name());
            lineParams.put("variantId", line.variantId());
            lineParams.put("quantity", line.quantity());
            lineParams.put("description", line.descriptionSnapshot());
            lineParams.put("unit", line.unitAmount().minor());
            lineParams.put("base", line.baseAmount().minor());
            lineParams.put("finalAmount", line.finalAmount().minor());
            lineParams.put("tax", line.taxAmount().minor());

            jdbc.sql("""
                    INSERT INTO pricing.quote_lines (
                        quote_id, line_id, tenant_id, line_type, source_variant_id, quantity,
                        description_snapshot, unit_amount_minor, base_amount_minor,
                        final_amount_minor, tax_amount_minor)
                    VALUES (:quoteId, :lineId, :tenantId, :lineType, :variantId, :quantity,
                        :description, :unit, :base, :finalAmount, :tax)
                    """).params(lineParams).update();
        }

        for (Quote.Adjustment adjustment : quote.adjustments()) {
            jdbc.sql("""
                    INSERT INTO pricing.quote_adjustments (
                        quote_id, sequence, tenant_id, line_id, adjustment_type,
                        source_type, source_id, source_version, amount_minor, description_code)
                    VALUES (:quoteId, :sequence, :tenantId, :lineId, :type,
                        :sourceType, :sourceId, :sourceVersion, :amount, :code)
                    """)
                    .param("quoteId", quote.quoteId())
                    .param("sequence", adjustment.sequence())
                    .param("tenantId", quote.tenantId())
                    .param("lineId", adjustment.lineId())
                    .param("type", adjustment.type().name())
                    .param("sourceType", adjustment.sourceType())
                    .param("sourceId", adjustment.sourceId())
                    .param("sourceVersion", adjustment.sourceVersion())
                    .param("amount", adjustment.amount().minor())
                    .param("code", adjustment.descriptionCode())
                    .update();
        }
    }

    public Optional<QuoteRow> findQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT id, status, context_hash, total_minor, currency, expires_at,
                       catalog_publication_id, calculation_version
                FROM pricing.quotes
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", quoteId)
                .query((row, number) -> new QuoteRow(
                        row.getObject("id", UUID.class),
                        Quote.Status.valueOf(row.getString("status")),
                        row.getString("context_hash"),
                        row.getLong("total_minor"),
                        row.getString("currency"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getObject("catalog_publication_id", UUID.class),
                        row.getInt("calculation_version")))
                .optional();
    }

    /**
     * The full priced result, for an order to copy from (ADR 0019).
     *
     * <p>Three queries rather than one join: a join across lines and adjustments
     * multiplies rows, and reassembling it costs more than reading each set once.
     * All three carry the tenant predicate, so a quote id from another tenant
     * returns nothing rather than someone else's basket.
     */
    public Optional<QuoteSnapshot> findQuoteSnapshot(UUID tenantId, UUID quoteId) {
        Optional<QuoteSnapshot> header = jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, customer_account_id, currency, status,
                       catalog_publication_id, context_hash, subtotal_minor, tax_minor, fee_minor,
                       discount_minor, total_minor, expires_at
                FROM pricing.quotes
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", quoteId)
                .query((row, number) -> new QuoteSnapshot(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("currency"),
                        QuoteSnapshot.Status.valueOf(row.getString("status")),
                        row.getObject("catalog_publication_id", UUID.class),
                        row.getString("context_hash"),
                        row.getLong("subtotal_minor"),
                        row.getLong("tax_minor"),
                        row.getLong("fee_minor"),
                        row.getLong("discount_minor"),
                        row.getLong("total_minor"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        List.of(),
                        List.of()))
                .optional();

        if (header.isEmpty()) {
            return header;
        }

        // Item lines only. ADR 0019 snapshots these onto ordering.order_lines,
        // whose source_variant_id is NOT NULL and whose rows are what inventory is
        // decremented against — a delivery-fee line arriving there would be a
        // basket item nobody can cook. The fee reaches the order through
        // fee_minor on the header, which CheckoutService already copies; giving it
        // an order line of its own belongs with the ADR 0014 delivery plan and the
        // fiscal receipt, and is not this change.
        List<QuoteSnapshot.Line> lines = jdbc.sql("""
                SELECT line_id, source_variant_id, quantity, description_snapshot,
                       unit_amount_minor, base_amount_minor, final_amount_minor, tax_amount_minor
                FROM pricing.quote_lines
                WHERE quote_id = :quoteId AND tenant_id = :tenantId AND line_type = 'ITEM'
                ORDER BY line_id
                """)
                .param("quoteId", quoteId)
                .param("tenantId", tenantId)
                .query((row, number) -> new QuoteSnapshot.Line(
                        row.getString("line_id"),
                        row.getObject("source_variant_id", UUID.class),
                        row.getInt("quantity"),
                        row.getString("description_snapshot"),
                        row.getLong("unit_amount_minor"),
                        row.getLong("base_amount_minor"),
                        row.getLong("final_amount_minor"),
                        row.getLong("tax_amount_minor")))
                .list();

        List<QuoteSnapshot.Adjustment> adjustments = jdbc.sql("""
                SELECT sequence, line_id, adjustment_type, source_type, source_id,
                       source_version, amount_minor, description_code
                FROM pricing.quote_adjustments
                WHERE quote_id = :quoteId AND tenant_id = :tenantId
                ORDER BY sequence
                """)
                .param("quoteId", quoteId)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    // Read the nullable integer through getObject rather than
                    // getInt plus wasNull: wasNull refers to the most recent
                    // column read, so any intervening read silently invalidates
                    // it and a null version becomes a real zero.
                    Number sourceVersion = (Number) row.getObject("source_version");
                    return new QuoteSnapshot.Adjustment(
                            row.getInt("sequence"),
                            row.getString("line_id"),
                            row.getString("adjustment_type"),
                            row.getString("source_type"),
                            row.getObject("source_id", UUID.class),
                            sourceVersion == null ? null : sourceVersion.intValue(),
                            row.getLong("amount_minor"),
                            row.getString("description_code"));
                })
                .list();

        QuoteSnapshot found = header.get();
        return Optional.of(new QuoteSnapshot(
                found.quoteId(),
                found.tenantId(),
                found.brandId(),
                found.locationId(),
                found.customerAccountId(),
                found.currency(),
                found.status(),
                found.catalogPublicationId(),
                found.contextHash(),
                found.subtotalMinor(),
                found.taxMinor(),
                found.feeMinor(),
                found.discountMinor(),
                found.totalMinor(),
                found.expiresAt(),
                lines,
                adjustments));
    }

    public Optional<UUID> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT id FROM pricing.quotes
                WHERE tenant_id = :tenantId AND idempotency_key = :key
                """)
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional();
    }

    /**
     * Marks a quote accepted, but only while it is still active and unexpired.
     *
     * <p>The predicate is in the UPDATE rather than checked first, so two
     * concurrent checkouts cannot both observe an active quote and both accept
     * it. The row count tells the caller which one won.
     */
    public boolean acceptQuote(UUID tenantId, UUID quoteId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.quotes
                SET status = 'ACCEPTED', accepted_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND status = 'ACTIVE' AND expires_at > :now
                """)
                        .param("tenantId", tenantId)
                        .param("id", quoteId)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** Sweeps quotes past their TTL so a stale one cannot be accepted later. */
    public int expireQuotes(Instant now) {
        return jdbc.sql("""
                UPDATE pricing.quotes SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND expires_at <= :now
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    // ------------------------------------------------------------------ authoring

    /**
     * Creates a price book, always as a draft.
     *
     * <p>Nothing a draft contains reaches a cart: {@code resolvePriceBook} matches
     * {@code status = 'ACTIVE'} only. So an operator can build a whole seasonal
     * menu, price it wrongly, fix it, and none of it is visible until somebody
     * with {@code pricing.activate} says so.
     */
    public void insertPriceBook(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String name,
            String currency,
            Instant validFrom,
            @Nullable Instant validUntil,
            int priority,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.price_books (
                    id, tenant_id, brand_id, name, currency, status,
                    valid_from, valid_until, priority, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, :currency, 'DRAFT',
                    :validFrom, :validUntil, :priority, 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("name", name)
                .param("currency", currency)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC))
                .param("validUntil", validUntil == null ? null : OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC))
                .param("priority", priority)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * A brand's price books, for the price book list screen.
     *
     * <p>Ordered by priority then name, the same precedence order {@code
     * resolvePriceBook} ranks by, so the book that would win resolution reads
     * first.
     */
    public List<PriceBookSummaryRow> priceBooksForBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, name, currency, status, valid_from, valid_until, priority, version
                FROM pricing.price_books
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY priority DESC, name
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new PriceBookSummaryRow(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("currency"),
                        row.getString("status"),
                        row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        row.getObject("valid_until", OffsetDateTime.class) == null
                                ? null
                                : row.getObject("valid_until", OffsetDateTime.class)
                                        .toInstant(),
                        row.getInt("priority"),
                        row.getInt("version")))
                .list();
    }

    public Optional<PriceBookHeader> findPriceBookHeader(UUID tenantId, UUID brandId, UUID priceBookId) {
        return jdbc.sql("""
                SELECT id, name, currency, status, valid_from, valid_until, priority, version
                FROM pricing.price_books
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", priceBookId)
                .query((row, number) -> new PriceBookHeader(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("currency"),
                        row.getString("status"),
                        row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        row.getObject("valid_until", OffsetDateTime.class) == null
                                ? null
                                : row.getObject("valid_until", OffsetDateTime.class)
                                        .toInstant(),
                        row.getInt("priority"),
                        row.getInt("version")))
                .optional();
    }

    /**
     * Activates a draft, and only if nobody else already did.
     *
     * <p>The status and the version are both in the WHERE clause rather than
     * checked first, so two operators pressing activate on the same book cannot
     * both win: the second updates no rows and learns it lost. A read followed by
     * a write would let both proceed, and the losing one would bump the version
     * of a book somebody else had already put in front of customers.
     */
    public boolean activatePriceBook(UUID tenantId, UUID brandId, UUID priceBookId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.price_books
                SET status = 'ACTIVE', version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                  AND status = 'DRAFT' AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", priceBookId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /**
     * Bumps the book's version because what it prices has changed.
     *
     * <p>Load-bearing, not bookkeeping. A quote's context hash pins the price book
     * by id <em>and version</em> and never by the amounts themselves, so a price
     * edited underneath a book whose version stood still would leave two quotes
     * with identical hashes and different totals — and the hash's whole promise is
     * that it covers every input the total depends on.
     */
    public void touchPriceBook(UUID tenantId, UUID brandId, UUID priceBookId, Instant now) {
        jdbc.sql("""
                UPDATE pricing.price_books
                SET version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", priceBookId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Points a book at a scope, or repoints the one already there.
     *
     * <p>One live assignment per book per scope. A second row saying the same
     * thing adds nothing a resolver can use and ties against itself in the
     * ordering, so repeating the request re-points the existing row instead of
     * accumulating duplicates. The database states no such uniqueness — unlike
     * the one live price per priceable, which {@code ux_price_current} does state
     * and which is therefore not re-checked here.
     */
    public void upsertAssignment(
            UUID tenantId,
            UUID brandId,
            UUID priceBookId,
            String scopeType,
            @Nullable UUID scopeId,
            int priority,
            Instant validFrom,
            @Nullable Instant validUntil) {
        // IS NOT DISTINCT FROM, because a BRAND assignment's scope_id is null and
        // `scope_id = null` matches nothing — which would silently insert a second
        // brand assignment on every repeat.
        int updated = jdbc.sql("""
                UPDATE pricing.price_book_assignments
                SET priority = :priority, valid_from = :validFrom, valid_until = :validUntil
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND price_book_id = :priceBookId AND scope_type = :scopeType
                  AND scope_id IS NOT DISTINCT FROM :scopeId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("priority", priority)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC))
                .param("validUntil", validUntil == null ? null : OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC))
                .update();

        if (updated > 0) {
            return;
        }
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (
                    id, tenant_id, brand_id, price_book_id, scope_type, scope_id,
                    valid_from, valid_until, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :scopeType, :scopeId,
                    :validFrom, :validUntil, :priority)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("priority", priority)
                .param("validFrom", OffsetDateTime.ofInstant(validFrom, ZoneOffset.UTC))
                .param("validUntil", validUntil == null ? null : OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC))
                .update();
    }

    /**
     * Sets what one thing costs in one book, from {@code now} onwards.
     *
     * <p>A price that was already in force is closed rather than overwritten. The
     * old row is the only evidence of what a quote issued yesterday was priced
     * from, and an UPDATE would destroy it — so the history stays and
     * {@code ux_price_current} keeps exactly one open row per priceable.
     *
     * <p>A row that has not started yet is amended in place instead. It has been
     * in force for nobody, so there is no history to keep, and closing it would
     * violate {@code ck_price_window}, which requires the close to come strictly
     * after the open.
     */
    public void setPrice(
            UUID tenantId,
            UUID brandId,
            UUID priceBookId,
            String priceableType,
            UUID priceableId,
            long amountMinor,
            Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        int amended = jdbc.sql("""
                UPDATE pricing.prices
                SET amount_minor = :amount, version = version + 1
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND price_book_id = :priceBookId AND priceable_type = :type
                  AND priceable_id = :priceableId
                  AND valid_until IS NULL AND valid_from >= :at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("type", priceableType)
                .param("priceableId", priceableId)
                .param("amount", amountMinor)
                .param("at", at)
                .update();

        if (amended > 0) {
            return;
        }

        jdbc.sql("""
                UPDATE pricing.prices
                SET valid_until = :at, version = version + 1
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND price_book_id = :priceBookId AND priceable_type = :type
                  AND priceable_id = :priceableId
                  AND valid_until IS NULL AND valid_from < :at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("type", priceableType)
                .param("priceableId", priceableId)
                .param("at", at)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.prices (
                    id, tenant_id, brand_id, price_book_id, priceable_type, priceable_id,
                    amount_minor, valid_from, version)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :type, :priceableId,
                    :amount, :at, 1)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("type", priceableType)
                .param("priceableId", priceableId)
                .param("amount", amountMinor)
                .param("at", at)
                .update();
    }

    /** How many things this book currently prices. */
    public long openPriceCount(UUID tenantId, UUID brandId, UUID priceBookId) {
        return jdbc.sql("""
                SELECT count(*) FROM pricing.prices
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND price_book_id = :priceBookId AND valid_until IS NULL
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .query(Long.class)
                .single();
    }

    /**
     * Whether activating this book would leave two books tied for the same scope.
     *
     * <p>ADR 0018 rejects overlapping assignments of equal precedence at
     * validation time, and this is that check. Resolution orders by scope
     * specificity, then assignment priority, then book priority, then id — so a
     * tie on all three does still resolve, deterministically, by an id nobody
     * chose. That is worse than a refusal: the operator gets a price they did not
     * pick and nothing anywhere says why.
     *
     * <p>Overlap is computed on the intersection of the book window and the
     * assignment window, because both must contain an instant for the book to
     * price anything at it. {@code LEAST} ignores nulls, so an open-ended window
     * needs the explicit infinity rather than collapsing the comparison.
     */
    public boolean tiesWithALivePriceBook(UUID tenantId, UUID brandId, UUID priceBookId) {
        return jdbc.sql("""
                SELECT count(*)
                FROM pricing.price_books mine
                JOIN pricing.price_book_assignments ma ON ma.price_book_id = mine.id
                JOIN pricing.price_book_assignments oa
                       ON oa.tenant_id = mine.tenant_id AND oa.brand_id = mine.brand_id
                      AND oa.scope_type = ma.scope_type
                      AND oa.scope_id IS NOT DISTINCT FROM ma.scope_id
                      AND oa.priority = ma.priority
                      AND oa.price_book_id <> mine.id
                JOIN pricing.price_books other
                       ON other.id = oa.price_book_id
                      AND other.status = 'ACTIVE'
                      AND other.priority = mine.priority
                WHERE mine.tenant_id = :tenantId AND mine.brand_id = :brandId AND mine.id = :id
                  AND GREATEST(mine.valid_from, ma.valid_from)
                        < COALESCE(LEAST(other.valid_until, oa.valid_until), 'infinity'::timestamptz)
                  AND GREATEST(other.valid_from, oa.valid_from)
                        < COALESCE(LEAST(mine.valid_until, ma.valid_until), 'infinity'::timestamptz)
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", priceBookId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Supersedes the brand's tax profile for a jurisdiction, or writes its first.
     *
     * <p>Same shape as a price change and for the same reason: the old rate is
     * what an already-issued quote extracted its VAT at, and rewriting it would
     * make a filed fiscal total unreproducible.
     *
     * <p>Returns empty when another operator closed the same profile first. The
     * insert is conditional on there being no open row, so the loser writes
     * nothing rather than leaving two open profiles for the resolver to pick
     * between.
     */
    public Optional<UUID> supersedeTaxProfile(
            UUID tenantId, UUID brandId, String jurisdictionCode, String mode, int rateBasisPoints, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        int amended = jdbc.sql("""
                UPDATE pricing.tax_profiles
                SET mode = :mode, rate_basis_points = :rate, version = version + 1
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND jurisdiction_code = :jurisdiction
                  AND valid_until IS NULL AND valid_from >= :at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("jurisdiction", jurisdictionCode)
                .param("mode", mode)
                .param("rate", rateBasisPoints)
                .param("at", at)
                .update();

        if (amended > 0) {
            return findTaxProfileId(tenantId, brandId, jurisdictionCode);
        }

        jdbc.sql("""
                UPDATE pricing.tax_profiles
                SET valid_until = :at, version = version + 1
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND jurisdiction_code = :jurisdiction
                  AND valid_until IS NULL AND valid_from < :at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("jurisdiction", jurisdictionCode)
                .param("at", at)
                .update();

        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO pricing.tax_profiles (
                    id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points,
                    valid_from, version)
                SELECT :id, :tenantId, :brandId, :jurisdiction, :mode, :rate, :at, 1
                WHERE NOT EXISTS (
                    SELECT 1 FROM pricing.tax_profiles
                    WHERE tenant_id = :tenantId AND brand_id = :brandId
                      AND jurisdiction_code = :jurisdiction AND valid_until IS NULL)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("jurisdiction", jurisdictionCode)
                .param("mode", mode)
                .param("rate", rateBasisPoints)
                .param("at", at)
                .update();

        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<TaxProfileHeader> findTaxProfileHeader(UUID tenantId, UUID brandId, String jurisdictionCode) {
        return jdbc.sql("""
                SELECT id, jurisdiction_code, mode, rate_basis_points, valid_from, version
                FROM pricing.tax_profiles
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND jurisdiction_code = :jurisdiction AND valid_until IS NULL
                ORDER BY valid_from DESC, id
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("jurisdiction", jurisdictionCode)
                .query((row, number) -> new TaxProfileHeader(
                        row.getObject("id", UUID.class),
                        row.getString("jurisdiction_code"),
                        row.getString("mode"),
                        row.getInt("rate_basis_points"),
                        row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        row.getInt("version")))
                .optional();
    }

    private Optional<UUID> findTaxProfileId(UUID tenantId, UUID brandId, String jurisdictionCode) {
        return findTaxProfileHeader(tenantId, brandId, jurisdictionCode).map(TaxProfileHeader::id);
    }

    public record PriceBookRow(UUID id, String currency, int version) {}

    public record TaxProfileRow(UUID id, String mode, int rateBasisPoints, int version) {}

    public record PriceBookHeader(
            UUID id,
            String name,
            String currency,
            String status,
            Instant validFrom,
            @Nullable Instant validUntil,
            int priority,
            int version) {}

    /** One row of {@link #priceBooksForBrand}. Same fields as {@link PriceBookHeader}, listed rather than singular. */
    public record PriceBookSummaryRow(
            UUID id,
            String name,
            String currency,
            String status,
            Instant validFrom,
            @Nullable Instant validUntil,
            int priority,
            int version) {}

    public record TaxProfileHeader(
            UUID id, String jurisdictionCode, String mode, int rateBasisPoints, Instant validFrom, int version) {}

    public record QuoteRow(
            UUID id,
            Quote.Status status,
            String contextHash,
            long totalMinor,
            String currency,
            Instant expiresAt,
            UUID catalogPublicationId,
            int calculationVersion) {}
}
