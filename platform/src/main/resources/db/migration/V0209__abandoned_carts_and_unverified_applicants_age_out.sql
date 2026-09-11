-- ADR 0092, as decided on 2026-09-11: the two kinds of personal data the
-- control plane listed with no retention rule now have one.
--
--   * A cart that never became an order is deleted 90 days after it was last
--     touched and after it expired (CartRetentionSweeper). A converted cart
--     is an order's history and is never touched; nor is any cart an order
--     names, whatever its status says.
--   * A courier applicant who was never verified -- the courier module has no
--     rejection step, so an application nobody verified in 12 months is a
--     dead one -- has their name overwritten with a tombstone and is archived
--     (CourierApplicantRetentionSweeper). A verified courier's record is their
--     settlement history and is not an applicant record.
--
-- The application role already holds DELETE on ordering.carts (V0022) and
-- UPDATE on fulfillment.couriers and courier_engagements (V0040), and both
-- sweeps' row locks name only those tables, so no grant is needed. This adds
-- the one index the cart scan wants: every non-converted cart by when it was
-- last touched, without reading the converted majority.

CREATE INDEX ix_carts_retention ON ordering.carts (updated_at) WHERE status <> 'CONVERTED';

COMMENT ON INDEX ordering.ix_carts_retention IS
    'ADR 0092. CartRetentionSweeper''s oldest-first scan of carts that never became an order.';
