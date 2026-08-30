-- ADR 0029 catches up with ADR 0031: what a replayed response may look like at rest.
--
-- V0006 declared `response_body text` and IdempotencyService writes the verbatim
-- response of every effectful endpoint into it, kept for at least twenty-four
-- hours. Three of those endpoints answer with data the envelope stack had just
-- finished decrypting -- the customer's own address, their delivery instruction,
-- their display name -- so the plaintext that ADR 0029 exists to keep out of
-- every column was being written straight back beside it.
--
-- The storefront address endpoints did not create this. They gave a property of
-- the shared mechanism its worst payload, which is why the fix is here and in
-- the interceptor rather than in a controller: a response body is now encrypted
-- whenever the handler's own response type is classified, decided by the same
-- ClassificationScanner that already keeps personal data off Kafka. Nobody
-- annotates an endpoint to be safe, so nobody can forget to.
--
-- This column is what tells a replay which of the two it is holding. Sniffing
-- the text for a `$`-joined envelope would work until a JSON body happened to
-- contain one, and "happens to look like ciphertext" is not a decision to make
-- on a payment retry.

ALTER TABLE platform.idempotency_records
    ADD COLUMN response_body_protected boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN platform.idempotency_records.response_body_protected IS
    'Whether response_body holds an ADR 0029 ProtectedValue rather than plaintext. '
    'Set from the handler''s response classification, never by the endpoint author.';

-- A record claiming protection with nothing to decrypt would fail a replay at
-- the moment the caller is retrying, which is the worst moment to discover it.
ALTER TABLE platform.idempotency_records
    ADD CONSTRAINT ck_idempotency_protected_has_a_body CHECK (
        NOT response_body_protected OR response_body IS NOT NULL
    );

-- ---------------------------------------------------------------------------
-- The bodies already written in clear
-- ---------------------------------------------------------------------------
--
-- Retention would age these out within a day, but "it expires tomorrow" is not
-- the property ADR 0029 claims, and a migration that fixes the mechanism while
-- leaving a day of legible addresses behind it has not finished the job.
--
-- Narrow on purpose. Only StorefrontCustomerController answers with a decrypted
-- value today -- addAddress, updateAddress and updateProfile -- and nulling
-- every body on the table instead would strip the replay bodies off in-flight
-- checkouts and payment sessions, where a retry that comes back empty leaves a
-- client with an effect it can no longer read the result of. The scope key
-- begins with the method and the controller and keeps that prefix even when it
-- is digested for length, so the controller is what this matches on.
--
-- A replay of one of these three within the remaining window now returns the
-- recorded status with an empty body. That is a real cost, and it is the right
-- one: an empty 201 on a retried address save is a worse morning for one
-- customer than a readable address book is for all of them.
UPDATE platform.idempotency_records
   SET response_body = NULL
 WHERE response_body IS NOT NULL
   AND scope_key LIKE '%StorefrontCustomerController#%';

-- No GRANT block: V0035 granted SELECT, INSERT, UPDATE, DELETE on the table
-- itself to horecaos_application, and a table-level grant covers a column added
-- afterwards. Verified rather than assumed -- a column-level grant here would
-- have needed its own line.
DO $$
BEGIN
    IF NOT has_column_privilege(
            'horecaos_application', 'platform.idempotency_records',
            'response_body_protected', 'UPDATE') THEN
        RAISE EXCEPTION
            'horecaos_application cannot write response_body_protected; the table grant did not cover it';
    END IF;
END $$;
