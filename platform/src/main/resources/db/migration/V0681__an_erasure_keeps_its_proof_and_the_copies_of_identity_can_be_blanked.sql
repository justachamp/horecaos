-- ADR 0029, batch 21 (w10-privacy): the erasure of a customer reaches the copies of her identity that other
-- modules hold, and keeps evidence of what it reached.
--
-- 1. customer.erasure_requests.proof: what each participant overwrote or removed, per table, as counts. Written in
--    the transaction that completes the request, read by GET .../erasure-requests/{id}/proof. Counts and table
--    names only: the proof is shown to staff and must be as free of personal data as the audit trail.
--
-- 2. The column grants the ordering, assistant and amendment copies need. Each of these tables was written once and
--    never updated (V0022: "an order is a historical fact"); the erasure is the one reason to change a column, so
--    the grant is the column and not the table, and a trigger refuses every value but the blanked one, so a later
--    caller that holds the grant still cannot use it to edit what a customer wrote.

ALTER TABLE customer.erasure_requests ADD COLUMN proof jsonb;

COMMENT ON COLUMN customer.erasure_requests.proof IS
    'ADR 0029. What the execution changed: {"participants":[{"name":..., "tables":[{"table":..., "rows":n}]}]}. Counts and table names only, never an identifier or a value. Null for a request that has not completed, or that completed before proofs were kept.';

-- A line's note is what the customer asked the kitchen for ("no nuts, my daughter is allergic"): free text, and the
-- most likely place for health data to sit. Blanked, not deleted: the line, its price and its tax stay.
GRANT UPDATE (note_encrypted) ON ordering.order_lines TO horecaos_application;
GRANT UPDATE (note_encrypted) ON ordering.order_outcomes TO horecaos_application;

CREATE FUNCTION ordering.refuse_a_note_that_is_not_erased() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.note_encrypted IS NOT NULL THEN
        RAISE EXCEPTION 'A recorded note is immutable: it may only be erased, by setting it to null (ADR 0029)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_lines_note_is_only_erased
    BEFORE UPDATE OF note_encrypted ON ordering.order_lines
    FOR EACH ROW EXECUTE FUNCTION ordering.refuse_a_note_that_is_not_erased();

CREATE TRIGGER trg_order_outcomes_note_is_only_erased
    BEFORE UPDATE OF note_encrypted ON ordering.order_outcomes
    FOR EACH ROW EXECUTE FUNCTION ordering.refuse_a_note_that_is_not_erased();

-- An amendment that changed a contact or a delivery address kept the new recipient name, phone and address in its
-- command payload, in the clear (ordering.order_amendment_commands.payload_json, V0029). The erasure replaces that
-- payload with a marker. Nothing else may be written: the trigger accepts the marker and refuses every other value.
GRANT UPDATE (payload_json) ON ordering.order_amendment_commands TO horecaos_application;

CREATE FUNCTION ordering.refuse_an_amendment_payload_that_is_not_erased() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.payload_json IS DISTINCT FROM '{"erased": true}'::jsonb THEN
        RAISE EXCEPTION 'An amendment command is immutable: its payload may only be replaced by the erasure marker (ADR 0029)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_amendment_commands_payload_is_only_erased
    BEFORE UPDATE OF payload_json ON ordering.order_amendment_commands
    FOR EACH ROW EXECUTE FUNCTION ordering.refuse_an_amendment_payload_that_is_not_erased();

-- The assistant's turn ledger holds no words (V0511), only a keyed hash of the customer, to count a customer's turns
-- against the tenant's ceiling. The hash is nulled when the customer is erased; nothing else is ever updated.
GRANT UPDATE (customer_pseudonym) ON assistant.turns TO horecaos_application;

CREATE FUNCTION assistant.refuse_a_pseudonym_that_is_not_erased() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.customer_pseudonym IS NOT NULL THEN
        RAISE EXCEPTION 'An assistant turn is immutable: its customer pseudonym may only be erased, by setting it to null (ADR 0029)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assistant_turns_pseudonym_is_only_erased
    BEFORE UPDATE OF customer_pseudonym ON assistant.turns
    FOR EACH ROW EXECUTE FUNCTION assistant.refuse_a_pseudonym_that_is_not_erased();

-- A SendPulse import report keeps, for each imported subscriber, the Telegram chat id and the account the row was matched
-- or created as (V0111). Together they say which chat belongs to which customer, so the erasure nulls both on her rows;
-- the outcome, the row number and the subscription flag stay, which is all the report needs. Nothing else is ever updated.
GRANT UPDATE (chat_id, customer_account_id) ON integration.sendpulse_import_run_rows TO horecaos_application;

CREATE FUNCTION integration.refuse_an_import_row_edit_that_is_not_erasure() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.chat_id IS NOT NULL OR NEW.customer_account_id IS NOT NULL THEN
        RAISE EXCEPTION 'An import report row is immutable: its chat id and account may only be erased, by setting them to null (ADR 0029)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sendpulse_import_run_rows_identity_is_only_erased
    BEFORE UPDATE OF chat_id, customer_account_id ON integration.sendpulse_import_run_rows
    FOR EACH ROW EXECUTE FUNCTION integration.refuse_an_import_row_edit_that_is_not_erasure();
