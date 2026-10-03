-- ADR 0140 (Accepted 2026-10-01): the redemption ledger.
--
-- V0458 granted the application role SELECT, INSERT and UPDATE on
-- pricing.promotion_redemptions, because an amendment moves a row in place and a
-- release flips it to RELEASED. It missed the one statement that removes rows:
-- JdbcPromotionStore.deleteByClaimedQuote, which PromotionRedemptionService
-- .releaseForQuote calls to give back every claim a checkout took for its quote
-- when a later step of the same transaction fails. Under the owner connection
-- that DELETE succeeds and nobody notices; under horecaos_application it would
-- be 'permission denied' at the first failed checkout. DatabasePrivilegeTests
-- finds it by reading the SQL the application runs.
--
-- Only DELETE is added. The statement's own filter (tenant, claimed quote,
-- status REDEEMED) stays in the application.

GRANT DELETE ON pricing.promotion_redemptions TO horecaos_application;
