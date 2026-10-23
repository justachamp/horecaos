-- ADR 0029, ADR 0182 (D13): an erased customer's unsubscribe tokens are deleted by the account they were minted for
-- (NotificationsErasureParticipant). V0671 indexed the table by token hash (the lookup), by notification and by expiry
-- (the sweep); none of them serves a delete by customer, which would read every token of every tenant for an act that
-- happens once per erased customer and must not wait on the table's size.

CREATE INDEX ix_unsubscribe_token_customer
    ON notifications.unsubscribe_tokens (tenant_id, customer_account_id);
