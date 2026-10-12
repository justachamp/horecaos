-- ADR 0166, Decision 8: a correction or void request has to have an exit that files nothing.
--
-- V0551 gave the request four statuses (REQUESTED, ATTACHED, DECLINED, CANCELLED) and wrote only the
-- first two. A request raised against the wrong order, or one the tenant's own fiscal agent declined
-- to issue, therefore had one way out: attach a reference, which creates a linked CORRECTION or REFUND
-- document and moves the original to CORRECTED or VOIDED. An operator who only wanted the request off the
-- worklist was left to type a reference that did not exist and have HorecaOS record, as a fiscal
-- document, a receipt nobody issued.
--
-- DECLINED (the agent would not issue it) and CANCELLED (a person withdrew it) now close a request
-- without attaching anything: the document returns to ISSUED, its evidence untouched, and the request
-- keeps who closed it, when and why. The attachment columns keep their meaning, and the existing check
-- that ties them to ATTACHED is unchanged; the closure has columns of its own and a check that ties
-- them to the other two statuses.
ALTER TABLE fiscal.fiscal_correction_requests
    ADD COLUMN closed_by varchar(255),
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN closed_note varchar(255);

ALTER TABLE fiscal.fiscal_correction_requests
    ADD CONSTRAINT ck_fiscal_correction_closure CHECK (
        (status IN ('DECLINED', 'CANCELLED')) = (closed_at IS NOT NULL)
        AND (closed_at IS NULL) = (closed_by IS NULL)
        AND (closed_at IS NULL) = (closed_note IS NULL)
        AND (closed_note IS NULL OR length(btrim(closed_note)) > 0));

COMMENT ON COLUMN fiscal.fiscal_correction_requests.closed_note IS
    'ADR 0166 Decision 8. Why a request was closed without a receipt: the sentence the person who closed it wrote. Not evidence of anything filed.';
