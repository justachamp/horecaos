package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ADR 0083: finding one identifier across every tenant, by exact match.
 *
 * <p>Not a search index. Each probe is one indexed equality against the table
 * that could hold the identifier: a primary key for an id, and for the
 * identifiers people read aloud — an order number, a courier's reference, a
 * provider's order or transaction id, a partner's reference — the value-first
 * indexes V0197 adds. A probe returns what the thing is, whose it is and a
 * label with no personal data in it; a customer or courier is named by id
 * only, because their names and phones are protected (ADR 0029) and a lookup
 * that printed them would be a cross-tenant directory of people.
 *
 * <p>Reads across schemas on purpose, the one place that does: the question
 * is "which of everything is this", and asking each module in turn would be
 * ten round trips to answer it.
 */
@Repository
public class JdbcGlobalLookup {

    /** Enough to recognise a common order number across a few tenants, not a listing. */
    private static final int PER_PROBE = 20;

    private final JdbcClient jdbc;

    public JdbcGlobalLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What an identifier turned out to be. */
    public enum EntityType {
        TENANT,
        BRAND,
        LOCATION,
        ORDER,
        CUSTOMER,
        COURIER,
        DEVICE,
        INSTALLATION,
        POS_EXPORT,
        FISCAL_DOCUMENT,
        PAYMENT
    }

    /** Which property of the thing the query matched. */
    public enum MatchedOn {
        ID,
        SLUG,
        NAME,
        ORDER_NUMBER,
        COURIER_REFERENCE,
        PROVIDER_REFERENCE,
        PARTNER_REFERENCE
    }

    /**
     * @param label no personal data: an order number and status, a device's
     *              own name, a provider — never a customer's or courier's name
     */
    public record Hit(
            EntityType type,
            UUID id,
            @Nullable UUID tenantId,
            @Nullable String tenantName,
            MatchedOn matchedOn,
            String label) {}

    public List<Hit> find(String query) {
        String value = query.strip();
        List<Hit> hits = new ArrayList<>();
        UUID id = asUuid(value);
        if (id != null) {
            hits.addAll(byId(id));
            return hits;
        }
        hits.addAll(tenants(value));
        hits.addAll(orderNumbers(value));
        hits.addAll(couriers(value));
        hits.addAll(providerReferences(value));
        String partner = partnerForm(value);
        if (partner != null) {
            hits.addAll(partnerReferences(partner));
        }
        return hits;
    }

    private List<Hit> byId(UUID id) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.TENANT, MatchedOn.ID, """
                SELECT t.id, t.id AS tenant_id, t.display_name AS tenant_name, t.slug || ' · ' || t.status AS label
                  FROM tenant.tenants t WHERE t.id = :id
                """, id);
        probe(hits, EntityType.BRAND, MatchedOn.ID, """
                SELECT b.id, b.tenant_id, t.display_name AS tenant_name, b.display_name AS label
                  FROM tenant.brands b JOIN tenant.tenants t ON t.id = b.tenant_id WHERE b.id = :id
                """, id);
        probe(hits, EntityType.LOCATION, MatchedOn.ID, """
                SELECT l.id, l.tenant_id, t.display_name AS tenant_name, l.display_name AS label
                  FROM tenant.locations l JOIN tenant.tenants t ON t.id = l.tenant_id WHERE l.id = :id
                """, id);
        probe(hits, EntityType.ORDER, MatchedOn.ID, """
                SELECT o.id, o.tenant_id, t.display_name AS tenant_name,
                       '№ ' || o.public_order_number || ' · ' || o.status AS label
                  FROM ordering.orders o JOIN tenant.tenants t ON t.id = o.tenant_id WHERE o.id = :id
                """, id);
        probe(hits, EntityType.CUSTOMER, MatchedOn.ID, """
                SELECT c.id, c.tenant_id, t.display_name AS tenant_name, c.status AS label
                  FROM customer.customer_accounts c JOIN tenant.tenants t ON t.id = c.tenant_id WHERE c.id = :id
                """, id);
        probe(hits, EntityType.COURIER, MatchedOn.ID, """
                SELECT c.id, c.tenant_id, t.display_name AS tenant_name,
                       c.display_reference || ' · ' || c.status AS label
                  FROM fulfillment.couriers c JOIN tenant.tenants t ON t.id = c.tenant_id WHERE c.id = :id
                """, id);
        probe(hits, EntityType.DEVICE, MatchedOn.ID, """
                SELECT d.id, d.tenant_id, t.display_name AS tenant_name, d.display_name || ' · ' || d.status AS label
                  FROM iam.device_principals d JOIN tenant.tenants t ON t.id = d.tenant_id WHERE d.id = :id
                """, id);
        probe(hits, EntityType.INSTALLATION, MatchedOn.ID, """
                SELECT i.id, i.tenant_id, t.display_name AS tenant_name,
                       i.provider_type || ' · ' || i.display_name || ' · ' || i.status AS label
                  FROM integration.installations i JOIN tenant.tenants t ON t.id = i.tenant_id WHERE i.id = :id
                """, id);
        probe(hits, EntityType.POS_EXPORT, MatchedOn.ID, """
                SELECT e.id, e.tenant_id, t.display_name AS tenant_name, e.state AS label
                  FROM integration.pos_order_exports e JOIN tenant.tenants t ON t.id = e.tenant_id WHERE e.id = :id
                """, id);
        probe(hits, EntityType.FISCAL_DOCUMENT, MatchedOn.ID, """
                SELECT f.id, f.tenant_id, t.display_name AS tenant_name, f.document_type || ' · ' || f.status AS label
                  FROM fiscal.fiscal_documents f JOIN tenant.tenants t ON t.id = f.tenant_id WHERE f.id = :id
                """, id);
        return hits;
    }

    private List<Hit> tenants(String value) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.TENANT, MatchedOn.SLUG, """
                SELECT t.id, t.id AS tenant_id, t.display_name AS tenant_name, t.slug || ' · ' || t.status AS label
                  FROM tenant.tenants t WHERE t.slug = lower(:value)
                """, value);
        if (value.length() >= 3) {
            probe(hits, EntityType.TENANT, MatchedOn.NAME, """
                    SELECT t.id, t.id AS tenant_id, t.display_name AS tenant_name, t.slug || ' · ' || t.status AS label
                      FROM tenant.tenants t
                     WHERE (t.display_name ILIKE '%' || :value || '%' OR t.legal_name ILIKE '%' || :value || '%')
                       AND t.slug <> lower(:value)
                     ORDER BY t.display_name
                    """, value);
        }
        return hits;
    }

    private List<Hit> orderNumbers(String value) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.ORDER, MatchedOn.ORDER_NUMBER, """
                SELECT o.id, o.tenant_id, t.display_name AS tenant_name,
                       '№ ' || o.public_order_number || ' · ' || l.display_name || ' · ' || o.status AS label
                  FROM ordering.orders o
                  JOIN tenant.tenants t ON t.id = o.tenant_id
                  JOIN tenant.locations l ON l.tenant_id = o.tenant_id AND l.id = o.location_id
                 WHERE o.public_order_number = :value
                 ORDER BY o.created_at DESC
                """, value);
        return hits;
    }

    private List<Hit> couriers(String value) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.COURIER, MatchedOn.COURIER_REFERENCE, """
                SELECT c.id, c.tenant_id, t.display_name AS tenant_name,
                       c.display_reference || ' · ' || c.status AS label
                  FROM fulfillment.couriers c JOIN tenant.tenants t ON t.id = c.tenant_id
                 WHERE c.display_reference = :value
                """, value);
        return hits;
    }

    private List<Hit> providerReferences(String value) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.POS_EXPORT, MatchedOn.PROVIDER_REFERENCE, """
                SELECT e.id, e.tenant_id, t.display_name AS tenant_name, e.state AS label
                  FROM integration.pos_order_exports e JOIN tenant.tenants t ON t.id = e.tenant_id
                 WHERE e.external_order_id = :value
                """, value);
        probe(hits, EntityType.PAYMENT, MatchedOn.PROVIDER_REFERENCE, """
                SELECT p.id, p.tenant_id, t.display_name AS tenant_name,
                       p.transaction_type || ' · ' || coalesce(p.provider_state, '') AS label
                  FROM payments.payment_transactions p JOIN tenant.tenants t ON t.id = p.tenant_id
                 WHERE p.provider_reference = :value
                """, value);
        return hits;
    }

    private List<Hit> partnerReferences(String normalised) {
        List<Hit> hits = new ArrayList<>();
        probe(hits, EntityType.ORDER, MatchedOn.PARTNER_REFERENCE, """
                SELECT o.id, o.tenant_id, t.display_name AS tenant_name,
                       '№ ' || o.public_order_number || ' · ' || r.reference_type || ' ' || r.reference_value AS label
                  FROM ordering.order_external_references r
                  JOIN ordering.orders o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
                  JOIN tenant.tenants t ON t.id = r.tenant_id
                 WHERE r.reference_value_normalised = :value
                """, normalised);
        return hits;
    }

    private void probe(List<Hit> into, EntityType type, MatchedOn matchedOn, String sql, Object value) {
        into.addAll(jdbc.sql(sql + " LIMIT " + PER_PROBE)
                .param(value instanceof UUID ? "id" : "value", value)
                .query((row, number) -> new Hit(
                        type,
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("tenant_name"),
                        matchedOn,
                        row.getString("label")))
                .list());
    }

    private static @Nullable UUID asUuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * The form partner references are stored and matched in: uppercase, with
     * whitespace, hyphens and one leading {@code #} removed. The same rule as
     * {@code partner.domain.ExternalReference#normalise}, restated because that
     * type is internal to its module; {@code GlobalLookupTests} holds the two
     * to the same answers.
     */
    public static @Nullable String partnerForm(String raw) {
        String stripped = raw.strip();
        if (stripped.startsWith("#")) {
            stripped = stripped.substring(1);
        }
        StringBuilder normalised = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char character = stripped.charAt(i);
            if (!Character.isWhitespace(character) && character != '-') {
                normalised.append(Character.toUpperCase(character));
            }
        }
        String result = normalised.toString().toUpperCase(Locale.ROOT);
        return result.isEmpty() ? null : result;
    }
}
