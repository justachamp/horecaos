package uz.horecaos.platform.customers.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The products a customer marked (ADR 0015).
 *
 * <p>Every statement here has the account in its predicate, and the account
 * comes from the caller's own verified token rather than from a path. There is
 * no method that takes somebody else's account id, which is what makes this
 * ownership-authorised rather than capability-scoped: a caller cannot name whose
 * list they mean.
 */
@Component
public class JdbcFavouriteStore {

    private final JdbcClient jdbc;

    public JdbcFavouriteStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** This customer's product ids, most recently added first. */
    public List<UUID> list(UUID tenantId, UUID brandId, UUID accountId) {
        return jdbc.sql("""
                SELECT product_id FROM customer.favourites
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND account_id = :accountId
                ORDER BY created_at DESC, product_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .query(UUID.class)
                .list();
    }

    /**
     * Adds one, and says whether it was new.
     *
     * <p>Idempotent: favouriting twice is one fact, and `ON CONFLICT DO NOTHING`
     * makes a double-tap or a retried request a no-op rather than a constraint
     * violation the caller has to interpret.
     *
     * @return false when the product is not this brand's, which the foreign key
     *     refuses. Reported rather than thrown, because a stale menu in a
     *     customer's hand is an ordinary way to reach it.
     */
    public boolean add(UUID tenantId, UUID brandId, UUID accountId, UUID productId) {
        try {
            jdbc.sql("""
                    INSERT INTO customer.favourites (tenant_id, brand_id, account_id, product_id)
                    VALUES (:tenantId, :brandId, :accountId, :productId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("accountId", accountId)
                    .param("productId", productId)
                    .update();
            return true;
        } catch (DataIntegrityViolationException notThisBrands) {
            return false;
        }
    }

    /** Removes one. Removing what was never there is not an error. */
    public void remove(UUID tenantId, UUID brandId, UUID accountId, UUID productId) {
        jdbc.sql("""
                DELETE FROM customer.favourites
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND account_id = :accountId AND product_id = :productId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("productId", productId)
                .update();
    }
}
