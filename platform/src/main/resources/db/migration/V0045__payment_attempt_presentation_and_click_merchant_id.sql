-- ADR 0013: opening an attempt and presenting a checkout surface.
--
-- V0027 built the attempt table for the inbound half — the half that credits an
-- order — and it built it well enough that this migration adds no table. What it
-- did not build is the outbound half: the row that says a customer was handed a
-- Click payment link or a Payme base64 URL, and the rule that says a customer who
-- abandons that link and comes back gets the same one rather than a second
-- attempt against the same money.
--
-- Three changes, and each one is a correctness fix rather than a convenience.
--
-- ---------------------------------------------------------------------------
-- 1. Click's `merchant_id`, which the payment link cannot be built without
-- ---------------------------------------------------------------------------
--
-- V0027 modelled Click as `service_id` plus `merchant_user_id`, which is
-- everything the MERCHANT API and the SHOP API need. The redirect needs a third:
-- `https://my.click.uz/services/pay/` documents `merchant_id` as mandatory, and
-- it is a different identifier from both of the others. Until now the adapter
-- omitted the parameter rather than guessing a value, on the argument that a
-- wrong `merchant_id` would point a customer's payment at another merchant's
-- account. That argument is still right; the answer is to carry the value.
--
-- Nullable, because Payme has no equivalent and because a Click binding
-- registered before this column existed has nothing to put in it. A Click binding
-- without it can still take a payment through every other surface — the invoice
-- push, the SHOP API callbacks — and the presentation code refuses to build a
-- link rather than building a broken one.
ALTER TABLE payments.merchant_bindings
    ADD COLUMN merchant_id_reference varchar(255);

COMMENT ON COLUMN payments.merchant_bindings.merchant_id_reference IS
    'Click merchant_id, the third account identifier, mandatory on the my.click.uz payment link and used nowhere else. Distinct from merchant_account_reference (service_id) and merchant_user_reference (merchant_user_id). Null on Payme, which has no equivalent. Non-sensitive: it identifies the merchant without authenticating to it.';

-- ---------------------------------------------------------------------------
-- 2. How many times the customer was shown this payment
-- ---------------------------------------------------------------------------
--
-- An abandoned checkout is a fact worth knowing, and it is invisible in the
-- states: an attempt a customer opened, ignored, and returned to twice looks
-- exactly like one they followed immediately. The count is what separates "the
-- link is broken" from "the customer is hesitating", and it is the number a
-- support conversation about a stuck order actually starts from.
ALTER TABLE payments.payment_attempts
    ADD COLUMN presentation_count integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN payments.payment_attempts.presentation_count IS
    'How many times a checkout surface was handed to a customer for this attempt. Re-presentation of an abandoned checkout increments it and never opens a second attempt, so a value above one is a customer who came back rather than a second charge.';

-- An invoice id is not a payment id, and the distinction is not pedantic. Click's
-- `invoice/create` answers an `invoice_id` that names a request pushed to a phone;
-- the `payment_id` that `payment/status`, `payment/reversal` and the fiscal calls
-- all take arrives later and only if the customer accepts. Writing the invoice id
-- into `external_payment_id` would put it under the unique index that identifies a
-- payment, and the COALESCE in every later transition would then preserve it
-- against the real one — so a reversal would be attempted at an identifier Click
-- would not recognise.
ALTER TABLE payments.payment_attempts
    ADD COLUMN external_invoice_id varchar(64);

COMMENT ON COLUMN payments.payment_attempts.external_invoice_id IS
    'Click invoice_id from invoice/create: a payment request pushed to a phone, which is not a payment and not the payment_id that the status, reversal and fiscal calls take. Null for every link and QR presentation, which mint nothing.';

-- Pair completeness, stated as an equality rather than as a disjunction. The
-- disjunctive form `(a IS NULL AND b IS NULL) OR (a IS NOT NULL AND b IS NOT
-- NULL)` evaluates to NULL — and therefore passes — whenever one side is NULL,
-- which is exactly the case it is meant to catch.
ALTER TABLE payments.payment_attempts
    ADD CONSTRAINT ck_payment_attempt_presented_pair CHECK (
        (presentation_kind IS NULL) = (presented_at IS NULL));

-- Backfilled before the constraint is declared, because ADD CONSTRAINT validates
-- the rows that are already there. An attempt presented before this migration has
-- a presented_at and would default to a count of zero, which the CHECK below
-- reads as "never presented" — so the migration would fail on exactly the
-- databases that have been taking payments.
UPDATE payments.payment_attempts
SET presentation_count = 1
WHERE presented_at IS NOT NULL;

ALTER TABLE payments.payment_attempts
    ADD CONSTRAINT ck_payment_attempt_presentation_count CHECK (
        (presented_at IS NULL) = (presentation_count = 0));

-- ---------------------------------------------------------------------------
-- 3. One open attempt per intent, not one live one
-- ---------------------------------------------------------------------------
--
-- V0027's `ux_payment_attempt_live_per_intent` permits one attempt per intent in
-- RESERVED, CAPTURED or UNCERTAIN — one that holds money, or holds a question
-- about money. That is the right rule for the inbound half, and it has a hole on
-- the outbound one: INITIATED and PRESENTED are outside it, so a customer who
-- opens a checkout, wanders off, and comes back could be given a second attempt
-- with a second merchant_trans_id while the first link is still in their history.
-- Both links are then payable. On Click both would Prepare and Complete against
-- different attempts under one intent; on Payme the second CreateTransaction
-- would be refused with -31008, but only after the customer had entered card
-- details. Neither is a state anybody should have to reconcile.
--
-- So the rule becomes: at most one *open* attempt per intent, where open is
-- everything that is not terminal. A terminal attempt — cancelled, expired,
-- reversed, failed — is finished, and a customer whose payment failed must be
-- able to try again, so those four are outside the index exactly as before.
--
-- Stated as NOT IN the four terminal states rather than IN the five open ones so
-- that a state added to the enumeration is covered by default. The failure mode
-- of the inclusive form is silent: a new state would simply not be indexed, and
-- the hole would look like the absence of a problem.
-- Widening a unique index can fail on rows that already exist, and here it cannot:
-- nothing in any released code path opened a second attempt against one intent.
-- `PaymentAttemptService.open` refused whenever a live attempt existed, and until
-- this release nothing opened an attempt at all — which is the gap this migration
-- accompanies. So at most one non-terminal attempt per intent exists by
-- construction, and the CREATE below has nothing to trip over.
DROP INDEX payments.ux_payment_attempt_live_per_intent;

CREATE UNIQUE INDEX ux_payment_attempt_open_per_intent
    ON payments.payment_attempts (intent_id)
    WHERE status NOT IN ('CANCELLED', 'EXPIRED', 'REVERSED', 'FAILED');

COMMENT ON INDEX payments.ux_payment_attempt_open_per_intent IS
    'ADR 0013. At most one non-terminal attempt per intent. Replaces ux_payment_attempt_live_per_intent, which covered only RESERVED, CAPTURED and UNCERTAIN and so let an abandoned PRESENTED checkout acquire a second payable link against the same money. A terminal attempt is outside it, because a customer whose payment failed must be able to try again.';

-- ---------------------------------------------------------------------------
-- 4. Grants
-- ---------------------------------------------------------------------------
--
-- Unchanged in shape from V0027: the application reads and writes attempts and
-- reads bindings. Repeated because a column added later inherits the table's
-- grants but a reader of this file should not have to go and check that.
GRANT USAGE ON SCHEMA payments TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.payment_attempts TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.merchant_bindings TO horecaos_application;
