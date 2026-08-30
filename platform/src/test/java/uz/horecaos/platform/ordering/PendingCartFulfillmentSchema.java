package uz.horecaos.platform.ordering;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one table this change needs and cannot create.
 *
 * <p>Flyway numbers are allocated centrally in this repository and several agents
 * share the migration directory, so {@code ordering.cart_fulfillment} arrives in a
 * migration written elsewhere. This is the exact DDL handed over for it, applied
 * after {@code Flyway.migrate()} so the suites below run against the shape the
 * migration will create.
 *
 * <p><strong>Delete this class the moment that migration lands.</strong> Every
 * statement is {@code IF NOT EXISTS} or guarded, so it becomes a no-op rather than
 * a failure on the first run that finds the real table already there — which is
 * what stops it silently masking a migration that arrived with a different shape.
 * It exists to keep the suite honest in the window between the code and the
 * schema, not to be a second schema authority.
 */
final class PendingCartFulfillmentSchema {

    private PendingCartFulfillmentSchema() {
    }

    static void apply(JdbcClient jdbc) {
        jdbc.sql("""
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_constraint WHERE conname = 'uq_cart_identity_mode'
                    ) THEN
                        ALTER TABLE ordering.carts
                            ADD CONSTRAINT uq_cart_identity_mode
                            UNIQUE (id, tenant_id, fulfillment_mode);
                    END IF;
                END $$;
                """).update();

        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS ordering.cart_fulfillment (
                    cart_id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    fulfillment_mode varchar(16) NOT NULL,
                    customer_address_id uuid,
                    address_encrypted text NOT NULL,
                    delivery_instructions_encrypted text,
                    recipient_name_encrypted text,
                    recipient_phone_encrypted text,
                    latitude double precision NOT NULL,
                    longitude double precision NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now(),

                    CONSTRAINT ck_cart_fulfillment_is_delivery
                        CHECK (fulfillment_mode = 'DELIVERY'),
                    CONSTRAINT ck_cart_fulfillment_coordinates CHECK (
                        latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180),
                    CONSTRAINT fk_cart_fulfillment_cart
                        FOREIGN KEY (cart_id, tenant_id, fulfillment_mode)
                        REFERENCES ordering.carts (id, tenant_id, fulfillment_mode)
                        ON DELETE CASCADE
                )
                """).update();
    }
}
