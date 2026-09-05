package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Cart persistence (ADR 0019).
 *
 * <p>Every statement carries the tenant predicate inside the query rather than
 * checking ownership after loading. A cart id is a UUID a caller supplies, and a
 * post-load check is one forgotten branch away from serving another tenant's
 * basket.
 *
 * <p>Version is advanced by conditional UPDATE with the expected version in the
 * statement. Two edits racing on one cart therefore produce one winner and one
 * stale-version answer, rather than the second silently overwriting the first.
 */
@Repository
public class JdbcCartStore {

    private final JdbcClient jdbc;

    public JdbcCartStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCart(CartRow cart, Instant now) {
        jdbc.sql("""
                INSERT INTO ordering.carts (
                    id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                    guest_reference_hash, fulfillment_mode, currency, status, version,
                    expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :customerId,
                    :guestHash, :mode, :currency, :status, 1, :expiresAt, :now, :now)
                """)
                .param("id", cart.cartId())
                .param("tenantId", cart.tenantId())
                .param("brandId", cart.brandId())
                .param("locationId", cart.locationId())
                .param("channelId", cart.channelId())
                .param("customerId", cart.customerAccountId())
                .param("guestHash", cart.guestReferenceHash())
                .param("mode", cart.fulfillmentMode().name())
                .param("currency", cart.currency())
                .param("status", cart.status().name())
                .param("expiresAt", utc(cart.expiresAt()))
                .param("now", utc(now))
                .update();
    }

    /**
     * Reads a cart for this tenant and brand.
     *
     * <p>Both predicates are in the query. A cart belonging to another brand of
     * the same tenant is as invisible here as one belonging to another tenant,
     * because a storefront request is always scoped to one brand.
     */
    public Optional<CartRow> find(UUID tenantId, UUID brandId, UUID cartId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                       guest_reference_hash, fulfillment_mode, currency, status,
                       pricing_quote_id, pricing_context_hash, catalog_publication_id,
                       version, expires_at, converted_order_id, applied_coupon_code
                FROM ordering.carts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", cartId)
                .query(JdbcCartStore::mapCart)
                .optional();
    }

    /**
     * Reads and locks a cart for the checkout transaction.
     *
     * <p>{@code FOR UPDATE} rather than an optimistic read: checkout goes on to
     * touch pricing, inventory and tenancy, and a second checkout arriving
     * mid-flight must wait rather than run the same sequence in parallel and lose
     * on the last statement, having already consumed a hold and a capacity slot.
     */
    public Optional<CartRow> findForUpdate(UUID tenantId, UUID brandId, UUID cartId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                       guest_reference_hash, fulfillment_mode, currency, status,
                       pricing_quote_id, pricing_context_hash, catalog_publication_id,
                       version, expires_at, converted_order_id, applied_coupon_code
                FROM ordering.carts
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", cartId)
                .query(JdbcCartStore::mapCart)
                .optional();
    }

    public List<CartLineRow> lines(UUID tenantId, UUID cartId) {
        return jdbc.sql("""
                SELECT id, line_key, variant_id, quantity,
                       selected_modifier_snapshot::text AS modifiers, customer_note_encrypted
                FROM ordering.cart_lines
                WHERE tenant_id = :tenantId AND cart_id = :cartId
                ORDER BY line_key
                """)
                .param("tenantId", tenantId)
                .param("cartId", cartId)
                .query((row, number) -> new CartLineRow(
                        row.getObject("id", UUID.class),
                        row.getString("line_key"),
                        row.getObject("variant_id", UUID.class),
                        row.getInt("quantity"),
                        row.getString("modifiers"),
                        row.getString("customer_note_encrypted")))
                .list();
    }

    /**
     * Adds or replaces one line.
     *
     * <p>An upsert rather than a delete-then-insert, so the line keeps its
     * identity across an edit and a concurrent read never observes a cart with the
     * line briefly missing.
     */
    public void upsertLine(
            UUID lineId,
            UUID tenantId,
            UUID cartId,
            String lineKey,
            UUID variantId,
            int quantity,
            String modifiersJson,
            @Nullable String noteEncrypted,
            Instant now) {
        jdbc.sql("""
                INSERT INTO ordering.cart_lines (
                    id, tenant_id, cart_id, line_key, variant_id, quantity,
                    selected_modifier_snapshot, customer_note_encrypted, version,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :cartId, :lineKey, :variantId, :quantity,
                    CAST(:modifiers AS jsonb), :note, 1, :now, :now)
                ON CONFLICT (cart_id, line_key) DO UPDATE
                SET variant_id = EXCLUDED.variant_id,
                    quantity = EXCLUDED.quantity,
                    selected_modifier_snapshot = EXCLUDED.selected_modifier_snapshot,
                    customer_note_encrypted = EXCLUDED.customer_note_encrypted,
                    version = ordering.cart_lines.version + 1,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", lineId)
                .param("tenantId", tenantId)
                .param("cartId", cartId)
                .param("lineKey", lineKey)
                .param("variantId", variantId)
                .param("quantity", quantity)
                .param("modifiers", modifiersJson)
                .param("note", noteEncrypted)
                .param("now", utc(now))
                .update();
    }

    public boolean deleteLine(UUID tenantId, UUID cartId, String lineKey) {
        return jdbc.sql("""
                DELETE FROM ordering.cart_lines
                WHERE tenant_id = :tenantId AND cart_id = :cartId AND line_key = :lineKey
                """)
                        .param("tenantId", tenantId)
                        .param("cartId", cartId)
                        .param("lineKey", lineKey)
                        .update()
                == 1;
    }

    /**
     * Bumps the version and clears the pricing binding.
     *
     * <p>Called by every line edit. Leaving a quote id attached to a changed cart
     * is precisely how a customer checks out at a price for a basket they no
     * longer have; clearing it forces a re-quote, which is the only correct
     * response to an edit.
     *
     * @return false when the expected version has moved on
     */
    public boolean touchAndInvalidatePricing(UUID tenantId, UUID cartId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.carts
                SET version = version + 1,
                    pricing_quote_id = NULL,
                    pricing_context_hash = NULL,
                    catalog_publication_id = NULL,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND version = :expectedVersion AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("id", cartId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * ADR 0072: applies, replaces, or removes the cart's promo code, and
     * invalidates the attached price exactly as {@link #touchAndInvalidatePricing}
     * does — applying a code changes what the total will be exactly as a line
     * edit does.
     *
     * @param normalizedCode null to remove whatever code is applied
     * @return false when the expected version has moved on
     */
    public boolean setCouponCodeAndInvalidatePricing(
            UUID tenantId, UUID cartId, int expectedVersion, @Nullable String normalizedCode, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.carts
                SET applied_coupon_code = :code,
                    version = version + 1,
                    pricing_quote_id = NULL,
                    pricing_context_hash = NULL,
                    catalog_publication_id = NULL,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND version = :expectedVersion AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("id", cartId)
                        .param("code", normalizedCode)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Records the quote a cart was priced at.
     *
     * <p>The version is deliberately <em>not</em> bumped. Pricing does not change
     * what is in the cart, and bumping would mean re-pricing an unchanged basket
     * derived a new idempotency key and produced a second quote holding a second
     * reservation. The expected version is still in the predicate, so a quote
     * computed from contents that changed underneath cannot be attached.
     */
    public boolean attachQuote(
            UUID tenantId,
            UUID cartId,
            int expectedVersion,
            UUID quoteId,
            String contextHash,
            UUID publicationId,
            Instant now) {
        return jdbc.sql("""
                UPDATE ordering.carts
                SET pricing_quote_id = :quoteId,
                    pricing_context_hash = :contextHash,
                    catalog_publication_id = :publicationId,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND version = :expectedVersion AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("id", cartId)
                        .param("expectedVersion", expectedVersion)
                        .param("quoteId", quoteId)
                        .param("contextHash", contextHash)
                        .param("publicationId", publicationId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Moves a cart to a new status, only from the status the caller expects.
     *
     * <p>The from-status is in the statement, so a cart already converted by a
     * checkout that won cannot be converted again, and a released
     * {@code CHECKOUT_IN_PROGRESS} cannot be released twice.
     */
    public boolean transition(
            UUID tenantId, UUID cartId, CartStatus from, CartStatus to, @Nullable UUID convertedOrderId, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.carts
                SET status = :to,
                    -- Cast explicitly: an untyped null inside COALESCE leaves
                    -- PostgreSQL unable to infer the argument type and the
                    -- statement fails for a reason unrelated to the transition.
                    converted_order_id = COALESCE(CAST(:orderId AS uuid), converted_order_id),
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", cartId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("orderId", convertedOrderId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // ----------------------------------------------------------- destination

    /**
     * Records where a delivery cart is going.
     *
     * <p>An upsert keyed on the cart, because a cart has one destination and
     * choosing a second address replaces the first rather than adding to it. The
     * version is not touched here: {@code CartService} bumps it through
     * {@link #touchAndInvalidatePricing} in the same transaction, so a destination
     * change clears the attached quote exactly as a line edit does — the delivery
     * fee is a function of the address (ADR 0037), and a basket priced to one door
     * is not priced to another.
     *
     * <p>Every value but the coordinate is ciphertext bound to this cart id by the
     * ADR 0029 associated data. Nothing here may be logged.
     */
    public void upsertFulfillment(
            UUID tenantId,
            UUID cartId,
            UUID customerAddressId,
            String addressEncrypted,
            @Nullable String instructionsEncrypted,
            String recipientNameEncrypted,
            String recipientPhoneEncrypted,
            double latitude,
            double longitude,
            Instant now) {
        jdbc.sql("""
                INSERT INTO ordering.cart_fulfillment (
                    cart_id, tenant_id, fulfillment_mode, customer_address_id, address_encrypted,
                    delivery_instructions_encrypted, recipient_name_encrypted,
                    recipient_phone_encrypted, latitude, longitude, created_at, updated_at)
                VALUES (:cartId, :tenantId, 'DELIVERY', :addressId, :address, :instructions,
                    :name, :phone, :latitude, :longitude, :now, :now)
                ON CONFLICT (cart_id) DO UPDATE
                SET customer_address_id = EXCLUDED.customer_address_id,
                    address_encrypted = EXCLUDED.address_encrypted,
                    delivery_instructions_encrypted = EXCLUDED.delivery_instructions_encrypted,
                    recipient_name_encrypted = EXCLUDED.recipient_name_encrypted,
                    recipient_phone_encrypted = EXCLUDED.recipient_phone_encrypted,
                    latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("cartId", cartId)
                .param("tenantId", tenantId)
                .param("addressId", customerAddressId)
                .param("address", addressEncrypted)
                .param("instructions", instructionsEncrypted)
                .param("name", recipientNameEncrypted)
                .param("phone", recipientPhoneEncrypted)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("now", utc(now))
                .update();
    }

    /**
     * The destination attached to a cart, as ciphertext.
     *
     * <p>The tenant is in the statement rather than inherited from the cart the
     * caller happens to be holding, for the reason every other read here gives: a
     * cart id is a UUID, and this row is a home address.
     */
    public Optional<CartFulfillmentRow> findFulfillment(UUID tenantId, UUID cartId) {
        return jdbc.sql("""
                SELECT cart_id, customer_address_id, address_encrypted,
                       delivery_instructions_encrypted, recipient_name_encrypted,
                       recipient_phone_encrypted, latitude, longitude
                FROM ordering.cart_fulfillment
                WHERE tenant_id = :tenantId AND cart_id = :cartId
                """)
                .param("tenantId", tenantId)
                .param("cartId", cartId)
                .query((row, number) -> new CartFulfillmentRow(
                        row.getObject("cart_id", UUID.class),
                        row.getObject("customer_address_id", UUID.class),
                        row.getString("address_encrypted"),
                        row.getString("delivery_instructions_encrypted"),
                        row.getString("recipient_name_encrypted"),
                        row.getString("recipient_phone_encrypted"),
                        row.getDouble("latitude"),
                        row.getDouble("longitude")))
                .optional();
    }

    /**
     * Carts started and never converted (IA 1.4, operations-spec/orders.md
     * §6): {@code ACTIVE}, {@code EXPIRED} or {@code ABANDONED}, with no
     * {@code converted_order_id}. Newest first — this is a log, not a queue.
     *
     * <p>{@code lineCount} only, not a first-line preview: the product name
     * behind a cart line lives in the catalog module's schema, and resolving
     * it here would be a cross-module join inside a persistence adapter this
     * module does not own. The abandonment-by-channel breakdown the screen is
     * built around needs none of it.
     */
    public List<DraftCartRow> listDrafts(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable UUID channelId,
            int limit) {
        return jdbc.sql("""
                SELECT cart.id, cart.created_at, cart.channel_id, cart.location_id,
                       cart.customer_account_id, cart.guest_reference_hash,
                       cart.expires_at, cart.status,
                       (SELECT count(*) FROM ordering.cart_lines line
                         WHERE line.tenant_id = cart.tenant_id AND line.cart_id = cart.id) AS line_count
                  FROM ordering.carts cart
                 WHERE cart.tenant_id = :tenantId
                   AND cart.brand_id = :brandId
                   AND cart.location_id = :locationId
                   AND cart.status IN ('ACTIVE', 'EXPIRED', 'ABANDONED')
                   AND cart.converted_order_id IS NULL
                   AND (:from::timestamptz IS NULL OR cart.created_at >= :from)
                   AND (:to::timestamptz IS NULL OR cart.created_at < :to)
                   AND (:channelId::uuid IS NULL OR cart.channel_id = :channelId)
                 ORDER BY cart.created_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("from", from == null ? null : utc(from))
                .param("to", to == null ? null : utc(to))
                .param("channelId", channelId)
                .param("limit", limit)
                .query((row, number) -> new DraftCartRow(
                        row.getObject("id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("channel_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("guest_reference_hash"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        CartStatus.valueOf(row.getString("status")),
                        row.getInt("line_count")))
                .list();
    }

    /**
     * One row of the drafts list.
     *
     * @param customerAccountId null for a guest cart
     * @param guestReferenceHash null for an account cart — never the raw reference
     */
    public record DraftCartRow(
            UUID cartId,
            Instant createdAt,
            UUID channelId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            @Nullable String guestReferenceHash,
            Instant expiresAt,
            CartStatus status,
            int lineCount) {}

    /** Sweeps carts past their TTL so an abandoned basket stops looking live. */
    public int expireStaleCarts(Instant now) {
        return jdbc.sql("""
                UPDATE ordering.carts
                SET status = 'EXPIRED', version = version + 1, updated_at = :now
                WHERE status = 'ACTIVE' AND expires_at <= :now
                """).param("now", utc(now)).update();
    }

    private static CartRow mapCart(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new CartRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("channel_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("guest_reference_hash"),
                FulfillmentMode.valueOf(row.getString("fulfillment_mode")),
                row.getString("currency"),
                CartStatus.valueOf(row.getString("status")),
                row.getObject("pricing_quote_id", UUID.class),
                row.getString("pricing_context_hash"),
                row.getObject("catalog_publication_id", UUID.class),
                row.getInt("version"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("converted_order_id", UUID.class),
                row.getString("applied_coupon_code"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A cart as it stands right now.
     *
     * @param customerAccountId    null for a guest cart
     * @param guestReferenceHash   null for an account cart — a cart carries
     *                             exactly one of the two identities
     * @param pricingQuoteId       null until the cart is priced, and cleared by
     *                             every edit — a cart holding a quote for
     *                             contents it no longer has is not this cart's
     *                             price
     * @param pricingContextHash   null exactly when {@code pricingQuoteId} is
     * @param catalogPublicationId null exactly when {@code pricingQuoteId} is
     * @param convertedOrderId     null until checkout converts this cart into an
     *                             order
     * @param appliedCouponCode    ADR 0072: the customer-typed promo code, normalized,
     *                             or null when none is applied. Never trusted as an
     *                             eligibility verdict — every consumer re-resolves it
     *                             against {@code pricing.coupon_codes} itself
     */
    public record CartRow(
            UUID cartId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            @Nullable UUID customerAccountId,
            @Nullable String guestReferenceHash,
            FulfillmentMode fulfillmentMode,
            String currency,
            CartStatus status,
            @Nullable UUID pricingQuoteId,
            @Nullable String pricingContextHash,
            @Nullable UUID catalogPublicationId,
            int version,
            Instant expiresAt,
            @Nullable UUID convertedOrderId,
            @Nullable String appliedCouponCode) {}

    /**
     * One line of a cart.
     *
     * @param selectedModifiersJson the chosen options, stored whole and read whole
     * @param customerNoteEncrypted null when the customer left no note on this line
     */
    public record CartLineRow(
            UUID lineId,
            String lineKey,
            UUID variantId,
            int quantity,
            String selectedModifiersJson,
            @Nullable String customerNoteEncrypted) {}

    /**
     * A cart's destination as it is stored: four ciphertexts and a point.
     *
     * @param customerAddressId provenance only, and deliberately not a foreign
     *                          key. The columns beside it are a copy taken when
     *                          the customer chose the address, so archiving that
     *                          address later leaves this cart deliverable and this
     *                          id pointing at a row that is no longer offered
     */
    public record CartFulfillmentRow(
            UUID cartId,
            UUID customerAddressId,
            String addressEncrypted,
            @Nullable String instructionsEncrypted,
            String recipientNameEncrypted,
            String recipientPhoneEncrypted,
            double latitude,
            double longitude) {

        /** Prints nothing: every component is a person's home or a key to it. */
        @Override
        public String toString() {
            return "CartFulfillmentRow[cart=%s]".formatted(cartId);
        }
    }
}
