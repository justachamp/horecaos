-- ADR 0063: Telegram Gateway becomes a second delivery provider behind the
-- ADR 0015 verification challenge. The challenge, its attempts, its rate
-- limits (ADR 0033) and its single-use grant are the existing machinery,
-- untouched -- what is new is only which provider actually carried the code,
-- and what that carriage cost. The challenge row is the one "attempt" a
-- SIGN_IN code has (a superseded resend opens a fresh row rather than a
-- second delivery attempt on this one), so recording the decision here is
-- recording it on the same row the rest of the challenge's life already
-- lives on -- there is no separate delivery-attempts table to invent.
--
-- These four columns are deliberately never a secret and never PII: a
-- provider name, an opaque provider-issued message id, and a cost. The
-- phone number the code went to is already `destination_encrypted` on this
-- same row (ADR 0029) and nothing here duplicates it.

ALTER TABLE customer.verification_challenges ADD COLUMN delivery_channel varchar(32);
ALTER TABLE customer.verification_challenges ADD COLUMN delivery_provider_message_id varchar(128);
ALTER TABLE customer.verification_challenges ADD COLUMN delivery_cost_minor bigint;
ALTER TABLE customer.verification_challenges ADD COLUMN delivery_cost_currency varchar(3);

-- Null for a preset code (CustomerVerificationService.deliver returns before
-- any transport is ever asked to send one) and for every challenge issued
-- before this column existed. Set exactly once, the moment a transport
-- answers ACCEPTED -- never rewritten by a later attempt against the same
-- challenge, because a challenge accepts at most one successful delivery.
ALTER TABLE customer.verification_challenges
    ADD CONSTRAINT ck_verification_delivery_channel CHECK (
        delivery_channel IS NULL OR delivery_channel IN ('SMS', 'TELEGRAM_GATEWAY'));

-- Money as an integer minor unit with its own ISO code (CLAUDE.md), never a
-- bare float -- Telegram Gateway's own API answers a fractional-USD
-- `request_cost`, converted to minor units at the adapter boundary rather
-- than stored as the provider sent it.
ALTER TABLE customer.verification_challenges
    ADD CONSTRAINT ck_verification_delivery_cost_pair CHECK (
        (delivery_cost_minor IS NULL) = (delivery_cost_currency IS NULL));
ALTER TABLE customer.verification_challenges
    ADD CONSTRAINT ck_verification_delivery_cost_amount CHECK (
        delivery_cost_minor IS NULL OR delivery_cost_minor >= 0);

-- Table-level GRANT already covers every column (V0055); no new grant needed.
