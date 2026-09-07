-- ADR 0060: the staff Telegram bot is a decision channel of its own.
--
-- `OrderDecisionPortAdapter` has passed the literal 'HORECAOS_TELEGRAM_BOT' as
-- the decision channel since wave 6, and ck_approval_channel (V0022) has never
-- admitted it. Every Approve or Reject tapped in Telegram raised a check
-- violation -- ADR 0060's headline feature could not complete a single
-- decision in production. Nothing caught it because the only test of that path
-- injects a fake port and never constructs the real adapter, so the one
-- untested line was the one carrying the wrong value.
--
-- Two ways to fix it, and the choice is a real one.
--
-- Collapsing the bot into HORECAOS_OPERATIONS is defensible on ADR 0060's own
-- terms -- "the bot and the app are consumers of the same application
-- services, never parallel sources of truth" -- and it is what wave 61 did for
-- the till, which records the already-permitted 'POS' and carries the vendor
-- in actor_id as "pos:clopos". But that precedent puts *identity* in actor_id
-- while the channel keeps naming the *surface*, and here actor_id is already
-- spoken for: BotCallbackAuthorizer resolves the tap to a staff principal and
-- passes that subject, exactly as OperationsOrderController does for the web
-- board. Collapse the channel and a decision taken on a phone in a Telegram
-- chat becomes byte-for-byte indistinguishable from one taken on the board at
-- the pass. That is the question this column exists to answer, and the two
-- surfaces do not carry the same risk: the bot's authority arrives through an
-- opaque callback token and a linked Telegram account, a chain the board does
-- not have. ADR 0027 asks what a decision record must prove years later; "which
-- surface accepted this order" is squarely that.
--
-- So the set widens. This does not contradict V0022's reason for a CHECK over
-- a lookup table -- that rule is about tenants, who may not reorder or extend
-- this set at runtime; the platform extending it in a forward migration is
-- precisely the mechanism that comment leaves open.
--
-- The staff Flutter app (ADR 0060's other half, unbuilt) does NOT get a fourth
-- value: it authenticates like the web operations app and speaks the same
-- operations OpenAPI group, so it genuinely is HORECAOS_OPERATIONS. The bot is
-- not, and that asymmetry is the test for anything asking to be added here.
--
-- Restates ck_approval_channel in full. 'HORECAOS_TELEGRAM_BOT' is 21
-- characters and the column is varchar(24).
ALTER TABLE ordering.approval_decisions DROP CONSTRAINT ck_approval_channel;
ALTER TABLE ordering.approval_decisions
    ADD CONSTRAINT ck_approval_channel CHECK (
        decision_channel IN (
            'HORECAOS_OPERATIONS', 'HORECAOS_TELEGRAM_BOT', 'POS', 'SYSTEM_TIMEOUT'));

COMMENT ON COLUMN ordering.approval_decisions.decision_channel IS
    'The surface the decision arrived through, not who took it -- actor_type and actor_id carry that. Code-owned: OrderDecisionChannel holds the same set and a test asserts the two agree.';
