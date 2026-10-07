# ADR 0159: Service recovery and feedback settings

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — what exists is ADR 0071's order review and none
  of what this record adds. `reviews.order_reviews` (V0168) holds one immutable row per
  order, a 1–5 rating and an envelope-encrypted `comment_protected`, granted
  `SELECT, INSERT` only. Built on it: `ReviewSubmissionService`, `ReviewQueryService`,
  `StorefrontReviewController` (submit, the caller's own list), `OperationsReviewController`
  (list, summary, and the `customerAccountId` filter row 5.2h added) and
  `CustomerReviewPortAdapter` (ADR 0075's five star buttons in Telegram, rating only,
  no comment). `Capability.REVIEW_READ` is held by `tenant-owner`, `tenant-admin` and
  `brand-manager`. The console's 5.4 page is a read-only filtered list and
  `customers/feedback-settings` still routes to `NotBuiltPage` (`app.routes.ts`).
  **Nothing exists for any of the following**, checked by grep on 2026-10-07: a tag
  table or a tag on a review; a publication state or any way to hide a comment;
  a case, an owner, an SLA or an outcome; a review event (`EventCatalog` has none and
  `reviews.api` carries only `CustomerReviewPort`); a reporting fact or metric for
  reviews (no `fact_review`, no `average_rating`); a prompt policy.
  *The prompt today:* `OrderNotificationTrigger` creates the `ORDER_COMPLETED` intent the
  moment `OrderCompleted` is published, classed `TRANSACTIONAL_REQUIRED`
  (which `notifications.notification_preferences` says it ignores), sent at once,
  expiring after `horecaos.notifications.order-expiry` (default PT6H, which its own
  comment calls "a stated default, not a considered answer") on whatever channel
  `CustomerTelegramChannelRouter` picks (default `SMS`); the only off switch is not
  activating the template. *The seams that wait for this record:*
  `Capability.RECOVERY_CASE_MANAGE` and `RECOVERY_REMEDY_APPROVE` are constants that no
  endpoint declares (the first is held by `tenant-owner`, `tenant-admin`, `tenant-finance`,
  `location-manager`, `support-session-assist` and `support-agent`, the second by
  `tenant-owner` and `tenant-finance`); `pricing.benefit_grants.source_type` accepts
  `RECOVERY_CASE` (V0265) and no caller sets it; `payments.order_remedies` (V0052, ADR 0048)
  is keyed by order and carries no case reference; `customers.spi.CustomerErasureParticipant`
  has one registered participant (`dinein`) and none for `reviews`, so a customer's erasure
  does not touch their review comment today.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0010](../partial/0010-s3-media-lifecycle-and-filesystem-migration.md),
  [ADR 0015](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md),
  [ADR 0020](../partial/0020-notification-preferences-templates-and-delivery.md),
  [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md),
  [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md),
  [ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md),
  [ADR 0043](../partial/0043-reporting-analytics-and-the-metric-layer.md),
  [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md),
  [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md),
  [ADR 0056](../partial/0056-tenant-isolation-enforcement-and-rls.md),
  [ADR 0058](../partial/0058-telegram-notification-channels.md),
  [ADR 0071](../built/0071-order-reviews-a-rating-the-tenant-can-see.md),
  [ADR 0075](../partial/0075-a-customer-orders-from-the-chat-they-are-already-in.md),
  [ADR 0082](../built/0082-a-feature-flag-is-a-boolean-configuration-key.md),
  [ADR 0087](../built/0087-a-module-is-sold-on-its-own-unit-and-switches-features-on.md),
  [ADR 0101](../partial/0101-wave-p01-the-console-owns-a-shared-component-library.md),
  [ADR 0135](../partial/0135-object-storage-runtime-rustfs-replaces-minio.md),
  [ADR 0139](../partial/0139-staff-identity-the-staff-person-record.md),
  [ADR 0146](../not-started/0146-sms-gateway-contract.md),
  [ADR 0149](../not-started/0149-languages-beyond-ru-uz-latn-and-en.md)
- Supersedes / Superseded by: Amends [ADR 0071](../built/0071-order-reviews-a-rating-the-tenant-can-see.md)
  without replacing it: that record stays Accepted and Built, and this one does not
  edit it. It also reopens one decision each in
  [ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md) and
  [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md), the two
  records that placed service recovery, because the case built here lives in `reviews`.
  **Reopened, exactly:** (a) ADR 0071's Alternatives row *"The four-dimension
  service-recovery kanban with compensation actions"*, for the workflow half only — the
  four scored dimensions and the second compensation mechanism stay rejected; the row's
  revisit trigger ("a tenant with real review volume asks for a triage state ADR 0048 plus
  a status filter genuinely cannot answer") has not fired, and the owner's acceptance of
  this record waives it; (b) its row
  *"A moderation/publishing pipeline (hide, approve, feature, respond) in v1"*, whose own
  revisit trigger ("a review is displayed anywhere outside operations") this record
  fires on purpose, and its open input *"whether a tenant is ever given a way to hide or
  flag a review"* (answered: yes, the comment only); (c) its row *"Registering
  `average_rating.v1` in the ADR 0043 metric registry now"* and its open input on an
  ADR 0043 fact, whose trigger ("a second surface (a dashboard tile, an export) needs the
  same average and the two must agree") the public summary fires; (d) the sentence of its
  Decision that keeps `review.read` off `location-manager`, for item-level reads only;
  (e) its "no UPDATE" invariant, for one column and one purpose (erasure, Decision 13);
  (f) ADR 0013's Decision bullet *"Service recovery stays a separate `recovery` module"*
  and its `### Service recovery` case model (`recovery.cases` and the OPEN → ASSESSING →
  AWAITING_APPROVAL → APPROVED → EXECUTING → RESOLVED machine), replaced by
  `reviews.recovery_cases` and the four states of Decision 8; the capability
  `recovery.remedy.approve` is left unused, and `recovery.remedy_decisions` and
  `recovery.remedy_executions` are not built; (g) ADR 0048's Decision bullet that service
  recovery lives in `payments` rather than in a separate `recovery` module, and its
  Alternatives row *"Put the remedies in the separate `recovery` module ADR 0013
  specified"*, whose revisit trigger ("when a recovery case with its own states, SLA and
  versioned remedy policy is actually built") **has fired** and is answered no: the case
  stays in `reviews`, for the reason in this record's Alternatives row on a separate
  `recovery` module; (h) two sentences of ADR 0071's Decision, for the comment only — a
  rating is never hidden, changed or left out of the average: its headline *"never shown
  to anyone but its author and the tenant's own staff"* (now: the aggregate, and a comment
  the customer ticked and a moderator approved, once a tenant switches visibility on) and
  its bullet *"No moderation, no kanban, no compensation workflow"* (moderation and the
  worklist are built; "no compensation workflow" stands, Decision 9). **Standing,
  unchanged:** every other decision in ADR 0071 (see the table in Context), and ADR 0048's
  remedy model itself — three order remedies in `payments`, one approval threshold —
  beside which the case is built, linking to it and never replacing it. Also supersedes
  the `## Reviews` section of
  [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md) as a
  design: its `marketing.reviews` and `marketing.review_tags` were never created, its
  `POST_ORDER_REVIEW` trigger was never offered (`AutomationTriggerType` says why), and
  this record builds the same ideas where ADR 0071 put the entity.
- Open inputs: each is closed on its proposed default if the owner accepts the record as
  written; the ones that name a person other than the owner stay with that person and the
  work they block is marked.
  - **Whether the rating prompt may keep the `TRANSACTIONAL_REQUIRED` class** (legal).
    It ignores a customer's notification preferences and quiet hours today, and asking
    for a review is not a receipt. Proposed default: the class is unchanged by this
    record; a tenant's SMS, email and push prompts are off until the tenant enables them
    in the prompt policy, Telegram and the in-app prompt are on, and the policy can only
    make a prompt rarer or later than today's, never more frequent. Blocks: only a later
    move to `TRANSACTIONAL_OPTIONAL`, which needs a consent purpose that no checkout
    collects.
  - **The consent purpose and wording for showing a comment publicly** (legal, product).
    Proposed default: purpose code `review.public_display` at brand scope, an unticked
    box, one sentence in ru, uz-Latn and en saying the words may appear on this
    restaurant's page without the customer's name, policy version 1, recorded through
    ADR 0015's consent recorder. Blocks: Decision 6 only; the aggregate rating shows no
    one's words and needs no consent.
  - **The public-display defaults** (product). Proposed default:
    `feedback.public_visibility = NONE`, `feedback.public_minimum_reviews = 10`,
    `feedback.comments_require_approval = true`, so nothing is public until a tenant
    turns it on, a single bad review never is the rating, and no comment appears
    unread.
  - **The closed list of moderation reasons, and whether hiding a published comment
    needs a second person** (product, legal). Proposed default: the five reasons in
    Decision 7, one holder of `review.moderate`, reversible by the same capability, no
    maker-checker; the audit trail and the hidden-share metric are the control.
  - **The case defaults** (operations): the auto-open threshold, the two SLA clocks, the
    clock's basis and whether staff are alerted. Proposed default: a rating of 2 or less
    opens a case; first touch within 240 minutes; resolution within 4320 minutes (72
    hours); wall-clock time, not business hours; no Telegram alert on open or breach.
  - **The role-bundle changes this record needs** (platform owner). Proposed default:
    `brand-manager` gains `recovery.case.manage`; `location-manager` and `support-agent`
    gain `review.read` (item-level reads of a case's own review; the brand-wide list stays
    brand scope); `tenant-finance` and `support-session-assist` keep what they hold. No
    role loses a capability.
  - **Whether a case may have a source other than a review** (support operations).
    Proposed default: no. A phone complaint with no review is not a case here.
  - **Whether any courier is identified by a review, tag, case or fact** (platform
    owner; legal on the employment classification ADR 0042 and
    `intent/0004-rating-what-happened` name). Proposed default: no. `COURIER` is a tag
    category counted per location, reporting only, with no automatic effect on dispatch,
    pay or availability and no courier-facing view.
  - **What erasure takes from a review and a case** (legal). Proposed default: a data
    subject's erasure nulls the review comment and every case note and hides any public
    comment; the rating, tags, ids, states and outcomes stay, so no average moves.
  - **The starter tag pack, icon limits and library scope** (product). Proposed default:
    the twelve tags in Specification, wording written in ru, uz-Latn and en by product
    before the pack ships (no machine translation: `ProviderCategory` has no translation
    category); icons 3:2, at most 1 MB, raster types the media pipeline already accepts;
    one tenant-wide library, not one per brand.
  - **Whether the new metrics need finance's signature, and whether public reviews are a
    separately sold module** (finance). Proposed default: the metrics ship provisional
    under ADR 0043's amber rule until signed (the arithmetic is a mean and a count), and
    public reviews are part of the base plan with no entitlement key (ADR 0087).

**To accept as written:** say "accept 0159". Every open input above is then closed on its
proposed default.

## Context

**What the two rows say.** Gap-map row `5.4` (Reviews — service-recovery board, tier 2,
PARTIAL): "An operator cannot work a bad review: there is no New → In progress → Resolved
board, no handling history, no courier or four-dimension (meal/operator/courier/delivery-time)
breakdown, and no compensation action — issuing a promo code or bonus means leaving for
the order-remedy console with the order id copied by hand." Blocked by: "Reopening this
needs a new ADR: ADR 0071 rejected the kanban and the scored dimensions on the record."
Row `5.5` (Feedback settings, tier 3, NOT BUILT): "A tenant cannot offer one-tap feedback
tags, cannot choose when a customer is asked for a review per channel or order type, and
cannot decide whether ratings are public." Blocked by "a moderation/tagging decision that
ADR 0071 explicitly declined, plus LocalizedFieldGroup and MediaUploader (3:2 tag icons)".
Both are `XL` and sit in the gap map's table of rows that are "scheduled as decisions",
with the same unblocker: "A superseding ADR, or an IA amendment. The `review → remedy`
link is small and can ship regardless."

**What the IA and the specs ask for.** §5.4 owns "the kanban; four scored dimensions
(meal, operator, courier, delivery time); compensation actions (promo code, bonus,
discount); handling history; filters by branch/courier/rating", with `Board`,
`Timeline` and `ActorChip` named as its components. §5.5 owns the "review tag library
(localized text, icon, **category × sentiment**); prompt timing per channel/order type;
public visibility". `operations-spec/settings.md` puts "Отзывы (tags, review CRM)" in
Customers because "it is service recovery, not configuration", and
`operations-spec/statistics.md` sends Delever's comment report to 5.4 because "a comment
needs an action and an owner, and a read-only report gives it neither". Its skip table
says review analytics "belongs to 5.4 with a per-dimension mean and a distribution".
`operations-spec/couriers.md` leaves "courier rating and the four-axis review taxonomy"
out "until [an ADR] exists". The delever parity matrix records the legacy shape: a
four-column board whose cards carry date, stars, customer, order number, branch and
courier; one-tap tags with localized text and a 3:2 icon of at most 1 MB.

**Three records already say something, and they disagree.** ADR 0013 specified a
`recovery` module with cases, remedy decisions and executions, and ADR 0048 superseded
its remedy half with three order remedies inside `payments`, noting that "the module
split is right for a recovery *case* — a lifecycle, an SLA, a versioned policy" and
setting its own revisit trigger at "when a recovery case with its own states, SLA and
versioned remedy policy is actually built". ADR 0044 designed `marketing.reviews` with
five subject ratings, tags, a board status and a handoff to a recovery case — and none of
its tables exists. ADR 0071 then built `reviews.order_reviews` as a leaf module with one
rating, and declined the board, the dimensions, moderation and tags. So the constants
`recovery.case.manage` and `recovery.remedy.approve` are held by six roles and guard
nothing, a benefit-grant table names `RECOVERY_CASE` as a source with no case, and the
IA row asks for what ADR 0071 declined. This record picks one shape: it keeps ADR 0071's
entity and ADR 0048's remedies, and takes from ADR 0013 only the idea of a case with a
lifecycle and a clock, placed in `reviews` and not in a `recovery` module. That reopens ADR
0013's and ADR 0048's placement decisions, and Supersedes (f) and (g) say so.

**What ADR 0071 decided, and what happens to each part.**

| ADR 0071 | This record |
|---|---|
| A review belongs to exactly one order; `UNIQUE (tenant_id, order_id)` | **Stands** |
| Only the order's own customer, only once the order is `COMPLETED`; refunded orders are reviewable; a guest cannot review | **Stands** (guests stay an open question of ADR 0071) |
| One scalar rating, 1–5; no four scored dimensions | **Stands**; the dimensions are answered by tag categories (Decision 1) |
| Comment is `PERSONAL`, envelope-encrypted | **Stands** |
| Never shown to anyone but its author and the tenant's own staff | **Reopened** once a tenant switches visibility on: the aggregate rating, and a comment (with its rating) the customer ticked and a moderator approved (Decisions 5–7); a rating is never hidden |
| Immutable; no edit or withdrawal of rating or comment | **Stands**, with one erasure exception (Decision 13) |
| Retention tied to the order | **Stands** |
| No kanban, no case state | **Reopened**: a worklist exists (Decision 8) |
| No compensation workflow of its own | **Stands**: a case links to ADR 0048 and ADR 0044 compensation and pays nothing (Decision 9) |
| No hide or flag, because nothing shows a review outside operations | **Reopened**: something now can, and then the comment — never the rating — can be hidden (Decisions 5–7) |
| No fact-layer entry | **Reopened**: a second surface needs the same average (Decision 12) |
| A leaf `reviews` module, no `api` of its own | **Stands** as a leaf; it has had a narrow `api` since ADR 0075 (`CustomerReviewPort`) and grows it (events, fact sources, the prompt policy) |
| `review.read` at brand scope, not for `location-manager` | **Stands** for the list; **reopened** for item reads through a case (Decision 11) |
| Per-product and per-courier reviews rejected | **Stands** |

**Why now, and what makes it non-obvious.** Three facts change what a "no" costs.
First, the customer storefront's design asks for a three-way food/service/delivery
breakdown that `storefront-milliy`'s `ProfileComponent` says it cannot send ("there is no
per-category field on the wire to split a rating into"): tags are the smallest thing that
answers it. Second, a rating prompt that arrives as a required transactional SMS at 02:00
is a defect `5.5` is partly about, and `OrderNotificationTrigger` has no input to prevent
it. Third, ADR 0044 records the risk of the other half: "The review board is a second work
queue operations must actually work. An unworked board is worse than none, because the
customer sees that reviews are collected and infers they are read." A case with an owner
and a clock is the answer; a column of cards is not. What stays hard is that the courier
is the person a review most often names and the one person this platform may not rate:
ADR 0042 makes couriers self-employed, and `intent/0004-rating-what-happened` says a
score that affects how much work a courier is offered "is a long way down" the road to
reclassification. Everything below keeps the courier out of the data.

**What this record does not decide.** The remedy set and its approval threshold (ADR
0048); the unique apology code and its redemption (ADR 0044, row 6.2a); loyalty bonus
adjustments (ADR 0046); the contact policy for any message to the customer (ADR 0112);
editing or withdrawing a rating (ADR 0071); guest reviews (ADR 0071); legacy `ratings`
migration (ADR 0044's checklist).

## Decision

**Keep ADR 0071's one rating and put four things around it: tags that say which part
went wrong, a prompt the tenant can time, a public face the tenant may switch on under a
moderation that hides words and never numbers, and a service-recovery worklist whose unit
is a case about one review.** All of it lives in the existing leaf `reviews` module; none
of it touches an order, a payment or a courier.

1. **One rating stays; tags carry the dimensions.** `order_reviews` keeps its single
   1–5 rating. The IA's four dimensions are answered by what a customer taps, not by four
   scores: every tag has a code-owned **category** (`FOOD`, `SERVICE`, `COURIER`,
   `DELIVERY_TIME`, `OTHER`) and a **sentiment** (`POSITIVE`, `NEGATIVE`), and the
   per-dimension view row `5.4` asks for is a count of tags by category and sentiment per
   location and day (Decision 12). Adding a category is a release, not a tenant action.
2. **The tag library is the tenant's, one per tenant, authored in Settings > Feedback.**
   A tag has a stable `code` (immutable once a review uses it), a category and a
   sentiment (both immutable once used), a label per locale (the ADR 0149 translation-table
   shape, no new hard-coded locale list), an optional icon through `q-media-uploader`
   at 3:2 held as a tenant-owned `PUBLIC` media asset (ADR 0010), a sort order and a
   status. A tag is **retired, never deleted**: a retired tag is no longer offered and stays
   on every review that carries it. At most 40 tags are active. A tenant may import a
   starter pack (Specification) once and edit it; there is no shared cross-tenant library.
3. **Which tags a customer is offered, and what is stored.** Rating 4–5 offers positive
   tags, 1–2 negative, 3 both; at most eight per sentiment are shown, in sort order; a
   review carries at most five. The server refuses a tag that is retired, belongs to
   another tenant or does not match the rating. Tags are stored with the review, insert
   only, and never change. The Telegram star buttons (ADR 0075) stay rating-only; a
   bot rating still opens a case if it is low enough.
4. **The prompt is an order notification governed by a policy, not a marketing
   automation.** A versioned policy document `reviews.feedback_prompt` (ADR 0030, settable
   at tenant and brand) holds rows keyed by fulfilment mode (`DELIVERY`, `PICKUP`,
   `DINE_IN`, or any) and channel (`TELEGRAM`, `SMS`, `EMAIL`, `PUSH`, or any), each with
   `enabled`, `delayMinutes` and `giveUpAfterHours`, plus one `sendWindow` in the
   location's own time zone and one `inlineWindowDays` for the storefront's own prompt.
   Resolution is most specific row first, then the built-in default. **Built-in default:**
   Telegram on, SMS, email and push off, delay 30 minutes, give up after 6 hours (today's
   `order-expiry`), send window 09:00–21:00, inline prompt for 7 days. A prompt that
   would fall outside the window moves to its next opening. `OrderNotificationTrigger`
   asks one new `reviews.api` port for `(send, scheduledAt, expiresAt)` instead of using
   `now` and `now + order-expiry` (the `notifications -> reviews.api` edge, Ports between
   modules); it still decides nothing about the class (open input 1), the intent, its
   template and its delivery stay ADR 0020's, and an unactivated template still sends
   nothing. The ADR 0044 `POST_ORDER_REVIEW` marketing trigger stays unbuilt: a reminder,
   if wanted, is a 6.5 automation later.
5. **Public visibility has three levels and is off.** `feedback.public_visibility`
   (`NONE`, `RATING_ONLY`, `RATING_AND_COMMENTS`; ADR 0030 key, platform, tenant and
   brand) is read by two anonymous storefront endpoints: a summary (count, average to one
   decimal, the five-bar distribution) and a list. The summary reports nothing but a flag
   until the brand has `feedback.public_minimum_reviews` reviews. The average counts
   **every** review of the brand — consented or not, hidden comment or not — because a
   rating is not words and a number that leaves out the unwelcome ones is not a rating.
   The list shows only published comments, newest first, with the rating, the tags
   (labels and icons), the date to the day and the branch name; no name, no customer id,
   no order number, no time of day. A tenant cannot filter the list by rating, cannot pin,
   reorder or feature a comment, and cannot reply (Alternatives).
6. **A public comment needs the customer's yes, and a moderator's yes by default.** The
   submission carries `publicConsent` (default `false`), recorded through
   `customers.api.ConsentRecorder#recordGrant` as an ADR 0015 consent decision for purpose
   `review.public_display` (registered in the consent-type registry, Specification) and
   snapshotted on the review's publication row. With consent and a comment the row starts
   `PENDING` when `feedback.comments_require_approval` is true (default) and `PUBLISHED` when
   it is false; without either it is `NOT_SHARED`. The customer can **withdraw** public
   display at any time through a storefront call (refused with `UNPROCESSABLE_STATE` for a
   row that was never shared). The call records a `WITHDRAWN` decision for the same purpose
   through a new `ConsentRecorder#recordWithdrawal` — a change owned by `customers`, whose
   port today only grants and keeps a withdrawal behind `ConsentService` — stores that
   decision's id in `withdrawal_decision_id`, sets the comment `HIDDEN` with reason
   `CUSTOMER_WITHDREW`, evicts the brand's public list cache in the same request (after the
   commit, so the next read cannot return the comment), and cannot be reversed by a
   moderator. `public_consent` is **never flipped**: it is the immutable snapshot of what was
   ticked at submission, and the present state of a comment is `comment_state` with
   `withdrawal_decision_id`. Withdrawal does not touch the rating, the comment the tenant's
   staff see, or a case: ADR 0071's "no withdrawal of a review" stands for everything except
   who the public may read it.
7. **Moderation hides words, never numbers.** A holder of the new `review.moderate`
   (brand scope) may move a comment `PENDING → PUBLISHED`, `PENDING → HIDDEN`,
   `PUBLISHED → HIDDEN` and `HIDDEN → PUBLISHED` (except after `CUSTOMER_WITHDREW` or
   `CUSTOMER_ERASED`), and must give one of five reasons: `ABUSIVE_LANGUAGE`,
   `PERSONAL_DATA_OF_ANOTHER`, `NAMES_AN_INDIVIDUAL`, `SPAM_OR_UNRELATED`,
   `LEGAL_REQUEST`. "Negative", "unfair" and "I disagree" are not reasons and the form
   offers no free text. A decision changes only the publication row — never
   `order_reviews`, never the rating, never the average — and writes an ADR 0027 fact
   whose `reason` is the code's label and whose change document carries ids, states and
   the code, never the comment. The customer sees the state and the reason code of their
   own comment in "my reviews". Hidden-to-published ratios by rating band are a platform
   report so abuse of the hide is visible; nothing acts on it automatically.
8. **A recovery case is a worklist item about exactly one review.** `UNIQUE (tenant_id,
   review_id)`; created in the review's own transaction when the flag
   `feature.service_recovery` is on and the rating is at most `autoOpenMaxRating` of the
   `reviews.recovery` policy (default 2), or by hand from any review by a
   `recovery.case.manage` holder. States are the IA's: `NEW`, `IN_PROGRESS`, `RESOLVED`,
   `CLOSED_UNRESOLVED`. Transitions: `NEW → IN_PROGRESS` (the caller or a named staff member
   becomes the **owner**; this is "start work" and the first response), `IN_PROGRESS → NEW`
   (release), `IN_PROGRESS → RESOLVED`, `IN_PROGRESS → CLOSED_UNRESOLVED`, and
   `NEW → CLOSED_UNRESOLVED` with outcome `NOT_ACTIONABLE` only, which dismisses the case
   without answering it, so `first_response_at` stays null. A terminal state is final and a
   reopened complaint is a new conversation, not a new state (Alternatives). Closing
   needs an **outcome** from a closed list: for `RESOLVED`, `REMEDY_GRANTED` (needs at
   least one attached compensation), `OFFLINE_GESTURE`, `EXPLAINED_NO_REMEDY`,
   `NO_REMEDY_NEEDED`; for `CLOSED_UNRESOLVED`, `CUSTOMER_UNREACHABLE`,
   `CUSTOMER_DECLINED`, `COMPLAINT_NOT_UPHELD`, `NOT_ACTIONABLE`. The owner is a staff
   member (ADR 0139) and is resolved to a name only when read. Every step is a row in an
   append-only timeline; notes are free text and therefore `PERSONAL`, encrypted, and
   never carried by a response that an `@Idempotent` handler stores, an event, a log or
   an audit change document.
9. **A case links to compensation and never pays it.** An operator uses the ADR 0048
   order-remedy console (refund, delivery-fee reimbursement, future discount) or the ADR
   0044 benefit-grant mint (a one-off code, which already accepts `source_type =
   RECOVERY_CASE`), with the order and customer prefilled from the case, and the case
   records the link: `kind` (`ORDER_REMEDY` or `BENEFIT_GRANT`) and the id, verified to
   belong to the same order (a remedy, through `reviews.spi.CompensationDirectory`, which
   `payments` implements) or to the same customer and brand (a grant, through
   `pricing.api.BenefitGrantDirectory`). A remedy's amount and currency are copied onto the
   link at that moment, so `reviews` never reads `payments` again. A compensation belongs to
   at most one case. Loyalty bonus adjustments
   (row `5.2e`) are not linked at launch. No approval machinery is added:
   `recovery.remedy.approve` stays an unused constant, and ADR 0048's threshold and
   ADR 0027's approvals are the only money control.
10. **The case carries two clocks, snapshotted at opening.** `firstResponseDueAt` and
    `resolutionDueAt` are computed from the `reviews.recovery` policy (versioned, ADR
    0030; the case stores the policy version it used so a later edit never makes an old
    case late) on wall-clock time. A case is **at risk** in the last 20% of a clock and
    **breached** past it; both are computed on read (a closed case against when it was
    answered or closed, not against now). A worker marks each breach once and publishes it,
    and only while the case is open (`closed_at IS NULL`): a case closed before the worker
    reaches it — dismissed from `NEW`, or resolved — is never marked afterwards, so it
    publishes no `RecoveryCaseSlaBreached` and adds nothing to `recovery.sla_breach_rate`.
    Staff alerts through the ADR 0058 chat subscriptions are off unless
    `alertOnOpen` or `alertOnBreach` is set in the policy.
11. **Who may do what (ADR 0025).** `review.read` keeps the brand-wide list (brand scope).
    New `review.moderate` (brand scope) holds moderation and the queue: `tenant-owner`,
    `tenant-admin`, `brand-manager`. Working a case is `recovery.case.manage`: the brand
    worklist at brand scope, one case at the **case's location** scope. The case drawer
    shows the rating, tags, order and timeline to any holder at that location, and the
    comment text only if the caller also holds `review.read` for that location; otherwise
    it is withheld with a marker and the case can still be worked. Role bundles change as
    open input 6 says. Settings (tags, prompt policy, visibility) use
    `tenant.configuration.read` and `tenant.configuration.write`, the capabilities the
    order-policy card already uses. No courier identity is in any of it (open input 8).
12. **Reporting facts, one definition.** ADR 0043's pattern, as ADR 0140 used it for
    promotions: facts built at day close through `reviews.api` fact sources, never by a
    report reading `reviews` tables, the customer as the ADR 0029 keyed hash. Three
    facts (`fact_review`, `fact_review_tag`, `fact_recovery_case`) and the metrics in
    Specification are registered in `MetricRegistry` as version 1. A case counts as
    opened on its opening date and closed on its closing date, as a refund lands on its
    own date. The public summary is computed live from `order_reviews` and must equal the
    metric over the same set; a test pins that, the way ADR 0140 pins its simulator to
    the real quote.
13. **Events, PII and erasure.** Five events through the outbox on a new topic
    `reviews.events`, keyed by `orderId`, carrying ids, states, codes and times only, a
    staff member as the non-personal `display_reference`. Review comment and case notes
    are `PERSONAL`. A customer's erasure registers a `reviews` participant that nulls the
    comment and every note, sets any public comment `HIDDEN` with `CUSTOMER_ERASED`,
    evicts the public list cache of each brand where a row changed (after the commit, as a
    withdrawal does), and keeps rating, tags, ids and outcomes. This is the one exception to "no UPDATE" on
    `order_reviews`: a column grant on `comment_protected` and a trigger that refuses
    any change except setting it to null.
14. **Rollout by two flags, dark by default (ADR 0082).** `feature.service_recovery`
    gates case creation and the worklist; `feature.feedback_settings` gates tags in the
    submission, the prompt policy and every public endpoint. With both off the platform
    behaves exactly as it does today, including the prompt's immediate send.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| ADR 0044's design: five subject ratings, `marketing.reviews`, the board inside marketing | Rebuilds the entity ADR 0071 already built, in another module, with a rating shape the storefront cannot send and the IA's "four scores" that tags answer with less to build; its tables do not exist, so nothing is lost | A tenant shows that tag counts cannot answer "which part is getting worse" and asks for a numeric split |
| Four numeric sub-ratings (IA 5.4, ADR 0071 row 1) | Asks the customer four questions when the storefront asks one, multiplies the write surface, and a per-subject score for "courier" is exactly the number `intent/0004` warns against | The owner and legal settle courier rating (open input 8) and product wants scored dimensions for the other three |
| Keep ADR 0071's "no moderation" and never show a review outside operations | Costs nothing, and leaves 5.5's "public visibility" with nothing to control and every competitor with a star rating on the page | The owner decides no tenant surface will ever display a review publicly |
| Public display with post-moderation only (everything visible until hidden) | A comment naming a person or a phone number is public until someone notices; the default is the safer way round, and a tenant that trusts its customers can set `comments_require_approval = false` | Measured abuse is near zero across tenants and approval latency is the complaint |
| Let a tenant hide a rating or exclude it from the average | Selective removal makes the number a marketing figure; consumer-protection rules in several markets treat suppressed reviews as deceptive. A rating comes from a completed order by its own customer and has nothing to moderate | Never |
| A separate `recovery` module (ADR 0013 and ADR 0048's shape) | The only source of a case is a review, created in the same transaction and read with it, and the case needs that review's comment, tags and location; a second module adds a two-way dependency and a second event interface for a table pair. ADR 0048's revisit trigger for this (a recovery case with its own states, SLA and versioned remedy policy is actually built) fires here and is answered no. The module cycle that a call from `reviews` into `payments` would close is avoided either way, by inverting that one edge (Ports between modules) | A second intake exists (open input 7): a phone complaint, a courier-service complaint, a call-centre screen. Then the case moves, which is a package move and a schema rename |
| Tenant-defined board columns | The order state machine is code-owned for the same reason ("Order status CRUD" is declined in the specs); custom states make reporting and SLAs ungovernable | A tenant shows a stage the four states cannot express |
| Reopen a resolved case | Needs a second SLA, a second owner history and a rule for the compensation already given; a complaint that returns is a new review or a new order | Support operations report returning complaints that a new case loses context for |
| Business-hours SLA | Needs per-location calendars wired into a case clock (ADR 0107 has the calendar); wall-clock is simpler and strictly stricter | A tenant asks not to be counted late overnight and weekends |
| Embed refund and discount authoring inside the case | A second compensation mechanism answers the question ADR 0048 and ADR 0044 already answer, with a second cap | Never; the link is the integration |
| Compensation approval by `recovery.remedy.approve` | `ApprovalService.decide` cannot scope a decision to an action code (ADR 0048's own accepted trade-off), so a second approve endpoint is a bypass | ADR 0048's approvals console exists |
| The prompt as an ADR 0044 `POST_ORDER_REVIEW` automation | Marketing class needs consent no checkout collects, and the ORDER_COMPLETED notification already exists and is used by the Telegram star buttons | A tenant wants a reminder; author it as a 6.5 automation then |
| Key the prompt policy by sales channel too | Orders from kiosks, point-of-sale and aggregators mostly carry no account, and ADR 0071 refuses a guest, so the rows would be dead | A tenant needs different timing for QR dine-in than for web |
| One tag library per brand | More screens and more copies of the same twelve tags | A chain with differently branded concepts asks for different tag sets |
| Auto-translate tag labels | No translation provider exists (`ProviderCategory` has none, row `X.5`) | A provider is chosen |
| Show the customer's first name on a public comment | A first name plus a branch and a date is enough to identify a regular | Never without a separate consent line |
| A tenant reply to a public review | A public reply is a second moderation surface and a second legal exposure | The owner asks for it after public display has run for a quarter |
| Filter or break a review down by courier | Identifies a person the platform may not rate (ADR 0042) | Counsel confirms in writing that reporting by courier does not change the engagement |
| Add `UPDATE` on `order_reviews` for moderation | Breaks "immutable once submitted" for every column to serve one; the overlay row keeps the original untouched | Never |

## Consequences

### Positive

- Rows `5.4` and `5.5` stop waiting on a decision, and the part of `5.4` that the gap map
  calls small — the review-to-remedy link — ships inside a worklist with an owner and a
  clock instead of as a loose button.
- The IA's four dimensions are answered without a second rating shape: the storefront
  keeps asking one question, the tenant gets "which part is getting worse" as tag counts
  per location and day, and the courier stays out of the data.
- The prompt stops being a required SMS at any hour. A tenant that has never touched the
  policy gets fewer, later, in-hours prompts; a tenant that had an SMS template active is
  preserved by an explicit row (Rollout).
- Public display exists only behind four independent gates — a tenant switch, a minimum
  count, the customer's tick and a moderator's approval — and the number is never edited.
- Every mechanism is one the platform already trusts: ADR 0030 policies and flags, ADR 0010
  media, ADR 0015 consent, ADR 0027 audit, ADR 0043 facts, ADR 0048 and ADR 0044
  compensation, ADR 0139 staff references. No new cross-cutting machinery.

### Negative

- A second work queue now exists, and ADR 0044's warning applies in full: an unworked
  worklist tells customers nothing and tells the tenant it is failing. Auto-open at 2 stars
  starts clocks that nobody may be watching; breaches are counted and, by default, not
  announced.
- `recovery.case.manage` is held by `tenant-finance` and `support-session-assist`, roles
  chosen before a case existed. They can work cases; they see no comment text without
  `review.read`. The bundle question is recorded, not settled.
- Giving `location-manager` and `support-agent` `review.read` widens who can read
  customers' words. It is item-level for a location manager and tenant-wide for a support
  agent, who already holds `customer.pii.reveal`.
- The comment is hidden from the public but not from staff, and a customer cannot edit or
  delete it. A customer who regrets a comment can stop its public display and nothing more
  until erasure.
- A hide control invites misuse. Five reasons, an audit fact and a platform report make
  misuse visible, and nothing stops it.
- Tag counts are a coarser instrument than four scores: they show what customers chose to
  tap, not an average per dimension, and a customer who taps nothing says nothing about
  four of the five categories.
- `reviews` gains module edges, and each is chosen so that no cycle closes
  (`ModularArchitectureTests` verifies the module graph and refuses one). Into `reviews`:
  `notifications -> reviews` (events and the prompt policy, through `reviews.api`),
  `reporting -> reviews` (two more fact sources, through `reviews.api`) and `payments ->
  reviews` (`payments` implements `reviews.spi.CompensationDirectory`, the direction `dinein`
  already takes for `customers.spi.CustomerErasureParticipant`), beside the existing
  `integration -> reviews`. Out of `reviews`: `reviews -> pricing` (`BenefitGrantDirectory`),
  beside the `reviews -> ordering` and `reviews -> customers` edges it already has. The tree
  already holds `payments -> notifications`, `payments -> integration` and `integration ->
  reviews`, so `reviews -> payments` is not available (it closes cycles through
  `notifications` and `integration`), and `pricing -> reviews` is not either (`reviews ->
  ordering -> pricing`). `reviews` imports nothing from `notifications`, `payments`,
  `integration` or `reporting`: it publishes events and is called, never the caller.
- The anonymous list decrypts a `PERSONAL` comment. A published, consented and approved
  comment is revealed under its own purpose (`reviews.public.display`) and sits decrypted in
  the brand's public cache for up to 60 s. Moderation, withdrawal and erasure evict that cache
  in the same request, so a hidden comment is gone by the next read, and it never holds a
  `PENDING`, `HIDDEN` or unconsented one.
- One more table pair is `UPDATE`-able in a module whose first record sold itself on
  immutability. The overlay and the case are mutable by design; the review row is not.

### Accepted trade-offs

- A case opens from a review only. A complaint by phone is outside the worklist until
  support operations ask for a second source.
- The SLA is wall-clock, so a case opened at 22:00 is "at risk" by morning. That is
  stricter than what staff can meet and is accepted over building a calendar into a clock.
- The average counts ratings whose comments were hidden. A tenant that wanted a flattering
  number does not get one.
- Reviews placed before this record have no consent, so none of their comments can ever be
  shown publicly. A tenant's public page therefore starts with an average and no words.
- A breach is the worker's mark on an open case. A case closed in the gap between its due
  time and the worker's next pass is not counted as breached; that under-count is accepted
  over marking breaches after a case is closed.
- The first release has no reply, no reminder, no apology message and no courier view.

## Specification

### Physical model

Every table carries `tenant_id`; every unique and foreign key includes it; every table is
granted to `horecaos_application` in its own migration (V0035's lesson); none holds a name,
phone, address or code word. Migration numbers are taken at implementation time. ADR 0056
applies as it does to V0168: isolation is application-enforced, and the RLS backstop
template (V0161) is attached when the production-hardening phase reaches this schema.

```text
reviews.feedback_tags                        -- the tenant's library
  id uuid pk, tenant_id
  code varchar(48)            -- ^[a-z][a-z0-9_]{1,47}$, immutable once used
  category varchar(16)        -- FOOD | SERVICE | COURIER | DELIVERY_TIME | OTHER
  sentiment varchar(8)        -- POSITIVE | NEGATIVE
  icon_asset_id uuid null     -- media.assets: tenant-owned, PUBLIC, AVAILABLE, 3:2
  sort_order integer, status varchar(8)   -- ACTIVE | RETIRED
  version integer, created_at, updated_at, created_by_subject, updated_by_subject
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, code)
  FOREIGN KEY (icon_asset_id, tenant_id) REFERENCES media.assets (asset_id, tenant_id)
  GRANT SELECT, INSERT, UPDATE            -- no DELETE

reviews.feedback_tag_translations            -- the V0430 shape
  tenant_id, tag_id, locale varchar(16), label varchar(40), created_at, updated_at
  PRIMARY KEY (tag_id, locale); locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'
  FOREIGN KEY (tag_id, tenant_id) REFERENCES reviews.feedback_tags (id, tenant_id)
  GRANT SELECT, INSERT, UPDATE, DELETE

reviews.order_review_tags                    -- insert only, like the review
  tenant_id, review_id, tag_id, PRIMARY KEY (review_id, tag_id)
  FOREIGN KEY (tenant_id, review_id) REFERENCES reviews.order_reviews (tenant_id, id)
  FOREIGN KEY (tenant_id, tag_id)    REFERENCES reviews.feedback_tags (tenant_id, id)
  GRANT SELECT, INSERT

reviews.review_publications                  -- the overlay; order_reviews stays untouched
  tenant_id, review_id, PRIMARY KEY (tenant_id, review_id), brand_id, location_id
  public_consent boolean       -- what the customer ticked at submission: a snapshot, never flipped
  consent_decision_id uuid null      -- ADR 0015: the GRANTED decision, when ticked
  withdrawal_decision_id uuid null   -- ADR 0015: the WITHDRAWN decision; set once, with CUSTOMER_WITHDREW
  comment_state varchar(10)    -- NOT_SHARED | PENDING | PUBLISHED | HIDDEN
  state_reason_code varchar(32) null, decided_by_actor_type varchar(8) null  -- STAFF|CUSTOMER|SYSTEM
  decided_by_subject varchar(64) null, decided_at timestamptz null
  version integer, created_at, updated_at
  CHECK (comment_state = 'NOT_SHARED' OR public_consent)
  CHECK (withdrawal_decision_id IS NULL OR comment_state = 'HIDDEN')
  FOREIGN KEY (tenant_id, review_id) REFERENCES reviews.order_reviews (tenant_id, id)
  INDEX (tenant_id, brand_id, comment_state, updated_at DESC)
  INDEX (tenant_id, brand_id, location_id, review_id DESC) WHERE comment_state = 'PUBLISHED'
  GRANT SELECT, INSERT; GRANT UPDATE (comment_state, state_reason_code, decided_by_actor_type,
     decided_by_subject, decided_at, withdrawal_decision_id, version, updated_at)  -- not public_consent

reviews.recovery_cases
  id uuid pk, tenant_id, brand_id, location_id, order_id, review_id, customer_account_id
  source varchar(8)             -- AUTO | MANUAL (review-only at launch)
  state varchar(18)             -- NEW | IN_PROGRESS | RESOLVED | CLOSED_UNRESOLVED
  outcome_code varchar(24) null
  owner_staff_member_id uuid null    -- FK (owner_staff_member_id, tenant_id) -> iam.staff_members (id, tenant_id)
  opened_by_staff_member_id uuid null
  policy_version integer, first_response_due_at, resolution_due_at timestamptz
  first_response_at, closed_at null, first_response_breached_at null, resolution_breached_at null
  version integer, created_at, updated_at
  UNIQUE (tenant_id, id), UNIQUE (tenant_id, review_id)
  FOREIGN KEY (tenant_id, review_id) REFERENCES reviews.order_reviews (tenant_id, id)
  FOREIGN KEY (order_id, tenant_id) REFERENCES ordering.orders (id, tenant_id)
  FOREIGN KEY (tenant_id, brand_id, location_id) REFERENCES tenant.locations (tenant_id, brand_id, id)
  FOREIGN KEY (customer_account_id, tenant_id) REFERENCES customer.customer_accounts (id, tenant_id)
  CHECK state/outcome pairing (RESOLVED -> 4 outcomes; CLOSED_UNRESOLVED -> 4; NEW and IN_PROGRESS -> null)
  CHECK (state <> 'IN_PROGRESS' OR owner_staff_member_id IS NOT NULL)
  CHECK (closed_at IS NOT NULL) = (state IN ('RESOLVED', 'CLOSED_UNRESOLVED'))
  CHECK (first_response_breached_at IS NULL OR closed_at IS NULL OR first_response_breached_at <= closed_at)
  CHECK (resolution_breached_at IS NULL OR closed_at IS NULL OR resolution_breached_at <= closed_at)
  INDEX (tenant_id, brand_id, state, resolution_due_at); INDEX (tenant_id, location_id, state)
  PARTIAL INDEX for the first-response sweeper: (first_response_due_at) WHERE closed_at IS NULL
     AND first_response_at IS NULL AND first_response_breached_at IS NULL
  PARTIAL INDEX for the resolution sweeper: (resolution_due_at) WHERE closed_at IS NULL
     AND resolution_breached_at IS NULL
  GRANT SELECT, INSERT, UPDATE

reviews.recovery_case_events                 -- the handling history, insert only
  id, tenant_id, case_id, kind      -- OPENED|ASSIGNED|STARTED|RELEASED|NOTE|COMPENSATION_ATTACHED
                                    -- |FIRST_RESPONSE_BREACHED|RESOLUTION_BREACHED|RESOLVED|CLOSED_UNRESOLVED
  from_state null, to_state null, detail_code varchar(24) null, actor_type varchar(8)   -- STAFF|SYSTEM
  actor_staff_member_id null, note_protected text null      -- ADR 0029 PERSONAL
  occurred_at; FOREIGN KEY (tenant_id, case_id) REFERENCES reviews.recovery_cases (tenant_id, id)
  GRANT SELECT, INSERT; GRANT UPDATE (note_protected) for the erasure participant only

reviews.recovery_case_compensations
  tenant_id, case_id, kind          -- ORDER_REMEDY | BENEFIT_GRANT
  ref_id uuid, order_id uuid, attached_by_staff_member_id, attached_at
  amount_minor bigint null, currency char(3) null   -- copied from the directory at attach, never re-read
  PRIMARY KEY (case_id, kind, ref_id), UNIQUE (tenant_id, kind, ref_id)
  CHECK ((amount_minor IS NULL) = (currency IS NULL)), CHECK (amount_minor >= 0)
  CHECK (kind = 'ORDER_REMEDY' OR amount_minor IS NULL)    -- a benefit grant is a count, not money
  GRANT SELECT, INSERT
```

`payments.order_remedies` has `uq_remedy_identity UNIQUE (tenant_id, id)` and no unique key
over the order; the `ORDER_REMEDY` check is made through `reviews.spi.CompensationDirectory`
(Ports between modules), not a foreign key, so payments' schema is untouched, and the amount
and currency are copied into the link row when it is attached so that no later read goes
back into `payments`. `order_reviews` gains one thing:
`GRANT UPDATE (comment_protected)` and a `BEFORE UPDATE` trigger that raises unless the only
change is `comment_protected` going from a value to null.

The purpose `review.public_display` is registered in the ADR 0015 consent-type registry
(V0289, `customer.consent_types`): it is added to `ConsentTypeService.DEFAULTS` for a tenant
not yet seeded, with a label in ru, uz-Latn and en written by product with the consent wording
(open input 2), `channel_specific` false and policy version `1`; and a migration inserts the
same row for every tenant that already has a registry, because that bootstrap runs only for a
tenant with none.

### Keys and policies (ADR 0030, ADR 0082)

```text
feature.service_recovery        Boolean  default false  platform, tenant
feature.feedback_settings       Boolean  default false  platform, tenant
feedback.public_visibility      String   default NONE   platform, tenant, brand   NONE | RATING_ONLY | RATING_AND_COMMENTS
feedback.public_minimum_reviews Integer  default 10     platform, tenant, brand   0..1000
feedback.comments_require_approval Boolean default true  platform, tenant, brand

PolicyKey reviews.feedback_prompt   settable at tenant, brand; replaced whole
  { sendWindow: {start: "09:00", end: "21:00"} | null, inlineWindowDays: 7,
    rows: [ { fulfillmentMode: DELIVERY|PICKUP|DINE_IN|"*", channel: TELEGRAM|SMS|EMAIL|PUSH|"*",
              enabled, delayMinutes: 0..1440, giveUpAfterHours: 1..72 } ] }

PolicyKey reviews.recovery          settable at tenant, brand; replaced whole
  { autoOpenMaxRating: 0..5 (default 2; 0 = never), firstResponseMinutes: 15..10080 (default 240),
    resolutionMinutes: 60..43200 (default 4320), alertOnOpen: false, alertOnBreach: false }
```

The scalar keys are `tenantVisible` and are declared in `reviews.api.ReviewsConfigurationKeys` and, for the startup
validator, identically in tenancy's `ConfigurationKeys`, kept in step by a test, as
`OrderingConfigurationKeys` does. The scalar keys are written through
`OperationsConfigurationController` and the two documents through an editor beside the
order-lateness one, under `tenant.configuration.write`. The 12 starter tags are
`food_tasty`, `food_fresh`, `food_portion`, `service_friendly`, `delivery_on_time`,
`delivery_careful` (positive) and `food_cold`, `food_wrong_item`, `service_rude`,
`delivery_late`, `delivery_damaged`, `order_incomplete` (negative); category follows the
code prefix (`food_` is `FOOD`, `service_` is `SERVICE`, `delivery_` is `DELIVERY_TIME`) and
`order_incomplete` is `OTHER`; none is `COURIER`.

### APIs (ADR 0031, capability per ADR 0025)

```text
Storefront (@CustomerOwned unless stated)
POST .../brands/{b}/orders/{orderId}/review          body gains tagIds[<=5], publicConsent   existing; @Idempotent
GET  .../brands/{b}/reviews                          each row gains tags[], publicDisplay {state, reasonCode}
POST .../brands/{b}/reviews/{reviewId}/public-display/withdrawal        @Idempotent; records a WITHDRAWN decision
GET  .../brands/{b}/reviews/pending                  completed, unreviewed orders inside the policy's inline window
GET  .../brands/{b}/feedback/config                  anonymous, cached: offered tags by sentiment, localized, icon urls, consent text key
GET  .../brands/{b}/reviews/public/summary           anonymous, cached 300 s per brand, rate limited
GET  .../brands/{b}/reviews/public                   anonymous, cached 60 s per brand, evicted on moderation, withdrawal and erasure, rate limited

Operations (prefix /api/v1/operations/tenants/{t})
GET  /brands/{b}/recovery-cases?state&ownerStaffMemberId&locationId&slaState&cursor&limit   recovery.case.manage @BRAND
GET  /brands/{b}/recovery-cases/summary              counts by state and SLA state            recovery.case.manage @BRAND
GET  /brands/{b}/locations/{l}/recovery-cases[/{caseId}]   list and one case               recovery.case.manage @LOCATION
POST /brands/{b}/locations/{l}/recovery-cases        {reviewId}                              recovery.case.manage @LOCATION; @Idempotent
POST /brands/{b}/locations/{l}/recovery-cases/{caseId}/transitions   {to, ownerStaffMemberId?, outcomeCode?, expectedVersion}
POST /brands/{b}/locations/{l}/recovery-cases/{caseId}/notes         {note}
POST /brands/{b}/locations/{l}/recovery-cases/{caseId}/compensations {kind, refId}
GET  /brands/{b}/reviews/moderation-queue?state=PENDING            review.moderate @BRAND
POST /brands/{b}/reviews/{reviewId}/moderation-decisions {to, reasonCode, expectedVersion}   review.moderate @BRAND; @Idempotent
GET|POST|PUT /feedback/tags, POST /feedback/tags/starter-pack        tenant.configuration.read|write
GET|PUT /brands/{b}/feedback/prompt-policy, /brands/{b}/recovery-policy   resolved + authored per scope
```

The two anonymous endpoints use ADR 0033's shared cache and rate limiter, keyed per brand. The
list's cache entry holds the decrypted text of `PUBLISHED` rows only, for at most 60 s; a moderation
decision, a withdrawal and an erasure each evict it in the same request, after the commit, so the
next read cannot return the comment. The summary holds no words and needs no eviction: a hide, a
withdrawal and an erasure never move the average.

Mutating responses carry ids, states, versions and codes, never the comment or a note (an
`@Idempotent` response is stored for a day; `IdempotentResponseClassificationTests` guards
it). The drawer is a `GET`. Refusals use the ADR 0031 codes already in use:
`RESOURCE_NOT_FOUND` for a case outside the caller's location, `UNPROCESSABLE_STATE` for a
transition the table forbids, `STALE_VERSION` on a lost race, `VALIDATION_FAILED` for a
tag that does not match the rating or an outcome that does not match the state.

### Events (ADR 0032, outbox)

`ReviewsEvent` is a sealed interface in `reviews.api`, appended by a `ReviewsOutboxEventListener`
at `BEFORE_COMMIT` exactly as `OrderingOutboxEventListener` does; topic `reviews.events`
(3 partitions, 7 days, replication 1 per ADR 0034), key `orderId`; each has a catalogue
entry, a JSON schema and a frozen baseline.

| Event | Payload (no personal data) |
|---|---|
| `ReviewSubmitted` v1 | reviewId, orderId, brandId, locationId, rating, hasComment, tagCount, submittedAt |
| `ReviewCommentModerated` v1 | reviewId, orderId, brandId, fromState, toState, reasonCode, actorType |
| `RecoveryCaseOpened` v1 | caseId, reviewId, orderId, brandId, locationId, source, rating, firstResponseDueAt, resolutionDueAt |
| `RecoveryCaseStateChanged` v1 | caseId, orderId, brandId, locationId, fromState, toState, outcomeCode, ownerReference (`S-0142`), changedAt |
| `RecoveryCaseSlaBreached` v1 | caseId, orderId, brandId, locationId, kind (`FIRST_RESPONSE`, `RESOLUTION`), dueAt, breachedAt |

`EventPayloadClassificationTests` must see no `PERSONAL` type reachable. `notifications`
consumes `RecoveryCaseOpened` and `RecoveryCaseSlaBreached` in its own trigger class and
calls `OperationsAlertPort`, with two new event classes added to the ADR 0058 chat
subscription vocabulary: the whole `ck_telegram_binding_event_class` list is restated from
the live constraint, never from V0104's copy of it.

### Reporting (ADR 0043)

```text
reporting.fact_review         tenant_id, review_id, business_date, boundary_version, metric_calculation_version,
                              brand_id, location_id, order_id, customer_subject_hash, rating, has_comment, tag_count
reporting.fact_review_tag     tenant_id, review_id, tag_id, business_date, brand_id, location_id, category, sentiment
reporting.fact_recovery_case  tenant_id, case_id, opened_business_date, closed_business_date null, brand_id, location_id,
                              order_id, review_id, rating, state, outcome_code, seconds_to_first_response null,
                              seconds_to_close null, first_response_breached, resolution_breached,
                              compensation_count, remedy_money_minor, currency
```

Built at day close through `reviews.api.ReviewFactSource` and `RecoveryFactSource` (the latter
reads remedy money from `recovery_case_compensations.amount_minor` and `currency`, copied when
the compensation was attached, and reads no `payments` table), derived and rebuildable, granted
`SELECT, INSERT, UPDATE, DELETE`. Metrics, all version 1 and provisional until signed:
`reviews.count`, `reviews.average_rating` (sum of ratings over count, half-up to one decimal),
`reviews.tagged` (by category × sentiment), `recovery.cases_opened`, `recovery.cases_closed`
(by outcome), `recovery.first_response_seconds`, `recovery.close_seconds` (mean and 90th
percentile), `recovery.sla_breach_rate`, `recovery.remedy_money` (money only, as attached; a benefit grant
is a count). No metric has a courier dimension.

### Ports between modules

The edges of `reviews` after this record, each chosen so that no cycle forms (`ModularArchitectureTests`
refuses one; an import-graph run over `src/main` with exactly these edges added shows no cycle through
`reviews`):

- **Into `reviews`, through `reviews.api`:** `ReviewsEvent`, `FeedbackPromptPolicy#decide(tenant, brand,
  location, mode, channel, completedAt) -> (send, scheduledAt, expiresAt)` (called by `notifications`),
  `ReviewFactSource` and `RecoveryFactSource` (called by `reporting`). The existing `integration ->
  reviews.api` (`CustomerReviewPort`) is unchanged.
- **Into `reviews`, through `reviews.spi`:** `payments` implements
  `reviews.spi.CompensationDirectory#summary(tenant, refId) -> CompensationSummary(kind, refId, orderId,
  amountMinor, currency)` over `payments.order_remedies`, so the dependency is `payments -> reviews` and
  `reviews` never imports `payments`. The package is a named interface (`@NamedInterface("spi")`),
  declared as `customers.spi` is, and `reviews` collects the implementations by type, the way
  `CustomerErasureService` collects `CustomerErasureParticipant`. Calling `payments` from `reviews`
  would close `reviews -> payments -> notifications -> reviews` and `reviews -> payments -> integration
  -> reviews`, which is why the edge is inverted.
- **Out of `reviews`:** `pricing.api.BenefitGrantDirectory#summary(tenant, grantId)` (new, in `pricing`)
  returns the customer and brand a grant belongs to; `reviews -> pricing` is safe because `pricing`
  imports nothing from `reviews`, and the opposite edge is not available (`reviews -> ordering ->
  pricing`). `customers.api.ConsentRecorder#recordWithdrawal(tenantId, accountId, brandId, purpose,
  channel, policyVersion, source, evidenceReference) -> decisionId` (new, a change owned by `customers`)
  delegates to `ConsentService` with `source = STOREFRONT` and records a `WITHDRAWN` decision, only for a
  customer's own withdrawal of a purpose they granted through their own storefront action, never an
  import, a backdating or a support agent's. `reviews` also registers a `CustomerErasureParticipant`
  (`customers.spi`), and keeps its existing `ordering.api` and `customers.api` edges.
- `reviews` imports nothing from `notifications`, `payments`, `integration` or `reporting`, and
  `pricing` imports nothing from `reviews`.

### PII, audit and observability

Comment and notes are `PERSONAL`, protected with `FieldProtection` and revealed only under one
of four purposes, a closed list in `reviews` and not a free string: `reviews.operations.reviews-screen`,
`reviews.recovery.case-drawer`, `reviews.storefront.my-reviews` and `reviews.public.display`. The
last is the one reveal no signed-in actor performs: the anonymous list decrypts a comment for it,
and only for a row with `comment_state = PUBLISHED` whose consent snapshot is true, never for a
`PENDING`, `HIDDEN` or `NOT_SHARED` row, and no other path may use it (a test pins both). The
decrypted text exists only in that response and in the brand's public list cache (60 s), which
moderation, withdrawal and erasure evict in the same request, after the commit (ADR 0029's
named-purpose rule therefore holds for the public read too). Audit facts (ADR 0027, class `BUSINESS`):
`reviews.moderation.decide`, `reviews.case.open`, `reviews.case.transition`,
`reviews.case.assign`, `reviews.case.compensate`, `reviews.tag.create`, `reviews.tag.update`,
`reviews.tag.retire`; a staff `reason` is always a code's label, never typed text. No
approval action is registered. Metrics, counters only: `reviews_submitted{rating}`,
`reviews_comment_decisions{to,reason}`, `recovery_cases{state}`, `recovery_sla_breaches{kind}`;
a gauge for reviews at or under the threshold with no case (expected zero); no tenant text
in any label or log line.

### Providers

No provider is added. Prompts leave through the channel adapters that exist (ADR 0146's SMS
contract, ADR 0058 and ADR 0075's Telegram path) and are tested against their controlled
fakes (`FakeSmsGateway`, `FakeTelegramBotApi`); icons go through ADR 0010's pipeline and its
object-store port (ADR 0135).

### Testing

Against real PostgreSQL, because isolation, uniqueness and trigger claims proven on a mock
prove nothing:

- Tags: tenant isolation; code, category and sentiment frozen after use; retired tags not
  offered and still shown; another tenant's, a `PRIVATE` or an unverified asset refused as
  an icon; sentiment must match the rating; the sixth tag refused.
- Public: nothing under `NONE`; no summary under the minimum; `PENDING`, `HIDDEN` and
  unconsented comments never in the list; the average equals `reviews.average_rating` over
  the same set; a hide, a withdrawal and an erasure each evict the brand's list entry in the
  same request, so the next read lacks the comment; the public response's only field
  `ClassificationScanner` marks personal is `comment`, present only on a `PUBLISHED` row with
  the consent snapshot true, and no customer id, order id, name or time of day appears; the
  `reviews.public.display` reveal is refused for any other row state and any other caller.
- Moderation and withdrawal: a rating is never changed and the average never moves; only the
  five reasons; a post-withdrawal restore is refused; the audit fact carries no comment text; a
  withdrawal records a `WITHDRAWN` decision through `ConsentRecorder#recordWithdrawal`, keeps its
  id in `withdrawal_decision_id`, leaves `public_consent` as ticked, and is refused for a row that
  was never shared.
- Cases: auto-open at the threshold, not above, not with the flag off; the policy version and
  due times frozen; every row of the transition table, allowed and refused; `REMEDY_GRANTED`
  needs a compensation; two workers mark a breach exactly once; a location manager of one
  branch cannot read another's case; the drawer withholds the comment from a caller without
  `review.read`; no `@Idempotent` response, event, log or audit change carries a note; a case
  closed from `NEW` is never marked breached afterwards, neither sweeper selects a closed case,
  and no `RecoveryCaseSlaBreached` is published for it; attaching a remedy copies its amount and
  currency, a remedy of another order or tenant is refused, and the fact source reads no
  `payments` table.
- Prompt: precedence of rows; the send window; with the flag off, the intent is byte-for-byte
  today's.
- Erasure: comment and notes null, public comment hidden and evicted from the public list cache,
  rating kept; the trigger refuses any other `UPDATE` of `order_reviews`.
- Build: `ModularArchitectureTests` (with the edges in Ports between modules and no import of
  `payments`, `notifications`, `integration` or `reporting` from `reviews`, or of `reviews` from
  `pricing`), `EventCatalogCompletenessTests`,
  `EventSchemaCompatibilityTests`, `EndpointCapabilityDeclarationTests`, the OpenAPI baselines,
  and the console's i18n parity and initial-bundle budget (the new screens are lazy).

## Rollout and rollback

Both flags ship off. First the schema, the capability, the keys and the events with nothing
reading them. Then the prompt policy behind `feature.feedback_settings`: before a tenant is
switched on, a migration step writes an explicit tenant-scope `reviews.feedback_prompt` version
for every tenant that has an active `ORDER_COMPLETED` template, with a row enabling the channel
that template reaches, delay 0, give up after 6 hours and no send window, so the switch changes
nothing for them. Then tags, then the worklist behind `feature.service_recovery` for one pilot
tenant, then public visibility for one brand. Rollback at any step is the flag: tags and the
public endpoints disappear, new cases stop opening, existing cases and every review stay, and
the prompt returns to today's immediate send. The tables are additive and nothing else derives
from them, so dropping them is the last resort.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs; legal answers inputs 1, 2
      and 8 before `feature.feedback_settings` is turned on for a real tenant.
- [ ] Migrations: the seven `reviews` tables, the column grant and trigger on `order_reviews`,
      the three reporting facts, the widened `ck_telegram_binding_event_class`, and the
      `review.public_display` row for every tenant that already has a `customer.consent_types`
      registry.
- [ ] `customers` (the owner of this change): `ConsentRecorder#recordWithdrawal`, the
      `ConsentRecorder` Javadoc amended so that a customer's own storefront withdrawal is the one
      withdrawal the port records, and `review.public_display` added to
      `ConsentTypeService.DEFAULTS`.
- [ ] `Capability.REVIEW_MODERATE`; the three role-bundle changes; `EndpointCapabilityDeclarationTests`.
- [ ] `ReviewsConfigurationKeys`, tenancy declarations, the two flags, the two policy documents
      and their editors.
- [ ] Tag library service and controller; starter pack; icon check through `MediaAvailability`.
- [ ] Submission: tags, consent decision, publication row, `ReviewSubmitted`; `CustomerReviewPort`
      unchanged.
- [ ] Recovery: intake in the submission transaction, state machine, owner, timeline, notes,
      compensations with the amount and currency copied at attach, `reviews.spi.CompensationDirectory`
      (a named interface, implemented by `payments`) and `pricing.api.BenefitGrantDirectory`, the SLA
      worker over open cases only.
- [ ] Prompt: `FeedbackPromptPolicy`; `OrderNotificationTrigger` consults it; preservation step.
- [ ] Public endpoints, caches and rate limits (eviction on moderation, withdrawal and erasure);
      the `reviews.public.display` reveal purpose; moderation queue and decisions; withdrawal.
- [ ] Erasure participant, its cache eviction and the trigger test.
- [ ] `ModularArchitectureTests` green with exactly these new module edges: `notifications -> reviews`,
      `reporting -> reviews`, `payments -> reviews` (through `reviews.spi`) and `reviews -> pricing`,
      and with no import of `payments`, `notifications`, `integration` or `reporting` from `reviews`
      and none of `reviews` from `pricing` (an import-graph run over `src/main` with those edges
      added shows no cycle through `reviews`).
- [ ] Events: sealed interface, outbox listener, topic, catalogue, schemas, baselines.
- [ ] Facts, fact sources, metrics; the live-versus-metric agreement test.
- [ ] Operations console: 5.4 worklist (board and table over the `Board`, `Timeline` and `ActorChip`
      that ADR 0101 puts in the shared library), moderation tab, 5.5 settings (tag library with
      ADR 0101's `q-localized-field-group` and `q-media-uploader`, prompt matrix, visibility), both
      lazy, ru / uz-Latn / en.
- [ ] Storefronts: tag chips, the consent box, "my reviews" state, the public block, withdrawal.
- [ ] `make openapi-baseline`; `Superseded by` pointers added by the owner on acceptance, on ADR 0071
      (its headline Decision sentence, its no-moderation bullet and the three Alternatives rows named
      in Supersedes), ADR 0044 (`## Reviews`), ADR 0013 (the `recovery` module bullet and
      `### Service recovery`) and ADR 0048 (the Decision bullet and the Alternatives row on a separate
      `recovery` module); gap-map rows `5.4` and `5.5` and the IA's §5.4 and §5.5 updated by the wave
      that builds them.

## Exit criteria

A customer rates a completed order 1 star, taps two negative tags and leaves a comment with the
box ticked; a case is `NEW` in the same request, due times set. A support agent starts work,
becomes the owner, reads the comment, mints an apology code from the case, attaches it, resolves
with `REMEDY_GRANTED`, and the timeline shows every step with the staff member's name. A case left
alone is marked breached once, and a case dismissed from `NEW` is never marked. A tenant authors six tags in ru, uz-Latn and en with 3:2 icons, and
the customer is offered only those matching the rating. A tenant with an SMS template and no
policy change still prompts at once; a tenant with none gets a Telegram prompt 30 minutes later,
inside 09:00–21:00, and no SMS; the SMS half is proven against `FakeSmsGateway` only, because no
production binding can use `GENERIC_SMS` until ADR 0146 binds a provider, so no real customer receives
an SMS prompt before then. The public page shows nothing until the tenant sets visibility,
shows the average only after ten reviews, and shows a comment only after the customer ticked and
a moderator approved; a hide, a withdrawal or an erasure evicts it in the same request, so the next
read of the list lacks it, and the average does not change. Reports show tag counts by category,
cases opened and closed on their own dates and the share breached. No endpoint response stored by idempotency, event, log, trace or audit change
carries a comment or a note, and an erasure leaves ratings and nulls both.

## References

- [ADR 0071](../built/0071-order-reviews-a-rating-the-tenant-can-see.md) (amended, not edited),
  [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md) (`## Reviews`),
  [ADR 0013](../partial/0013-payment-refund-and-service-recovery-compensation.md) and
  [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md) (the remedy
  model and the recovery module they set aside),
  [ADR 0075](../partial/0075-a-customer-orders-from-the-chat-they-are-already-in.md),
  [ADR 0042](../partial/0042-courier-compensation-shifts-and-settlement.md),
  [ADR 0087](../built/0087-a-module-is-sold-on-its-own-unit-and-switches-features-on.md),
  [ADR 0140](../partial/0140-promotions-the-automatic-discount-and-markup-rule-engine.md) (the fact
  pattern), [ADR 0149](../not-started/0149-languages-beyond-ru-uz-latn-and-en.md),
  [ADR 0145](../not-started/0145-map-and-geocoding-provider.md) (the record shape)
- [`operations-gap-map.md`](../../operations-gap-map.md) rows `5.4`, `5.5`, `5.2h`, `6.2a`, `X.5`, `X.12`
- [`frontend-information-architecture.md`](../../frontend-information-architecture.md) §5.4, §5.5 and
  PART 4's pilot-blocker table
- [`operations-spec/statistics.md`](../../operations-spec/statistics.md),
  [`operations-spec/settings.md`](../../operations-spec/settings.md),
  [`operations-spec/couriers.md`](../../operations-spec/couriers.md),
  [`delever-parity-matrix.md`](../../delever-parity-matrix.md)
- [`intent/0004-rating-what-happened`](../../../intent/0004-rating-what-happened/intent.md)
- `V0168`, `V0052`, `V0265`, `V0026`, `V0104`, `V0430`; `ReviewSubmissionService`,
  `OperationsReviewController`, `OrderNotificationTrigger`, `OrderingOutboxEventListener`,
  `CustomerErasureParticipant`, `PlatformRole`, `Capability`; `frontend/operations/src/app/app.routes.ts`
