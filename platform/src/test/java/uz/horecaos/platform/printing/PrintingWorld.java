package uz.horecaos.platform.printing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.support.CommentPresetRows;

/**
 * The rows a printing test needs and nothing else (ADR 0154): a tenant with two branches, a sales channel and a
 * catalogue publication, two kitchen stations, a company assigned as the seller, and orders inserted with their
 * lines, modifiers, coded presets, an encrypted note and an encrypted customer -- the way a checkout leaves
 * them, without running one. Checkout is ADR 0019's own suite; what printing needs from an order is what is
 * on it.
 *
 * <p>The customer's name, phone and address are seeded with recognisable text so that a test can prove none
 * of it reaches a stored document.
 */
final class PrintingWorld {

    static final UUID TENANT = UUID.fromString("018fc154-1000-7000-8000-0000000000a1");
    static final UUID BRAND = UUID.fromString("018fc154-1000-7000-8000-0000000000b1");
    static final UUID LOCATION = UUID.fromString("018fc154-1000-7000-8000-0000000000c1");
    static final UUID SIBLING = UUID.fromString("018fc154-1000-7000-8000-0000000000c2");
    static final UUID GRILL = UUID.fromString("018fc154-1000-7000-8000-0000000000d1");
    static final UUID BAR = UUID.fromString("018fc154-1000-7000-8000-0000000000d2");
    static final UUID SIBLINGS_STATION = UUID.fromString("018fc154-1000-7000-8000-0000000000d3");
    static final UUID LEGAL_ENTITY = UUID.fromString("018fc154-1000-7000-8000-0000000000ee");

    static final UUID OTHER_TENANT = UUID.fromString("018fc154-2000-7000-8000-0000000000a1");
    static final UUID OTHER_BRAND = UUID.fromString("018fc154-2000-7000-8000-0000000000b1");
    static final UUID OTHER_LOCATION = UUID.fromString("018fc154-2000-7000-8000-0000000000c1");

    static final String CUSTOMER_NAME = "Azizbek Karimov";
    static final String CUSTOMER_PHONE = "+998901234567";
    static final String CUSTOMER_ADDRESS = "Mustaqillik 77, flat 12, Tashkent";

    private final JdbcClient jdbc;
    private final FieldProtection protection;
    private UUID channelId = UUID.randomUUID();
    private UUID publicationId = UUID.randomUUID();
    private int sequence;

    PrintingWorld(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    /** What an order line is made of. */
    record LineSpec(
            String product,
            @Nullable String variant,
            BigDecimal quantity,
            long finalAmountMinor,
            @Nullable String note,
            List<String> modifiers,
            List<String> presetCodes,
            @Nullable UUID comboSelection,
            @Nullable String comboName) {

        static LineSpec of(String product, int quantity, long finalAmountMinor) {
            return new LineSpec(
                    product,
                    null,
                    BigDecimal.valueOf(quantity),
                    finalAmountMinor,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null);
        }

        LineSpec withNote(String text) {
            return new LineSpec(
                    product,
                    variant,
                    quantity,
                    finalAmountMinor,
                    text,
                    modifiers,
                    presetCodes,
                    comboSelection,
                    comboName);
        }

        LineSpec withModifier(String name) {
            List<String> all = new ArrayList<>(modifiers);
            all.add(name);
            return new LineSpec(
                    product, variant, quantity, finalAmountMinor, note, all, presetCodes, comboSelection, comboName);
        }

        LineSpec withPreset(String code) {
            List<String> all = new ArrayList<>(presetCodes);
            all.add(code);
            return new LineSpec(
                    product, variant, quantity, finalAmountMinor, note, modifiers, all, comboSelection, comboName);
        }

        LineSpec inCombo(UUID selection, String name) {
            return new LineSpec(
                    product, variant, quantity, finalAmountMinor, note, modifiers, presetCodes, selection, name);
        }
    }

    record SeededOrder(UUID orderId, List<UUID> lineIds, long totalMinor) {}

    // ------------------------------------------------------------------ the tenant

    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE printing.print_jobs, printing.print_routes, printing.receipt_profiles, "
                        + "printing.agent_printer_adverts, printing.printers CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fiscal.fiscal_documents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE kitchen.ticket_events, kitchen.ticket_items, kitchen.tickets, "
                        + "kitchen.location_routing_rules, kitchen.brand_routing_rules, "
                        + "kitchen.stations CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.order_revisions, "
                        + "ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        sequence = 0;
        channelId = UUID.randomUUID();
        publicationId = UUID.randomUUID();
        seedTenant(
                TENANT,
                BRAND,
                "printing-w1",
                List.of(
                        location(LOCATION, "CHI", "chilanzar", "Chilanzar"),
                        location(SIBLING, "YUN", "yunusobod", "Yunusobod")));
        seedTenant(
                OTHER_TENANT,
                OTHER_BRAND,
                "printing-other",
                List.of(location(OTHER_LOCATION, "OTH", "other", "Other")));
        seedChannelAndCatalogue();
        station(GRILL, LOCATION, "GRILL", "GRILL", "Гриль", "Gril", "Grill", false);
        station(BAR, LOCATION, "BAR", "BAR", "Бар", "Bar", "Bar", false);
        station(SIBLINGS_STATION, SIBLING, "GRILL", "GRILL", "Гриль", "Gril", "Grill", false);
        seedSeller();
    }

    private record Place(UUID id, String code, String slug, String name) {}

    private static Place location(UUID id, String code, String slug, String name) {
        return new Place(id, code, slug, name);
    }

    private void seedTenant(UUID tenant, UUID brand, String slug, List<Place> places) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brand).param("tenantId", tenant).update();
        for (Place place : places) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version, address_line, district, city, contact_phone)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0,
                            'Bunyodkor 12', 'Chilonzor', 'Tashkent', '+998712000000')
                    """)
                    .param("id", place.id())
                    .param("tenantId", tenant)
                    .param("brandId", brand)
                    .param("code", place.code())
                    .param("slug", place.slug())
                    .param("name", place.name())
                    .update();
        }
    }

    private void seedChannelAndCatalogue() {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void station(
            UUID id, UUID location, String code, String role, String ru, String uz, String en, boolean fallback) {
        jdbc.sql("""
                INSERT INTO kitchen.stations
                    (id, tenant_id, brand_id, location_id, code, role, display_name_ru, display_name_uz,
                     display_name_en, sort_order, is_fallback, status, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :code, :role, :ru, :uz, :en, 0, :fallback, 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("code", code)
                .param("role", role)
                .param("ru", ru)
                .param("uz", uz)
                .param("en", en)
                .param("fallback", fallback)
                .update();
    }

    /** A fallback station for a location, so a real ticket can be opened there. */
    void fallbackStation(UUID id, UUID location) {
        station(id, location, "PASS", "EXPO", "Раздача", "Tarqatish", "Pass", true);
    }

    private void seedSeller() {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status,
                    registered_address, contact_phone, version)
                VALUES (:id, :tenantId, 'LE-1', 'Sinov MCHJ', '123456789', true, 'ACTIVE',
                        'Bunyodkor 12, Tashkent', '+998712000001', 1)
                """).param("id", LEGAL_ENTITY).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id, location_id, legal_entity_id,
                    effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :entityId, :from, 'owner')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("entityId", LEGAL_ENTITY)
                .param("from", LocalDate.of(2020, 1, 1))
                .update();
    }

    // ----------------------------------------------------------------- an order

    /** The version a seeded order was confirmed at: what its history's transition to {@code CONFIRMED} carries. */
    static final int CONFIRMED_AT_VERSION = 3;

    /**
     * An order at {@code CONFIRMED} with its lines, inserted rather than checked out. The total is the sum of the
     * lines' final amounts, so the order reconciles the way a real one does. It carries what a checkout leaves
     * besides: the history row of its transition to {@code CONFIRMED}, whose sequence number is the version the
     * order was confirmed at ({@link #CONFIRMED_AT_VERSION}) -- the number an {@code OrderConfirmed} names.
     */
    SeededOrder order(
            UUID location, String number, @Nullable Long cashTendered, boolean withCustomer, LineSpec... lines) {
        return insertOrder(location, number, cashTendered, withCustomer, true, lines);
    }

    /**
     * An order that was never transitioned into {@code CONFIRMED}: inserted already confirmed, at version 1, with
     * no history and no event -- the way an aggregator's delivery order arrives.
     */
    SeededOrder bornConfirmedOrder(UUID location, String number, LineSpec... lines) {
        return insertOrder(location, number, null, false, false, lines);
    }

    private SeededOrder insertOrder(
            UUID location,
            String number,
            @Nullable Long cashTendered,
            boolean withCustomer,
            boolean transitioned,
            LineSpec... lines) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        long total = 0;
        for (LineSpec line : lines) {
            total += line.finalAmountMinor();
        }
        String guest = "guest-" + number + "-" + (++sequence);

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash', :total, 0, :total,
                        now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("publicationId", publicationId)
                .param("total", total)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id, fulfillment_mode, currency,
                    status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'PICKUP', 'UZS', 'ACTIVE', :guest,
                        now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("channelId", channelId)
                .param("guest", guest)
                .update();

        Map<String, Object> params = new HashMap<>();
        params.put("id", orderId);
        params.put("number", number);
        params.put("tenantId", TENANT);
        params.put("brandId", BRAND);
        params.put("locationId", location);
        params.put("channelId", channelId);
        params.put("quoteId", quoteId);
        params.put("cartId", cartId);
        params.put("publicationId", publicationId);
        params.put("guest", guest);
        params.put("total", total);
        params.put("cash", cashTendered);
        params.put("confirmedAt", OffsetDateTime.now(ZoneOffset.UTC));
        params.put("version", transitioned ? CONFIRMED_AT_VERSION : 1);
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    promise_basis, cash_tendered_expected_minor, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'TELEGRAM', :guest, 'PICKUP',
                    'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS', :total, 0, :total, :quoteId, 'hash',
                    :publicationId, :cartId, :guest, 'NOT_PROMISED', CAST(:cash AS bigint), :version, :confirmedAt)
                """).params(params).update();
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor, total_minor)
                VALUES (:orderId, 1, :tenantId, 'CHECKOUT', :quoteId, 'hash', 'UZS', :total, 0, :total)
                """)
                .param("orderId", orderId)
                .param("tenantId", TENANT)
                .param("quoteId", quoteId)
                .param("total", total)
                .update();

        List<UUID> lineIds = new ArrayList<>();
        int lineNumber = 1;
        for (LineSpec line : lines) {
            UUID lineId = UUID.randomUUID();
            lineIds.add(lineId);
            insertLine(orderId, lineId, lineNumber++, line);
        }
        if (withCustomer) {
            customerSnapshot(orderId);
        }
        if (transitioned) {
            // The history allocates its sequence from the order's version (V0022): the row of the transition to
            // CONFIRMED is the record of the version the order was confirmed at.
            jdbc.sql("""
                    INSERT INTO ordering.order_state_history (id, tenant_id, order_id, sequence_number, from_status,
                        to_status, trigger, actor_type, actor_id, occurred_at)
                    VALUES (:id, :tenantId, :orderId, :sequence, 'RECEIVED', 'CONFIRMED', 'CHECKOUT', 'SYSTEM_JOB',
                        'order-acceptance-policy', :at)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("orderId", orderId)
                    .param("sequence", CONFIRMED_AT_VERSION)
                    .param("at", params.get("confirmedAt"))
                    .update();
        }
        return new SeededOrder(orderId, lineIds, total);
    }

    private void insertLine(UUID orderId, UUID lineId, int lineNumber, LineSpec line) {
        String noteCipher = line.note() == null
                ? null
                : protection
                        .protect(
                                TENANT,
                                DataClass.PERSONAL,
                                new FieldProtection.RecordRef("ordering.order_lines", "note_encrypted", lineId),
                                line.note())
                        .serialize();
        boolean combo = line.comboSelection() != null;
        jdbc.sql("""
                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_product_id,
                    source_variant_id, product_name_snapshot, variant_name_snapshot, quantity, unit_amount_minor,
                    base_amount_minor, final_amount_minor, tax_amount_minor, note_encrypted,
                    combo_selection_id, combo_container_variant_id, combo_name_snapshot, combo_component_id,
                    combo_quantity, combo_pick_quantity)
                VALUES (:id, :tenantId, :orderId, :lineNumber, :productId, :variantId, :product, :variant, :quantity,
                    :final, :final, :final, 0, :note,
                    :selection, :container, :comboName, :component, :comboQuantity, :comboPick)
                """)
                .param("id", lineId)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .param("productId", UUID.randomUUID())
                .param("variantId", UUID.randomUUID())
                .param("product", line.product())
                .param("variant", line.variant())
                .param("quantity", line.quantity())
                .param("final", line.finalAmountMinor())
                .param("note", noteCipher)
                .param("selection", line.comboSelection())
                .param("container", combo ? UUID.randomUUID() : null)
                .param("comboName", line.comboName())
                .param("component", combo ? UUID.randomUUID() : null)
                .param("comboQuantity", combo ? 1 : null)
                .param("comboPick", combo ? 1 : null)
                .update();
        for (String modifier : line.modifiers()) {
            jdbc.sql("""
                    INSERT INTO ordering.order_line_modifiers (id, tenant_id, order_line_id, source_group_id,
                        source_option_id, group_name_snapshot, option_name_snapshot, quantity, unit_amount_minor,
                        final_amount_minor)
                    VALUES (:id, :tenantId, :lineId, :group, :option, 'Extras', :name, 1, 0, 0)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("lineId", lineId)
                    .param("group", UUID.randomUUID())
                    .param("option", UUID.randomUUID())
                    .param("name", modifier)
                    .update();
        }
        int sort = 0;
        for (String code : line.presetCodes()) {
            UUID snapshotId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO ordering.order_line_comment_presets (id, tenant_id, order_line_id, source_preset_id,
                        code_snapshot, sort_order)
                    VALUES (:id, :tenantId, :lineId, :preset, :code, :sort)
                    """)
                    .param("id", snapshotId)
                    .param("tenantId", TENANT)
                    .param("lineId", lineId)
                    .param("preset", UUID.randomUUID())
                    .param("code", code)
                    .param("sort", sort++)
                    .update();
            CommentPresetRows.insertSnapshotLabels(
                    jdbc, snapshotId, TENANT, CommentPresetRows.triple("Без лука", "Piyozsiz", "No onion"));
        }
    }

    private void customerSnapshot(UUID orderId) {
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (order_id, tenant_id, display_name_encrypted,
                    contact_encrypted, address_encrypted)
                VALUES (:orderId, :tenantId, :name, :phone, :address)
                """)
                .param("orderId", orderId)
                .param("tenantId", TENANT)
                .param("name", encrypt(orderId, "display_name_encrypted", CUSTOMER_NAME))
                .param("phone", encrypt(orderId, "contact_encrypted", CUSTOMER_PHONE))
                .param("address", encrypt(orderId, "address_encrypted", CUSTOMER_ADDRESS))
                .update();
    }

    private String encrypt(UUID orderId, String column, String plaintext) {
        return protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("ordering.order_customer_snapshots", column, orderId),
                        plaintext)
                .serialize();
    }

    // ----------------------------------------------------------------- a ticket

    /**
     * A released ticket for {@code order}, with an item per line at the station named for it. Inserted rather than
     * opened: routing is the kitchen's own suite, and what printing needs is which station holds which line.
     */
    UUID releasedTicket(
            SeededOrder order, UUID location, String label, Map<Integer, UUID> stationOfLine, Instant releasedAt) {
        UUID ticketId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO kitchen.tickets (id, tenant_id, brand_id, location_id, order_id, sequence_label,
                    fulfilment_mode, channel_code, status, release_mode, released_at, target_ready_at,
                    routing_version, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :label, 'PICKUP', 'TELEGRAM', 'FIRED',
                    'AUTO_ON_CONFIRM', :releasedAt, :target, 1, 1)
                """)
                .param("id", ticketId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", location)
                .param("orderId", order.orderId())
                .param("label", label)
                .param("releasedAt", releasedAt.atOffset(ZoneOffset.UTC))
                .param("target", releasedAt.plusSeconds(1200).atOffset(ZoneOffset.UTC))
                .update();
        stationOfLine.forEach((index, station) -> jdbc.sql("""
                        INSERT INTO kitchen.ticket_items (id, tenant_id, ticket_id, location_id, order_line_id,
                            station_id, quantity, routed_by, status, combo_selection_id, combo_container_variant_id)
                        SELECT :id, :tenantId, :ticketId, :locationId, l.id, :station, l.quantity, 'FALLBACK',
                               'QUEUED', l.combo_selection_id, l.combo_container_variant_id
                          FROM ordering.order_lines l
                         WHERE l.id = :lineId AND l.tenant_id = :tenantId
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("ticketId", ticketId)
                .param("locationId", location)
                .param("station", station)
                .param("lineId", order.lineIds().get(index))
                .update());
        return ticketId;
    }

    // ------------------------------------------------------------ fiscal document

    /** A fiscal document of the order in the given state, with a payment intent so the sent line has a total. */
    UUID fiscalDocument(SeededOrder order, String type, String status) {
        UUID intentId = jdbc.sql("SELECT id FROM payments.payment_intents WHERE tenant_id = :t AND order_id = :o")
                .param("t", TENANT)
                .param("o", order.orderId())
                .query(UUID.class)
                .optional()
                .orElseGet(() -> insertIntent(order));
        UUID documentId = UUID.randomUUID();
        boolean issued = "ISSUED".equals(status);
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id, payment_intent_id,
                    provider_type, document_type, status, reason_code, reason_note, external_receipt_id, fiscal_sign,
                    terminal_id, receipt_reference, registered_at, receipt_url, submitted_at, issued_at,
                    corrects_document_id)
                VALUES (:id, :tenantId, :orderId, :entity, :intent, 'CLICK', :type, :status, 'PARTNER', 'test',
                    :external, :sign, :terminal, :reference, :registered, :url, :submitted, :issued, :corrects)
                """)
                .param("id", documentId)
                .param("corrects", "SALE".equals(type) ? null : saleOf(order))
                .param("tenantId", TENANT)
                .param("orderId", order.orderId())
                .param("entity", LEGAL_ENTITY)
                .param("intent", intentId)
                .param("type", type)
                .param("status", status)
                .param("external", issued ? "ext-1" : null)
                .param("sign", issued ? "987654321098" : null)
                .param("terminal", issued ? "EP000000000000" : null)
                .param("reference", issued ? "1234567" : null)
                .param("registered", issued ? OffsetDateTime.parse("2026-10-08T07:05:01Z") : null)
                .param(
                        "url",
                        issued
                                ? "https://ofd.soliq.uz/epi?t=EP000000000000&r=1234567&c=20261008120501&s=987654321098"
                                : null)
                .param("submitted", "PENDING".equals(status) ? null : OffsetDateTime.now(ZoneOffset.UTC))
                .param("issued", issued ? OffsetDateTime.now(ZoneOffset.UTC) : null)
                .update();
        return documentId;
    }

    /** The fiscal provider's answer arriving: a submitted document becomes issued, with the evidence a receipt prints. */
    void issue(UUID documentId) {
        jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                   SET status = 'ISSUED', external_receipt_id = 'ext-1', fiscal_sign = '987654321098',
                       terminal_id = 'EP000000000000', receipt_reference = '1234567',
                       registered_at = :registered, receipt_url = :url, issued_at = now()
                 WHERE id = :id
                """)
                .param("id", documentId)
                .param("registered", OffsetDateTime.parse("2026-10-08T07:05:01Z"))
                .param("url", "https://ofd.soliq.uz/epi?t=EP000000000000&r=1234567&c=20261008120501&s=987654321098")
                .update();
    }

    /** The sale a refund corrects: a correction names the document it corrects, and that is a database fact. */
    private UUID saleOf(SeededOrder order) {
        return jdbc.sql(
                        "SELECT id FROM fiscal.fiscal_documents WHERE tenant_id = :t AND order_id = :o AND document_type = 'SALE'")
                .param("t", TENANT)
                .param("o", order.orderId())
                .query(UUID.class)
                .single();
    }

    private UUID insertIntent(SeededOrder order) {
        UUID intentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id, location_id, legal_entity_id,
                    tender, payment_method_code, requested_amount_minor, currency, status, capture_timing,
                    idempotency_key, created_at, settled_at)
                VALUES (:id, :tenantId, :orderId, :brandId, :locationId, :entity, 'CASH', 'CASH', :amount, 'UZS',
                    'PAID', 'ON_HANDOVER', :key, now(), now())
                """)
                .param("id", intentId)
                .param("tenantId", TENANT)
                .param("orderId", order.orderId())
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("entity", LEGAL_ENTITY)
                .param("amount", order.totalMinor())
                .param("key", "intent-" + UUID.randomUUID())
                .update();
        return intentId;
    }
}
