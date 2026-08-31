-- ADR 0043: DayCloseService gets a production caller, and the claim it needs to
-- run safely when more than one replica is scheduling at once.
--
-- Idempotency-per-(tenant, day) is already true of DayCloseService.close/recut in
-- effect: both delete-then-rewrite the whole day inside one transaction, so a
-- repeated call reproduces the same rows. What it does not protect against is two
-- replicas calling close() for the same (tenant, business_date) at the same
-- moment, which is two concurrent delete-then-insert transactions racing each
-- other rather than one clean run. This table is the durable claim that
-- serialises that race, on the same shape ADR 0058 uses for its own per-chat
-- lease (integration.telegram_chat_locks): an upsert against a lease that is only
-- taken over once it has expired, contended with ON CONFLICT rather than
-- read-then-write.
--
-- The row is transient by design: a claim exists only while a close or a recut
-- is actually in flight for that (tenant, day, kind), and the scheduler deletes
-- it the moment the run finishes, succeeds or fails. Whether that day is already
-- closed is answered from reporting.close_runs, which is the authority; this
-- table only ever answers "is someone working on it right now".
CREATE TABLE reporting.day_close_claims (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    run_kind varchar(16) NOT NULL,

    lease_owner uuid NOT NULL,
    lease_expires_at timestamptz NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_day_close_claims PRIMARY KEY (tenant_id, business_date, run_kind),
    CONSTRAINT fk_day_close_claim_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_day_close_claim_kind CHECK (run_kind IN ('CLOSE', 'RECUT'))
);

COMMENT ON TABLE reporting.day_close_claims IS
    'ADR 0043. The durable multi-replica lease for the day-close heartbeat, the OutboxRelay/telegram_chat_locks lease pattern applied to (tenant, business_date, run_kind). Transient: a row exists only while a close or recut is in flight.';

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.day_close_claims TO horecaos_application;
