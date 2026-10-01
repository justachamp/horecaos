package uz.horecaos.platform.ordering;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real-schema fixtures for the order board's tests: tenants with several
 * brands and branches, orders that differ in the one attribute a test is
 * about, the aggregator bindings V0038 refuses an order without, process
 * states and fiscal documents.
 *
 * <p>Shared by the store-level board tests and the brand-board HTTP tests so
 * that neither invents a fixture production never builds: every order is
 * inserted with the cart and quote a HorecaOS-origin order really has, and every
 * MARKETPLACE order names a binding that {@code ordering.assert_marketplace_binding}
 * accepts. Ids are derived from seeds, so a test names the order it means.
 */
public final class OrderBoardFixtures {

    /** Every fixture order is placed relative to this, newest last. */
    public static final Instant NOON = Instant.parse("2026-09-10T12:00:00Z");

    private final JdbcClient jdbc;

    public OrderBoardFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ setup

    /** Empties every table the fixtures write, in dependency order. */
    public void clean() {
        jdbc.sql("TRUNCATE TABLE fulfillment.shipments, fulfillment.delivery_plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_types CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /** A tenant with one brand and one branch, and the customer, channel and menu an order needs. */
    public void tenant(UUID tenantId, String slug, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", customerOf(tenantId)).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelOf(tenantId)).param("t", tenantId).update();
        brand(tenantId, slug, brandId, locationId);
    }

    /** Another brand of an existing tenant, with its own first branch and menu. */
    public void brand(UUID tenantId, String slug, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", tenantId)
                .param("code", "B" + brandId.toString().substring(30).toUpperCase(java.util.Locale.ROOT))
                .param("slug", slug + "-brand-" + brandId.toString().substring(30))
                .update();
        location(
                tenantId,
                brandId,
                locationId,
                "CHI" + locationId.toString().substring(30).toUpperCase(java.util.Locale.ROOT),
                "Chilonzor");

        UUID catalogId = derived("catalog:" + brandId);
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationOf(brandId))
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
    }

    /** Another branch of an existing brand. */
    public void location(UUID tenantId, UUID brandId, UUID locationId, String code, String displayName) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", "loc-" + locationId)
                .param("name", displayName)
                .update();
    }

    /**
     * One {@code MARKETPLACE}-category installation and one binding of it, bound to
     * {@code locationId} — or brand-wide when {@code locationId} is null. The shape
     * {@code AggregatorOrderIntakeServiceTests} proves the real intake against.
     */
    public void marketplaceBinding(
            UUID tenantId,
            UUID bindingId,
            UUID brandId,
            @Nullable UUID locationId,
            String providerType,
            String displayName) {
        jdbc.sql("""
                        INSERT INTO integration.provider_environments (
                            code, provider_category, provider_type, base_url, is_production,
                            egress_allowlist)
                        VALUES (:env, 'MARKETPLACE', :provider, 'https://example.test', false, 'example.test')
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("env", "board-tests-" + providerType.toLowerCase(java.util.Locale.ROOT))
                .param("provider", providerType)
                .update();

        UUID installationId = derived("installation:" + bindingId);
        jdbc.sql("""
                        INSERT INTO integration.installations (
                            id, tenant_id, provider_category, provider_type, environment_code,
                            display_name, status)
                        VALUES (:id, :t, 'MARKETPLACE', :provider, :env, :name, 'ACTIVE')
                        """)
                .param("id", installationId)
                .param("t", tenantId)
                .param("provider", providerType)
                .param("env", "board-tests-" + providerType.toLowerCase(java.util.Locale.ROOT))
                .param("name", displayName)
                .update();

        jdbc.sql("""
                        INSERT INTO integration.bindings (
                            id, tenant_id, installation_id, brand_id, location_id, status,
                            effective_from)
                        VALUES (:id, :t, :installationId, :b, :loc, 'ACTIVE', :now)
                        """)
                .param("id", bindingId)
                .param("t", tenantId)
                .param("installationId", installationId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("now", NOON.atOffset(ZoneOffset.UTC))
                .update();
    }

    // ----------------------------------------------------------------- orders

    public static OrderSpec order(String seed) {
        return new OrderSpec(seed);
    }

    /** One fixture order, with the board-relevant attributes it may differ in. */
    public static final class OrderSpec {
        final String seed;

        @Nullable
        UUID tenant;

        @Nullable
        UUID brand;

        @Nullable
        UUID location;

        Instant createdAt = NOON;
        String status = "RECEIVED";
        String mode = "DELIVERY";
        String channelCode = "TELEGRAM";

        @Nullable
        String createdByActorId;

        boolean confirmed;
        String origin = "HORECAOS";
        String paymentStatusProjection = "NOT_REQUIRED";
        boolean callbackRequested;
        boolean unpromised;

        @Nullable
        Instant promisedAt;

        @Nullable
        UUID marketplaceBindingId;

        private OrderSpec(String seed) {
            this.seed = seed;
        }

        /** The tenant, brand and branch this order belongs to. */
        public OrderSpec at(UUID tenantId, UUID brandId, UUID locationId) {
            this.tenant = tenantId;
            this.brand = brandId;
            this.location = locationId;
            return this;
        }

        public OrderSpec createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public OrderSpec status(String value) {
            this.status = value;
            return this;
        }

        public OrderSpec mode(String value) {
            this.mode = value;
            return this;
        }

        public OrderSpec channelCode(String value) {
            this.channelCode = value;
            return this;
        }

        public OrderSpec createdByActorId(String value) {
            this.createdByActorId = value;
            return this;
        }

        public OrderSpec confirmed() {
            this.confirmed = true;
            return this;
        }

        /** An aggregator order recorded under {@code bindingId}. */
        public OrderSpec marketplace(UUID bindingId) {
            this.origin = "MARKETPLACE";
            this.marketplaceBindingId = bindingId;
            return this;
        }

        public OrderSpec paymentStatusProjection(String value) {
            this.paymentStatusProjection = value;
            return this;
        }

        public OrderSpec callbackRequested() {
            this.callbackRequested = true;
            return this;
        }

        /** No promise was ever made (basis {@code NOT_PROMISED}). */
        public OrderSpec unpromised() {
            this.unpromised = true;
            return this;
        }

        /** The promise, absolute — otherwise thirty-five minutes after {@code createdAt}. */
        public OrderSpec promisedAt(Instant value) {
            this.promisedAt = value;
            return this;
        }
    }

    public UUID insertOrder(OrderSpec spec) {
        UUID tenantId = Objects.requireNonNull(spec.tenant, "OrderSpec.at(tenant, brand, location) is required");
        UUID brandId = Objects.requireNonNull(spec.brand, "OrderSpec.at(tenant, brand, location) is required");
        UUID locationId = Objects.requireNonNull(spec.location, "OrderSpec.at(tenant, brand, location) is required");
        UUID orderId = derived("order:" + tenantId + spec.seed);
        // A MARKETPLACE-origin order has no HorecaOS cart or quote behind it —
        // ck_order_cart_matches_origin and ck_order_quote_matches_authority
        // (V0038) refuse one that carries either, so neither row is inserted.
        boolean marketplace = "MARKETPLACE".equals(spec.origin);
        @Nullable UUID cartId = marketplace ? null : derived("cart:" + tenantId + spec.seed);
        @Nullable UUID quoteId = marketplace ? null : derived("quote:" + tenantId + spec.seed);

        if (!marketplace) {
            jdbc.sql("""
                    INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                        customer_account_id, fulfillment_mode, currency, status, expires_at,
                        converted_order_id)
                    VALUES (:id, :t, :b, :loc, :ch, :cust, :mode, 'UZS', 'CONVERTED', :expires, :orderId)
                    """)
                    .param("id", cartId)
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("loc", locationId)
                    .param("ch", channelOf(tenantId))
                    .param("cust", customerOf(tenantId))
                    .param("mode", spec.mode)
                    .param("expires", spec.createdAt.atOffset(ZoneOffset.UTC))
                    .param("orderId", orderId)
                    .update();

            jdbc.sql("""
                    INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                        customer_account_id, currency, status, catalog_publication_id,
                        calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                        discount_minor, total_minor, expires_at, accepted_at)
                    VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                        100000, 0, 2000, 1000, 101000, :expires, :accepted)
                    """)
                    .param("id", quoteId)
                    .param("t", tenantId)
                    .param("b", brandId)
                    .param("loc", locationId)
                    .param("cust", customerOf(tenantId))
                    .param("pub", publicationOf(brandId))
                    .param("hash", "hash-" + orderId)
                    .param("expires", spec.createdAt.atOffset(ZoneOffset.UTC))
                    .param("accepted", spec.createdAt.atOffset(ZoneOffset.UTC))
                    .update();
        }

        @Nullable
        Instant promisedAt =
                spec.unpromised ? null : spec.promisedAt != null ? spec.promisedAt : spec.createdAt.plusSeconds(2100);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, payment_status_projection, currency, subtotal_minor, tax_minor,
                    discount_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    promised_at, promise_basis, promise_prep_minutes, version, created_at,
                    confirmed_at, created_by_actor_type, created_by_actor_id,
                    accepted_by_actor_type, accepted_by_actor_id, accepted_at,
                    origin, pricing_authority, marketplace_binding_id, callback_requested)
                VALUES (:id, :number, :t, :b, :loc, :ch, :channelCode, :cust,
                    :mode, 'AUTO_CONFIRM', 'NONE',
                    :status, :paymentStatusProjection, 'UZS', 100000, 0,
                    1000, 2000, 101000, :quote,
                    :hash, :pub, :cart, :key,
                    :promisedAt, :promiseBasis, :prepMinutes, 1, :createdAt,
                    :confirmedAt, 'USER', :createdBy,
                    'USER', 'manager-1', :createdAt,
                    :origin, :pricingAuthority, :marketplaceBinding, :callbackRequested)
                """)
                .param("id", orderId)
                .param("number", spec.seed)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelOf(tenantId))
                .param("channelCode", spec.channelCode)
                .param("cust", customerOf(tenantId))
                .param("mode", spec.mode)
                .param("status", spec.status)
                .param("paymentStatusProjection", spec.paymentStatusProjection)
                .param("quote", quoteId)
                .param("hash", marketplace ? null : "hash-" + orderId)
                .param("pub", marketplace ? null : publicationOf(brandId))
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("promisedAt", promisedAt == null ? null : promisedAt.atOffset(ZoneOffset.UTC))
                .param("promiseBasis", promisedAt == null ? "NOT_PROMISED" : "PREPARATION_BAND")
                .param("prepMinutes", promisedAt == null ? null : 35)
                .param("createdAt", spec.createdAt.atOffset(ZoneOffset.UTC))
                .param(
                        "confirmedAt",
                        spec.confirmed || confirmedStatus(spec.status)
                                ? spec.createdAt.plusSeconds(60).atOffset(ZoneOffset.UTC)
                                : null)
                .param("createdBy", spec.createdByActorId == null ? "operator-1" : spec.createdByActorId)
                .param("origin", spec.origin)
                .param("pricingAuthority", marketplace ? "EXTERNAL" : "HORECAOS")
                .param("marketplaceBinding", spec.marketplaceBindingId)
                .param("callbackRequested", spec.callbackRequested)
                .update();

        return orderId;
    }

    /** {@code ck_order_confirmed_at} (V0022): an order at or past CONFIRMED has the instant it was. */
    private static boolean confirmedStatus(String status) {
        return java.util.Set.of("CONFIRMED", "PREPARING", "READY", "FULFILLING", "COMPLETED")
                .contains(status);
    }

    public UUID orderId(UUID tenantId, String seed) {
        return derived("order:" + tenantId + seed);
    }

    public void insertProcess(UUID tenantId, UUID orderId, String processName, String status) {
        jdbc.sql("""
                INSERT INTO ordering.order_process_states (order_id, process_name, tenant_id,
                    status, next_attempt_at)
                VALUES (:orderId, :name, :t, :status, :retryAt)
                """)
                .param("orderId", orderId)
                .param("name", processName)
                .param("t", tenantId)
                .param("status", status)
                // ck_order_process_retry: a retryable failure with no scheduled
                // retry is a process that has silently stopped.
                .param(
                        "retryAt",
                        "FAILED_RETRYABLE".equals(status) ? NOON.plusSeconds(30).atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    /**
     * One fiscal document for an order, in {@code status}, carrying whatever the
     * table's own checks demand of that status (V0027, V0039): an issued receipt
     * its two identifiers, a blocked one the instant it was blocked, a
     * not-applicable one neither provider nor evidence.
     */
    public void insertFiscalDocument(UUID tenantId, UUID orderId, String status) {
        boolean issued = "ISSUED".equals(status);
        boolean blocked = "BLOCKED".equals(status);
        boolean notApplicable = "NOT_APPLICABLE".equals(status);
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, provider_type,
                    document_type, status, reason_code, reason_note, external_receipt_id,
                    fiscal_sign, issued_at, blocked_at)
                VALUES (:id, :t, :orderId, :provider, 'SALE', :status, :reason, 'board test',
                    :receipt, :sign, :issuedAt, :blockedAt)
                """)
                .param("id", derived("fiscal:" + orderId + status))
                .param("t", tenantId)
                .param("orderId", orderId)
                .param("provider", notApplicable ? null : "CLICK")
                .param("status", status)
                .param("reason", notApplicable ? "CASH_TENDER_NO_PROVIDER_FISCALIZATION" : "PARTNER_FISCALIZED")
                .param("receipt", issued ? "rcpt-" + orderId : null)
                .param("sign", issued ? "sign-" + orderId : null)
                .param("issuedAt", issued ? NOON.atOffset(ZoneOffset.UTC) : null)
                .param("blockedAt", blocked ? NOON.atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    // --------------------------------------------------------------- payments

    /**
     * The provider-tender payment intent of an order, in {@code status}, carrying
     * what {@code ck_payment_intent_settled} demands of it: a settled instant for
     * every status but the two open ones. Its seller is set, because an intent
     * with none is one {@code PaymentCheckoutService} refuses to present.
     */
    public UUID insertProviderIntent(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, String status) {
        return insertIntent(tenantId, brandId, locationId, orderId, status, true, true);
    }

    /** A provider intent with no legal entity: {@code SELLER_UNRESOLVED}, so nothing can be charged through it. */
    public UUID insertSellerlessProviderIntent(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, String status) {
        return insertIntent(tenantId, brandId, locationId, orderId, status, true, false);
    }

    /** A cash intent: collected at handover, with no checkout surface ({@code NOT_PAYABLE_ONLINE}). */
    public UUID insertCashIntent(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, String status) {
        return insertIntent(tenantId, brandId, locationId, orderId, status, false, false);
    }

    private UUID insertIntent(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            String status,
            boolean provider,
            boolean seller) {
        boolean open = "PENDING".equals(status) || "AUTHORIZING".equals(status);
        UUID intentId = derived("intent:" + orderId);
        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, provider_type,
                    requested_amount_minor, currency, status, capture_timing, idempotency_key,
                    settled_at)
                VALUES (:id, :t, :orderId, :b, :loc, :seller, :tender, :method, :providerType,
                    101000, 'UZS', :status, :captureTiming, :key, :settledAt)
                """)
                .param("id", intentId)
                .param("t", tenantId)
                .param("orderId", orderId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("seller", seller ? derived("seller:" + tenantId) : null)
                .param("tender", provider ? "PROVIDER" : "CASH")
                .param("method", provider ? "PAYME" : "CASH")
                .param("providerType", provider ? "PAYME" : null)
                .param("status", status)
                .param("captureTiming", provider ? "BEFORE_CONFIRMATION" : "ON_HANDOVER")
                .param("key", "intent-" + orderId)
                .param("settledAt", open ? null : NOON.atOffset(ZoneOffset.UTC))
                .update();
        return intentId;
    }

    /**
     * One attempt against {@code intentId}, in {@code status}, with the merchant
     * account and the obligation or settled instant the table's checks demand of
     * that status (V0027): an {@code UNCERTAIN} attempt its named resolver and
     * deadline, every terminal one the instant it settled.
     */
    public void insertPaymentAttempt(UUID tenantId, UUID intentId, UUID merchantBindingId, String status) {
        boolean open = Set.of("INITIATED", "PRESENTED", "RESERVED", "UNCERTAIN").contains(status);
        boolean uncertain = "UNCERTAIN".equals(status);
        jdbc.sql("""
                INSERT INTO payments.payment_attempts (id, tenant_id, intent_id, provider_type,
                    merchant_binding_id, merchant_trans_id, business_date,
                    requested_amount_minor, currency, status, uncertain_since,
                    uncertain_resolver, uncertain_deadline, settled_at)
                VALUES (:id, :t, :intent, 'PAYME', :binding, :mti, DATE '2026-09-10',
                    101000, 'UZS', :status, :since, :resolver, :deadline, :settledAt)
                """)
                .param("id", derived("attempt:" + intentId + status))
                .param("t", tenantId)
                .param("intent", intentId)
                .param("binding", merchantBindingId)
                .param("mti", derived("mti:" + intentId + status).toString().replace("-", ""))
                .param("status", status)
                .param("since", uncertain ? NOON.atOffset(ZoneOffset.UTC) : null)
                .param("resolver", uncertain ? "PAYME_CHECK_TRANSACTION" : null)
                .param("deadline", uncertain ? NOON.plusSeconds(900).atOffset(ZoneOffset.UTC) : null)
                .param("settledAt", open ? null : NOON.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * An active Payme merchant account for {@code tenantId}, resolved through the
     * integration installation and binding {@link #marketplaceBinding} made for
     * {@code integrationBindingId} — the only two rows the account must point at,
     * which is all an attempt's foreign key needs.
     */
    public UUID insertMerchantBinding(UUID tenantId, UUID integrationBindingId) {
        UUID legalEntityId = derived("seller:" + tenantId);
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'LE-BOARD', 'Board MCHJ', '123456789', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """).param("id", legalEntityId).param("t", tenantId).update();
        UUID merchantBindingId = derived("merchant-binding:" + integrationBindingId);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :t, :legalEntity, 'PAYME', :installation, :binding, 'cashbox-board',
                    'horecaos:test:provider_payment:tenant:payme', :segment, false, false,
                    'ACTIVE', DATE '2026-01-01')
                """)
                .param("id", merchantBindingId)
                .param("t", tenantId)
                .param("legalEntity", legalEntityId)
                .param("installation", derived("installation:" + integrationBindingId))
                .param("binding", integrationBindingId)
                .param("segment", "board-" + merchantBindingId.toString().substring(0, 8))
                .update();
        return merchantBindingId;
    }

    // ------------------------------------------------------------------ ids

    public static UUID customerOf(UUID tenantId) {
        return derived("customer:" + tenantId);
    }

    public static UUID channelOf(UUID tenantId) {
        return derived("channel:" + tenantId);
    }

    public static UUID publicationOf(UUID brandId) {
        return derived("publication:" + brandId);
    }

    public static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
