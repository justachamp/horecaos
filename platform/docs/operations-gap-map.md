# Operations gap map

What `apps/operations` owes the information architecture, row by row, verified against the
working tree rather than against the specs. Derived from
[the frontend information architecture](frontend-information-architecture.md)
PART 2 and PART 4, read back through `frontend/operations/src` and
`platform/src/main/java`, and checked a second time by a frontend and a backend lens that
were allowed to disagree with the reader and with each other.

This document is a **build input, not a decision record**. Where a row turns out to need a
decision rather than a wave, it is moved out of the plan and into PART B with the name of
the input and the person who owns it.

**Tier legend** — `P` = first single-location pilot (go-live blocker) · `2` = wave 2
(multi-location + Delever parity) · `3` = wave 3 (parity tail, or blocked on a decision) ·
`?` = the row has no tier in the IA, usually because the IA has no row for the screen at all.

**Status legend** — `BUILT` = every capability the IA row names is real and reachable ·
`PARTIAL` = a real screen or a real endpoint serves some of the row and a named capability
is absent · `NOT BUILT` = no screen, or a placeholder, or an endpoint with no caller ·
`BLOCKED` = the work cannot start because an owner, legal or provider input is missing.

A **†** on a row id means the verification moved the status. The **Reader said** column
carries the reader's original grade so a reviewer can see the disagreement without
re-reading the evidence.

---

## The shape of the debt

| | BUILT | PARTIAL | NOT BUILT | BLOCKED | Total |
|---|---|---|---|---|---|
| **P** — pilot | 124 | 25 | 1 | 1 | **151** |
| **2** — parity | 68 | 21 | 8 | 3 | **100** |
| **3** — tail | 16 | 7 | 5 | 2 | **30** |
| **?** — no IA row | 3 | 0 | 4 | 0 | **7** |
| **Total** | **211** | **53** | **18** | **6** | **288** |

**Re-audited 2026-10-03 after batch 18: five rows moved status, all of them up (five PARTIAL → BUILT), fifteen more had
their notes or their Blocked-by rewritten against the merged code, and this audit found six things that change what a
reader of the batch would conclude.** (Nine waves — `w1-order-entry`, `w2-audit-approvals-privacy`,
`w3-promotions-completion`, `w4-combos-completion`, `w5-attributes-fiscal-tab`, `w6-settings-readiness-shell`,
`w7-courier-app-deliveries`, `w8-marketplace-stops` and `w9-frontend-hygiene` — plus a review-fix round
(`fix18-a-order-entry`, `fix18-b-audit`, `fix18-c-promotions`, `fix18-d-combos`, `fix18-e-attributes-fiscal`,
`fix18-f-settings-shell`, `fix18-g-courier-app`, `fix18-h-marketplace` and `fix18-i-hygiene`) landed on
`wave18-integration`, worktree HEAD `f313ec8c`, 143 commits ahead of `main`; all eighteen branches were proved ancestors
of it with `git merge-base --is-ancestor`. Every row a wave claimed was re-verified against the code on this branch
rather than copied, per the platform owner's standing rule: "no call site = PARTIAL." Beyond reading each screen, the
endpoint it calls and the test that exercises it, this audit ran what needs no browser and no deploy: 74 Java test
classes (1,594 tests, 0 failures, 0 errors, 0 skipped) covering the operator quote and the cash tender, party close and
the brand-wide order stream, the configuration-authoring audit facts and the classification scan, the promotion
lifecycle from checkout through day close to the report and the reveal, the scheduled re-quote and the engine counters,
combos and modifier depth through the cart, the kitchen and combo reporting, decimal amendments and catchweight, the
fiscal backfill, the courier delivery gates, the partner pull and the stop materialisation, the marketplace reconciler,
the readiness checks and scope resolution, plus `ChangeDocumentUsageTests`, `ModularArchitectureTests`,
`OpenApiContractTests`, `EndpointCapabilityDeclarationTests`, `DatabasePrivilegeTests`,
`RowLevelSecurityBackstopTests` and `EventCatalogCompletenessTests`; the full suites of three frontends (operations 376
files and 4,391 tests, storefront 55 and 1,439, storefront-milliy 51 and 1,730); operations `npm run lint`,
`lint:rules` and `format:check` (clean) and a production build; storefront's and storefront-milliy's `lint`,
`lint:rules`, whole-tree `format:check` (clean) and a cold production build each, with no budget warning; the dead-key
finder (5,939 keys, none unreferenced) and its 25 tests; the 39 tests of `frontend/tools/test_format_changed.py`; and
`python3 tools/ci/test_shard_tests.py` (41 tests) and `python3 tools/ci/shard_tests.py check` (721 test classes, each
in exactly one of 3 shards or the gate), then `make fmt-check doc-links` over this edit (17 hygiene checks clean, 905
relative links resolving across 252 files). What it could not run: the full Java suite (the partition runs in CI),
control-plane's suite and lint (the batch changed nothing in `frontend/control-plane`, and this worktree has no
`node_modules` for it), a browser against the console, `deploy.sh` against a real OpenBao and RustFS, a hosted CI run,
and anything against pre-prod.)

The five. `w1-order-entry`: `1.3e` PARTIAL → BUILT — the operator sees the server's own price, discount and delivery fee
before «Создать», «Сдача» is computed against it, and the cash tendered is on the order from its first read. `w2-audit-approvals-privacy`: `9.3a` PARTIAL → BUILT — the ten configuration-authoring services (six that batch 16 named and four the wave's own scan added) each leave
a before/after fact in their own transaction, and the 31 candidates the scan still lists were read one by one and are
ledgers, ingestion, job runners or audited elsewhere, none of them configuration. `w3-promotions-completion`: `7.9`
PARTIAL → BUILT — a redemption names its customer through an audited reveal, and one test goes from a real checkout
through the production day close to the report and the reveal. `w4-combos-completion`: `4.2a` and `4.2b` PARTIAL →
BUILT — the add-lines dialog picks a combo, the second modifier level is choosable on four surfaces, the cart and quote
read the live publication, combo sales are reported and duplicating a product copies its combo groups. No row went
down.

Six things that change a reader's conclusion. First, `3.9`: the four courier-policy switches now have enforcement points
and the policy screen unlocks them, but nothing calls them. `/api/v1/courier/...` has six capabilities, a pure gate
class, 27 HTTP tests and a spec section in `couriers.md`, and there is no courier client in the repository: `mobile/`
is the customer app and is on hold (ADR 0055), `apps/courier` is not scaffolded (ADR 0022), and neither console, the
bot nor either storefront has a caller. An operator can switch a gate on and nothing in production is held to it, so
the row stays PARTIAL. Second, `2.5a`: batch 18 built eight things around the missing marketplace adapter (the partner
pull, two kinds of marker, two events, the stale-channel alert, the read-switch decommission and the explainer dialog)
and a stop still reaches no marketplace, because the only implementation of `MarketplaceAvailabilityAdapter` in the
tree is still the test fake; and the materialisation run, which is how the read switch can be thrown at all, has no
console screen, only routes. Third, `9.4`: the approval thresholds now have their screen (Settings → Approvals, and the
export limit became an ADR 0030 key a tenant sets), but whether a threshold then requires a second signature is the
tenant's approval policy, and `ApprovalPolicyController` has no caller in `frontend/operations` or `frontend/control-plane`. A tenant that sets a limit from the new card still cannot make a signature required from any screen. Fourth,
two capabilities the specifications name and no earlier audit counted, found while reading the rows the batch closed
against the specs rather than against the earlier notes: `10.0`'s «Find a setting» does not show where a value is set
or what it is and does not set the scope bar when it navigates (settings.md §10.0), and `10.7c`'s Tab 3 has no way to
pick one ИКПУ and apply it to a chosen set of rows (settings.md §10.7). Both rows keep PARTIAL for these alone; every
gap their earlier notes listed is closed. Fifth, `7.9` and `6.1` together: the reveal that answers «who redeemed it» was
bypassed by the first cut, because the two redemption lists returned the same account ids under `pricing.read` with no
purpose and no record, and the review round nulled them for every caller (deprecated in the v1 contract, since ADR 0031
allows no field to be removed); and the scheduled-order re-quote, which ADR 0140 asked for, writes its findings to a
table nothing reads, since no screen, storefront or notification calls its two routes. ADR 0140's status line still says
the lists return account ids. Sixth, the operations initial-bundle budget, the third finding of batch 17: the catalogue
split (`w9-frontend-hygiene`, 17 areas of which only `core` ships in the initial bundle) took the production build from
831.38 kB to 374.33 kB on this tree, the budget was lowered from 832 kB to 434 kB (warning 400 kB) where batch 17 had
raised it twice, headroom is 59.7 kB to the error limit, and the build now carries no warning, the 500 kB one included.

Rows a wave built that this audit holds below BUILT, each for a reason that can be shown in code: `1.1`, which still
draws none of the IA's courier-type, source-icon, accepted-by, created-by and ETA columns and withholds bulk selection
on «Все филиалы»; `1.2c`, `REMOVE_LINES` and the decrease of a quantity; `1.3`, the map pin (`1.3b`, ADR 0145 Proposed);
`2.5a` and `3.9` (above); `3.4c`, where `ORDER_UNDELIVERED` and `ORDER_DAMAGED` still have no reader; `4.2c`, where the
till is told the nominal weight and a weighed basket cannot be paid online until ADR 0153 is decided; `6.1`, order-level
markup, named-customer targeting, per-locale titles and the re-quote's screen; `9.4` (above); `10.0` and `10.7c`
(above); `10.10c`, a clean decline whose version card is now read by the reports too and whose record, ADR 0107, the
owner has not accepted; and the two `X.1` rows, 'Locked by plan' and every screen but the order board and the settings
home for the keyboard layer. Rows it moved up with a residue it records on the row: `1.3e` (page and endpoint are each
proved with the other mocked), `4.2a` (a terminal that groups a combo's lines is not sent the grouping; the per-component
fiscal receipt is `8/X.2`), `4.2b` (the hidden-charge wording is still a placeholder pending product and legal), `7.9`
(redemptions of today appear after the day closes; `GRANT` has no producer) and `9.3a` (nothing fails when a new
configuration writer ships with no fact; the guard is on the shape of a fact, not its existence).

Rows that kept their status with the note rewritten. `1.1` gains the brand-wide stream, `1.2c` the promo cases batch 15
named, the combo picker and the decimal quantity, `1.3` the placement proved over HTTP and «Закрыть стол» (and a stale
code comment to correct), `10.2d` the party close that closes batch 17's gap, `10.0` three readiness checks and an
expiring tier, the two `X.1` rows the per-level provenance (the scope bar) and the brand picker and the keyboard layer (the
shell), whose earlier notes were also out of date on the tenant level of the bar and on the toast host, both of which
predate this batch; `4.2c`
the decimal amendment and the per-quantum price wording, `10.7c` modifier options, the ИКПУ picker and the read-only VAT
and responsibility projections, `10.10c` its closed loose end, `3.4c` `CASH_VARIANCE` in the rule form, `6.1` the gift,
the main storefront's code entry, the re-quote and the counters, `9.4` the Approvals settings card, `3.9` the courier
surface that enforces the four switches, and `2.5a` the adapter-free half of ADR 0141. PART B's entries for `2.5a`, `4.2a`, `4.2b`, `4.2c`,
`6.1`, `7.9` and `3.4c` say what batch 18 changed.

Items with no PART A row, recorded here as earlier batches recorded infrastructure. Seven migrations, V0474, V0477, V0478,
V0484, V0487, V0488 and V0489 (the numbers between them unused), none an edit of an applied one, and
`DatabasePrivilegeTests` (20) and `RowLevelSecurityBackstopTests` (7) green. Seven capabilities: `marketplace.availability.pull`
and the six courier ones (`courier.offer.accept`, `courier.offer.decline`, `courier.delivery.read`,
`courier.delivery.advance`, `courier.delivery.payment.confirm` and `courier.delivery.location.reveal`);
`EndpointCapabilityDeclarationTests` (10), `PlatformRoleTests` (40) and `JdbcAuthorizationServiceTests` (32) are green, and
the operator quote reuses `order.place`. Two new event schemas (`MarketplaceAvailabilityPushed`,
`MarketplaceChannelWentStale`) with catalogue entries (`EventCatalogCompletenessTests`, 141, green). The OpenAPI
baselines and the typed clients were regenerated; the change is additive except one deprecation, the redemption lists'
`customerAccountId`, which is always null, and `OpenApiContractTests` (6) and `AcceptedContractWideningsTests` (13) are
green. The ADR 0029 seam batch 17 reported is closed in part: `ClassificationScanner` now descends into lists, maps,
optionals, arrays and generic records (paths carry `[]` where an element sits) and `ResponseBodyProtection` hands it the
parameterised response type. Run over every `@Idempotent` handler it flagged ten paths on three components
(`resultingOrderVersion` holds «tin», `commentPresetCodes` «comment», `hasCustomerNote` «note»), all false positives of
the name heuristic, each declared `INTERNAL` with its reason; and the review round found by hand the one true positive
it missed, `OrderLatenessPolicyEditorController.LevelResponse.approvedByName`, a staff name from `StaffDirectory` in the
reply to the idempotent lateness-policy publish and so stored in clear in `platform.idempotency_records` for the
retention day, now `@Classified(PERSONAL)` (`ClassificationScannerTests`, 21, and `IdempotentResponseClassificationTests`,
14, which fails for any component named like `approvedByName`, `createdByDisplayName` or `operatorName` that an
`@Idempotent` response reaches undeclared). What stays: the scan is only as strong as its name heuristic, whose protected
terms hold `firstname`, `lastname`, `fullname` and `personname` and not `name`, so a staff name under a component name the
pattern does not cover is unseen, and the rule for the next author is to declare every staff-name field where it is
added (ADR 0139 says the same, and says three false positives where the commit that declared them counts ten paths).
`ChangeDocumentUsageTests` is green and the audit allow-list gained no entry (one line number moved). The frontend and
CI hygiene items of `w9-frontend-hygiene`, which no row names: (a) the operations message catalogues are split by
feature area (`messages/<area>.<locale>.ts`, 17 areas), the route guard fetches an area before its route opens, only
`core` for the default locale is in the initial bundle, and `require()` waits for every area to settle and merges the
ones that arrived when a sibling chunk fails; the initial total is 374.33 kB where it was 831.38 kB, the error limit is
434 kB and the warning 400 kB, `I18n`, the `t` pipe and the specs that import the catalogues keep working (the whole
suite is green) and the format and dead-key tooling still see every key (5,939, with 25 tool tests); (b) CI runs the
plain whole-tree `format:check` for operations, storefront and storefront-milliy and keeps the changed-files ratchet
for control-plane alone, and both storefronts' trees are prettier-clean; (c) `dine-in-table.component.scss` is under the
4 kB component-style warning in both storefronts (storefront-milliy's order bar became its own component) and a cold
build of each carries no warning; (d) every `*IntegrationTest` class is in a CI shard, pinned by a test that also fails
for a JUnit class hiding from the shard enumeration under a name Surefire does not select (`test_shard_tests.py`; 721
classes in three shards and the gate, new classes weighted at the median 3.88 seconds until a run records them).

The counts below are a full programmatic recount of every row in PART A (id, tier, status), not a hand tally — see the
shape-of-the-debt table above and the per-section headers below, both regenerated straight from the row table with a
Python script, its output pasted verbatim (the same script, run on the unmodified batch-17 document, reproduced that
document's counts exactly before any row was edited). Net for this batch: BUILT 206→211, PARTIAL 58→53, NOT BUILT
unchanged at 18, BLOCKED unchanged at 6, total unchanged at 288. By tier: pilot BUILT 123→124 and PARTIAL 26→25;
parity BUILT 66→68 and PARTIAL 23→21; tail BUILT 14→16 and PARTIAL 9→7. The `§1`, `§4`, `§7` and `§9` headers moved with
the rows.

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 11 built · 1 not built
§1: 40 rows — 32 built · 6 partial · 2 not built
§2: 16 rows — 12 built · 3 partial · 1 blocked
§3: 15 rows — 5 built · 9 partial · 1 not built
§4: 25 rows — 20 built · 3 partial · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 5 partial · 5 not built · 1 blocked
§7: 39 rows — 33 built · 2 partial · 4 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 13 built · 5 partial · 2 not built
§10: 36 rows — 26 built · 9 partial · 1 not built
§X: 40 rows — 33 built · 7 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=124 PARTIAL=25 NOT BUILT=1 BLOCKED=1 Total=151
2: BUILT=68 PARTIAL=21 NOT BUILT=8 BLOCKED=3 Total=100
3: BUILT=16 PARTIAL=7 NOT BUILT=5 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 211, 'PARTIAL': 53, 'NOT BUILT': 18, 'BLOCKED': 6}
Grand total: 288
```

**Re-audited 2026-10-01 after batch 17: thirteen rows moved status, all of them up (seven NOT BUILT → PARTIAL, four
NOT BUILT → BUILT, two PARTIAL → BUILT), thirty-eight more had their notes or their Blocked-by rewritten against the
merged code, and this audit found three things that change what a reader of the batch would conclude.** (Nine waves —
`w1-combos-modifiers`, `w2-physical-attributes`, `w3-marketplace-preview`, `w4-staff-identity`,
`w5-promotions-engine`, `w6-stop-scope`, `w7-dispatch-rules`, `w8-walk-in-sessions` and `w9-decision-adrs` — plus a
review-fix round (`fix17-a-combos`, `fix17-b-attributes`, `fix17-c-preview`, `fix17-d-identity`, `fix17-e-promotions`,
`fix17-f-stops`, `fix17-g-dispatch`, `fix17-h-walkin` and `fix17-i-decision-adrs`) landed on `wave17-integration`,
worktree HEAD `1aaaeafe`, 180 commits ahead of `main`; all eighteen branches were proved ancestors of it with `git
merge-base --is-ancestor`. Every row a wave claimed was re-verified against the code on this branch rather than
copied, per the platform owner's standing rule: "no call site = PARTIAL." Beyond reading each screen, the endpoint it
calls and the test that exercises it, this audit ran what needs no browser and no deploy: 71 Java test classes (1,241
tests, 0 failures, 0 errors, 0 skipped) covering combos and modifier depth, the physical attributes and the decimal
quantity with its OpenAPI allowance, the channel preview, the staff member record and its endpoints, the promotion
engine, ledger, report and fact, stop scope and the marketplace reconciler, dispatch rules and the payment window,
walk-in seating and the table-token checkout, plus `ChangeDocumentUsageTests`, `ModularArchitectureTests`,
`OpenApiContractTests`, `EndpointCapabilityDeclarationTests`, `DatabasePrivilegeTests`,
`RowLevelSecurityBackstopTests` and `EventCatalogCompletenessTests`; the full suites of three frontends (operations
361 files and 4,024 tests, storefront 51 and 1,310, storefront-milliy 48 and 1,641); operations `npm run lint`,
`lint:rules` and `format:check` (clean) and a production build; storefront's and storefront-milliy's `lint`,
`lint:rules` and changed-files format ratchet (52 and 59 changed files, clean) and the 37 tests of
`frontend/tools/test_format_changed.py`; and `make fmt-check doc-links shards-test` (701 test classes, each in exactly
one of 3 shards or the gate). What it could not run: the full Java suite (the partition runs in CI), control-plane's
suite (the batch changed nothing there and this worktree has no `node_modules` for it), a browser against the console,
`deploy.sh` against a real OpenBao and RustFS, a hosted CI run, and anything against pre-prod.)

The thirteen. `w1-combos-modifiers`: `4.2a` and `4.2b` NOT BUILT → PARTIAL — an operator can author a combo or a
hidden, order-type-scoped charge, a customer or a call-centre operator can buy a combo as its components, and the
order, the kitchen and the till read it; the console's add-lines dialog cannot pick a combo, no screen offers the
second modifier level, and the fiscal and reporting readers are absent. `w2-physical-attributes`: `4.2c` NOT BUILT →
PARTIAL — described, published, sold by the portion, weighed and reconciled at the pass; the till is told the nominal
weight and a weighed basket cannot be paid online. `w3-marketplace-preview`: `4.6a` NOT BUILT → PARTIAL (a preview
that is the live menu's own assembly over the draft, and a per-channel photo layer, with no marketplace rule behind
it) and `X.28` PARTIAL → BUILT (its frames finally have a consumer). `w4-staff-identity`: `0.1d`, `0.2c`, `9.2` and
`9.2b` NOT BUILT → BUILT and `0.1` PARTIAL → BUILT — the tenant keeps its own record of each person, and the live
board, «Мой профиль», the People screen, branch contacts and emergency contacts read it; `9.2c` and `X.5` of §9 stay
PARTIAL. `w5-promotions-engine`: `6.1` NOT BUILT → PARTIAL; `7.9` stays PARTIAL; `X.25` stays BUILT and gains its
second consumers. `w6-stop-scope`: `2.5a` NOT BUILT → PARTIAL. `w7-dispatch-rules`: `3.8` NOT BUILT → PARTIAL.
`w8-walk-in-sessions` moved no status: `10.5` stays PARTIAL, and `1.5a`, `10.5b` and `10.2d` stay BUILT.
`w9-decision-adrs` moved none either: seven decision records were drafted and are Proposed, and the rows that wait on
each now name it. No row went down.

Three things that change a reader's conclusion. First, `9.2c`: the staff-identity wave's POS operator pairing is built
up to the port and sends nothing — the mapping pane writes an `OPERATOR` row, the export resolves it onto
`OrderExport#operatorExternalId`, and `CloposAdapter`, the only POS adapter, never reads it, because Clopos documents
no operator field on order creation. A field with a writer and a reader and no sender is the defect "no call site =
PARTIAL" names, so the row stays PARTIAL although its writer now exists. Second, `2.5a`: the stop scope is real and
read by every reader the platform owns, but the marketplace propagation that the row's title promises has no
production caller — the only implementation of `MarketplaceAvailabilityAdapter` in the tree is the test fake, so every
real binding reads `MANUAL` and a stop still never reaches Yandex, Uzum or Wolt. Third, the operations initial-bundle
budget was raised twice during integration, 825 → 831 kB (`37ac8381`) and 831 → 832 kB (`68967a90`), against the
instruction the batch's agents were given, and the production build now measures 831.38 kB: 0.62 kB of headroom, where
batch 16 left 41.5 kB (783.46 kB built). The growth is the console's Russian message catalogue, which ships in the
initial bundle and gained about 610 lines for this batch's screens; a handful more message keys fail the build unless
the default catalogue is split or lazy-loaded the way control-plane's was in batch 16, or the owner raises the budget
a third time. The build's only warning is still the 500 kB one.

Rows a wave built that this audit holds below BUILT, each for a reason that can be shown in code: `9.2c` and `2.5a`
(above); `7.9`, whose report names its customer only as the ADR 0029 pseudonym that nothing in the console resolves,
and whose chain from checkout through day close to the report is proved in segments, never together; `3.8`, where
courier grouping and the unpaid-order cancellation are refused at publish; `6.1`, where a gift is a line the cart must
already hold and order-level markup, named-customer targeting and per-locale titles are not built; `4.2a` and `4.2b`,
where the add-lines dialog cannot choose a combo and no screen can choose a nested modifier; `4.2c`, where the till is
told the nominal weight; `4.6a`, where no marketplace rule exists to preview against; and `10.5`, where the kiosk
pairing is as before. Rows it moved up with a residue it records on the row: `0.1` and `0.1d` (the wallboard shell
still draws no operator band, a note on `0.1e`), `0.2c` (the email is not on the surface, by ADR 0139), `9.2` (the
drift half of ADR 0009, a retention sweeper that only reports, and four places that still print the Keycloak subject),
`9.2b` (a branch manager cannot edit the contacts) and `X.28` (the Telegram arm of the preview has no page test).

Rows that kept their status with the note rewritten. `4.2f`, `4.4a`, `4.4b` and `4.6` gain the channel preview as a
consumer; the residue on `4.6` narrows to the publication screen's own readiness list, whose findings carry no product
id, because the preview's do. `4.9` stays BLOCKED, loses the clause that waited on a portions attribute and gains the
one narrow case hidden modifiers cover. `7.5` and `9.2d` read their names from the staff record, and the Keycloak-only
read they used is deleted; `0.2` links to «Мой профиль» where it had a locked band, and `X.2` and `X.3` now say ADR
0139 splits shifts and terminals off the staff member record on purpose. `9.4` has a second producer, a promotion's
activation. `X.25` has its second and third consumers. `1.5a`, `10.2d`, `10.5` and `10.5b` carry the walk-in flow, and
the `storefront-milliy` checkout defect that batches 15 and 16 recorded is closed: it now sends `X-Dine-In-Token`,
although each side's tests still mock the other. Two sentences the batch made false are corrected: `10.2d` said the
console had no caller for a session's `state-actions` and the dine-in module no scheduled job, which now holds only
for a party staff seated, and `10.5b` said `storefront-milliy` binds a table and does not send the token. `10.3b`
gains the dispatch summary in card 3 and a pointer to ADR 0150 for three of its ten fields; a search of `src/main`
still finds no reader for any of the ten. `X.5` of §9 stays PARTIAL.

Seven decision records were drafted and are Proposed; the owner has decided none of them, and drafting is not
deciding, so no status moved for one. ADR 0145 (map and geocoding provider) now stands behind nine rows (`X.4`,
`1.3b`, `3.2`, `3.6`, `3.6c`, `5.2c`, `10.2b`, `7.10` and `7.10a`), ADR 0146 (SMS gateway contract) behind `6.4a` and
`6.4b`, ADR 0147 (road distance and routing provider) behind `3.7`, ADR 0148 (staff multi-factor authentication)
behind `X.38` and the rest of `X.5`, ADR 0149 (languages beyond ru, uz-Latn and en) behind `10.12` and `X.40`, ADR
0150 (what «order is late after» means) behind `X.39` and `10.3b`, and ADR 0151 (a wall-display device class) behind
`2.4`. What they say about the code was spot-checked against it and held: no map in the console or the control plane
(the only one is the customer storefronts' Yandex Maps script), `RoadDistancePort` answering empty,
`CampaignMessagePort.isWired` false for SMS, no consumer of `q-otp-input`, `DevicePrincipalClass` holding one
constant, and a late-order threshold nothing reads. ADR 0149 corrected its own first count (twenty-seven locale
declarations, now more than sixty). ADR 0153 (weighed orders paid through a provider, Proposed 2026-10-03) is the
review round's and stands behind `4.2c`; ADR 0152, the contact centre, is a separate project and names no row. ADR
0136 to 0143 were Accepted by the owner on 2026-10-01 with every open input closed on the record's proposed default,
and each is now Partial, with what it leaves unbuilt written on the row it serves.

Items with no PART A row, recorded here as earlier batches recorded infrastructure. Twenty-five migrations, V0443 to
V0469 with V0466 and V0468 left unused, none an edit of an applied one, and `DatabasePrivilegeTests` (20) green. Eight
capabilities: `inventory.stop.manage`, `order.payment-window.manage`, `delivery.dispatch_rules.read` and `.write`, and
the four staff ones (`staff.profile.read`, `staff.profile.manage`, `staff.emergency-contact.read` and
`staff.self.manage`, the last authorised by `@StaffSelfAuthorized` and not by scope coverage);
`EndpointCapabilityDeclarationTests` (10) is green and the console's capability sentences and approval labels carry
them. Three new event schemas (`InventoryStopChanged`, `PromotionActivated`, `PromotionSuspended`) with catalogue
entries (`EventCatalogCompletenessTests`, 135, green). The OpenAPI baselines and the four typed clients were
regenerated, and the one allowed type change (a line quantity from integer to number, ADR 0137) is a single named
allowance in `AcceptedContractWidenings`. `ChangeDocumentUsageTests` is green and the audit allow-list gained no entry
(one line number moved). The CI partition now holds 701 test classes in three shards; new classes have no recorded
duration and are weighted at the median 3.88 seconds, so the balance is a plan and no hosted run has used it. The
Caddyfile puts the self-seating route in the guest dine-in rate-limit group, and a Keycloak lockout probe for ADR 0148
was added under `infra/keycloak/spikes` and is not deployed. The initial-bundle budget is the third finding above.

The counts below are a full programmatic recount of every row in PART A (id, tier, status), not a hand tally — see the
shape-of-the-debt table above and the per-section headers below, both regenerated straight from the row table with a
Python script, its output pasted verbatim (the same script, run on the unmodified batch-16 document, reproduced that
document's counts exactly before any row was edited). Net for this batch: BUILT 200→206, PARTIAL 53→58, NOT BUILT
29→18, BLOCKED unchanged at 6, total unchanged at 288. By tier: pilot BUILT 121→123, PARTIAL 25→26 and NOT BUILT 4→1;
parity BUILT 62→66, PARTIAL 22→23 and NOT BUILT 13→8; tail PARTIAL 6→9 and NOT BUILT 8→5 (BUILT unchanged at 14). The
`§0`, `§2`, `§3`, `§4`, `§6`, `§9` and `§X` headers moved with the rows.

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 11 built · 1 not built
§1: 40 rows — 31 built · 7 partial · 2 not built
§2: 16 rows — 12 built · 3 partial · 1 blocked
§3: 15 rows — 5 built · 9 partial · 1 not built
§4: 25 rows — 18 built · 5 partial · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 5 partial · 5 not built · 1 blocked
§7: 39 rows — 32 built · 3 partial · 4 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 12 built · 6 partial · 2 not built
§10: 36 rows — 26 built · 9 partial · 1 not built
§X: 40 rows — 33 built · 7 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=123 PARTIAL=26 NOT BUILT=1 BLOCKED=1 Total=151
2: BUILT=66 PARTIAL=23 NOT BUILT=8 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=9 NOT BUILT=5 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 206, 'PARTIAL': 58, 'NOT BUILT': 18, 'BLOCKED': 6}
Grand total: 288
```

**Re-audited 2026-10-01 after batch 16: five rows moved status (four PARTIAL → BUILT, one BUILT → PARTIAL), seventeen
more had their notes rewritten against the merged code, and this audit found a row that earlier audits had passed whose
controls change nothing.**
(Eight waves — `w1-order-board-completion`, `w2-lateness-policy-editor`, `w3-readiness-fiscal-backfill`,
`w4-locales-tail`, `w5-milliy-frontend-hygiene`, `w6-billing-self-service`, `w7-console-budgets-hygiene` and
`w8-declines-and-docs` — plus a review-fix round (`fix16-a-board`, `fix16-b-lateness`, `fix16-c-readiness-fiscal`,
`fix16-d-locales-tail`, `fix16-e-milliy-lint`, `fix16-f-billing`, `fix16-g-budgets` and `fix16-h-docs`) landed on
`wave16-integration`, worktree HEAD `08481701`; all sixteen branches were proved ancestors of it with `git
merge-base --is-ancestor`. Every row a wave claimed was re-verified against the code on this branch rather than copied,
per the platform owner's standing rule: "no call site = PARTIAL." Beyond reading each screen, the endpoint it calls and
the test that exercises it, this audit ran what needs no browser and no deploy: 40 Java test classes (806 tests, 0
failures) covering the brand-scoped board and its toggles, the action policy and the re-issue audit, the lateness editor
with its cache and version handling, the readiness checks and endpoint, the fiscal backfill, the regional formats and
the locale fallback, module acquisition and the tenant's end, `ChangeDocumentUsageTests`, the modular-architecture,
row-level-security, database-privilege and OpenAPI-contract tests, and the ordering regression suites; the full suites of
all four frontends (operations 320 files and 3,312 tests, storefront-milliy 43 and 1,371, storefront 44 and 1,049,
control-plane 48 and 328); operations `npm run lint`, `lint:rules`, `format:check` and a production build; and each other
app's `lint`, `lint:rules`, changed-files format ratchet and, for control-plane, `check:tokens`. What it could not run:
the full Java suite (the partition runs in CI), a browser against the console, `deploy.sh` against a real OpenBao and
RustFS, a hosted CI run, and anything against pre-prod.)

Five rows moved. Four go up, each because the thing it was missing now has a screen, an endpoint and a test. `1.1c`
(order-board filters) PARTIAL → BUILT: the branch, one aggregator binding and the four booleans `orders.md` marked "not
read by ordering" are all read now, on the new brand-scoped board and the branch board alike. `1.1e` (row actions)
PARTIAL → BUILT: `ISSUE_INVOICE` was the last declared action code that was never emitted; it is emitted now, gated on
the scope its endpoint declares, with that endpoint's audit written before the checkout is opened. `X.39` (colour ramp)
PARTIAL → BUILT: the late line itself is tenant-authorable (a versioned editor for the `ordering.lateness` document at
tenant, brand or location, whose publication reaches the boards and the always-on wall screens, which the first cut did
not). `X.25` (condition builder, rule list, simulator) PARTIAL → BUILT: `q-rule-simulator` had no caller anywhere and now
leads the automations preview. One goes down, and no wave moved it: `10.3b` (order policy, cards 2 to 5) BUILT → PARTIAL.

The finding behind `10.3b`. Searching `src/main` for a reader of each configuration key the order-policy cards write
finds three of thirteen: the minimum order sum (`CheckoutEligibilityGuard`) and the at-risk minutes and late colour (the
lateness policy). The other ten — working-day start hour, average and maximum order time, «Order is late after», VAT
rate, routing poll interval, auto-accept eligible channels, auto-accept minimum prior orders, pre-order branch selection
and the operator promo-code toggle — are stored, validated, inherited and traced, and read by nothing; the keys class
says so in its own Javadoc. Two of the ten say so on the screen. Batch 15 recorded two of them under `X.39` and kept
`10.3b` BUILT; this audit applies the standing rule one level down, because a control that changes nothing is the same
defect as an endpoint nobody calls. Batch 16 did not cause it, and the wave that added the lateness card also made the
third field's inertness explicit on the screen («Not applied yet»). The readers belong to ADR 0002, 0019, 0037 and 0018.

Rows that kept their status with the note rewritten. `1.1` stays PARTIAL: «Все филиалы» (the brand-scoped board, a Филиал
column and select, each row judged by its own branch's lateness policy) closes the branch the note named, and the
re-read shows what no earlier note listed, that the IA row also owns courier type, source icon, accepted-by, created-by
and courier ETA as columns and the table draws none (the response carries created-by and accepted-by; the column picker is
`0.2d`). `10.0` stays PARTIAL: two more of the spec's readiness conditions exist (channel fulfilment-mode coverage and
location service-binding coverage, with per-item subject links) and two do not (a location forced closed with no expiry,
an active location no sales channel reaches). `10.7c` stays PARTIAL: the bulk ИКПУ and package-code backfill the note
said existed nowhere now exists, as a fill-down table whose MERGE mode cannot replace a stored code, for dishes only,
with typed codes and without the spec's read-only VAT defaults. `10.12` stays PARTIAL: the regional-format editor (unit
placement, thousands grouping, phone pattern, on a Форматы card), formatters that follow the operator's own location, the
locale fallback behind the stop list and New order's item search, and the console's reading of preset wording beyond the
triple are built; the remaining forms are still on the platform triple. `2.1b` (BUILT) has the sentence that said the
console shows a beyond-the-triple wording nowhere rewritten. `8.6` stays PARTIAL: a tenant can end a module it bought and
only that one; the prepaid wallet, top-up and credit-expiry warning remain (`8/X.3`). `10.5`'s milliy defect was re-read
at HEAD and is still open: `CartService.checkout` sends no `X-Dine-In-Token`, and neither `cart.service.ts` nor
`dine-in.service.ts` changed in batch 16. Three more rows had one stale sentence corrected because a wave made it
false: `9.3a` said the `ordering.lateness` document has no authoring endpoint (it has one, and the endpoint leaves an audit
fact), `10.10c` said an operator cannot change where "late" starts (the reporting buckets, no; the boards' line, yes
since `X.39`), and `6.1` said every shared rule primitive has a consumer, which was untrue of the simulator until `X.25`
gave it one. `10.1` gains a pointer to the Форматы card on its screen.

The rows `w8-declines-and-docs` handled (`1.2d`, `1.6a`, `7.7c`, `10.10c`, `3.4c`, `7.3b`), with `1.6` and `6.7a`, now
point at [the amended IA](frontend-information-architecture.md) by link and keep the status their evidence earns; the
legend still has no DECLINED status and none was invented. The docs pass left two calls to this re-audit. `3.4c` stays
PARTIAL: it is not a decline, no courier code changed in batch 16, and the two bases have no reader. `7.3b` stays
PARTIAL: the IA row reads «both as flat lists» only because a documentation wave amended it to match the build, which is
not an owner ruling. `6.7a`'s `amend` Wave marker and its note (it said the IA «still lists» the editorial sub-features;
IA row `6.7` was struck on 2026-09-11) were stale and are corrected. These eight rows now carry `w8-declines-and-docs` in
the Wave column, in place of the dash the review fixes left there.

Items with no PART A row, recorded here as earlier batches recorded infrastructure. The operations initial bundle builds
at 783.46 kB against the 825 kB error budget (41.5 kB of headroom, 779.36 kB at batch 15; the build's only warning is
the 500 kB initial-bundle one), and no component stylesheet reaches the 4 kB warning: `w7` split the order queue, the
order detail pane, the product editor, the kitchen queue and New order into components and moved the modal and field
styles several pages repeated into shared sheets (`order-queue.css` from 7.97 kB to 3.39 kB), so batch 15's note that the
queue sat at 7.97 kB of its 8 kB limit is out of date. `w7` also lazy-loads every storefront route but home (initial 590.85 kB to
405.13 kB) and control-plane's `en` and `uz-Latn` catalogues (643.13 kB to 442.95 kB), both as their commit messages
state and not re-measured here, and adds `npm run i18n:dead` for operations, which removed 109 unreferenced keys from
operations and 9 from control-plane and now finds one of 5,413 (`settings.brandProfile.formats.timezone.branches`, from
the new Форматы card). `w5` gives control-plane, storefront and storefront-milliy ESLint (clean) and Prettier, enforced
in CI as a changed-files ratchet because `format:check` over each whole tree still reports 118, 116 and 75 files; the
ratchet passes against `acd96539` for all three. `w5` also lets storefront-milliy order a dish with required option
groups at a table; `fix16-e` takes a held table line the platform will no longer write out whole and marks the groups
that must be chosen from. The listing-backfill runbook's "Last executed" block now says one thing; ADR 0047, 0127 and
0143 carry dated status notes and ADR 0144 (the brand-scoped board, Proposed) is new. None of these has a PART A row.

The counts below are a full programmatic recount of every row in PART A (id, tier, status), not a hand tally — see the
shape-of-the-debt table above and the per-section headers below, both regenerated straight from the row table with a
Python script, its output pasted verbatim (the same script, run on the unmodified batch-15 document, reproduced that
document's counts exactly before any row was edited). Net for this batch: BUILT 197→200, PARTIAL 56→53, NOT BUILT
unchanged at 29, BLOCKED unchanged at 6, total unchanged at 288. By tier: pilot BUILT 119→121 and PARTIAL 27→25; parity
BUILT 61→62 and PARTIAL 23→22. The `§1`, `§10` and `§X` headers moved with the rows.

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 8 built · 1 partial · 3 not built
§1: 40 rows — 31 built · 7 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 18 built · 1 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 3 partial · 4 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 26 built · 9 partial · 1 not built
§X: 40 rows — 32 built · 8 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=121 PARTIAL=25 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=62 PARTIAL=22 NOT BUILT=13 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=6 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 200, 'PARTIAL': 53, 'NOT BUILT': 29, 'BLOCKED': 6}
Grand total: 288
```

**Re-audited 2026-09-29 after batch 15: no row moved status; 21 rows had their notes rewritten against the
merged code, two claims in the wave briefs were checked and rejected, and this audit found one integration
defect across two waves that no test caught.** (Seven waves — `w1-ordering-money-audit`,
`w2-locale-consumption`, `w3-locale-remaining-forms`, `w4-milliy-dine-in-order`, `w5-readiness-and-scope-fixes`,
`w6-dine-in-operator-flows` and `w7-ci-frontend-hygiene` — plus a review-fix round (`fix15-a-money-audit`,
`fix15-b-locale-consumption`, `fix15-c-locale-forms`, `fix15-d-milliy-dine-in`, `fix15-e-readiness-scope`,
`fix15-f-dine-in-ops` and `fix15-g-ci-hygiene`) landed on `wave15-integration`, worktree HEAD `b1b39b93`; all
fourteen branches were proved ancestors of it with `git merge-base --is-ancestor`. Every row a wave claimed was
re-verified against the code on this branch rather than copied, per the platform owner's standing rule: "no call
site = PARTIAL." Beyond reading each screen, the endpoint it calls and the test that exercises it, this audit
ran what needs no browser and no deploy: 40 Java test classes (777 tests, 0 failures) covering the money fix,
the policy audit facts and lateness settings, the brand-default readers and the storefront catalog, presets,
payment methods, dine-in seating, cart binding and checkout, the readiness checks, the Telegram scope, the
architecture tests and the OpenAPI contract; the full suites of all four frontends (operations 292 files and
2,926 tests, storefront-milliy 38 and 1,249, storefront 44 and 1,048, control-plane 47 and 317); operations `npm
run lint`, `lint:rules` and `format:check` (clean) and production builds of operations and storefront-milliy;
the CI tooling's own checks (`make fmt-check doc-links hooks-test bands-test shards-test makefile-test`, which
ends "625 test classes, each in exactly one of 3 shards or the gate", and the 29 tests of
`frontend/tools/test_format_changed.py`); and, read-only through `gh`, the two hosted CI runs of 2026-09-29.
What it could not run: the full Java suite (the 625-class partition runs in CI), a browser against the console,
`deploy.sh` against a real OpenBao and RustFS, and anything against pre-prod.)

No status moved, and that is a finding as much as a count. The batch brief expected `9.3a`, `X.39`, `10.12` and
`10.0` each to advance; each closed the sub-gap it named and each stays `PARTIAL` for a reason that can be shown
in code. `9.3a`: the three named services now audit, but a scan finds configuration authoring that writes no
audit fact and that no earlier audit listed (promo codes, sales channels, channel setup and pages, loyalty and
referral programmes). `X.39`: a tenant can now set its late colour and its at-risk minutes, but the late line
itself is still not settable — `ordering.late_order_threshold_minutes` and `ordering.maximum_order_minutes` have
no reader anywhere in `src/main`. `10.12`: the readers a customer sees moved to the brand default, but
`variantsAtLocation` (behind the stop list, New order's item search and the bulk price change) still joins one
literal locale, and a wording beyond ru/uz/en is stored on the order line and shown nowhere in the console.
`10.0`: the three named checks exist, but the spec's forced-closed-with-no-expiry and no-schedule-bound
conditions have none.

The money bug (`1.2c`, `1.3e`). Batch 14's audit found that an amendment repriced a promo-coded order at full
price; batch 15 fixes it (the reprice carries the order's own redemption, and V0438 makes one live redemption
per order a database rule) and this audit ran the fix over HTTP (`OrderAmendmentPromoDiscountHttpTests`, green).
It also corrects a stale claim two earlier audits repeated: `1.3` and `1.3e` still said a nonzero promo code
cannot complete a checkout anywhere. The fix `a76ee821` (2026-09-14) is an ancestor of this branch,
`PricingEngine` reports a subtotal gross of the discount, and `CartCheckoutAndOrderTests` places an operator
order with a 10% code and asserts total = subtotal + tax + fee - discount. The brief named `2.1d` among the
affected rows and it is not: `CHANGE_PAYMENT_METHOD` is not a repricing command, so that row's verdict stands
with one sentence added. What the fix leaves open is on `1.3e`: New order shows no discounted price before
Create, and its «Сдача» subtracts the undiscounted, fee-less subtotal.

Locale consumption (`10.12`, and `X.2` terms of service, `10.6`, `4.1`, `2.1b` through it). Batch 14 stored
per-locale names; batch 15 makes the readers that put a name in front of a customer or an import follow the
brand's default (catalog import and export, the onboarding sample, the storefront menu and its ETag, the menu
item list, the price-book matrix, add-by-filter), sends the brand's list locale from three more console forms,
lets terms, payment-method names and the new-product dialog author in the brand's or tenant's own languages (ten
of roughly 40 forms now do), and freezes a preset's wording in every locale onto the order line (V0433, granted
to the application role). The brief also listed regions and zones under this wave: nothing changed there (the
`fulfillment` module's diff is empty, `features/delivery` differs by prettier line-wrapping only) and only the
console reads their names, so `3.6` and `3.6b` stay as batch 14 left them. Two review-fix findings are worth
naming because a passing spec would have hidden them: the terms editor could publish the previous brand's text
as the newly picked brand's terms of service in the window while the new brand's reads were in flight, and the
storefront menu answered 304 for a menu whose default language or preset wording had changed.

Dine-in (`1.1`, `1.2`, `2.1`, `1.3`, `1.5a`, `10.2d`, `X.36`, `10.5b`, `10.5`). The operator side of ADR 0047 is
real: New order has a DINE_IN mode that names the table and puts the order on the party's bill in the same
request (the first cut did it in a second call and could leave a cooked order on no bill; the fix round moved it
into the placement); the floor plan seats a walk-in; every session read and write is scoped to the branch in its
path; and a guest's cart can be bound to the table its token was minted for, so checkout puts the order on the
bill in its own transaction, with the guest re-proved from a live token at checkout (the first cut trusted the
stored binding). The table chip batch 14 drew from guest orders only now also shows for an operator-keyed order.
Three things the wave reports did not say. First, the defect under `10.5`: `frontend/storefront-milliy` binds
its basket and does not send the token the platform now demands at checkout, so ordering at a table through it
should be refused with `TABLE_TOKEN_REQUIRED` (read from the two codebases and the platform's own test,
`aBoundCartWithoutATokenIsRefused`; not reproduced against a running stack); each side's tests mock the other
and nothing caught it. This audit did not fix it. Second, a party seated from the console (a walk-in, a booking,
New order) can be closed only by an API call: the console has no caller for a session's state-actions or
force-closures and the dine-in module has no scheduled job (`10.2d`). Third, `TableSessionsApi.attachRound` lost
its only caller when the attach moved into the placement, and the kitchen VDU and both wall shells still draw no
table (`2.1`). `1.5a`, `10.2d` and `X.36` stay `BUILT`, and `10.5b`'s console screen is unchanged.

Readiness and scope (`10.0`, and the Telegram card, `X.1` of §9). The panel gains the three conditions it lacked
(fiscal classification, channel payment methods, secret age) with an advisory severity, and the fix round found
the first cut rendered N offending items as N identical rows sharing one track key. The Telegram bug was found
while testing: a location-manager, brand-manager or location-staff could not mint their own link code, because
the capability sat at tenant scope on the only route and a grant covers only the routes whose path names its
level. The row read `BUILT` for an owner and was refused for the roles it was designed for; a branch-scoped
route now exists and the row is `BUILT` for them too.

Items with no PART A row, recorded here as batches 12 to 14 recorded infrastructure. CI: the hosted workflow has
now run twice, so batch 14's "has not yet run on a hosted runner" is out of date. Run 36545048373 (main
`10d6cfdc`, the first sharded run) failed only on shard 3, in `TelegramWebhookRegistrationEndpointTests`, with a
`ConcurrentModificationException` from AssertJ reading a logback `ListAppender` while another thread logged; its
Test steps took 31.3, 25.9 and 29.2 minutes and the aggregator skipped its "every class ran once" step. Main's
own fix `7f4bd3d8` (a synchronized snapshot) is in this branch, so w7's competing thread-safe appender was
reverted (`d9002cbd`) instead of kept beside it. Run 36548918034 (main `7f4bd3d8`) is green end to end — three
shards, "Every test class ran exactly once", the merged coverage floor, image publish — with Test steps of 21.2,
21.6 and 28.7 minutes, so shard 3 still runs about seven minutes longer than the others. `f84d5ef3` rebuilds
`test-durations.tsv` from the first run's 613 classes (about 50.9 recorded minutes per shard, 51.1 with today's
625 classes); it rebalances the next run and no run has used it yet, so the balance is a plan, not a result. The
operations lint is clean (`npm run lint` exits 0) and enforced with `lint:rules` and a whole-tree `npm run
format:check`, which replaced the changed-files-only step once the tree was reformatted in one commit
(`ac7fecb8`); `format_changed.py` stays a local shortcut, and its tests read the workflow's commands rather than
its comments. The operations initial bundle builds at 779.36 kB against the 825 kB error budget (about 45.6 kB
of headroom), and `order-queue.css` is at 7.97 kB of its 8 kB component-style limit. Build tooling: `MVN` in the
platform Makefile defaults to the per-worktree lock (`tools/mvn-serial`), and `make run` compiles under the lock
and serves outside it (`tools/checks/test_makefile.py`, part of `make lint`). The listing-backfill runbook now
records the 2026-09-29 pre-prod run as it was — ten offerings checked, all already listed, the backlog empty,
and steps 2 to 4 therefore never exercised against a real host; this audit cannot re-check that from here.

The counts below are a full programmatic recount of every row in PART A (id, tier, status), not a hand tally —
see the shape-of-the-debt table above and the per-section headers below, both checked against the row table with
a Python script, its output pasted verbatim. Net for this batch: no status moved, so BUILT stays 197, PARTIAL
56, NOT BUILT 29, BLOCKED 6, total 288, and no section header changed.

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 8 built · 1 partial · 3 not built
§1: 40 rows — 29 built · 9 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 18 built · 1 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 3 partial · 4 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 30 built · 10 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=119 PARTIAL=27 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=61 PARTIAL=23 NOT BUILT=13 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=6 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 197, 'PARTIAL': 56, 'NOT BUILT': 29, 'BLOCKED': 6}
Grand total: 288
```

**Re-audited 2026-09-29 after batch 14: 1 row moved status, found by this audit and built by no wave; every
row a wave claimed kept its prior verdict, most with a real, verified sub-gap closed and the note rewritten.**
(Seven waves — `w1-dine-in-ops-link`, `w2-locales-part-3`, `w3-storefront-milliy-parity`,
`w4-catalog-listing-ux`, `w5-infra-ci-shards`, `w6-adrs-identity-promotions` and
`w7-adrs-stops-dispatch-walkin` — plus a review-fix round (`fix14-a-dine-in`, `fix14-b-locales`,
`fix14-c-milliy`, `fix14-d-listing-ux`, `fix14-e-ci-infra`, `fix14-f-adrs-a` and `fix14-g-adrs-b`) landed on
`wave14-integration`, worktree HEAD `ed9bf9d0`; all fourteen branches were proved ancestors of it with
`git merge-base --is-ancestor`. Every row a wave claimed was re-verified against the code on this branch rather
than copied, per the platform owner's standing rule: "no call site = PARTIAL." Beyond reading each screen, the
endpoint it calls and the test that exercises it, this audit ran what needs no browser and no deploy: seventeen
Java test classes (250 tests) covering the dine-in table read, the comment-preset, region and zone controllers
and their translation migration, the brand-default readers, the unlisted-offerings report, the deploy script
and the audit-diff guard; 23 operations spec files (449 tests), every `storefront-milliy` spec (32 files, 959
tests) and the six `storefront` specs the sign-in return and table flow touch (122 tests); and the CI
partition's own checks (`make shards-test`: 39 unit tests, then "615 test classes, each in exactly one of 3
shards or the gate") — all green. What it could not run: the CI workflow on a hosted runner, `deploy.sh`
against a real OpenBao and RustFS, and the full Java suite.)

The one status move is `7.9` (promo-code summary and redemption detail), NOT BUILT → PARTIAL. It comes from
reading `w6`'s ADR 0140 against the code, not from a wave: the row's note ("a marketer cannot tell how many
times a code was redeemed ... let alone who redeemed it") had been false since batch 3, because the promo-codes
screen (`6.2`) opens a per-code redemption ledger over `GET .../promo-codes/{couponId}/redemptions` — customer
account id, order id, amount, status and time — tested in `promo-codes-page.spec.ts`. A real screen and a real
endpoint serve part of the row, which is this document's own definition of PARTIAL. The report the row names is
still absent (no summary, no channel, no masked customer, no `reporting.fact_promotion_redemption`), so it is
PARTIAL and not BUILT. The batch brief expected every ADR row to keep its status; this is the one place the code
disagreed, and the row is marked † with the earlier grade beside it.

`w1` and its fix round put the table beside a dine-in order. `OrderTablesPort` (dine-in's own tables, answered
by one batched read with the tenant a predicate of the statement) feeds `OrderQueryService#withTables` for the
board, `#tableFor` for the detail header and `KitchenBoardController` for the ticket, and `q-order-table-chip`
draws it in the queue's Type cell, the detail header, and the kitchen queue, buffer and expo pages. Rows `1.1`
(`PARTIAL`), `1.2`, `2.1` and `10.5b` (`BUILT`) keep their status and now say what is actually missing. Two of
those gaps the wave's brief did not name. The `/wallboard/kitchen` touch shell (batch 11) draws only the
sequence label and the lines, so the tablet at the pass shows a hall ticket with no table. And the chip has one
source today — an order a guest placed through the table-QR flow in `frontend/storefront` — because New order
creates only PICKUP and DELIVERY orders and no screen calls the staff-side table-session rounds, so an operator
cannot put a keyed-in DINE_IN order on a table (ADR 0143, Proposed, is the walk-in half of the same hole). The
fix round corrected two real defects: every table of one party shared a single `joined_at`, so a party could
read `T10 + T2`, and a held ticket lost its chip on the buffer page because a mutation response carries no
table. `10.5b`'s table card used to encode the bare token, which no phone camera can open; it now encodes
`https://<verified hostname>/dine-in/<token>` and prints a visible warning, never a silent bare token, when the
tenant has no verified hostname or the address will not fit the QR symbol. The verified hostname is what
`10.5`'s DNS-TXT verification (batch 13) produces, so that work now has its first downstream consumer, noted on
`10.5`. `X.36` is named in `w1`'s citations, but `FloorPlanCanvas`, `TableToken` and `TimelineScheduler` are
untouched (a diff against main of those directories and of the reservations page is empty), so it stays `BUILT`
exactly where batch 12 left it.

`w2` moves `10.12` further (still `PARTIAL`): comment presets, delivery regions and delivery zones are now
worded in the brand's or tenant's own locale set, backed by three new per-locale tables written alongside the
legacy ru/uz/en columns for one release (V0430 and V0431, granted to the application role, seeded from the
columns); a zone can be renamed for the first time; and `CatalogQueryService` reads every list name in the
brand's default language, which lets the category page and the product editor drop batch 13's compensating
pins. Seven of roughly 40 forms now read a locale set. The regions and zones screens live under §3, so `3.6`
and `3.6b` carry the same facet. `4.7`'s note was corrected on the way: product comment presets are built (a
table, a settings screen, and the round trip of `2.1b`) and are not waiting on the attribute-vocabulary
decision that blocks the rest of the row.

`w4` gives `4.1`'s «not listed at N branches» banner every variant instead of only the default one, reports a
partial or unverifiable list-everywhere instead of hiding it, and gives `4.4c`'s stock page an unlisted-dishes
report and a one-click list-all over batch 13's backfill endpoint (a new read-only report with the exact
count, a 200-item cap and names in the console language). Both rows stay `BUILT`. The listing-backfill runbook
it validated against the shipped endpoints is not a gap-map row; three cases in
`InventoryUnlistedOfferingsReportTests` run its commands and check that it names only tests that exist.

`w3` and its fix round touch only the customer apps this document does not track (its own opening line:
`apps/operations` only), so no row moves. Cart, checkout and delivery-fee refusals in `storefront-milliy` name
their reason; sale windows and sold-out or low stock show on the menu grid (the home screen used to draw
category chips and no dishes at all); and the table-QR scan landing and `VIEW_ONLY` table menu exist there
(ordering at a table is not ported). The wave's citations of `4.2g`, `4.4c`/`4.4d` and `10.5` are customer-app
ports of capabilities those console rows already track, and the rows are where batch 13 left them. One
wave-report claim was checked and rejected rather than copied: the commit that introduced the fee preview said
it "keeps the resolver's reasonCode", and the brief's row ("the preview keeps `DeliveryFeeView.reasonCode`")
repeats it. The review fix (`6633e2ad`) found that on `POST .../delivery-fee` that field is the resolver's
evidence string, not the vocabulary any refusal message is keyed on, so every real refusal read as "we couldn't
work out the fee"; the merged code reads the preview's `outcome` and deliberately does not carry `reasonCode`
(or the below-minimum branch, which the resolver never reaches). `frontend/storefront`'s half of the row-`10.5`
dine-in facet — a guest who signs in from a table returns to it — is real, allow-listed and tested
(`ReturnDestination`), and its fix round keeps a placed round queued on the device until the table's bill
confirms it.

`w5` is deploy and CI infrastructure with no PART A row, recorded here rather than invented as rows, matching
the precedent batches 12 and 13 set. The Java job is now `verify-static` plus three `test-shard` jobs behind
one aggregator that keeps the id `verify` and the name "Build and test", so `publish-images` still depends on
one job meaning "the whole Java build is green" and the aggregator fails on any upstream result but success.
`tools/ci/shard_tests.py` partitions the 615 test classes deterministically, `verify-reports` fails the build
when a class ran twice, in the wrong job or not at all, and ADR 0054's coverage floor is applied to the merged
execution data of every job (a `jacoco-merge-shards` execution bound to no phase). All of it is proved by unit
tests over the script and the workflow text, not by a hosted run: the eighty-minutes-to-slowest-shard claim
rests on recorded per-class durations (three shards of about 16.0 minutes each), and the workflow has not yet
run on a hosted runner. `deploy.sh` now mints the media object-store credential as well as the backup one,
with a write-access preflight that stops before anything is minted; `DeployScriptTests` pins the script's text
and executes the media block and the preflight against stubs, and the three runbooks point at it — none of it
has run against the colo host's real OpenBao and RustFS.

`w6` and `w7` draft ADR 0139 (staff person record), 0140 (promotions rule engine), 0141 (stop scope and
channel propagation), 0142 (dispatch rules) and 0143 (walk-in self-seating), all Proposed and Not started,
none decided. Ten rows now name their Proposed ADR in *Blocked by* and none moves because of it: `0.1d`,
`0.2c`, `9.2`, `9.2b`, `9.2c` and `X.5` (ADR 0139), `6.1` and `7.9` (ADR 0140), `2.5a` (ADR 0141) and `3.8`
(ADR 0142). PART B's Deferred table names them too, and its `9.2 · 9/X.2 · 9/X.3` row is corrected: ADR 0139
proposes keeping shifts and terminals as separate records, so it does not unblock "nine rows". Reading the ADRs
against the code corrected three stale row notes. `9.2` said every actor in the audit log is a UUID (the
activity log and the order detail resolve names through an interim Keycloak read; it is the People screen that
names no one). `0.1d` said a counting endpoint "would" print UUIDs (`operator-leaderboard` exists, and does).
`6.1` said the console has no `ConditionBuilder`, `RuleList` or `RuleSimulator` (all exist under `shared/ui/`
with consumers; what is missing is the decision and the authoring endpoints). ADR 0143 blocks no numbered row:
the nearest are `1.5a` and `10.5b`, both `BUILT` for the staff side and unchanged.

The counts below are a full programmatic recount of every row in PART A (id, tier, status), not a hand tally —
see the shape-of-the-debt table above and the per-section headers below, both regenerated straight from the row
table with a Python script, its output pasted verbatim. Net for this batch: BUILT unchanged at 197, PARTIAL
55→56, NOT BUILT 30→29, BLOCKED unchanged at 6, total unchanged at 288. The `§7` header moved with `7.9`. The
`§0` header had read `7 built · 2 partial · 3 not built` since before batch 13 (batch 13's note said it had
been corrected; the header was not) and now reads the row-level truth, `8 · 1 · 3`.

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 8 built · 1 partial · 3 not built
§1: 40 rows — 29 built · 9 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 18 built · 1 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 3 partial · 4 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 30 built · 10 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=119 PARTIAL=27 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=61 PARTIAL=23 NOT BUILT=13 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=6 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 197, 'PARTIAL': 56, 'NOT BUILT': 29, 'BLOCKED': 6}
Grand total: 288
```

**Re-audited 2026-09-28 after batch 13: 1 of 5 re-checked rows moved status; the other four
kept their prior verdict, each with a real, verified sub-gap closed and the note rewritten.**
(Eight waves — `w1-catalog-auto-listing`, `w2-new-order-branch-resolver`,
`w3-storefront-table-qr`, `w4-audit-diffs-a`, `w5-audit-diffs-b`, `w6-locales-part-2`,
`w7-hostname-verification-infra` and `w8-test-health-fixes` — plus a review-fix round
(`fix13-a-listing`, `fix13-b-resolver`, `fix13-c-table-qr`, `fix13-d-audit-a`,
`fix13-e-audit-b`, `fix13-f-locales`, `fix13-g-hostname-infra` and `fix13-h-test-health`, all
merged) landed on `wave13-integration`, worktree HEAD `ed430552`. Every row a wave claimed
was re-verified against the code on this branch rather than copied, per the platform owner's
standing rule: "no call site = PARTIAL."

One row moves PARTIAL → BUILT: `0.1c` (branch leaderboard / per-branch active-order load) —
the live-board half was already efficient; this batch's cross-branch resolver on `1.3` New
order now threads that same leaderboard's live counts into the branch picker itself, so an
operator sees load at the moment of choosing a branch for a call rather than only on the
board. Tested both ends (`NewOrderBranchResolutionHttpTests`, `new-order-page.spec.ts`).

Four rows keep their prior status but had a real, verified sub-gap close, with the note
rewritten to say what is actually still missing. `1.3` (New order) gets the cross-branch
resolver itself — `BranchResolutionQueryService` ranks DELIVERY candidates by winning
delivery zone and PICKUP candidates by live load, with a curated override-reason picker and
a `ChangeDocuments`-audited override fact — closing the last of the row's own named gaps
(the address geocoder and the promo-code total mismatch stay open, both blocked on other
ADRs). A same-batch fix closed a real hole the feature's own review found:
`OperatorOrderingService#place` trusted the client's own `proposedLocationId` to decide
whether an override had happened, so a caller could omit it and place at any branch with no
reason required and no audit fact written; the server now re-derives the resolver's own
proposal itself before deciding, never the request body. `10.5` (channel setup) gets DNS-TXT
ownership verification for a custom hostname — a challenge token, real DNS resolution over
dnsjava, and a periodic sweeper that un-verifies a hostname the moment its record stops
matching — closing the "stored unverified pending out-of-band confirmation" gap the prior
audit's own words named; kiosk device pairing stays the row's one remaining gap, unchanged.
`10.12` (languages & regional formats) reaches two more forms — the category tree/content
grid and the product editor — with the same `LocaleSet` service batch 12 wired onto the
location and channel-page editors, catching two real bugs along the way (the category editor
was writing the operator's own console language instead of a locale the tree's reads ever
resolved, and the grid could drop `uz-Latn` out of edit reach even though the tree's default
read always depends on it); four of roughly 40 forms now read the brand's own locale set.
`9.3a` (activity & audit) is the one worth naming for its scale: eleven commits split by
module finish essentially the whole before/after-diff migration `ChangeDocumentUsageTests`
exists to track, converting roughly 200 more `.changed(...)` call sites and pruning the
allow-list from 202 entries to 3 — a full re-scan against the merged tree confirms 235 of 238
sites are now compliant, `ChangeDocumentUsageTests` passes clean, and the 3 remaining are the
same documented parameter-forwarding false-negative the scan's own doc comment already
excuses, not new debt. The row still cannot move past PARTIAL, because its one remaining gap
is not a wrong-shaped call site but a missing one: `OrderAcceptancePolicyService`,
`OrderLatenessPolicyService` and `OrderOutcomeReasonService` still write no audit fact at
all, unchanged from last batch, and a migration that reshapes existing calls cannot close a
row whose gap is that the call does not exist.

Several commits this batch's own titles cite gap-map rows that, verified against the code,
turn out to touch only the customer-facing apps this document does not track (its own
opening line: `apps/operations` only) — recorded here so a future reader does not assume
otherwise. `10.5b`/ADR 0047: `w3-storefront-table-qr`'s storefront table-QR ordering flow (a
real, tested, guest-scoped cart-to-table binding, plus a same-batch fix closing a real
cross-table order-attachment exploit) and its commit's own "(row 10.5, ADR 0047)" both touch
`frontend/storefront` only — the operations-console settings screen `10.5b` actually names
(turnaround minutes, guest-session TTL, the per-table QR card) is untouched and stays `BUILT`
exactly as batch 10 left it. `4.4c`/`4.4d`: this batch's storefront fix (`ProductComponent`
no longer falls back a missing variant id to the product's own id, closing a real
add-to-cart bug) touches `frontend/storefront` for row `4.2g`'s own sale-window guard, not
`4.4c`/`4.4d`'s stock/QR-kiosk-pricing capabilities; neither operations row moves. `X.36` and
`1.1e` are named in the task's own wave citations but no commit this batch touches
`TimelineScheduler`, `FloorPlanCanvas`, `OrderActionCode` or either row's own spec files —
both stay exactly where batch 12 left them (`X.36` `BUILT`, `1.1e` `PARTIAL` on
`ISSUE_INVOICE` alone). `4.1` is unchanged and stays `BUILT`; the only diff touching its own
files this batch is a test-fixture type addition (`CategorySummary.translations`) carried by
`10.12`'s own category-editor conversion, not a change to the product library itself.

Two items this batch built are not gap-map rows at all, and are recorded here rather than
silently folded into a row that does not name them, matching the precedent batch 12 set for
infrastructure with no console screen. `w1-catalog-auto-listing`'s pilot-critical fix closes
a real, previously silent gap: an offering set `AVAILABLE` was never automatically listed in
`inventory.stock_items`, so it read as `NOT_STOCKED_AT_LOCATION` regardless of
`catalog.use_stock_logic` until an operator happened to touch `/catalog/stock` by hand.
`CatalogAuthoringService` now publishes `OfferingBecameAvailable` and
`inventory.application.CatalogOfferingListingTrigger` lists it automatically going forward; a
one-time, idempotent `POST .../inventory/listing-backfill` runbook
(`docs/runbooks/catalog-offering-listing-backfill.md`, tested by
`CatalogOfferingListingBackfillRunbookIdempotencyKeyTests`) closes the backlog for offerings
that were already `AVAILABLE` before the fix shipped. This is inventory-listing plumbing
behind row `4.1`'s own product library, not a console screen any row names, so it is
documented here rather than invented as a new row. `w7-hostname-verification-infra` also
carries the batch's RustFS follow-up (`2e3ac519`): `object-store-seed` and its compose twin
stop authenticating as the object store's own root credential, minting a scoped
create-bucket-only service account instead — deploy/runbook infrastructure with no console
screen or platform-module capability any PART A row tracks, the same category as batch 12's
own ADR 0135 follow-ups. `w3-storefront-table-qr` also carries `storefront-milliy`'s own
per-reason cart/checkout error messages (ported verbatim from `frontend/storefront`) — a
customer-app-only change, out of this document's scope by its own opening line.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally — see the shape-of-the-debt table above and the per-section headers below,
both freshly regenerated straight from the row table with a Python script, pasted verbatim.
Net for this batch: BUILT 196→197, PARTIAL 56→55, NOT BUILT unchanged at 30, BLOCKED unchanged
at 6, total unchanged at 288. One section header (`§0`) was recounted and corrected alongside
its own row move:

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 8 built · 1 partial · 3 not built
§1: 40 rows — 29 built · 9 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 18 built · 1 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 2 partial · 5 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 30 built · 10 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=119 PARTIAL=27 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=61 PARTIAL=22 NOT BUILT=14 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=6 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 197, 'PARTIAL': 55, 'NOT BUILT': 30, 'BLOCKED': 6}
Grand total: 288
```
)

**Re-audited 2026-09-13 after batches 1 and 2 merged; 46 rows changed status.** (27 waves,
main at `99f4af70` — see the wave index for which.)

**Re-audited 2026-09-13 after batch 3 merged; 46 rows changed status.** (18 more waves —
`P06`, `P09`, `P13`, `P18`, `P21`, `P22`, `P23`, `P24`, `P26`, `P32`, `P33`, `P36`, `P41`,
`T04`, `T05`, `T10`, `T12`, `T20` — main at `e871d604`; see the wave index for which. Four
of the 46 — `X.6`, `X.7`, `X.8` and `X.29` — are console primitives this batch gave their
first real call site as a side effect, not rows any of the 18 waves' own briefs named.)

**Re-audited 2026-09-14 after batch 4 merged; 29 rows changed status.** (12 waves —
`S01`, `P07`, `P10`, `P14`, `P16`, `P39`, `P43`, `P45`, `P47`, `T15`, `T18`, `W01` — plus a
review-fix round (`fix4-orders`, `fix4-kitchen-catalog` and `fix4-reports-finance` are
merged onto this branch; `fix4-settings-marketing` and `fix4-staff-iam` fixed confirmed
adversarial-review findings but were **not** merged as of this audit) — worktree HEAD at
`786d36e6`, base `wave138-integration`; see the wave index for which. Every row a wave
claimed was verified against the code rather than copied, per the platform owner's standing
rule; two moved less far than the wave's own report claimed: `4.2g` (kitchen department and
the sale-schedule editor are built and tested, but `CatalogAuthoringService.isOnSaleNow` has
no caller anywhere in ordering/checkout, so sale-window enforcement is unwired — held at
PARTIAL, not the wave's claimed BUILT) and `9.1a` (invite/revoke/outstanding are built end to
end and tested, but resend discards its own returned one-time link instead of showing it to
the operator, and no test exercises the resend button — held at PARTIAL, not the wave's
claimed BUILT; a fix exists on the unmerged `fix4-staff-iam` branch). `9.1a` also leaves
PART B's deferred table this round: ADR 0097's still-unconfigured sending provider blocks the
*owner*-invitation email, not the staff-invite flow `S01` built, which hands the operator a
copyable one-time link instead of sending anything.)

**Re-audited 2026-09-14 after `fix4-settings-marketing` and `fix4-staff-iam` merged; 1 row
changed status.** (`wave138-integration` at `8483ef25` — `c3037246` merges
`fix4-settings-marketing`, `8483ef25` merges `fix4-staff-iam` — merged into this branch at
`48f21987`. Re-checked only the rows those two branches could move under THE RULE: `9.1a`
now moves PARTIAL → BUILT, matching `S01`'s original claim — `staff-page.ts`'s resend branch
captures `resendStaffInvitation`'s response and reopens the invite dialog to show the fresh
link, tested in both `staff-page.spec.ts` and `staff-invite-dialog.spec.ts`'s `resent` input,
closing the one gap that held it at PARTIAL. `6.4`'s note is corrected, not its status:
`CampaignScheduledSendScheduler.runOnce` now disarms a due-but-unwired campaign after one
refusal (`clearFailedSchedule`, proven by a test that a second sweep pass no longer re-selects
it) instead of retrying forever — the row stays PARTIAL because no console screen surfaces
`haltedReason` yet. The brand-scope isolation fixes in `LoyaltyPolicyAuthoringService`,
`AttributionLinkService` and `ServiceScheduleService.bind()` are security/correctness fixes on
already-wired features — none of `6.3`, `6.4b`, `6.6a` or `10.2c`'s notes named that gap, so
none of those rows change.)

**Re-audited after batch 5, 2026-09-15: 31 of 44 re-checked rows changed status.** (Eleven
waves — `P08`, `P11`, `P17`, `P40`, `P27`, `P38`, `T02`, `T11`, `T07`, `T19` and `T23` — plus
a review-fix round (`fix5-realtime-orders`, `fix5-kitchen-kds`, `fix5-customers-settings` and
`fix5-reports-finance`, all merged) landed on `wave139-integration` at `e65d891f`. `T23` had
zero commits: the wallboard shell (`0.1e`, `X/X.3`) was already built and merged by an earlier
`wave133-t23` run before batch 1/2's own audit, so both rows were already correctly `BUILT` and
are untouched here. Every other row a wave claimed was verified against the code on this
branch rather than copied, per the platform owner's standing rule; three moved less far than
their wave's own claimed `BUILT`: `7.4b` and `7.4c` (T11's tariff audit and external-delivery
cost reports are real, tested and wired into `courier-report-page.ts`, but `JdbcReportingStore
.readTariffAudit`/`.readExternalDeliveryCost` read `fulfillment`/`ordering` schema live on
every request instead of through a closed `reporting.fact_*` row — the read-only-projection
invariant this document's own `7.9`/`7.9b` rows and PART C traps already cite ADR 0023 for; an
adversarial review confirmed the violation and the fix round only corrected the class's own
Javadoc to admit it honestly, leaving the actual projection as an open design question — held
at PARTIAL, not BUILT) and `X.36` (`P38`'s `FloorPlanCanvas` and `TableToken` are now wired
into `floor-plan-pane.ts`, but `TimelineScheduler` — the third named component, and the piece
that would answer "can I fit a party of six at 20:00" — has no consumer anywhere but its own
spec test; held at PARTIAL). Two annotation claims from wave reports were checked and rejected
rather than applied: `T23` and `T19` each asked this audit to correct row `0.1e` and row
`8/X.4`'s "Reader said: NOT BUILT" as a "stale" contradiction of their `BUILT` status — but
that is exactly what the **Reader said** column is for per this document's own legend (the
reader's *original* grade, kept once a `†` records that verification moved the status away
from it), and every other `†` row in this document — `1.2h`, `1.2m`, `4.5a`, `X.9` and a dozen
more — carries the identical pattern of a stale-looking `Reader said` beside a long-since-
corrected `BUILT`/`PARTIAL`. Both annotations are left exactly as they were.)

**Re-audit after batch 6, 2026-09-15: 16 of 19 re-checked rows changed status.** (Eight waves —
`P44`, `P12`, `T01`, `P28`, `T06`, `T13`, `T14` and `W02` — plus a review-fix round
(`fix6-orders-fulfilment`, `fix6-reports-customers` and `fix6-reports-products`, all merged,
closing all 12 confirmed adversarial-review findings: the shipment-cancel `reasonCode`
overflow via `V0373`'s widening of `shipments.cancellation_reason_code` to `varchar(64)`; the
export status/history PII leak to a viewer lacking `customer.pii.export`; `customers.new.v1`/
`customers.distinct.v1` silently dropping a customer whose only order was cancelled; the RFM
and ABC/XYZ legal-entity revenue combining with no ADR 0038 refusal, the latter scoped by
`V0370`'s `fact_order_line.legal_entity_id`; and the missing cross-tenant-isolation and HTTP
capability-refusal tests) landed on `wave140-integration`, worktree HEAD `7bc8877d`. Every row
a wave claimed, plus every row PART C assigns to these eight waves, was verified against the
code on this branch rather than copied, per the platform owner's standing rule. Thirteen rows
moved further than the map had them, matching what the waves built: `0.2`/`0.2a`/`0.2b` (My
work is real at `/today/my-work`), `1.2l` (payment and fiscal panels mounted on the order
detail), `7.3a` (the SLA arithmetic fix, median column and tint ramp), `7.6`/`7.6a`/`7.6b`
(customer analytics KPI tiles, cohorts and RFM), `7.7a`/`7.7b` (ABC/XYZ, now legal-entity
scoped), and `7.8`/`7.8a`/`7.8b` (the forecast model, department breakdown and holiday
awareness) all move to BUILT. Two rows move only as far as PARTIAL, matching what each
wave's own report already claimed: `1.2f` (the Millenium quote-delta
seam is wired only on the order detail, not the dispatch board the brief also named) and `7.2e`
(the export centre's whole audited chain is real, but only `ReportExportRegistry
.CUSTOMER_DIRECTORY` is wired — the row's own "order reports" export still produces nothing).
Two rows moved less far than their wave's own claimed `BUILT`, both for the same "built, no
consumer" reason this document has applied consistently since `X.32`/`X.38`/`4.2g`: `1.2g`
(`P44` claimed BUILT — the automatic cascade on order-cancel is real, wired to
`OperationsOrderController.cancel` and tested, but the row's own ADR-0014-named dedicated
endpoint, `DispatchController`'s `POST .../shipments/{shipmentId}/cancel`, and its
`DispatchApi.cancelShipment()` client method have no console caller anywhere in
`frontend/operations` — held at PARTIAL, not BUILT) and `7.7` (`T14` claimed BUILT — the
`Категория` column, `СТОП` marker and filter-bar reactivity defect are all fixed, but the
row's own remaining named gaps, a sort control and the 200-row cap, are untouched by this wave
— held at PARTIAL, not BUILT). `7.3` and `7.3b` stay PARTIAL, notes only, matching their own
waves' claims. Per this wave's own brief, `0.2c`/`0.2d` stay deferred (not part of `T01`'s row
set); `7.8a`'s "department" breakdown is catalog category (`catalog.category_products`), not
kitchen station/routing, noted on the row; `7.6a`'s new-vs-returning revenue split is GROSS
only, `reporting.fact_refund` carrying no customer attribution to net it, also noted on the
row.)

**Re-audit after batch 7, 2026-09-16: 2 of 3 re-checked rows changed status, closing PART
C's wave plan.** (Two waves — `P42` (POS export on the order) and `W04` (geography
histograms and the week grid) — plus a review-fix round (`fix7-pos-export` and
`fix7-geography`, both merged, closing all 8 confirmed adversarial-review findings: the
§3.11 AMEND-interlock bypass when a POS binding later drops `ORDER_EXPORT` capability while
a real, unsettled export row still exists; the `pos.export_push_requested` audit write not
sharing a transaction with the state change it describes, now inside
`PosOrderExportService`'s own `TransactionTemplate` unit of work; raw, potentially
address-bearing Clopos provider text surfaced verbatim in `ExportView.lastError`/
`PushResultResponse.detail`, now a platform-authored sentence keyed off `errorCode`; no
HTTP-level capability-refusal or genuine cross-tenant isolation test for the two new POS
export endpoints, now `OrderPosExportControllerEndpointTests`; a geography branch-switch
race letting a slower stale-branch response overwrite the newly selected branch's
histogram/grid, now guarded by a load-generation counter; the cohort grid's operating-hour
math untested against a non-midnight `businessDayStart`; and the drill-down fetching by
date-range instead of the sampled weekday, almost never representing older sampled weeks)
landed on `wave141-integration`, worktree HEAD `19b5f97c`. Every row a wave claimed, plus
every row PART C assigns to these two waves, was verified against the code on this branch
rather than copied, per the platform owner's standing rule. `7.10b` moves NOT BUILT →
PARTIAL and `7.10c` moves PARTIAL → BUILT, both matching what `W04`'s own report claimed:
`7.10b`'s duration histogram is real and tested (`q-histogram-chart` over the existing
`GET .../reporting/sla-buckets`, no new endpoint or SQL) and its distance half is honestly
not-yet-available — `T11`'s `reporting.fact_delivery` carries the raw `distance_meters`/
`transit_seconds` but no endpoint buckets it yet; `7.10c`'s seven-call cohort grid and its
per-sampled-date drill-down (capped at 300 rows, an honest "there may be more" note when a
date's own read came back full) are both real and tested. `1.2i` moves NOT BUILT → PARTIAL
— less far than `P42`'s own claimed BUILT: the operations-plane read
(`OrderPosExportController`, `GET .../pos-export`, `POS_EXPORT_READ`) and push/retry
(`POST .../pos-export/push`, `POS_EXPORT_RESOLVE`) are real, wired to `OrderDetailPane`'s
new POS export section and the §3.11 AMEND interlock, and tested — but the row's own name
is "POS integration errors *with a fix path*", and `orders.md` §3.11 (which `P42`'s own
commits updated) says outright that the fix path itself — the deep link from a
`LINE_UNMAPPED`/`MODIFIER_UNMAPPED` error to the ADR 0012 catalog-sync mapping screen with
the offending item pre-selected — "is not built either"; the console surfaces the adapter's
error code and detail honestly but links nowhere, so a named capability is absent and the
row is held at PARTIAL. Also noted on `1.2i`: `POS_EXPORT_READ`/`POS_EXPORT_RESOLVE` are
granted only to `TENANT_OWNER`, `TENANT_ADMIN` and the two support-session roles, not
`BRAND_MANAGER` or `LOCATION_MANAGER` — verified consistent with `P12`'s own
`PAYMENT_READ`/`FISCAL_DOCUMENT_READ` pattern, so a note rather than a further downgrade.
One discrepancy surfaced and not acted on: `P42`'s own wave report and the integration
notes both claim the wave "removed [the] stale gap-map row quoting the dead
`order_process_states.POS_ORDER_EXPORT` column" from this document — `git diff` of this
file between the batch-6 merge (`90bb2ca5`) and batch-7's HEAD is empty, so no such edit
exists on this branch. The only place that sentence appears in this document is PART C's
own `P42` brief, present unchanged since this document's original creation and never a
PART A row to begin with — flagged as a wave-report overclaim, per this document's own
standing caution, rather than reverted, since there was nothing on this branch to revert.
While recounting, the §1 and §7 section-header tallies were found already stale
independently of this batch's own row moves (§1 read 17/15/8, true count even before this
batch's `1.2i` move was 18/16/6; §7 read 13/13/13 against a true 22/12/5) — corrected to
the row-level truth (§1 now 18/17/5, §7 now 22/12/5) alongside this batch's own two moves,
rather than compounding a stale base with a fresh delta.

This closes PART C's wave plan: every wave in the wave index has now either been built and
verified against the code, or is explicitly deferred (PART B's Blocked and Deferred tables)
or struck as superseded by a decision (PART B's "Amend the IA, do not build"). The 47 rows
still NOT BUILT or BLOCKED, grouped by the reason this document itself gives:
- **Blocked-by-ADR-input (26):** `0.1d`, `0.2c`, `0.2d`, `1.3d`, `2.5a`, `3.8`, `4.2a`,
  `4.2b`, `4.2c`, `4.4a`, `4.4c`, `4.5b`, `4.6a`, `5.5`, `6.1`, `6.3b`, `6.7`, `6.8`, `7.6c`,
  `7.9`, `8/X.2`, `9.2`, `9.2b`, `9/X.2`, `9/X.3`, `10.14`.
- **Owner decision (9):** `2.1b`, `2.6a`, `3.1b`, `4.7`, `4.9`, `5.2f`, `6.3a`, `8/X.3`,
  `10.5`.
- **Provider input (3):** `6.4a`, `7.10`, `7.10a`.
- **Deferred-by-design (9):** `1.1d`, `1.2c`, `1.2d`, `1.6a`, `2.1d`, `6.5`, `6.7a`, `7.7c`,
  `9.2d`.

143 rows of 288 are finished. The pilot tier alone carries 69 rows that are neither
built nor blocked, and **56 of them are PARTIAL** — a real screen against a real endpoint
with one named capability missing. That ratio is still the single most useful fact in this
document: the pilot is not a greenfield build, it is a finishing job, and most of the
remaining finishing is frontend wiring over endpoints that already answer — the same
pattern every re-audit finds again and again: a component built with a spec and no call
site, or a capability fixed on the server with no screen that reads it yet.

By size: 52 S · 96 M · 92 L · 48 XL, scored `S`=1 · `M`=2 · `L`=4 · `XL`=8 size-points.
PART C sizes every wave in those points and caps a wave at eight points per agent-day.

**Re-audited 2026-09-22 after batch 8: 8 of 15 re-checked rows changed status.** (Eight
waves outside PART C's now-closed 75-wave plan — `w1-order-board`, `w2-new-order`,
`w3-kitchen`, `w4-catalog-import`, `w5-amendments-ui`, `w6-audit-followups`, `w7-reports`
and `w8-integrations-notifications` — landed on `wave8-integration`, worktree HEAD
`2d93ef95`. Every row a wave claimed, plus the wave index note below, was verified against
the code on this branch rather than copied, per the platform owner's standing rule.
`1.1d`/`1.3d` move NOT BUILT → BUILT (the board's search box and the New order pre-order
toggle both now work end to end, tested both ends), as do `7.1a`/`7.2c`/`10.8c`/`10.9d`
(the business-overview funnel, the two-roll-up «Сводка», and the POS/fiscal/notification
liveness watermarks and their two notification triggers — all real, wired and tested,
matching what `w7` and `w8` themselves claimed). `2.1b`/`4.5b` move NOT BUILT → PARTIAL —
less far than a literal reading of `w3`'s and `w4`'s own commits might suggest: `2.1b`'s
coded comment vocabulary and its settings screen are real and tested, but the row's own
headline capability — an operator attaching a preset comment to an order line, and it
reaching the KDS and a POS modifier — has no call site anywhere, exactly as `w3`'s own
commit messages say; `4.5b`'s import/export is a full, tested round trip, but it is CSV
(`commons-csv`, already a dependency) rather than the Excel workbook the row itself names —
`platform/pom.xml` still carries no spreadsheet library. `4.2g` and `1.1e` stay PARTIAL,
matching their own waves' claims: `4.2g`'s kitchen-department GET/409 bug is fixed end to
end (`w3`), but `isOnSaleNow` still has no caller in `ordering`/checkout, so sale-window
enforcement is still unwired; `1.1e` now emits six of nine action codes (`COMPLETE` wired
from the row itself this batch, `AMEND` already live since wave P10) but `RESOLVE`,
`ASSIGN_COURIER` and `ISSUE_INVOICE` remain declared, never emitted. Two rows this batch's
own waves did not actually touch — `1.2c` and `2.1d` — stay exactly where they were
(`NOT BUILT`, `Wave: deferred`): `w5`'s only commit added test coverage for an existing
behaviour, not a new command, and no wave built `CHANGE_PAYMENT_METHOD`; both rows' notes
are corrected in place (their older "no UI"/"the endpoint is there" framing predates wave
P10's five-dialog amendment menu and was already stale) but their status and Wave column
are left alone rather than credited to a wave that did not earn them. `1.1`, `1.3` and
`1.3a` all keep their prior status (`PARTIAL`) but had materially stale notes rewritten:
`1.1`'s claim that the board already showed customer and courier per row was simply wrong
— `OrderSummaryResponse` carries no courier field at all and only an opaque customer id —
and `1.3`/`1.3a`'s "still unbuilt"/"wait for P14" framing predates wave P14 itself, which
had already shipped delivery, non-cash payment and the order-history popover before batch
8 began; this batch's own real contribution to those two rows is `1.3d`'s pre-order time
and `1.3a`'s `CUSTOMER_CREATE` 403 fix, both called out in the rewritten notes rather than
folded into the stale claims they replace.

Four items batch 8's own wave briefs named are not PART A rows at all — a coded vocabulary,
a chart or a trigger has an IA row; an idempotency-key reuse fix or a coordinate-leak
redaction does not — so they are recorded here rather than invented as new rows. **(a)**
Seven operations API files (`order-actions-api.ts`, `new-order-api.ts`,
`order-bulk-actions-api.ts`, `order-pos-export-api.ts`, `order-handover-api.ts`,
`table-sessions-api.ts`, `reservations-api.ts`) genuinely gained a held-and-reused
`Idempotency-Key` across a manual retry or an in-flight double click, each proved RED
before the fix and green after, `w6`. The wave brief's further claim of "a full inventory
of the remaining ~45 files" has no artifact on this branch — no audit note, no additional
fix, no comment trail — against roughly that many other mutating `*-api.ts` files that do
exist; flagged as an overclaim per this document's own standing caution, not applied.
**(b)** `DeliveryFeeController.quote` (the storefront delivery-fee preview) moved off a
coordinate-bearing `GET` onto a `POST` body, both storefront apps' callers updated with it
(the finding named only `storefront-milliy`; `frontend/storefront`'s own `ui-cart.service.ts`
turned out to need the identical fix and got it) — real, tested, `w6`. **(c)**
`GlobalApiErrorHandler.invalidArgument` no longer echoes a `GeoPoint` range failure's raw
coordinate in `problem.detail` — real, tested, `w6`. **(d)** The storefront order detail
does name the pickup branch today, but not because of this batch: the capability (locationId/
fulfillmentMode on `OrderResponse`, the branch profile endpoint, `LocationProfileService`)
was already live from the pre-batch-8 `storefront-followups` merge. `w6` built a second,
independent implementation of the same profile endpoint without knowing the first one
existed; batch 8's own integration correctly found the collision and kept the pre-existing
`StorefrontLocationProfileController` as the single source of truth, discarding `w6`'s
duplicate — documented in the surviving controller's own doc comment. No PART A row names
this capability, so nothing here moves; the finding is recorded only so a future audit does
not credit `w6` with work it did not end up shipping.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally and not last batch's table with a delta applied — see the shape-of-the-debt
table above, freshly regenerated. Net for this batch: BUILT 144→150, PARTIAL 97→95,
NOT BUILT 41→37, BLOCKED unchanged at 6, total unchanged at 288. Five section headers
(`§1`, `§2`, `§4`, `§7`, `§10`) were recounted and corrected alongside their own row moves;
`§0`'s header (`4 built · 2 partial · 6 not built`) was found already wrong against its own
rows (true count `7 built · 2 partial · 3 not built`) independently of anything batch 8
touched — left uncorrected here since no batch-8 wave claimed a `§0` row, and flagged
separately rather than folded into this batch's own delta.

**Re-audited 2026-09-23 after batch 9: 16 of 17 re-checked rows changed status or note; one
row this batch's own list named was never actually touched.** (Eight further waves —
`w1-queue-override-filters`, `w2-brand-channel-presentation`, `w3-branches-settings-readiness`,
`w4-reports-distance-crm`, `w5-external-dispatch`, `w6-menu-entity`,
`w7-catalog-channel-controls` and `w8-notification-templates-installs` — plus a review-fix
round (`fix9-a-orders`, `fix9-b-settings`, `fix9-c-reports`, `fix9-d-menu` and
`fix9-f-notifications-installs`, all merged) landed on `wave9-integration`, worktree HEAD
`90d742c1`. Every row a wave claimed was verified against the code on this branch rather
than copied, per the platform owner's standing rule.

Nine rows move PARTIAL/NOT BUILT → BUILT, each with a real screen, a real endpoint and a
test on both ends: `1.1h` (the OVERRIDE reason-picker dialog, wired into both the order
detail pane and the queue row menu, over the `POST .../state-overrides` endpoint that
already existed from `P41`), `1.2e`/`2.1c` (external-courier dispatch from the order and
from the KDS, over one new order-keyed `OrderDeliveryController.externalCourier` endpoint),
`4.2f` (the per-channel image-override picker in the product editor), `4.4a` (the named
Menu entity — built additively over ADR 0016's existing `location_offerings` model, so the
ADR 0016 amendment this row and the Deferred table below both said it needed turned out not
to be necessary; removed from the Deferred table as a result, though the older
`Blocked-by-ADR-input` list in this document's own PART-C-closing paragraph above is left
as the frozen historical snapshot it already was for `1.3d` — not corrected here, the same
way batch 8 left it stale for that row rather than hand-editing a closed snapshot), `7.1`
(the overview's distance KPI tile), `10.2a` (branch-list channel/INN filters, severity sort
and the bulk close/open bar), `10.4a` (channel icon, brand colours and social links) and
`10.9a` (order-status/OTP template variants by fulfilment mode and channel source).

Seven rows keep their prior status but had a real, verified sub-gap close, with the note
rewritten to say what is actually still missing: `1.1c` (an Источник/origin filter is now
real; a branch filter, a per-provider-binding filter, a payment-status filter and URL
round-tripping are still absent), `10.0` (delivery/POS/catalog readiness now name every
offending item, matching payment's own shape from `P31`; the checks no ADR covers and the
label-only find-a-setting search are unchanged), `10.1`/`X.12` (logo/banner now go through
the real `q-media-uploader`, closing the one gap each row separately named; `10.1`'s
country/currency/timezone display and `X.12`'s kiosk/story/review-tag/video gaps are
untouched), `4.4d` (`catalog.qr_kiosk_price_plane` is now actually wired into pricing —
before this batch the flag was readable and writable in the console but `QuoteService`
never consulted it, so setting it did nothing regardless of its value; `use_stock_logic`
stays correctly-enforced-but-read-only, blocked on no ADR deciding what turning it on
should even do while `QUANTITY` tracking stays unimplemented, reported as `notDone` rather
than built around a decision no one has made) and `7.2a` (the CRM order log is now a real,
tested endpoint, rendered in «Заказы» and wired into the export centre; only a column
chooser and saved views remain, exactly as the screen's own comment already disclosed).

One row, `10.8a`, is held at PARTIAL against a wave report that read as a fuller claim: the
fix round found the new "Bind to a branch" dialog offered POS/DELIVERY installations with
an empty `capabilities: []`, producing a binding `integration.bindings`'s own capability
join can never resolve — a dead write that looked like it worked. The fix restricts the
dialog to categories with no capability catalogue, so the per-branch install model now
genuinely works end to end for PAYMENT/NOTIFICATION, but POS and DELIVERY — the categories
this row's own capability-reconciliation read most centrally concerns — still cannot be
bound to a branch from the console; held at PARTIAL rather than credited with the wave's
own fuller claim.

`3.7` (delivery tariffs) was in this batch's own row list but no wave or fix commit on this
branch touches it — `RoadDistancePort`'s empty fallback is unchanged and ROAD distance mode
still silently prices as radius; left exactly as it was, not re-dated to this batch.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally — see the shape-of-the-debt table above and the per-section headers below,
both freshly regenerated straight from the row table with a small Python script rather than
adjusted by hand. Net for this batch: BUILT 150→159, PARTIAL 95→87, NOT BUILT 37→36,
BLOCKED unchanged at 6, total unchanged at 288. Five section headers (`§1`, `§2`, `§4`,
`§7`, `§10`) were recounted and corrected alongside their own row moves.)

**Re-audited 2026-09-24 after batch 10: 25 of 37 re-checked rows changed status; the other
12 kept their prior verdict with a real sub-gap closed and the note rewritten.** (Eight
further waves — `w1-order-board-actions`, `w2-financial-amendments`,
`w3-catalog-presets-schedule-xlsx`, `w4-dispatch-couriers`, `w5-reports-exports`,
`w6-settings-locations-policy`, `w7-integrations-pos-fixpath` and `w8-channel-setup` — plus a
review-fix round (`fix10-a-board`, `fix10-b-amendments`, `fix10-c-catalog`, `fix10-e-reports`,
`fix10-f-settings` and `fix10-g-integrations`, all merged) landed on `wave10-integration`,
worktree HEAD `1bb167d5`. Every row a wave claimed was verified against the code on this
branch rather than copied, per the platform owner's standing rule.

Twenty-one rows move PARTIAL/NOT BUILT → BUILT, each with a real screen, a real endpoint and
a test on both ends: `1.2f`/`1.2g` (external-courier call and shipment cancel now reach the
dispatch board itself, not only the order detail), `1.2i` (the POS-mapping deep link, with a
fix-round bug closing a binding-resolution gap it exposed), `1.3a`/`1.3f` (a LOCATION-scoped
reorder route finally reaches `LOCATION_STAFF`, this screen's own primary persona), `2.1b`
(comment presets round-trip from New Order/storefront to the KDS ticket and the POS export),
`2.1d` (`CHANGE_PAYMENT_METHOD` built, with a KDS «Изменить оплату» action over it), `3.4b`
(a stale "no production caller" claim corrected — see below), `4.2g` (per-item sale-window
enforcement wired into cart/price/checkout), `7.2a` (the CRM order log gets a real column
chooser and saved views), `7.7` (a sort control and cursor paging replace the hard 200-row
cap), `7.10b` (the distance histogram), `10.1` (country/currency/timezone on the brand
profile), `10.2c` (a real delete endpoint for dated hours exceptions), `10.3b` (a TENANT
scope-bar selector), `10.8a` (a capability-assignment picker lets POS/DELIVERY bind to a
branch), `10.8e` (`view_item`/`add_to_cart` fire from the storefront), `10.10a`
(drag-and-drop reorder for outcome reasons), `1.2k` and `3.4b`/`X.11`/`X.32` (stale-note
corrections — see below). `1.2c` moves NOT BUILT → PARTIAL rather than BUILT even though six
of its seven financial commands now carry `built()=true`: `REMOVE_LINES`, the seventh, still
needs an ADR 0017 primitive (return-to-stock or write-off) this wave correctly declined to
invent, so the row stays honestly short of the whole set. `10.5` and `9.2d` also move NOT
BUILT → PARTIAL: `10.5` gets a real hostname/domain claim, SEO, static pages and a
per-channel setup hub, with only kiosk device pairing left genuinely blocked; `9.2d`'s
order-detail attribution now resolves a display name, with only a staff-card orders-count
aggregate still missing.

Twelve rows keep their prior status but had a real, verified sub-gap close, with the note
rewritten to say what is actually still missing: `1.1`/`1.1c`/`1.1e` (a courier column, a
payment-status filter with URL round-tripping, and `ASSIGN_COURIER` emission — each closes
one more of several named gaps, not the last one), `3.1` (bulk assignment and 'call an
external courier' close two of five named gaps; the map, a non-PII customer/address label
and the SSE stream remain), `3.4c` (`evaluatePeriodClose` now runs at settlement close),
`4.8a` (tax profiles are no longer write-only), `6.4` (`haltedReason` and a campaign
history/statistics view are now surfaced), `10.0` (find-a-setting is a real combobox now),
`10.2b` (sort order, venue attributes and localized content are built; only a map-pin editor
remains) and `X.12` (video is now refused cleanly client-side; kiosk/story/review-tag upload
paths remain absent). Two are worth naming for the opposite reason — a real backend
capability with still no way to reach it from a screen, this document's own "built, no
consumer" pattern: `7.2e` (the export centre's registry now recognises `ORDER_REPORT_LOG`/
`ORDER_REPORT_SUMMARY`, but `ExportCentrePage`'s own picker still offers only
`CUSTOMER_DIRECTORY`, so an operator still cannot trigger either export from the screen
itself) and `4.5b` (the backend now genuinely produces and consumes `.xlsx` — fastexcel, a
real three-sheet template workbook, bounded against a decompression-amplification upload by a
fix round — but the console's own `q-import-wizard` still hard-codes `accept='.csv'` and
reads the file as text, which would corrupt a binary upload if one somehow reached it).

Three of the "BUILT" moves above are corrections, not batch 10's own work, and are recorded
as such rather than credited to a wave that did not earn them: `1.2k` (row-level Cancel
already routed through the reasoned dialog — fixed on `main` on 2026-09-22, on an unrelated
standalone branch, `bugfix-operations-console`, and simply never picked up by a prior audit),
`3.4b` (the "no production caller" claim was already false since batch 6/`T11`, and already
correctly stated on the sibling row `7.4` — this batch's own commit only strengthened the
proof with a real-figure assertion and flagged the staleness), and `X.32` (`ColorInput` has
had a real call site on `sales-channels-page.ts` since wave 9 w2 — a different consumer than
the one the row originally expected, and never credited). `X.11` is half of each: DateRangePicker
is this batch's own real work (wired into the reports filter bar), but the row's other half,
ScheduleGrid, has been live in the settings hours editor since `P43` and was already stale
before this batch touched anything. All four are judged against the code on this branch
exactly as the platform owner's standing rule requires, independent of which batch's commits
happen to be responsible.

PART B's Deferred table is trimmed to match: `2.1b` and `2.1d` are removed outright (both
fully built this batch); `1.2c` is narrowed from "the seven financial commands" (XL) to
`REMOVE_LINES` alone (S), the one command still genuinely blocked on a decision; `4.5b` is
removed — its remaining gap is ordinary frontend wiring, not a decision or an XL rebuild;
`10.5` is narrowed to kiosk device pairing alone (M, down from XL). The
`Blocked-by-ADR-input`/`Owner decision`/`Deferred-by-design` lists inside the
batch-7-closing paragraph above are left as the frozen historical snapshot they already were,
per the same precedent batch 8 and batch 9 both followed.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally — see the shape-of-the-debt table above and the per-section headers below,
both freshly regenerated straight from the row table with a Python script, pasted verbatim.
Net for this batch: BUILT 159→181, PARTIAL 87→69, NOT BUILT 36→32, BLOCKED unchanged at 6,
total unchanged at 288. Eight section headers (`§1`, `§2`, `§3`, `§4`, `§7`, `§9`, `§10`) were
recounted and corrected alongside their own row moves; `§0`'s own header (4 built · 2 partial
· 6 not built) was found wrong against its own rows independently of anything this batch
touched — the true count, 7 built · 2 partial · 3 not built, was flagged in the batch-8 audit
and left uncorrected there and again in batch 9's; corrected here, since no batch had actually
fixed it and this audit already has the tooling in hand:

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 7 built · 2 partial · 3 not built
§1: 40 rows — 28 built · 10 partial · 2 not built
§2: 16 rows — 11 built · 3 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 14 built · 4 partial · 5 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 3 built · 4 partial · 7 not built · 1 blocked
§7: 39 rows — 28 built · 6 partial · 5 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 8 built · 8 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 28 built · 12 partial · 0 not built

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=114 PARTIAL=32 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=51 PARTIAL=30 NOT BUILT=16 BLOCKED=3 Total=100
3: BUILT=13 PARTIAL=7 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 181, 'PARTIAL': 69, 'NOT BUILT': 32, 'BLOCKED': 6}
Grand total: 288
```
)

**Re-audited 2026-09-25 after batch 11: 14 of 25 re-checked rows moved status; the other 11
kept their prior verdict with a real, verified sub-gap closed and the note rewritten.**
(Eight further waves — `w1-quick-closes`, `w2-quantity-stock`, `w3-courier-policy-roster`,
`w4-kitchen-realtime`, `w5-fulfillment-destination`, `w6-reporting-facts`,
`w7-audit-people` and `w8-marketing-automations` — plus a review-fix round
(`fix11-b-stock`, `fix11-c-courier`, `fix11-d-kitchen`, `fix11-e-fulfillment`,
`fix11-f-reporting`, `fix11-g-audit` and `fix11-h-marketing`, all merged) landed on
`wave11-integration`, worktree HEAD `69b4e7f3`. Every row a wave claimed was verified
against the code on this branch rather than copied, per the platform owner's standing
rule.

Thirteen rows move PARTIAL/NOT BUILT → BUILT, each with a real screen, a real endpoint and
a test on both ends: `7.2e` (`ExportCentrePage` gains a report picker reaching all four
registered exports, not only `CUSTOMER_DIRECTORY`), `4.5b` (`q-import-wizard` finally
accepts and correctly reads a binary `.xlsx`, instead of corrupting it through
`readAsText`), `4.4c`/`4.4d` (QUANTITY tracking is now the enforced ADR 0017 branch behind
a genuinely writable `catalog.use_stock_logic`, with a new `/catalog/stock` console page),
`2.1` (the desk console and a new `/wallboard/kitchen` touch shell both subscribe to the
ADR 0045 `KITCHEN_BOARD` stream, closing the row's touch-shell/offline-banner/SSE trio at
once), `7.3` (the branch leaderboard's average delivery — courier transit — time),
`7.4b`/`7.4c` (both courier-cost reports now read closed `reporting.fact_*` rows instead of
joining `fulfillment`/`ordering` live, closing the ADR 0023 violation batch 10's fix round
could only document), `9.2d` (a staff card's own today's-orders aggregate), `9.3` (full
208-code action-dictionary coverage plus a deep link from a person's card), `X.26`
(`q-diff-viewer` finally has a call site — the activity log's own drawer), `1.4` (a
first-line product preview, and a real hand-off to an ADR 0044 recovery audience through
the new CART_ABANDONMENT automation) and `6.4` (the audience-snapshot CSV export the
campaign detail pane itself was missing, over an endpoint Segments already had audited).

Eleven rows keep their prior status but had a real, verified sub-gap close, with the note
rewritten to say what is actually still missing: `1.1` (a Клиент column, batched through a
capability-gated `POST .../orders/crm-log/labels` read — branch is still absent), `8.6`
(ADR 0127's purchase-confirmation dialog — self-service 'end' and the prepaid wallet
remain), `3.9` (a real If-Match write path, and a correction: four of the "six missing
switches" already had a backing field and are now honestly rendered locked rather than
silently inert, not actually missing storage), `3.3` (courier-app provisioning and
online/offline status — only rating remains, as before), `2.4` (a station filter and a
dedicated `GET .../kitchen/vdu` projection — a VDU device class stays undecided under ADR
0079), `3.1` (a non-PII destination label on the queue card — the map and the SSE stream
remain), `1.3g` (DELIVERY for manual aggregator entries, reusing New Order's own structured
address — the ADR 0114 settlement-evidence gap remains), `X.19` (the ABC
cumulative-revenue-share curve — funnel, cohort heatmap and geo heatmap remain), `9.3a` (8
more `.changed(...)` call sites migrated onto `ChangeDocuments.diff`, 10 of ~130 total) and
`9.4` (a PII-export approval gate closes one of the row's two named cases — the
discretionary discount producer still has no ADR). One of the eleven is worth naming for a
different reason — this document's own "built, no consumer" pattern closing rather than a
fresh sub-gap: `X.25`'s `q-rule-list` gets its first live consumer (the new
`/marketing/automations` page, itself row `6.5`'s own screen); `q-rule-simulator` still has
none, so the row stays PARTIAL.

One row, `6.5`, moves NOT BUILT → PARTIAL rather than the fuller claim its own commits
could have read as: BIRTHDAY, INACTIVITY and CART_ABANDONMENT all now fire unattended, each
behind a human-armed activation and the same eligibility/quiet-hours checks a campaign
recipient gets (a fix round closed a converted-first TOCTOU in the CART_ABANDONMENT guard
before merge). CASHBACK_CHANGE and LATE_ORDER_APOLOGY are correctly left unbuilt rather
than invented: no accrual/debit event exists in `loyalty` for the first, and ADR 0044
defers the second to the still-Proposed ADR 0112 — neither is a decision this batch could
make on its own, so the row is held at PARTIAL, not credited with a fuller claim.

PART B's Deferred table is trimmed to match: `4.4c` is removed outright (now BUILT in PART
A, `UnsupportedTrackingModeException` no longer thrown); `6.5` is removed as a Deferred
entry — its "no schema, no service, no table" reason is no longer true, and its real
remaining gap (two of five triggers, one lacking a source event and one lacking an accepted
ADR) is now carried in PART A's own row note instead of a separate table line.

One omission this audit also corrects, independent of anything batch 11 touched: batch 10's
own re-audit was never recorded in the wave index's traceability list below, unlike batches
8 and 9 before it. Added here rather than left stale, per the same precedent batch 10 itself
used to fix `§0`'s wrong header count left uncorrected by two prior audits.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally — see the shape-of-the-debt table above and the per-section headers below,
both freshly regenerated straight from the row table with a Python script, pasted verbatim.
Net for this batch: BUILT 181→194, PARTIAL 69→58, NOT BUILT 32→30, BLOCKED unchanged at 6,
total unchanged at 288. Seven section headers (`§1`, `§2`, `§4`, `§6`, `§7`, `§9`, `§X`)
were recounted and corrected alongside their own row moves:

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 7 built · 2 partial · 3 not built
§1: 40 rows — 29 built · 9 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 17 built · 2 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 2 partial · 5 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 29 built · 11 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=118 PARTIAL=28 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=60 PARTIAL=23 NOT BUILT=14 BLOCKED=3 Total=100
3: BUILT=13 PARTIAL=7 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 194, 'PARTIAL': 58, 'NOT BUILT': 30, 'BLOCKED': 6}
Grand total: 288
```
)

**Re-audited 2026-09-28 after batch 12: 2 of 16 re-checked rows moved status; the other 14
kept their prior verdict, twelve of them with a real, verified sub-gap closed and the note
rewritten.** (Eight further waves — `w1-storefront-inventory`, `w2-catalog-adrs`,
`w3-i18n-lazy-load`, `w4-infra-followups`, `w5-dispatch-realtime-actions`,
`w6-audit-diffs-locales`, `w7-marketing-triggers` and `w8-pricebook-reservations` — plus a
review-fix round (`fix12-a-storefront`, `fix12-b-adrs`, `fix12-c-i18n`, `fix12-d-infra`,
`fix12-e-dispatch`, `fix12-f-audit-locales`, `fix12-g-marketing` and
`fix12-h-pricebook-dinein`, all merged) landed on `wave12-integration`, worktree HEAD
`ccd66cdb`. Every row a wave claimed was re-verified against the code on this branch rather
than copied, per the platform owner's standing rule: "no call site = PARTIAL."

Two rows move PARTIAL → BUILT, each with a real screen, a real endpoint and a test on both
ends: `4.8a` (`q-price-book-matrix-page` finally lets an operator see every variant's price
in one book at once, filterable and inline-editable with a real If-Match concurrency guard —
a merge-time fix round caught and closed two real concurrency bugs, a check-then-act race and
a self-poisoning fallback insert, before either shipped) and `X.36` (`TimelineScheduler` gets
its first real consumer, a Grid/Timeline toggle on the reservations day screen, closing the
row's last of three named components).

Seven rows keep their prior status but had a real, verified sub-gap close, with the note
rewritten to say what is actually still missing: `3.1` (the dispatch board now subscribes to
`RealtimeClient` and refreshes on the ADR 0045 `DISPATCH_BOARD` signal, closing the SSE gap
the batch-11 audit named — a same-batch fix also closed a real stale-response race the new
accelerator exposed; only the map remains, blocked on `X.4`), `1.1e` (`RESOLVE` is now
emitted and wired end to end, leaving only `ISSUE_INVOICE` undeclared-but-inert of
`OrderActionCode`'s 9 values), `X.39` (a shared WCAG-AA contrast evaluator now warns when a
tenant's chosen brand colour is illegible against the SLA tint steps, wired into the channel
colour picker — the tenant-configurable SLA bucket/late-colour screen itself still does not
exist), `9.3a` (`ChangeDocuments` gains a `.created` shape and, more importantly, a source-scan
regression guard — `ChangeDocumentUsageTests` — that fails the build on any new
`.changed(...)` call site not already named on `change-document-allowlist.txt`; the migration
itself grows from 10 of 237 call sites to 35, with 202 still flat) and `10.12` (the new shared
`LocaleSet` service reaches two more forms — the location-content and channel static-page
editors — with a post-merge fix closing a real bug where both silently kept the platform
fallback because neither called `ensureLoaded()`; roughly 38 of ~40 forms still do not read
the brand's own locale set, and there is still no regional-format editor). One of the seven is
worth naming for the row's own `campaign.approve`-style discipline: `6.5` moves from three of
five named triggers firing to four — CASHBACK_CHANGE now fires off a PII-free in-process
`LoyaltyBalanceChanged` event — while LATE_ORDER_APOLOGY correctly stays unbuilt, since ADR
0044 (Accepted) excludes it until ADR 0112 (still Proposed) reconciles it with ADR 0013's
existing recovery case; the row's `Blocked by` column now names ADR 0112 instead of reading
`—`.

The eighth is the one this audit is most worth writing down, because it is exactly the
"reader believed the commit title" failure mode the platform owner's standing rule exists to
catch: `X.25`'s own commit is titled "rule-match preview wires `q-rule-simulator`'s
consumer," but the commit's own body — and the code — say the opposite. `AutomationRulePreviewService`
is a real, tested, purpose-built server read (`GET .../automations/{ruleId}/preview`), and it
is a genuine answer to the row's underlying question ("which customers would this rule match
today"), but it is deliberately **not** a reuse of `q-rule-simulator`: that component needs a
typed `ConditionGroup`, and an automation rule's single numeric threshold cannot become one
without inventing a predicate catalogue nobody asked for. A source search confirms
`q-rule-simulator` still has zero call sites anywhere in `frontend/operations` outside its own
spec file — every other hit is a doc comment. The row stays PARTIAL, with the note corrected
to say so plainly rather than letting the commit title stand.

Four rows had a decision drafted for them but, correctly, do not move: `4.2a`, `4.2b`, `4.2c`
and `4.6a` have been held in PART B's Deferred table since batch 3 for lacking an ADR. This
batch drafts three — ADR 0136 (combos + modifier depth, `4.2a`/`4.2b`), ADR 0137 (physical
attributes, `4.2c`) and ADR 0138 (marketplace projection preview, `4.6a`) — all filed
`docs/adr/not-started/`, Decision status `Proposed`, Implementation status `Not started`. Per
the owner's standing rule a Proposed ADR does not make a row BUILT, and none of the four
tables, columns or endpoints these records describe exist yet, so all four rows stay NOT
BUILT. What changes is honesty of citation: each row's `Blocked by` column now names the
actual record (`ADR 0136 (Proposed 2026-09-25) — the platform owner has not decided it`,
etc.) instead of the vaguer "An ADR 0016 amendment" / "ADR 0040's adapters" the doc carried
before a record existed to name, and PART B's Deferred table is split to match (`4.2a`/`4.2b`
under ADR 0136, `4.2c` under its own ADR 0137). A same-batch fix round caught and corrected
two drafting errors inside the new ADRs themselves before they could mislead a later reader —
ADR 0136 had misattributed `ck_order_total_reconciles` to ADR 0072 instead of its actual owner,
ADR 0019/V0022, and ADR 0138 had claimed `catalog.channel_offering_exclusions` has no writer
when wave P45 gave it one eleven days before ADR 0138 was proposed — corrections folded into
this audit's own citations rather than left for a reader to trip over.

Two rows this batch's own commit messages name are **not** touched by this audit, and are
recorded here so a future reader does not assume otherwise. `4.4c`/`4.4d`: `w1-storefront-inventory`'s
commits carry "(rows 4.4c/4.4d)" in their titles, but the code they touch is
`frontend/storefront`/`frontend/storefront-milliy` — the customer-facing apps — threading the
already-shipped `remainingQuantity` onto the product page. This document tracks
`apps/operations` only (its own opening line says so); the operations-console capabilities
`4.4c`/`4.4d` actually name (the `/catalog/stock` page, `catalog.use_stock_logic`) were
already `BUILT` in batch 11 and nothing in `frontend/operations` or the inventory module's
console-facing surface changed this batch — b13fb471 touches `inventory` only for `9.3a`'s
audit-diff migration, not a stock-page capability. Neither row moves. `1.3e`: `fix12-b-adrs`
fixes a citation inside the newly-drafted ADR 0136 (which ADR owns `ck_order_total_reconciles`
— ADR 0019, not ADR 0072), correcting who documents the constraint, not the constraint or the
promo-code-discount bug `1.3e`'s own note already named. No code path behind `1.3e` changed
this batch; the row is unchanged.

Two more items this batch's waves built are not gap-map rows at all, and are recorded here
rather than silently absorbed into a row that does not name them: `w3-i18n-lazy-load` lazy-loads
the console's own `en`/`uz-Latn` UI-string chunks (keeping `t()` synchronous) and lowers
`angular.json`'s initial-bundle budget to the measured post-lazy-load size plus headroom — a
performance change to the console shell itself, not a capability any IA row names, closed by
two of its own regression fixes (a rejected import cached forever, a preload failure that
blocked bootstrap entirely) before merge. `w4-infra-followups` carries the batch's ADR 0135
(RustFS) follow-ups — a scoped media service-account credential, a corrected backup-restore
runbook, and a fix that stopped logging a raw provider response on extraction failure — all
deploy/runbook infrastructure with no console screen or platform-module capability any PART A
row tracks.

The counts below are a full programmatic recount of every row in PART A (id, tier, status),
not a hand tally — see the shape-of-the-debt table above and the per-section headers below,
both freshly regenerated straight from the row table with a Python script, pasted verbatim.
Net for this batch: BUILT 194→196, PARTIAL 58→56, NOT BUILT unchanged at 30, BLOCKED unchanged
at 6, total unchanged at 288. Two section headers (`§4`, `§X`) were recounted and corrected
alongside their own row moves:

```
=== Section counts (BUILT/PARTIAL/NOT BUILT/BLOCKED) ===
§0: 12 rows — 7 built · 2 partial · 3 not built
§1: 40 rows — 29 built · 9 partial · 2 not built
§2: 16 rows — 12 built · 2 partial · 1 not built · 1 blocked
§3: 15 rows — 5 built · 8 partial · 2 not built
§4: 25 rows — 18 built · 1 partial · 4 not built · 2 blocked
§5: 19 rows — 15 built · 2 partial · 1 not built · 1 blocked
§6: 15 rows — 4 built · 4 partial · 6 not built · 1 blocked
§7: 39 rows — 32 built · 2 partial · 5 not built
§8: 11 rows — 7 built · 2 partial · 1 not built · 1 blocked
§9: 20 rows — 10 built · 6 partial · 4 not built
§10: 36 rows — 27 built · 8 partial · 1 not built
§X: 40 rows — 30 built · 10 partial

Total PART A rows counted: 288

=== Shape of the debt (Tier x Status) ===
P: BUILT=118 PARTIAL=28 NOT BUILT=4 BLOCKED=1 Total=151
2: BUILT=61 PARTIAL=22 NOT BUILT=14 BLOCKED=3 Total=100
3: BUILT=14 PARTIAL=6 NOT BUILT=8 BLOCKED=2 Total=30
?: BUILT=3 PARTIAL=0 NOT BUILT=4 BLOCKED=0 Total=7
Column totals: {'BUILT': 196, 'PARTIAL': 56, 'NOT BUILT': 30, 'BLOCKED': 6}
Grand total: 288
```
)

## What the verification changed

The two lenses moved **53 of 284 statuses**. The direction is lopsided and instructive:

- **34 rows moved up**, NOT BUILT → PARTIAL, almost all for one reason: the backend had landed and the reader believed a stale code comment.
  `1.3` New order is the headline — the whole operator place-order path exists,
  capability-granted and tested, and the console renders a placeholder. `10.2d` Floor plan,
  `10.5b` QR dine-in, `X.2` KDS device enrolment, `1.2m` handover codes, `10.8c`
  integration health and `5/X.1` customer erasure are all the same story: a finished,
  audited, capability-gated endpoint with no caller anywhere in `frontend/operations`.
- **13 rows moved down** — ten BUILT → PARTIAL and three PARTIAL → NOT BUILT. These are the
  dangerous ones, because a BUILT row is one nobody re-reads. `9.1` Staff cannot be opened by the
  branch manager it was designed for. `10.10a` Reference data has no edit path at all —
  only create and archive — although the `PUT` exists. `5.2i` Blacklist reveals the actor
  and then never renders it. `1.2a` `actions[]` is a status array, not a capability array:
  `LOCATION_STAFF` is offered «Отменить» on every open order and gets a 403 when it clicks.
- **6 rows moved off BLOCKED** — five to NOT BUILT and one to PARTIAL. `10.10c`, `7.5b`,
  `3.1b`, `1.3b`, `4.2e` and `X.4` were each filed as waiting on a decision that had either
  already been taken, or was needed for only part of the row.

Three stale-comment classes are worth fixing in the same passes that touch them, because
every one of them has already misled a reader: the `ADR 0045 is not built` comments in
`order-queue.ts` and `today-page.ts`; the `order_handover_challenges does not exist`
comments in `expo-page.ts` and `KitchenBoardController`; and the
`order_outcomes does not exist` comments in `business-overview-page.ts`,
`ReportingController` and `MetricRegistry`.

## What the review added

A second reading against the IA and the parity matrix found **four obligations with no row
at all**, which are now rows `4.2h` (recommended products / cross-sell, IA 4.2), `4.4d`
(catalog base settings — stock logic and the QR/kiosk price plane, IA 4.4), `10.8e`
(analytics installs, the GA4 ecommerce event contract and the telephony provider install,
IA 10.8) and `3.6d` (free geozone, IA 3.6). Three statements were also wrong rather than
merely incomplete and are corrected in place: `1.2a` said «nothing is missing» about the
one contract this document's own introduction calls a status array pretending to be a
capability array; `9.1c` counted eleven rail sections where `navigation.ts` declares
fourteen in three groups; and `X.1` (§X) said `service-status.ts` holds two signals where
it holds three — `open`, `late` and an `updated` stamp that is written and never rendered.

`3.6d` is the interesting one of the four, because the answer is that the gap is smaller
than the parity matrix implies. `ZoneRole`'s own Javadoc settles it: «a 'free geozone' is
not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as
a layer is what left three layers with no documented interaction». The mechanism is built;
what is missing is the console's ability to bind any tariff at all to a zone, which is
already `3.6`. The IA's free-geozone-as-a-layer wording is struck in PART B instead.

The build plan changed more than the map did. Waves are now scored in size-points and
capped at eight per agent-day, which split six of them (`P05`/`P41`, `P12`/`P42`,
`P22`/`P47`, `P23`/`P45`, `P31`/`P46`, `P32`/`P43`) and re-sized a dozen more; the wave
count is 75. Five file collisions the first draft left concurrent are now sequenced — the
order-board family is serialised end to end, the floor plan is built once rather than by two
waves at the same time, `@angular/cdk` has one owner, `reports-filter-bar` has one owner,
and every wave that writes into `shared/ui/` is behind the wave that creates it. Migration
numbers are allocated per wave rather than taken, since the sequence is strictly ordinal and
`V0210` is the head. Every brief now names its capability constants and its tests; three
capabilities that were left unnamed turned out to be two that already exist
(`SHIPMENT_CANCEL`, and `COURIER_REGISTRATION_REVEAL`, which is too narrow and is replaced
by a new `COURIER_PII_REVEAL`) and one pair that has to be minted
(`TENANT_CONFIGURATION_READ`/`WRITE`). Every repeated row id — `X.1` through `X.5`, one of
which names six different rows — is written section-qualified wherever a brief or the index
uses it.

---

# PART A — The gap map

One table per IA section. **What is missing** is the verified statement of what a person
cannot do today, not a restatement of the row title.
## §0 — Home: live board, my work, wallboard

12 rows — 11 built · 1 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `0.1 †` | Live board | P | BUILT | Every part of the row now has a screen, an endpoint and a test: the oversized counters (`0.1a`), both live mixes (`0.1b`), the branch leaderboard and per-branch load (`0.1c`), the operator leaderboard (`0.1d`, a band under the branch table with names from the tenant's staff record) and liveness (`0.1f`), with the TV-distance presentation in the wallboard shell (`0.1e`). The 'Отменено' counter and both mixes are cut to the tenant's business-day boundary and per-branch load answers in one brand-scoped request per tick. The operator band is a second read of its own (`LiveOperators`), so a band that cannot be read leaves the counters standing and says it could not load the operators. Tested and green in this audit's run: `today-page.spec.ts`, `live-operators.spec.ts`, `OperatorTodayLeaderboardEndpointTests` (8). Residue: the wallboard shell (`0.1e`) still draws no operator band, only a note that naming people on a shared screen is undecided (see `0.1e`). Moved PARTIAL → BUILT: the band the row said was a locked note is real. | L | — | w4-staff-identity |  |
| `0.1a` | Oversized counters + the canonical "in progress" grouping | P | BUILT | — | M | — | P15 |  |
| `0.1b` | Live source-mix and type-mix | P | BUILT | — | M | — | P15 |  |
| `0.1c †` | Branch leaderboard / per-branch active-order load | P | BUILT | The branch leaderboard answers in one brand-scoped request per tick (unchanged). This batch closes the row's other half: `1.3` New order's own cross-branch resolver (`BranchResolutionQueryService`) now carries each candidate branch's live active-order count — reusing this row's own leaderboard counts rather than a second read — straight into the New Order screen's branch picker, so an operator sees load at the moment of choosing a branch for a call, not only on the live board. Tested both ends (`NewOrderBranchResolutionHttpTests`, `new-order-page.spec.ts`). | M | — | w2-new-order-branch-resolver |  |
| `0.1d †` | Live operator leaderboard | P | BUILT | A shift supervisor sees, during service, who is taking and who is confirming orders: the live board's operator band (`today-page`, read by `LiveOperators` beside the counters on a request of its own) lists each person with the orders accepted and the orders created today, most accepted first, over `GET .../orders/operators/today` (`ORDER_READ`: a brand route and a branch route, because a grant covers only the routes whose path names its level; a brand reader gets the brand, a branch supervisor falls back to their branch after a remembered 403). `OperatorTodayLeaderboardService` counts `ordering.orders` over the tenant's business day, at most a hundred rows, `USER` actors only (a bot or website order names a customer or a channel, never a person), and the controller names each person from the tenant's own staff record through `StaffDirectory` (ADR 0139), a person with no name shown as «Сотрудник без имени», never as a Keycloak subject. The staff report's operator-leaderboard read names the same people (`7.5`). Tested and green in this audit's run: `OperatorTodayLeaderboardEndpointTests` (8), `ReportingControllerOperatorNamesTests` (3), `today-page.spec.ts`, `live-operators.spec.ts` (7). Residue: created and accepted counts can include the same order and are never added; the wallboard shell draws no band (`0.1e`). Moved NOT BUILT → BUILT. | XL | — | w4-staff-identity |  |
| `0.1e †` | Wallboard presentation of 0.1 (TV-distance shell + WallboardTile) | 2 | BUILT | Residue: the TV shell draws no operator band. `wallboard-shell` keeps the band as a note, changed in batch 17 from «not built» to «deferred», saying that naming people on a shared screen is an undecided product question, and `wallboard-shell.spec.ts` asserts the note and no table. The controller behind the live board's band (`0.1d`) says the same of any surface that must not show a name: it simply does not render `displayName`. | L | — | w4-staff-identity | NOT BUILT |
| `0.1f` | Liveness: refresh, staleness and the ADR 0045 COUNTERS stream | 2 | BUILT | — | M | — | P08 |  |
| `0.2` | My work | 2 | BUILT | `/today/my-work` loads a real `MyWorkPage` (route wired in `app.routes.ts`, replacing the `NotBuiltPage`), rendering `0.2a` and `0.2b` for real and a band that points to «Мой профиль» for the personal data (`0.2c`, batch 17: a link to `/my-profile`, asserted in `my-work-page.spec.ts`) and a `q-locked-state` band that says interface personalization is not built (`0.2d`) — exactly the boundary this row's own brief drew; `0.2c`/`0.2d` keep their own rows and are not discharged by this one. | XL | — | w4-staff-identity |  |
| `0.2a` | Personal statistics — orders by sales channel | 2 | BUILT | `GET .../orders/my-work/channel-mix` (self-scoped to the caller's own `created_by_actor_id` via `ix_orders_created_by`, refuses a request naming another subject) renders as a `q-bar-chart` on `MyWorkPage` via `MyWorkApi.channelMix`; tested both ends. | M | — | T01 |  |
| `0.2b` | Personal statistics — revenue by payment method | 2 | BUILT | `MyWorkPage` calls the existing `ReportingApi.paymentMix` and renders a `q-donut-chart`, gated by `SessionCapabilities` so a `REPORTING_READ`-less cashier sees a denied state instead of a spurious request. Rides on P39's `fact_order_tender`, populated only after the tenant's business day closes (~1h delay) — mid-shift the tile is an honest empty state, not a defect. | L | — | T01 |  |
| `0.2c †` | Personal data (own profile) | 2 | BUILT | A staff member edits their own name, contact phone, photo, interface language and spoken languages from «Мой профиль» → «Личные данные» (`my-profile-page`, `staff-profile-form`) over `GET` and `PUT /api/v1/operations/tenants/{tenantId}/staff/me` and its photo route (`StaffSelfController`, `@StaffSelfAuthorized(STAFF_SELF_MANAGE)`: authorised by the token's own subject holding an active job in the tenant, never by scope coverage, and naming no member id, so there is no request the page could be made to send about anybody else). A save carries the record's version as `If-Match`, is sealed under ADR 0029's envelope (`noPersonalValueReachesALogLine`), appears on the activity log at once (`aSelfEditIsOnTheActivityLogAtOnce`), cannot touch employment (`aSelfEditCannotTouchEmployment`), and is handed to `OwnProfile` so the shell chip shows the new name now, where the token's `name` claim would be stale until the next sign-in. A photo is limited to an image of at most a megabyte, checked in the browser and again by the platform. An account with no record (a support session, a device) gets a named note in place of a form that could only fail. Tested and green in this audit's run: `StaffMemberEndpointTests` (20), `StaffMemberServiceTests` (42), `my-profile-page.spec.ts` (14), `own-profile.spec.ts`, `staff-profile-form.spec.ts`. Not on this surface by decision (ADR 0139, Accepted): the password is Keycloak's, the sign-in phone and the reset email are neither shown nor edited here, and changing the sign-in identifier is not in v1, so the row's «or email» is answered by that decision and not built; managing MFA from here waits on ADR 0148 (Proposed). Moved NOT BUILT → BUILT. | XL | — | w4-staff-identity |  |
| `0.2d` | UI personalization (user-scoped) | 2 | NOT BUILT | An operator who sets a language on the kitchen terminal has to set it again on every other machine, and no other preference (default branch, column layout, notification sound) can be expressed at all. | L | ADR 0030 has no user scope — adding one is an ADR amendment, and the parity matrix's open question 'what is actually in Персонализация?' leaves the field set an owner decision. | deferred |  |

## §1 — Orders: queue, detail, taking an order, amendments, outcomes, inbox

40 rows — 32 built · 6 partial · 2 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `1.1` | Order board — queue shell, table and columns | P | PARTIAL | The board calls the cursor-paginated `GET .../orders/board` and renders four added columns — Оплата, Доставка (batch 8), Курьер (batch 10) and, from batch 11, Клиент: a capability-gated `POST .../orders/crm-log/labels` batch-resolves each page's `customerAccountId`/guest reference into a name (in full) and a masked phone through `OrderCrmLogQueryService`'s existing decrypt-not-mask path, body-only per ADR 0029, merged into a map that only grows across the 10s poll so a name already decrypted is never re-requested — tested both ends (`OrderCrmLogControllerLabelsHttpTests`, `order-queue.spec.ts`). Batch 14 added the table beside the order type: `OrderQueryService#withTables` makes one `OrderTablesPort` read (dine-in's own tables, the tenant a predicate of the statement) over the page's DINE_IN orders only, and `OrderSummaryResponse.table` — codes and display names, never a guest, a party size or a bill — renders as `q-order-table-chip` in the Type cell: `T7`, or `T7 + T8` for a party pushed together, in join order. Tested both ends (`OrderTableVisibilityHttpTests`, `order-table-chip.spec.ts`, `order-queue.spec.ts`). Batch 15 gives the chip the second source batch 14's note said it lacked. An order an operator keys in as DINE_IN on New order (`1.3`) is placed and put on the chosen party's bill in one request (`POST .../orders` with `dineInSessionId`), so it carries its table from the moment it exists; `TableSessionControllerHttpTests` proves an operator-attached order shows its table on the board at once (13 cases, green in this audit's run). That test attaches through the staff rounds endpoint, the way the first cut of New order did; batch 18 adds the test of the one-request placement itself (`OperatorOrderEntryHttpTests`, see `1.3`). Batch 16 (`w1-order-board-completion`, with `fix16-a-board`) gives the board the branch it lacked, in one mode. A principal who reads the whole brand (the console offers the mode when the shell's roster has two or more branches, and withdraws it for the session on the first 403) gets a «Все филиалы» select. On it the queue reads `GET .../brands/{brandId}/orders/board` (`ORDER_READ` at `BRAND`), which is the branch board's one statement over a set of branches (`OrderListQuery` names `locationIds`, the tenant and brand predicates stay unconditional, the cursor resolves inside the same set). The queue draws a Филиал column, narrows to one branch with a Филиал select (`locationId`), reads the brand's tab totals, judges each row by its own branch's lateness policy (re-read on the poll), and acts on or opens a row at the row's own branch (opening one of another branch switches the console to it first). Each row's `actions[]` is computed from the caller's grants at that row's branch. Tested both ends and green in this audit's run: `OrderBoardBrandScopeQueryTests` (13), `OperationsBrandOrderBoardHttpTests` (11), `order-queue-all-branches.spec.ts`. Batch 18 (`w1-order-entry`, with `fix18-a-order-entry`) gives «Все филиалы» the realtime channel it lacked. `ORDER_QUEUE` is now carried at the brand as well as at the branch (`StreamChannel`; `OrderRealtimeSignalTrigger` publishes each order change at its brand too), the console opens one brand stream (`BrandOrderStream`, a second `SseConnection`) only while the mode is on screen and closes it the moment the operator goes back to one branch or leaves, and the queue takes a frame from one stream only: the brand's when it is open, the branch's when it is down (it is what the mode heard before the brand stream existed), so one change is one read and a `resync` re-reads whichever stream sent it. That stream is told of an order's arrival and six status transitions, not of every change a row can show (a move to PREPARING, READY or FULFILLING, a courier, an amendment or a payment carry no frame), so the board keeps its ten-second poll with the stream open, as the branch board does; a board that took the stream for «everything» would have shown those rows up to a minute stale. Tested and green in this audit's run: `OperationsStreamBrandScopeHttpTests` (4, over HTTP through the real stream endpoint), `RealtimeSignalBrandHopTests` (2), `OrderRealtimeSignalTriggerTests` (4), `SseStreamRegistryTests` (20), and `order-queue-all-branches.spec.ts` (33 cases, among them: the stream is listened to only while the mode is on, a frame re-reads the brand board without waiting for the poll, with the stream open the branch stream is ignored, with it down the branch stream's frames still count, the poll stays at ten seconds) and `brand-order-stream.spec.ts`. What the mode leaves: bulk selection (the selection column is withheld and the queue says why), a board over just some branches for a principal whose grants are scattered branch by branch (they keep the branch board), a multi-select with a live count per branch (the control is a single-select), and the per-row fiscal chip (ADR 0144, Proposed). Why the row stays PARTIAL: the IA row also owns courier type, source icon, accepted-by, created-by and courier ETA as columns, and the table draws none of them. The channel is text beside the type (`Доставка · <channel code>`), the courier cell is the roster's display reference, and the response already carries `createdBy*`, `acceptedBy*` and `discountMinor`; orders.md §2.5 puts those behind a column picker, and the picker is `0.2d`, which waits on a user scope in ADR 0030. | L | — | w1-order-entry |  |
| `1.1a` | Status tabs (seven, route-bound, per-tab membership) | P | BUILT | Nothing for the pilot. `attention` membership omits the `MANUAL_ACTION_REQUIRED`/`FAILED_RETRYABLE` process signal and the unresolved callback flag, because neither is on OrderSummaryResponse. | S | — | — |  |
| `1.1b` | Live per-status counts and liveness | P | BUILT | — | M | — | P08 |  |
| `1.1c †` | Filters (period, branch, aggregator, source, delivery type, courier, payment type) persisted per tab | P | BUILT | Every filter the IA row names is a toolbar control bound to a parameter of `GET .../orders/board` and persisted per tab in `localStorage` (`order-queue-filter-state.ts`): period, channel, source (`origin`), fulfilment type, courier, payment method, payment status (`ordering.orders.payment_status_projection`, distinct from the method), «мои заказы» and, new in batch 16 (`w1-order-board-completion`), the aggregator binding and the branch. «Агрегатор» (`marketplaceBindingId`) is a select whose options are `GET .../orders/marketplace-bindings` at branch and brand scope: the bindings the orders in scope arrived through, most recently used first, the installation named through `MarketplaceBindingLookup` so ordering names no integration table. They are read when the control is focused, or at once when a link already carries a binding so its chip has a name. «Филиал» exists on «Все филиалы» (see `1.1`) and is never sent to the branch board. The four secondary toggles orders.md §2.4 marked not read by ordering are read now: «Только опаздывающие» (`late`: the resolved `ordering.lateness` policy applied per branch and mode inside the board statement, held to `OrderLatenessPolicy.evaluate` by `OrderBoardTogglesQueryTests#theLateFilterAgreesWithTheDomainRule`), «С проблемой» (`problem`: a process in one of its two failure states), «Требуется звонок» (`callbackRequested`) and «Фискализация» (`fiscalStatus`, repeatable: one `fiscal.fiscal_documents` status, or failed plus blocked as «Требует внимания»), each a chip and a URL parameter. The whole toolbar also round-trips through the URL (`filtersToQueryParams`/`filtersFromQueryParams`): a filter change pushes into the URL, a pasted link or the browser's back/forward is that load's source of truth, and switching tabs hands control back to the tab's own `localStorage` memory; «Сбросить фильтры» keeps the period and the branch mode. Tested both ends and green in this audit's run: `OrderBoardTogglesQueryTests` (12), `OperationsBrandOrderBoardHttpTests` (11), `OperationsOrderBoardReferenceValidationTests`, `order-queue-filter-state.spec.ts`, `order-queue.spec.ts`, `order-queue-all-branches.spec.ts`. Moved PARTIAL → BUILT: the three things the row listed as absent (a branch filter, one aggregator binding, the four booleans) each have a screen, an endpoint and a test now. Narrower than orders.md §2.4 still, and not what the IA row names: branch, binding and payment method are single-selects where the spec draws multi-selects, «Без курьера» is not offered, and a principal with scattered branch grants keeps the one-branch board. | L | — | w1-order-board-completion |  |
| `1.1d †` | Search across per-provider external IDs | P | BUILT | The board's toolbar now has an exact-match search box (minimum two characters), feeding `GET .../orders/board`'s `reference` filter, which matches the public order number or a per-provider external id (`JdbcOrderStore.normalisedExternalReference`, `order_external_references`). Batch 8 w1. Tested both ends: `order-queue.spec.ts` (debounced input → `reference` param, persists per tab, does not leak across tabs) and `OrderBoardQueryTests`/`OperationsOrderBoardReferenceValidationTests`. | M | — | w1-order-board |  |
| `1.1e †` | Row action menu (inline + overflow, server-driven) | P | BUILT | Every value of `OrderActionCode` is emitted now (the enum has ten, not the nine earlier notes counted, and an exhaustive switch in `OrderActionsPolicyTests` names a real route for each): `APPROVE`/`REJECT`, `ADVANCE`, `CANCEL`, `OVERRIDE`, `AMEND` (wave P10), `COMPLETE` (batch 8 w1) and `ASSIGN_COURIER` (batch 11) are unchanged. `RESOLVE` was added by the batch that first moved this row: `OrderActionsPolicy`'s new 5-argument overload offers it whenever `OrderQueryService#amendmentAwaitingOperatorFor` (or its batched board counterpart, `JdbcOrderAmendmentStore#ordersAwaitingOperatorResolution`) says an open amendment is blocked on the operator — an increase awaiting the customer's recorded agreement, or an ADR 0027 approval still pending — and the caller holds `ORDER_AMEND`, the same predicate the amendment history already applies per-amendment, now read back at the order level. The row action opens the order; the detail pane's header action fetches the amendment history fresh and opens `q-order-amendment-confirm-dialog` for whichever entry still carries its own `RESOLVE`. Tested both ends (`OrderActionsPolicyTests`, `order-actions.spec.ts`, `order-queue.spec.ts`, `order-detail-pane.spec.ts`). `ISSUE_INVOICE` («Выставить счёт», orders.md §4.9), the last code that was declared and never emitted, is offered by batch 16 (`w1-order-board-completion`, with `fix16-a-board`). `OrderActionsPolicy.availableFor`'s six-argument overload adds it for an order that has not ended, whose payment projection is `PENDING` and which has a live provider intent none of whose attempts forbids showing a surface again (`PaymentIntentPort#ordersWithPresentablePayment`, asked once per board page; the fix round found the projection alone offered it for an order whose attempt had expired or was in doubt, where the endpoint answers `NO_PAYMENT_INTENT` or `PAYMENT_IN_DOUBT`), and only to a caller who holds `PAYMENT_INITIATE` at tenant scope, the scope `POST .../orders/{id}/payment/re-presentations` declares, so the button is never shown to a principal the endpoint would refuse. The row action opens the order; the detail header action opens the payment panel's existing re-issue form (`q-order-payment-panel`, shown for a provider intent), which calls that endpoint with an `Idempotency-Key`. The endpoint, wave P12's, now audits what it does: `payment.checkout_reissue_requested` is written before the checkout is opened (an audit failure stops the issue with nothing sent) and `payment.checkout_reissued` after the surface exists, neither carrying the phone an invoice was pushed to nor the link; the activity log has a sentence for both. Tested and green in this audit's run: `OrderActionsPolicyTests` (37), `OperationsOrderControllerActionCapabilitiesTests`, `OperationsBrandOrderBoardHttpTests` (an unpaid online order offers it; cash, paid, ended and no-capability orders do not), `OperationsPaymentReissueAuditHttpTests` (8), `order-actions.spec.ts`, `order-queue-all-branches.spec.ts`, `order-detail-pane.spec.ts`, `order-payment-panel.spec.ts`. The form takes no reason from the operator, so the audit fact carries the endpoint's default sentence. Moved PARTIAL → BUILT: no declared code is without a caller. A terminal order still gets only the minimal read-only overflow (Открыть/Копировать номер). | S | — | w1-order-board-completion | BUILT |
| `1.1f` | Bulk selection and bulk courier assignment | P | PARTIAL | Selection, `POST .../orders/bulk-actions` (ADVANCE/CANCEL) and the §2.10 result panel with per-item outcomes and «Повторить проблемные» are now real, tested and capability-gated on `ORDER_BULK_ACTION` — a bulk Advance is only ever offered for a target every selected row can actually reach (`order-queue-selection.ts`'s `bulkAdvanceTarget`, fix4). Bulk courier assignment remains explicitly unbuilt — `BulkActionType` has no such member (ADR 0039's own scope) — the bar states this rather than offering a button that would 400. | M | — | P07 |  |
| `1.1g` | Late-order highlight / severity overlay | P | BUILT | — | XL | — | P06 |  |
| `1.1h †` | Backward status transitions (gated, reason-required, audited) | P | BUILT | The missing console affordance is built (wave 9 w1): a reason-picker dialog (`q-order-outcome-reason-dialog`, reusing the tenant's active CANCELLATION registry) in both the order detail pane's header/overflow and the queue row menu, confirming the exact compensating edge the clicked `actions[]` OVERRIDE entry already named rather than letting the operator choose a target. Submits through `OrderActionsApi.override` (`POST .../state-overrides`, If-Match + a per-order Idempotency-Key). Tested both ends: `OrderActionsApi`'s own spec (body/If-Match, STALE_VERSION retry), an `OrderDetailPane` and an `OrderQueue` describe block each (fetch-before-open, submitted target/reason/version). The mandatory reason still reuses `OutcomeReasonKind.CANCELLATION` rather than a dedicated kind — unchanged, not this wave's scope. | XL | — | w1-queue-override-filters |  |
| `1.2` | Order detail — card, lines, money, customer, address | P | BUILT | Batch 14: the header names the table of a dine-in order beside the order number and status (`q-order-table-chip` over `OrderSummaryResponse.table`, resolved for the single order by `OrderQueryService#tableFor` through the same `OrderTablesPort` the board batches, so the two screens cannot disagree; only asked for a DINE_IN order). Nothing renders for a delivery, a pickup or a dine-in order nobody seated. Tested both ends (`OrderTableVisibilityHttpTests`' board-and-detail case, `order-detail-pane.spec.ts`). Batch 15: the chip now also appears for an order an operator keys in as DINE_IN, from the first read after `Создать` — the party is named in the placement itself, so `tableFor` finds the table at once (`1.3`). The header's late state draws in the tenant's own colour for a LATE order (`X.39`); that path has a call site here and no spec of its own. | M | — | w6-dine-in-operator-flows |  |
| `1.2a †` | Server-supplied actions[] capability array | P | BUILT | — | S | — | P05 | BUILT |
| `1.2b` | Status timeline with per-stage clocks (three lanes) | P | BUILT | All three lanes render — commercial (unchanged), production (from `kitchen.ticket_events`) and delivery (from the shipment's own custody timestamps) — with elapsed durations and filled/hollow/muted marks, plus a lane for the losing side of any approval decision. `q-steps` (the two new lanes) has no actor slot, unlike `q-timeline`; actor resolution is carried by the commercial and losing-decision lanes only. | L | — | P11 |  |
| `1.2c †` | Amendments — add items, edit customer/address/type/pre-order time, change payment type | P | PARTIAL | Six of the seven financial commands carry `built()=true` on `AmendmentCommandType` and have a real console dialog each — `ADD_LINES` (increases only, reusing the New Order composer's search), `CHANGE_LINE_QUANTITY` (increase only — a decrease still has no ADR 0017 return/write-off primitive), `CHANGE_PAYMENT_METHOD` (`CASH` at either end, or opening a fresh online intent), `CHANGE_DELIVERY_ADDRESS` (repriced through `CartService#price`, refused out of zone), `CHANGE_FULFILLMENT_TIME` and `CHANGE_CONTACT` — all wired into `order-detail-pane.ts`'s amend menu beside the five pre-existing non-financial commands, each proved through `OrderAmendmentAndOutcomeTests` and `OperationsOrderControllerAmendmentsHttpTests` (the amend endpoint's first HTTP-level test class, which caught `AmendmentRefusedException` falling through to a bare 500 instead of the 409 every sibling refusal returns, since fixed). Only `REMOVE_LINES` stays refused by name — releasing or writing off already-committed stock needs a fourth ADR 0017 primitive (return-to-stock or write-off) beyond the port's deliberate three (hold, commit, release), a decision no wave makes. Batch 15 fixes the money bug batch 14's audit found (ADR 0072 x ADR 0039). `OrderAmendmentService#repriceFor` handed pricing a null coupon, so every amendment that reprices (added lines, a quantity increase, a new delivery address — `needsReprice()`) priced a discounted order at full price, and the increase the customer was asked to confirm included the promo they had already earned. The reprice now carries `carriedRedemptionOrderId` (the order's own id): `QuoteService` loads the redemption that order's checkout recorded from `pricing.coupon_redemptions` by order id — never the cart's mutable `applied_coupon_code` — presents its promotion by id even if the code has been retired since, does not re-run the coupon's caps or window for the order's own slot (a one-per-customer code is always at its cap after its own checkout), still evaluates the promotion's own conditions on the amended basket, takes no second redemption, and `restateForOrder` only restates the existing row's amount so cancellation still releases it. The fix round added V0438, the partial unique index `ux_redemption_order_live` on (tenant, order) where REDEEMED, because the two lookups an amendment makes had no index and 'one live redemption per order' was an assumption, not a rule. The decision is recorded in ADR 0072's implementation status. Tested and green in batch 15's run: `OrderAmendmentPromoDiscountHttpTests` (a promo-coded order placed through the customer path, then a proposed and confirmed ADD_LINES over HTTP: the operator is told +45,000, not +60,000; the applied order reads back with the discount and reconciles; the coupon's one slot is not taken twice), two `OrderAmendmentAndOutcomeTests` cases (the discount is kept; a code retired since placement still discounts), `CouponRedemptionOrderLookupTests`, `PromoCodeTests`. That sentence's last gap is closed in batch 18 (`w3-promotions-completion`): `OrderAmendmentPromoDiscountHttpTests` (3, green in this audit's run) now drives `CHANGE_LINE_QUANTITY` (a quantity increase keeps the promo-code discount and scales it to the larger basket) and `CHANGE_DELIVERY_ADDRESS` (the delivery fee is repriced, the discount kept, the order still reconciles) over HTTP as well as `ADD_LINES`, and `OrderAmendmentAndOutcomeTests` (92) is green. Three other things changed the commands. `ADD_LINES` takes a combo (`w4-combos-completion`, ADR 0136): a search result that is a combo container opens the composer's own `q-combo-picker-dialog` for the groups of the menu the order was placed through, the picks go with the line as `comboPicks`, two different combos stay two lines and the same picks twice are one line of two combos, and a menu that cannot be read says so; an added or a grown combo reaches its stations through `KitchenAmendmentListener` (V0477) even when the ticket is already open (proved in `ComboOrderFlowEndToEndTests`, 31, with `KitchenExecutionTests`, 65, and `AmendmentBasketTests`, 14, green beside it). `CHANGE_LINE_QUANTITY` takes a decimal for a splittable dish (`w5-attributes-fiscal-tab`, ADR 0137): the order read carries `portionSize` for such a line while the order can still be amended, and `order-change-quantity-dialog` starts one portion above the line, steps by it, sends a decimal and refuses what is not a whole number of portions, where a dish with no portion step keeps whole units (`DecimalAmendmentHttpTests`, 10: half a portion can be added and half a can cannot, a whole quantity sent as a bare integer still amends, a decimal cannot shrink a line either). And the review round closed two doors: the commands in an amendment request are now validated, so a missing `ADD_LINES` quantity is a 400 and not a 500 and a quantity with an absurd exponent is refused before it is expanded (`OrderAmendmentQuantityBoundsTests`, 4), and `CHANGE_PAYMENT_METHOD` refuses a method that takes the money first for an order holding a weighed line (`WEIGHED_LINES_PAY_AT_HANDOVER`, see `4.2c`). Held at PARTIAL for the one remaining command, `REMOVE_LINES`, and for the decrease of a quantity, which has no ADR 0017 return/write-off primitive either. | XL | Removing/decreasing a line needs an ADR 0017 primitive (return-to-stock or write-off) the port does not have; that module's decision, not this wave's. | w3-promotions-completion | PARTIAL |
| `1.2d` | Multi-step (multi-branch) composition with per-step status | ? | NOT BUILT | An order cannot be split across two branches, and that is a **decline, not a gap**. The IA amendment this row used to ask for is done: [the frontend information architecture](frontend-information-architecture.md) §1 row `1.2` shows the clause struck through with a dated note (2026-09-11), and [orders.md](operations-spec/orders.md) §0.1 now states the decline with its reason (two nested state machines and per-step money for a behaviour no evidence shows anyone using; the parity matrix cites ADR 0019 and ADR 0002) and the schema that agrees with it (`ordering.orders.location_id` is `NOT NULL` under `fk_order_location`; `trg_carts_no_rebinding` refuses to move a cart, which is rebuilt instead). This legend defines no DECLINED status, so the row keeps NOT BUILT. | S | — | w8-declines-and-docs |  |
| `1.2e †` | Assign in-house courier / call external courier from the order | P | BUILT | Assign/unassign an in-house courier from the order detail pane (`DispatchApi`, reusing `ManualDispatchService`) and, since wave 9 w5, calling an external provider from the order — `POST .../orders/{orderId}/external-courier` (`OrderDeliveryController`, reusing `ManualExternalBookingService.quote`/`.book`, gated `DELIVERY_MANUAL_ASSIGN`, idempotent per intent) wired into `order-detail-pane.ts`'s `q-external-courier-dialog`. Tested both ends: `OrderDeliveryExternalCourierHttpTests` over real HTTP (QUOTE/BOOK phases, capability refusal, Idempotency-Key), `order-detail-pane.spec.ts`'s dialog cases. A fix-round bug (the QUOTE phase 400'd on a missing `reasonCode` the frontend never sends) was caught and fixed before merge. | L | — | w5-external-dispatch |  |
| `1.2f †` | Provider quote-delta confirmation (the Millenium pattern) | P | BUILT | The Millenium seam (quote against the customer estimate, accept-at-persisted-price-only, abandon, `DELIVERY_COST_SUBSIDY` on accept) is wired to «Вызвать курьера» on both named surfaces now: the order detail pane (pre-existing) and, this batch, the dispatch board's own unassigned pool cards, reusing `q-external-courier-dialog` verbatim over `DispatchApi`'s plan-keyed `externalPartners`/`externalQuote`/`externalBook`. A PARTNER-sourced card renders the provider's own booking state. Tested both ends (`dispatch-board-page.spec.ts`). | L | — | w4-dispatch-couriers |  |
| `1.2g †` | Cascading cancel at the provider | P | BUILT | Both the automatic cascade (cancelling an order calls `ShipmentCancellationPort.cancelForOrder` after the order's own commit, surfaced in the order detail's notice/delivery-exception bands) and, this batch, the dedicated `DispatchController` endpoint (`POST .../shipments/{shipmentId}/cancel`, `Capability.SHIPMENT_CANCEL`) now have real console callers: a reasoned cancel-shipment action (`q-confirm-dialog`, a fixed audit reason code, If-Match/Idempotency-Key via `DispatchApi.cancelShipment`) on both the dispatch card and the order detail's Курьер panel, refreshing the delivery-exception band afterward. Tested both ends (`dispatch-board-page.spec.ts`, `order-detail-pane.spec.ts`). | M | — | w4-dispatch-couriers |  |
| `1.2h †` | The three comment channels (customer→order, customer→line, operator→kitchen) | P | BUILT | Residue: courier and internal notes have no materialized "current value" column (zero migration budget this wave) — the §3.6 «Комментарии» block shows "None yet — see history" for both until the operator opens the amendment history (`OrderAmendmentService`); the current value is always readable there, just not inlined a second time. | L | — | P10 | NOT BUILT |
| `1.2i †` | Print to POS, and POS integration errors with a fix path | P | BUILT | The read/push/retry half (unchanged from the prior wave) is joined this batch by the fix path itself: `order-detail-pane.ts`'s POS export section gets a "Fix the mapping" link, shown only when the server's own live re-check (`PosOrderExportService#findUnmappedEntity`) finds a currently-unmapped variant or modifier — never merely because a stale `lastErrorCode` names the gap. It navigates to `/catalog/import?entityType=&focusHorecaosId=`, opening straight to the right mapping tab with the offending item pre-selected (`MappingPane.focusHorecaosId`), and a fix-round bug (the deep link carried no `bindingId`, so a tenant with more than one POS binding could land on an unrelated branch's mapping pane) was caught and fixed before merge — `UnmappedEntity` now carries the resolved binding through. `MappingEntityType` also gained `VARIANT`/`MODIFIER` granularity server-side to back it. `POS_EXPORT_READ`/`POS_EXPORT_RESOLVE` staying `TENANT_OWNER`/`TENANT_ADMIN`/support-session-only is unchanged and, as before, consistent with `P12`'s own money-panel pattern, not a regression. Tested both ends: three new `MappingPane` specs, two new `catalog-import-page` specs, three new `order-detail-pane` specs, `PosProviderCapabilityCatalogTests`, `JdbcPosMappingStoreTests`, `OrderPosExportControllerTests`. | XL | — | w7-integrations-pos-fixpath |  |
| `1.2j` | Complete with completion reason | P | BUILT | — | M | — | P09 |  |
| `1.2k †` | Cancel with reason + write-off type | P | BUILT | Order detail's reasoned Cancel and the board's row-level Cancel both go through the same registry-backed reason dialog: `order-queue.ts`'s `requiresCancellationReason(status)` routes CONFIRMED/PREPARING/READY/FULFILLING rows to `openCancelReasonDialog` (the reasonless dialog only for the earlier statuses that need no reason), so the "still uses the old free-text dialog" gap this row carried was already closed on `main` before this batch (`bugfix-operations-console`, 2026-09-22) and simply never credited here — corrected on verification, not new work. Tested: `order-queue.spec.ts`'s "reasoned cancel from CONFIRMED onward" describe block. | M | — | P09 |  |
| `1.2l` | Оплата panel and manual re-fiscalize on the order | P | BUILT | Two self-contained panels (`OrderPaymentPanel`, `OrderFiscalPanel`) are mounted on `order-detail-pane.html` between the handover section and the timeline — tender, attempt history, re-presentation (payment link/invoice push, QR) from the existing `PaymentsApi`, and fiscal document state plus manual re-fiscalize (`retry`, gated on `FiscalDocumentService.retry`'s own refusal rule) from the previously-dead `FiscalApi.forOrder`. Every status renders through a localized label, never a raw enum token. Unblock is deliberately not offered. | M | — | P12 |  |
| `1.2m †` | Handover-code state | P | BUILT | — | M | — | P09 | NOT BUILT  |
| `1.2n` | Both delivery money fields (charged to customer vs billed by provider) | P | BUILT | Provider-billed cost is honestly null/"not tracked" when `fulfillment.delivery_cost_subsidies` recorded no gap for that order (no other figure records an exact provider invoice at order level) — a schema limit, not a missing render. | M | — | P11 |  |
| `1.2o` | Change-due (Сдача) | P | BUILT | — | S | — | P09 |  |
| `1.2p †` | Ревизии — the revision chain | P | BUILT | — | S | — | P09 | NOT BUILT  |
| `1.3 †` | New order — the call-centre order-entry screen | P | PARTIAL | The three-pane composer at `/orders/new` takes DELIVERY (structured address, `1.3b`), non-cash payment (the channel's own matrix, `1.3e`) and, from batch 15, DINE_IN. Batch 8 closed a `«Позже»` pre-order time with an out-of-hours confirm/refuse (`1.3d`) and create-on-miss for `LOCATION_STAFF`/`LOCATION_MANAGER` (`1.3a`), and added a running delivery-fee preview. Batch 13 replaced the static «по зоне» `Филиал` caption with a real cross-branch resolver (`BranchResolutionQueryService`, ADR 0037): DELIVERY candidates ranked by the brand's own winning delivery zone, PICKUP candidates by live load (reusing `0.1c`'s leaderboard counts), a curated override-reason picker and a `ChangeDocuments`-audited override fact; `OperatorOrderingService#place` re-derives the resolver's proposal server-side and never trusts the request body for it (`NewOrderBranchResolutionHttpTests`, `OperatorOrderingServiceBranchOverrideTests`, `new-order-page.spec.ts`). Batch 15 adds the DINE_IN mode (ADR 0047, operator side). A table picker (`dine-in-table-picker.ts`) replaces the address pane: a party already seated (`GET .../dine-in/sessions`), or a free table and a party size to seat one from here (`POST`, the endpoint the reservations screen seats a booking through). `Создать` places the order at the operator's own branch (a table is a room, so the resolver is not asked and a pre-order time is discarded) and names the party in the same request: `POST .../orders` takes an optional `dineInSessionId` (DINE_IN only), the handler checks `dinein.session.manage` at the branch first (one annotation declares one capability), `OperatorOrderingService#place` refuses a session that is not live at the branch before anything is created (`SESSION_NOT_LIVE`, 409; a session of another branch answers 404) and attaches the order to the bill in the same transaction, so it is on a bill or does not exist. The first cut placed the order and then POSTed a round as a second call; a party closed in between left a cooked order on no bill, with nothing in the console able to attach it later, and the fix round moved the attach into the placement (`TableSessionsApi.attachRound` and its path builder now have no caller). Tested and green in batch 15's run: `CartCheckoutAndOrderTests`' operator-order cases (against a recording port: the attach happens on the placement's own connection after the order row is written; a party that left refuses with nothing created), `DineInTests` (the real port), `OperationsOrderControllerDineInSessionTests` (the second capability, mocks), `new-order-page.spec.ts`, `dine-in-table-picker.spec.ts`, `table-sessions-api.spec.ts`. Batch 18 (`w1-order-entry`, with `fix18-a-order-entry`) closes the two gaps that paragraph named and the old note about the total. The one-request placement is now proved over HTTP through the real wiring: `OperatorOrderEntryHttpTests` (14 cases, green in this audit's run, a real Postgres under the production controller, service, cart, pricing and dine-in adapter) places an order for a seated party and reads it back on that party's bill with its table on the board at once, refuses it with `SESSION_NOT_LIVE` when the party has left and as a 404 when the party is another branch's, and in each refusal creates nothing. The party the screen seats can be closed from the console: the table picker mounts `q-party-close` («Закрыть стол»), which reads the bill first and never guesses, closes an empty party with one confirmation (`state-actions` to `CLOSED`), closes a party that owes through «Гости оплатили» (the same `CLOSED`, with the amount named in the confirmation because the audit trail carries it) or, for a holder of `dinein.session.force_close`, through «Гости ушли, не заплатив» (`force-closures`, a reason code and an audit record that carries the unsettled amount), steps a party whose guests asked for the bill through `SETTLING` first, and settles only the bill the operator saw: the bill is read again at the second confirmation and the close is conditional on that read's version, which moves whenever a round is attached (`f04921b3`). See `10.2d`. The total beside «Создать» is the server's own price, see `1.3e`. Tested and green in this audit's run: `OperatorOrderEntryHttpTests` (14), `CartCheckoutAndOrderTests` (217), `OperatorOrderingServiceBranchOverrideTests` (14), `DineInTests` (46), `TableSessionControllerHttpTests` (18), `OperationsOrderControllerDineInSessionTests` (2), and `new-order-page.spec.ts` (87 cases), `dine-in-table-picker.spec.ts`, `party-close.spec.ts` (24), `table-sessions-api.spec.ts`. Still missing: the address pane's map pin, suggest and geocoder (`1.3b`, which waits on ADR 0145, Proposed: the map provider the owner has not decided), and the IA row owns them. No server-side draft cart or quote-expiry: `OperatorOrderingService.place` prices and checks out atomically, so the IA's header draft-timer states do not apply to this backend shape at all (the new quote endpoint prices a basket and undoes it; it does not hold one). A stale code comment to correct on the next touch: the class comment in `new-order-page.ts` ('Still not built, honestly') still says the branch is a static «по зоне» caption and that no preview of the price exists; the first was closed in batch 13 and the second in this batch. | XL | The address pane's map pin: `1.3b`, ADR 0145 (Proposed). | w1-order-entry | NOT BUILT |
| `1.3a †` | New order — customer pane (phone lookup, auto-create, order-history peek) | P | BUILT | Phone lookup, candidate list, select-to-attach, create-on-miss (location-scoped `CUSTOMER_CREATE`, batch 8 w2) and an itemized order-history popover with a working «Повторить» are all built and tested. This batch closes the one remaining gap, shared with `1.3f`: a new `CustomerOrderReorderController` (`ORDER_READ` at `LOCATION`, not `BRAND`) gives `LOCATION_STAFF` — this screen's primary persona — its own route to the reorder plan, resolved against the location's own menu rather than the order's original one; the frontend's `operationsPaths.customerOrderReorder` now builds this LOCATION-scoped path. Tested: `CustomerOrderHistoryReorderHttpTests` (LOCATION_STAFF reaches the endpoint; the same grant at a different location still 403s; an ungranted principal is refused), a `CartCheckoutAndOrderTests` case proving the plan resolves against the given location's own menu, and `operations-paths.spec.ts`. | M | — | w1-order-board-actions |  |
| `1.3b †` | New order — address pane (map pin/search + дом/квартира/подъезд/этаж/ориентир) | P | PARTIAL | The structured address half is now built: a saved-address list/select behind the existing `CUSTOMER_PII_REVEAL` reveal, an inline add form (дом/квартира/подъезд/этаж/ориентир), recipient name/phone/note, and `DestinationRequest` threaded into `placeOrder`, saving `NOT_GEOCODED` or `LANDMARK_ONLY`. The branch selector is still a static label + "(by zone)" caption, not a real resolver. `LOCATION_STAFF` — this screen's primary persona — lacks `CUSTOMER_PII_REVEAL`, so the saved-address list 403s (handled gracefully, not a crash) for that exact role; only `LOCATION_MANAGER` can use it today. Batch 17 drafts the decision the pin, the suggest and the geocoder wait on, ADR 0145 (Proposed, Not started); nothing on this screen changed. | XL | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. ADR 0037's routing-provider input is now ADR 0147 (Proposed). | w9-decision-adrs | BLOCKED |
| `1.3c †` | New order — item search and the interactive full-screen menu/basket | P | BUILT | — | L | — | P13 | NOT BUILT  |
| `1.3d †` | New order — pre-order time with out-of-hours branch-resolution warning | P | BUILT | A «Позже» toggle asks for `requestedFor`; the backend validates it against the branch's own hours (`CheckoutEligibilityGuard`) and refuses `BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM` (rendered as an inline "place anyway?" confirmation) or `BRANCH_CLOSED_AT_REQUESTED_TIME` (no confirmation offered) when the branch's own policy refuses the slot outright. Batch 8 w2, tested both ends (`new-order-page.spec.ts`, `CartCheckoutAndOrderTests`, `OrderPromiseTests`). Nothing for this row's own two capabilities; ADR 0019 leaves the deeper scheduled-order policy open — a lead-time limit, a repricing checkpoint, or payment-authorization timing for a long-lead pre-order — `requestedFor` only ever asks "is the branch open then", never holds a price or a slot for the wait. | M | — | w2-new-order |  |
| `1.3e †` | New order — operator-channel payment/order types, promo code, change-due | P | BUILT | The operator sees the price the order will be booked at before pressing «Создать», and «Сдача» is right. `POST .../orders/quote` (`OperationsOrderController#quoteOrder`, `ORDER_PLACE` at the branch, an `Idempotency-Key` the screen mints fresh on every call) takes the body «Создать» would send and runs it through `OperatorOrderingService`'s own cart, destination, promo-code and payment steps and `PricingEngine`, inside a `REQUIRES_NEW` transaction that is always rolled back (`OperatorOrderQuoteService`). It therefore applies every rule the placement applies (the menu, the sale windows, stock, the combo and modifier rules, the promo code's eligibility, the delivery zone) by the same code, and it keeps nothing: no cart, no stored quote, no redemption (typing a code to see what it is worth does not use one up), no outbox row. The answer is the subtotal gross of the discount, the discount with each reduction named, the delivery fee and how its resolution ended (a basket short of the zone's minimum says by how much), tax, total, and a `provisional` flag while a line is sold by weight, all in integer minor units and a currency. `new-order-page` asks for a quote once the operator stops editing, shows the server's total (the menu arithmetic is labelled an estimate until the answer arrives and retired the moment the basket changes, and a slow answer for a basket that has since changed is dropped), says in words why the server will not price a basket instead of letting «Создать» find out, and computes «Сдача» as the tender minus that total, negative when the tender is short. The cash a customer will hand over is sent with the order (`cashTenderedMinor`, boxed and optional because Jackson 3 refuses a missing primitive, CASH only) and is recorded in the transaction that creates the order, so the change due is there from its first read; a tender short of the total still creates the order and the answer carries the notice `CASH_TENDERED_INSUFFICIENT`. Payment reads the operator channel's own matrix (`SalesChannelsApi.matrices`, `CHANNEL_READ`) and a promo code typed on the screen is applied between filling the basket and pricing it, as before; the amendment half of the old subtotal defect was closed in batch 15 (`1.2c`). Tested and green in this audit's run: `OperatorOrderEntryHttpTests` (14, of which seven are this row's: the quoted price is the booked price, promo code and all; a delivery quote carries the fee the order is then booked with; a quote is refused for what the order would be refused for and still keeps nothing; a quote needs the right to place at the branch it is priced at; the cash tendered is on the order from its first read with the change due; a short tender is a notice; a body that names no tender sends none), `CartCheckoutAndOrderTests` (217), `OpenApiContractTests` (6), and `new-order-page.spec.ts` ('the server's price', a describe of eleven cases within the 87) and `new-order-api.spec.ts`. Residue: the page and the endpoint are each proved with the other mocked at its edge (the page spec fakes `NewOrderApi`; the HTTP tests send the page's JSON by hand), the repository's usual both-ends proof and not one test through a browser; and `new-order-total.ts`'s menu arithmetic stays as the labelled fallback for the moment before the answer and for a read that failed. Moved PARTIAL → BUILT. | M | — | w1-order-entry |  |
| `1.3f †` | New order — repeat / re-order | P | BUILT | The staff-capability reorder wrapper now has a LOCATION-scoped route: `CustomerOrderReorderController.planForAtLocation` (`ORDER_READ` at `LOCATION`), which `LOCATION_STAFF` — this screen's primary persona — holds, replacing the `BRAND`-scoped wrapper that 403'd for that exact role. Wired to the New Order screen's «Повторить» button (`operationsPaths.customerOrderReorder` now builds the LOCATION-scoped path), adding every `AVAILABLE` line straight to the basket, resolved against the chosen location's own menu. Tested: `CustomerOrderHistoryReorderHttpTests`, a `CartCheckoutAndOrderTests` service-level case, `operations-paths.spec.ts`. | M | — | w1-order-board-actions |  |
| `1.3g` | New order — manual aggregator order creation with no live provider binding | P | PARTIAL | Manual aggregator entries (unchanged: `AggregatorOrderIntakeService`, all four V0038 authority columns, reconciled header subtotal) are no longer PICKUP-only: `fulfillmentMode` may now be DELIVERY, reusing the exact structured, saved-address `DestinationRequest` the New Order screen's own address pane already sends — no second, untyped address path. The order is written already CONFIRMED and `DeliveryPlanner#planFor` runs directly, opening the same plan/sourcing job a native order's confirmation opens. Tested both ends (`AggregatorOrderIntakeServiceTests`'s DELIVERY cases, the New Order screen's aggregator toggle). Still writes no `order_external_pricing`/handover-challenge evidence a manual entry would need for settlement — ADR 0114's own open inputs, unchanged. | L | — | w5-fulfillment-destination |   |
| `1.4 †` | Drafts and abandoned carts | 2 | BUILT | Correct abandonment counting, period/channel/owner-type filters and the location/expires_at columns are unchanged from batch 10. This batch closes both of the row's remaining gaps: the drafts table now shows each cart's first line's product name (`JdbcCartStore.listDrafts` returns the first line's `variant_id`, resolved through `catalog.api.ItemDisplayLookup` — one extra call per page, not per row, not a SQL join `JdbcCartStore`'s own doc had ruled out), and cart abandonment now hands off to an ADR 0044 recovery audience through the CART_ABANDONMENT automation trigger (row 6.5), which checks `AbandonedCartDirectory` before firing. Converting a cart into an order remains deliberately not offered — the IA names only display and recovery for this row, never conversion, and nobody agreed to that basket. Tested both ends (`OperationsOrderControllerActionCapabilitiesTests`, `drafts-page.spec.ts`). | S | — | w8-marketing-automations | BUILT |
| `1.5` | Reservations — day plan, create, confirm/reject/cancel/no-show, amend | 3 | BUILT | The day window is now bound to the location's own `DINE_IN` service schedule (`LocationsApi.serviceSummary`/`.profile`) instead of a hard-coded 08:00–23:00 browser-local window — bookings past 23:00 render, and a documented Asia/Tashkent 08:00–23:00 fallback covers a location with no schedule bound yet. Guest name/phone/note are revealable and correctable via `amend`, behind a stated purpose and an ADR 0027 audit fact; single-booking read/reveal/state/amend are now location-scoped, not just tenant-scoped (fix4). Residue: `selectedDate()`'s initial default (before the timezone loads) still reads the browser's UTC calendar day, a narrow day-boundary edge case. | M | — | W01 |  |
| `1.5a †` | Reservations — seat/complete, external reservation ID, auto-create client on unknown phone | 3 | BUILT | Seat-this-booking opens a table session (`TableSessionController`'s first frontend caller), moving the reservation `CONFIRMED → SEATED`; `COMPLETED` is reachable from a `SEATED` booking's state-actions. Session currency is a documented fixed 'UZS' constant (ADR 0055: single-currency pilot) pending a real read at this scope; since batch 15 it is one shared `SESSION_CURRENCY` that the reservations screen, the floor plan's «Seat walk-in» (`10.2d`) and New order's table picker (`1.3`) all use, so the three cannot drift. Batch 15 also fixes a trap in the shared `TableSessionsApi.open`: the platform stores a business rejection under its `Idempotency-Key` like any settled outcome, so a host refused `TABLE_OCCUPIED` who clicked again after the party left was sent the same 409 back; the client now releases the intent after a settled 4xx (not 408, 429 or 'still in progress') and keeps it for outcomes it cannot know (network failure, 5xx). Auto-create-client-on-unknown-phone and external-reservation-ID were struck from this row by ADR 0047 on 2026-09-11 and are not built, per that decision — not a gap. Batch 17 (`w8-walk-in-sessions`, ADR 0143) adds what a guest's self-seated table looks like to the host: the floor plan marks a table a guest seated themselves at (origin `GUEST_QR`), shows the unconfirmed claim with its expiry and offers «keep» (`POST .../claim-confirmations`, `dinein.session.manage`, the session's version as `If-Match`) or «release» (the table session's `state-actions` call to `CLOSED`, the first the console makes), and the reservations screen warns the host when an amended booking now holds a table with a party sitting at it. Tested and green in this audit's run: `WalkInSeatingTests` (39), `WalkInSeatingHttpTests` (20), `TableSessionControllerHttpTests` (13), `floor-plan-pane.spec.ts`, `reservations-page.spec.ts`, `table-sessions-api.spec.ts`. Not built, each deferred by the record to a named owner or an amendment: a realtime `FLOOR` signal to the host stand (it needs an ADR 0045 amendment), and the plan's «booked soon» warning still uses its advisory 90-minute window and not the branch's `walk_in_horizon_minutes`. | L | — | w8-walk-in-sessions | NOT BUILT |
| `1.6` | Call centre — presence, screen-pop, call log | 3 | BUILT | `POST .../call-provenance` now has a real caller — `new-order-page.ts`, wired from a claimed call via `?callEventId` — closing the join between a claimed call and the order it produced. `roster()` is no longer dead code (a team board on the call-centre page, gracefully hidden without `VOICE_PRESENCE_READ`). Presence and the screen-pop poll moved into one shared service (`shell/voice-presence.ts`) so the call bar and this page never race two intervals. Both adapters are still proven only against a fake PBX — no provider account exists — and the softphone is a recorded decline (ADR 0064, struck in [the frontend information architecture](frontend-information-architecture.md) §1 row `1.6`, see PART B); click-to-call is a different fact, is not built, and waits on a provider call-control adapter (row `1.6a`). | S | — | w8-declines-and-docs |  |
| `1.6a` | Call centre — softphone and click-to-call | 3 | NOT BUILT | An operator cannot dial from the console and keeps whatever handset they already have. The **softphone is a recorded decline**: ADR 0064 (Accepted) has the platform never carry audio and its Alternatives table refuses a softphone/WebRTC inside operations; the «Owns: softphone» line in [the frontend information architecture](frontend-information-architecture.md) §1 row `1.6` was struck 2026-09-11 and that row's note was clarified 2026-09-30 to say click-to-call is not built rather than struck, and [orders.md](operations-spec/orders.md) §0.5 records the ruling (2026-09-30). **Click-to-call is a different fact and this row had folded it in:** ADR 0064 does not name it. It would be a provider call-control capability, and neither shipped VOICE adapter declares one (`VoiceProviderCapabilityCatalog` lists `INGEST_EVENTS_PUSH` only), so it is not built and waits on such an adapter, not on a decision. No DECLINED status exists in this legend, so the row keeps NOT BUILT. | S | — | w8-declines-and-docs |  |
| `X.1` | Operator inbox (conversations) — not an IA row, but shipped in this section of the app | ? | BUILT | No needs-attention count badge on the rail entry (ADR 0059's own named gap — no counts service exists for it), and Telegram is the only channel adapter. The IA has no row for this screen at all, which is itself a documentation gap. | S | — | — |  |

## §2 — Kitchen (device shell)

16 rows — 12 built · 3 partial · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `2.1 †` | Kitchen queue (KDS) — the live board | P | BUILT | A cook can reveal a line's note on demand, the KDS has a real `aggregator` tab, and tab/board counts are exact server-side (unchanged from batch 10). Batch 11 closed the row's three remaining gaps: `KitchenTicketService` publishes an ADR 0045 `KITCHEN_BOARD` realtime signal on every ticket/item mutation, the desk-console `kitchen-queue-page.ts` subscribes to it through the shared `RealtimeClient` (a signal or a resync shortens the 10s poll rather than the poll standing in for the stream), and a `/wallboard/kitchen` touch shell — hosted outside the console Shell like the existing wallboard — gives a tablet or wall display every finger-sized action with no hover-only affordance and its own offline banner that names how long the board has actually been stale. Tested both ends (`KitchenExecutionTests`' `KITCHEN_BOARD`-publish cases, `kitchen-queue-page.spec.ts`, `wallboard-kitchen-page.spec.ts`). Batch 14 added the table to the ticket: `KitchenBoardController` resolves it for DINE_IN tickets only, through `OrderTablesPort` (one batch per board read, and the single-ticket read), and the desk console's kitchen queue, buffer and expo pages render `q-order-table-chip` on it; a mutation response carries no table, exactly as it carries no courier ETA, so the buffer keeps the last board read's chip across a hold or a reschedule (`mergeMutation`). Tested (`OrderTableVisibilityHttpTests`' kitchen-ticket case, `kitchen-queue-page.spec.ts`, `buffer-page.spec.ts`, `expo-page.spec.ts`). Batch 15: a hall ticket for an order an operator keyed in as DINE_IN now carries its table too (`1.3`), and a breached ticket is drawn in the tenant's own late colour on the queue and on both VDUs (`X.39`); the queue has a spec for the colour, the two VDUs have a call site and none. Still missing, and wider than batch 14 said: the `/wallboard/kitchen` touch shell, the kitchen VDU (`/kitchen/vdu`) and the wallboard VDU (`/wallboard/vdu`) render no table — only the queue, buffer and expo pages use `q-order-table-chip`, which a search of the templates confirms — so the tablet at the pass and a wall display show a hall ticket with no table. | M | — | w1-ordering-money-audit, w6-dine-in-operator-flows |  |
| `2.1a` | Courier ETA on the kitchen ticket | P | BUILT | The provider's own `etaMinutes` survives Yandex's `/check-price` and Noor's `/orders/eval` through `CamelShipmentBookingPort.quote()`, is captured onto the plan the instant its quote wins, is joined onto the kitchen board via a new cross-module port, and renders as a chip on the ticket card (`kitchen-queue-page.ts`). It is a captured snapshot from the winning quote, not a live-updating feed — ADR 0014's checklist still lists live tracking and partner callbacks as open. | L | — | P11 |  |
| `2.1b †` | Preset product comments on a kitchen line | P | BUILT | The whole round trip is real: an operator (New Order's item-modifier dialog) or a customer (the storefront product screen) can attach a coded preset comment to a line, `CartService.putLine`'s `commentPresetCodes` argument validates each against `CommentPresetLookup#offeredCodesForVariant`, `order_line_comment_presets` snapshots the label at checkout, the KDS ticket pass renders each line's presets, and `PosOrderExportService` maps them to POS modifier codes through a `COMMENT_PRESET` entity type (unmapped refuses `MODIFIER_UNMAPPED`; a fix round closed a gap where `findUnmappedEntity` did not check preset mappings at all). Tested both ends across all three surfaces: `CartCheckoutAndOrderTests`, `OrderPosExportControllerTests`, `kitchen-queue-page.spec.ts`, `item-modifier-dialog.spec.ts`, `product.component.spec.ts` (storefront). Batch 15 (row `10.12`) widens the snapshot to the wording of a locale beyond ru/uz/en (V0433, `ordering.order_line_comment_preset_labels`) and serves it as `CommentPresetChip.labels`, and batch 16 (`w4-locales-tail`, row `10.12`) makes the console read it: the order detail, the KDS ticket, the New order dialog and the New order basket line share one `presetLabelFor` (the `labels` map for the console language, then that language's column, then the platform-resolved label, then the triple), so a wording beyond ru/uz-Latn/en is shown where the switch over three columns showed nothing. Tested and green in this audit's run: `locale-labels.spec.ts`, `order-detail-pane.spec.ts` (a chip reads the labels map), `kitchen-queue-page.spec.ts`, `item-modifier-dialog.spec.ts`, `new-order-page.spec.ts`. | L | — | w4-locales-tail |  |
| `2.1c †` | Assign own courier / dispatch an external provider from the KDS | P | BUILT | Assigning an in-house courier to a delivery ticket from the pass (`DispatchApi.assign`, joined to the board by `orderId`) and, since wave 9 w5, dispatching to an external provider from the KDS — `kitchen-queue-page.ts` reuses the same `q-external-courier-dialog` and the new order-keyed `OrderDeliveryApi.requestExternalCourierQuote`/`.decideExternalCourier` pair row `1.2e` uses. A fix-round bug (the KDS poll only refreshed a ticket's shipment state lazily, on a picker click, so a courier assigned elsewhere kept showing the wrong affordance until someone clicked it) was caught and fixed before merge — `refresh()` now also refreshes every visible ticket's shipment state. Tested both ends: `kitchen-queue-page.spec.ts`'s dialog and eager-poll cases, the same backend HTTP tests as `1.2e`. | M | — | w5-external-dispatch | NOT BUILT |
| `2.1d †` | Change payment type from the KDS | P | BUILT | `CHANGE_PAYMENT_METHOD` carries `built()=true` (wave 10 row `1.2c`) and the KDS ticket pass gets its own «Изменить оплату» action reusing the same payment-method picker the order-detail dialog renders, so a KDS operator and a console operator choose from an identical list. No pre-check: a refusal (`PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND` for a provider-paid order moving to another online method) surfaces as an `actionNotice` band after the attempt rather than a proactively greyed-out option — a documented simplification, not a gap. Tested: `kitchen-queue-page.spec.ts` (231 new lines). Batch 15 checked this row against the promo-discount fix (`1.2c`) and it is not touched by it: `CHANGE_PAYMENT_METHOD` is not a repricing command (`needsReprice()` is added lines, quantity increases or a new delivery address only), so a payment-type change carries the order's totals forward unchanged and a discounted order's delta stays zero. | S | — | w2-financial-amendments |  |
| `2.1e †` | Create an order from the kitchen | P | BUILT | The counter-sale button now routes to the real New Order screen (`/orders/new`, `P13`/`P14`'s screen) rather than a placeholder — the dependency this row inherited from IA 1.3 is resolved. | L | — | P16 | NOT BUILT |
| `2.2` | Buffer — held tickets and the kitchen fire time | 2 | PARTIAL | `PUT .../release-schedule` now reaches the UI (`KitchenApi.reschedule` + buffer-page's per-row editor, interpreted in the branch's own timezone after a batch-5 review fix), so hold and fire-time edits both work. Paid-only hold is still deliberately unmodelled — every held ticket is listed and release offered unconditionally. | M | Which payment fact gates a paid-only hold is unspecified — orders.md names no rule and ADR 0013's payment-method registry is itself partial; a product decision, not code. | T02 |  |
| `2.3` | Expo / handover (Раздача) | 2 | BUILT | Verify/bypass are wired with attempts-remaining and a supervisor bypass (`q-order-handover-panel`, embedded per ticket, gated on `MARKETPLACE_HANDOVER_VERIFY`/`MARKETPLACE_HANDOVER_BYPASS`), plus a packing check and a quantity-weighted per-department ready roll-up; hand-over is gated on both. | M | — | T02 |  |
| `2.4` | Display board (VDU) | 2 | PARTIAL | `TicketResponse.externalReference` reaching `board()` is unchanged from batch 10. This batch closes two of the row's three remaining gaps: a dedicated `GET .../kitchen/vdu` projection (ADR 0041 rollout step 4 — no orderId, no per-line ids, no dish name or note, deliberately narrower than the KDS read) now backs a new `/wallboard/vdu` touch shell outside the console Shell, with a station filter `<select>` that re-fetches narrowed to one station's own lines, oversized TV-legible type and the same freshness banner the kitchen wallboard gets. Tested both ends (`KitchenBoardControllerTests`, `wallboard-vdu-page.spec.ts`). A dedicated VDU device class is deliberately not added: ADR 0079 names `KITCHEN_KDS` as the only built pairing class and explicitly defers a VDU/EXPO bundle. Batch 17 drafts the decision: ADR 0151 (Proposed, Not started) adds `KITCHEN_VDU`, a read-only wall display with its own role and capability and a server-side display configuration. Until then `/wallboard/vdu` runs as a signed-in person, and the page reads more than the projection (the session context, the station list, the lateness policy under `order.read` and the push stream), so a device holding only the kitchen ticket capabilities could not run it as written. Drafting is not deciding. | M | ADR 0151 (Proposed 2026-10-01, Not started) — the platform owner has not decided it: a `KITCHEN_VDU` device class with its own role and capability, a server-side display configuration and an approval step that can narrow a request and never widen it. | w9-decision-adrs |   |
| `2.5` | Stop list — available/on-stop tabs, single and bulk | P | BUILT | A server-side batch stop/unstop endpoint (`POST .../inventory/variants/bulk-availability`, 200-item cap, per-item outcomes) replaced the sequential-PUT loop; a debounced search box and exact server-side tab counts (`GET .../variants/availability-counts`) replaced the 50-row-page estimate. Residue: the AVAILABLE/ON_STOP tab badges still read "…" while more of the catalog is loading for a large catalog, rather than an exact count up front (fix4's own deliberate cheaper alternative — a literal fix needs a new server-side availability filter on `variantsAtLocation`). | S | — | P16 |  |
| `2.5a †` | Stop scope (product × branch/menu/terminal/brand) and channel propagation | P | PARTIAL | A manager can now stop a dish for a whole brand, a menu, a channel or one branch, and every reader the platform owns honours it; what a stop cannot yet do is reach a marketplace. Built: `inventory.availability_stops` (V0462: scope `LOCATION`, `BRAND`, `MENU` or `CHANNEL`, with `TERMINAL` a named, refused value because «terminal» folds into channel; source `OPERATOR`, `BOT` or `POS`; an optional end; row-level security), read by one `AvailabilityResolver` that the storefront menu, the cart, checkout and the amendment hold, the stop list, New order's picker, the effective cross-sell preview and the single-variant read all go through (union precedence; a stop is judged at the reader's own instant, so the expiry sweeper is not on the correctness path), `ON_STOP` refusing at cart and checkout; the create routes (`inventory.stop.manage` at brand scope, `inventory.availability.manage` at location scope) and their lifts under `If-Match`, `InventoryStopChanged` v1 with its schema and catalogue entry, and the first producer of the `STOP_LIST` realtime channel; the POS poll writes and ends its own per-binding stop, so a POS «back in stock» no longer lifts an operator's. The console's stop list gains a scope picker (`stop-scope-panel`), a chip with a lift for each stop, the partly-stopped state, and a propagation banner (`stop-propagation-banner`) over `GET .../inventory/marketplace-propagation`. The outbound half exists as machinery: `MarketplaceAvailabilityReconciler` (level-triggered, the desired state recomputed from the resolver, `confirmed_available` unknown until a partner answers, leases, a resync sweep as the guarantee, a breaker and a rate-limit door per binding, V0463 and V0464) behind `MarketplaceGateway`. Tested and green in batch 17's run: `AvailabilityStopTests` (25), `InventoryStopControllerEndpointTests` (8), `MarketplaceAvailabilityReconcilerTests` (29), `MarketplacePropagationControllerEndpointTests` (4), `EventCatalogCompletenessTests` (135), and the `stop-scope-panel`, `stop-propagation-banner` and `stop-list-page` specs. Batch 18 (`w8-marketplace-stops`, with `fix18-h-marketplace`) builds the items that list named which need no adapter, and none of them can make a stop reach a marketplace by itself. **The partner pull.** `GET /api/v1/partner/tenants/{tenantId}/restaurants/{locationId}/availability` (`marketplace.availability.pull`, partner-bound: the aggregator's own client credential and the bindings its installation holds) asks the one resolver on every call, never a cache, and answers the partner's own item id and one boolean per mapped dish in cursor pages in the partner's id order; a branch bound to another aggregator answers exactly what an unknown one does, and a branch with no single active channel is a 409, never an all-available list (`PartnerAvailabilityHttpTests`, 10, over HTTP, among them a branch of another aggregator's installation answering 404). **The markers.** A `MENU_ITEM` mapping added, repointed, retired or removed marks its binding for an early reconciler sweep from a trigger on `integration.provider_entity_mappings` (V0487), and a sales channel pointed at or away from an installation, paused, reopened or retired publishes `SalesChannelInstallationChanged` (in-process), which the marker listener turns into the same early sweep. Both are accelerators; a test switches the mapping trigger off and proves the resync sweep alone converges (`MarketplaceAvailabilityReconcilerTests`, 50; `SalesChannelInstallationAnnouncementTests`, 5). **The events and the alert.** `MarketplaceAvailabilityPushed` and `MarketplaceChannelWentStale` on `integration.events` through the outbox (schemas, catalogue entries, docs rows; `EventCatalogCompletenessTests`, 141), and `MarketplaceStaleChannelMonitor` reports a binding with a dish unconfirmed past `marketplace.availability.stale_after_seconds` once per episode, with the ADR 0058 operations alert (`MARKETPLACE_CHANNEL_STALE`, V0488) and no dish name or partner error text in either. The review round made a sweep clear only the marker it read before it began, a binding that stops being worked end its stale episode, and a stop's end give back the position a materialisation run wrote for it. **Rollback switch three, the read-switch decommission.** `inventory.stops.read_enabled` (platform and tenant, on by default, hidden from tenants) turned off, the resolver, the stop list's overlay and the explainer ignore a brand's stops, which stay on their rows and resume when it is turned back on; a new operator or bot stop is refused as frozen meanwhile and the POS poll goes back to flipping the position boolean (`SwitchedPosStopPort`). The write is refused `409 MATERIALISATION_REQUIRED` by a `ConfigurationWriteGuard` until a finished materialisation run of every brand that has had a stop has been acknowledged by an `inventory.stop.manage` holder and carried every stop in force (a set of stop ids, not a timestamp comparison, so a stop that committed a moment after the run's read re-blocks it). The run writes each stop that has an exact position onto it with the stop's own source and the movement reason `EMBARGO_MATERIALISED`, and reports `UNTRACKED` and `QUANTITY` items, `CHANNEL` stops and menus published to only some channels (V0489; `StopMaterialisationTests`, 29; `StopMaterialisationControllerEndpointTests`, 3). **The explainer.** A 'Why?' button on a stopped or partly stopped row opens `q-stop-explainer-dialog` over the explain endpoint: whether the dish sells at this branch, on no channel in particular or on one the operator picks, the reasons (a stop covers it, sold out, the channel's cut-off, not stocked here), every stop that covers it with its scope, source, reason code and end, and a line saying so when stops are switched off. It is read-only; the lift stays on the row's chip, which quotes the version the list showed (`stop-explainer-dialog.spec.ts`, 11 cases; `stop-list-page.spec.ts`). Also green in this audit's run: `AvailabilityStopTests` (25), `MarketplaceConfigurationKeyTests` (5), `PosAvailabilityPollTests` (4). Why PARTIAL, unchanged and decisive: **no adapter for any named aggregator ships.** The only implementation of `MarketplaceAvailabilityAdapter` in the tree is still the test fake, so every real binding reads `MANUAL` («not propagated automatically»), `MarketplaceAvailabilityPushed` is never emitted by a real push, the stale alert has nothing real to watch, and a stop set here still never reaches Yandex, Uzum or Wolt; the pull gives an aggregator a way to ask, and nothing in the tree or the product plan says one will. Not built either: a console surface for the materialisation run and its report (the routes exist, `POST/GET .../inventory/stop-materialisation-runs`, `.../report`, `.../acknowledgement`, and nothing in `frontend/operations` calls them, so the switch can be thrown only through the API); the Phase 3 dry-run mode, which has nothing to compare against until an adapter exists; and the ADR 0006 failure that ADR 0040 says a stale channel raises, because ADR 0006 records failures that happened and has no concept of work that stopped arriving, so an operator-resolved record for it is a decision of its own. Moved NOT BUILT → PARTIAL in batch 17; batch 18 leaves the status. | XL | A marketplace adapter: which aggregators expose an availability write API to a third party is an open input of ADR 0141 (product), and ADR 0040's outbound adapters. ADR 0141 is Accepted. | w8-marketplace-stops |  |
| `2.5b †` | Stop source taxonomy and the "why can't I sell this?" explainer | P | BUILT | `stopSource`/`stopReasonCode`/`stopChangedAt` are surfaced end to end (derived from the latest `inventory.movements` row) with a Source column and hover detail on the stop list, so an operator can tell Kitchen/POS/stock-out apart without phoning the branch — rendered as a column + tooltip rather than a dedicated explainer dialog. Bulk stop's reason is now an enumerated `<select>` (fix4), not free text. | L | — | P16 | NOT BUILT |
| `2.5c` | Stop-change digest notification | P | BUILT | `InventoryOperationsAlertTrigger` now enqueues both directions to a durable queue (`notifications.inventory_stop_digest_entries`, V0298), and `InventoryStopDigestSweeper` raises one grouped digest per location (stopped + restored name lists) instead of one message per item — closing the previously-missing back-in-stock announcement. The cadence/chat-selection settings screen is still not built (out of this wave's own scope). | S | — | P16 |  |
| `2.6` | Capacity & buffer settings — throughput ceilings | 3 | BUILT | `PUT`/`DELETE .../station-capacity/{id}` are added, version-conditional and overlap-checked; deleting an overlapping window unblocks authoring the correct one. The IA's per-product ceiling is still absent — the ADR 0041-decided station-level model, unchanged and out of scope. | S | — | T02 |  |
| `2.6a` | Cook headcount output | 3 | BLOCKED | A manager cannot ask how many cooks the evening needs — the platform has neither a demand forecast to scale nor a stated portions-per-cook-hour policy to divide by, so the screen shows a ceiling and stops there. | XL | Two named inputs, both owner/product: the portions-per-cook-hour policy (varies by station and cuisine, nobody has decided it) and a demand-forecast decision — PART 3's wave 3 lists demand forecast as blocked on a product decision, not on engineering, and there is no analytics/forecasting ADR (PART 5 §7). | deferred |  |
| `X.2 †` | Kitchen device enrolment and device registry (no IA row exists) | ? | BUILT | Kitchen → Devices (`devices-page.ts`) lists enrolled devices, approves a typed user code, and revokes a lost tablet with a reason, behind `KITCHEN_STATION_MANAGE` — all three endpoints now have a caller. | M | — | P17 | NOT BUILT |

## §3 — Delivery

15 rows — 5 built · 9 partial · 1 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `3.1` | Dispatch board | P | PARTIAL | The courier-keyed drag board, bulk assignment, 'call an external courier' and the non-PII destination label (batch 11) are unchanged. This batch closes the SSE gap the prior audit named: `dispatch-board-page.ts` now subscribes to `RealtimeClient.onFrame` and refreshes at once on the `DISPATCH_BOARD` signal `ManualDispatchService`/`ShipmentCancellationService`/`ManualExternalBookingService` already publish (ADR 0045), with `q-connection-state-banner` showing the stream's own state — the 10-second poll survives only as the resync fallback the kitchen board (row 2.1) also keeps. A same-batch fix closed a real race the new accelerator exposed: four independent refresh triggers (poll, realtime frame, manual refresh, the assign/unassign `finally` block) could resolve out of order and silently revert the board to stale data (e.g. an assignment another dispatcher just made); a generation-counter guard, the same shape `order-queue.ts` already uses, now keeps only the most recently started refresh's result. Tested both ends (`dispatch-board-page.spec.ts`'s realtime-frame and out-of-order-resolution cases). Still missing: a map of points and routes. | L | `X.4`'s MapCanvas does not exist and the map provider is unchosen (ADR 0015 open input). | w5-dispatch-realtime-actions |   |
| `3.1b †` | `/deliveries` variant for courier-service tenants | ? | NOT BUILT | A courier-service company cannot use this console at all: there is no delivery-first landing route and no screen that treats a delivery (rather than an order) as the record being managed. | XL | Owner/product decision named as an open question in platform/docs/delever-parity-matrix.md (~line 661): 'Is HorecaOS one product or several product shapes on one platform?' — ADR 0002 has no business-type axis and adding one after launch is called out as expensive. | deferred | BLOCKED |
| `3.2` | Live map | 2 | PARTIAL | `accuracyMeters`, `headingDegrees` and `speedMps` are now rendered, a branch filter is added (reusing `CurrentLocation`), and the audited track-reveal path is now called end to end with its stated purpose. Positions still render as decimal coordinates in a table rather than on a map — MapCanvas is `X.4`, blocked on an unmade map-provider decision — and partner-courier pins remain correctly unbuilt, since ADR 0045 rejects that alternative outright. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. | L | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `3.3` | Couriers (in-house roster) | P | PARTIAL | `P19`'s compliance file, courier groups and branch bindings are unchanged. This batch closes both of the row's other two named gaps: courier-app access is now provisioned from the register form itself — `CourierAccountProvisioningService` creates the Keycloak account (`StaffAccounts#create`, phone-first) and links tenant-org membership the same way `StaffInvitationService` does for a colleague, so the operator no longer types a subject created outside the console (`OperationsCourierAccountProvisioningEndpointTests`) — and online/offline now renders on the roster and detail pane from `CourierLastSeenPort`'s read of the courier's most recent telemetry fix, compared against a new, genuinely-enforced `onlineWithinMinutes` policy field (`CourierCompensationTests`, `couriers-page.spec.ts`). Rating stays unbuilt, as before: no source exists on either side. | S | — | w3-courier-policy-roster |   |
| `3.4a` | Courier types (Тип курьера) | 2 | BUILT | — | S | — | T16 |  |
| `3.4b †` | Courier rate cards (Тариф курьера) | 2 | BUILT | The per-km distance ladder is editable, a rate card can be activated from the console, and the create form sends `maxDistanceMeters`. This row's own "`CourierAccrualService.recordDelivery` has no production caller" claim is stale — `DeliveryAccrualOrderCompletionTrigger` has called it in production since `T11` (batch 6; already correctly credited on the sibling row `7.4`, but never corrected here). This batch strengthens the proof rather than fixing new code: the end-to-end trigger test now asserts the actual earned amount against the seeded rate card's own ladder (`PER_ORDER 3_000 + PER_KM_BAND 2_000/km`) instead of only `totalMinor() > 0`. | L | — | w4-dispatch-couriers |  |
| `3.4c` | Bonus / penalty rule definitions | 2 | PARTIAL | `AdjustmentRuleEvaluator` and the rule-authoring form are built and tested, and, this batch, `CourierSettlementService.close` now evaluates every wired `SETTLEMENT_PERIOD`/`SETTLEMENT_PERIOD_CLOSE` rule before reading the entries/totals that go into the statement — attributed to the courier's most recent delivery location in the period (a settlement period carries none of its own), skipped for a period with no deliveries. Tested: a period-close rule firing and appearing in the closed statement, idempotent re-evaluation, a no-deliveries period closing without evaluating rules. `ORDER_UNDELIVERED` and `ORDER_DAMAGED` outcome bases still have no reader at all and stay manual-only by design. That statement is now written into [couriers.md](operations-spec/couriers.md) §11 (2026-09-30) with what it does and does not mean: ADR 0108 (Decision status Proposed) keeps the two manual "by construction" until something reads `fulfillment.delivery_exceptions`, so this is *not built* with no ADR refusing it; the rule form will not wire a reason on either basis, but `POST .../adjustment-reasons` accepts a wired one that then never fires; and, until batch 18, the form's wireable list (`EVALUATED_BASES`) omitted `CASH_VARIANCE`, which the evaluator has read at settlement close since batch 10. The IA ([the frontend information architecture](frontend-information-architecture.md) §3 row `3.4`) names bonus/penalty rule definitions without listing bases and was not changed. Batch 18 (`w7-courier-app-deliveries`) adds `CASH_VARIANCE` to the form's wireable bases (`c9ad1dc5`). A rule on it was authorable only through the API; because the evaluator reads it over a settlement period only (a shift's own handover is one row, and one variance in one shift is a weaker signal than the period's), choosing it pins the window to the settlement period and the trigger to the period close, the form refuses to save any other pairing (a SHIFT-window rule on it would never fire), and it says the threshold is a count of variance entries and not an amount. `CourierCompensationTests` (58, green in this audit's run) proves a wired `CASH_VARIANCE` rule fires at settlement close and is stamped `RULE`, and does not fire for a period whose cash reconciled exactly; `courier-types-rates-page.spec.ts` (11 cases) covers the form. Still not built, and the status stays PARTIAL: `ORDER_UNDELIVERED` and `ORDER_DAMAGED` have no reader (ADR 0108, Proposed, keeps them manual by construction until something reads `fulfillment.delivery_exceptions`), the form will not wire a reason on either, and `POST .../adjustment-reasons` still accepts one that then never fires (`OperationsCourierController` says so in its own description). The IA ([the frontend information architecture](frontend-information-architecture.md) §3 row `3.4`) names bonus/penalty rule definitions without listing bases and was not changed. | L | — | w7-courier-app-deliveries |  |
| `3.5` | Shifts & attendance (Посещаемость) | 2 | BUILT | The roster grid, period filter and planned-vs-actual comparison are all live at LOCATION scope. The courier's own accept/decline of a published shift offer remains the courier app's surface, not this console's (ADR 0042's PUBLISHED→ACCEPTED/DECLINED has no writer here), and cash handed over at shift close is still confirmed in Finance, not here — both unchanged, neither part of this row's own ask. | L | — | T17 |  |
| `3.6` | Delivery zones | P | PARTIAL | The tariff-binding bug is fixed — `submitDraft` now sends `deliveryTariffId`, draft/activate/bind are separated with a version list, and unbind now exists alongside bind, with CATCHMENT offered. Batch 14 (`10.12`) names a zone in the brand's own supported languages — one field per language, the default required, and a language the brand does not offer never touched by a save — and a zone can now be renamed after the fact (`PUT .../service-zones/{zoneId}/names`, `DELIVERY_ZONE_MANAGE` at brand scope, audited as `delivery.zone.renamed` and writing only the names that changed), where it could be named only when drawn; `fulfillment.service_zone_translations`, V0431. Tested both ends (`OperationsServiceZoneControllerEndpointTests`, `delivery-zones-page.spec.ts`). Circles only remains true: no polygon drawing and no map at all, blocked on `X.4`'s MapCanvas/PolygonEditor and an unmade map-provider decision, so a real city zone with a river or a ring road in it still cannot be drawn. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. | L | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `3.6b` | Regions & geocoder bounding boxes | P | BUILT | Batch 14 (`10.12`): a region's name is edited per locale in the union of the tenant's brands' supported languages (`GET .../regions/locale-set`; the default required, every other offered language optional, a language the editor does not offer never touched by a rewrite) — `fulfillment.region_translations`, V0431. A platform region (`tenant_id` null) stays read-only and answers its three columns alone. Tested both ends (`OperationsRegionControllerEndpointTests`, `regions-page.spec.ts`). | M | — | w2-locales-part-3 |  |
| `3.6c` | Bulk geozone upload | 2 | PARTIAL | The dry-run/apply batch import flow is built and live (a batch endpoint, its report, and a frontend page), but activation is deliberately gated behind `X.4`'s map per ADR 0037 — a bulk-imported zone cannot go live from the console until that map exists. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. | L | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `3.6d` | Free geozone (Бесплатная геозона) | P | BUILT | — | S | — | P20 |  |
| `3.7` | Delivery tariffs | P | PARTIAL | Distance tiers, time-rule surcharges, discounts, min/max, rounding, distance mode/accrual/fee-source and branch binding are all now authorable from the console, with RADIUS_FALLBACK/PROVIDER_QUOTE rendered. ROAD distance mode still silently prices as radius, since ADR 0037's `RoadDistancePort` answers empty — unchanged by this wave. Batch 17 drafts the decision this row waits on, ADR 0147 (Proposed, Not started); `DeliveryRoutingConfiguration` still registers the port that answers empty, and `ROAD` still prices as radius. | L | ADR 0147 (Proposed 2026-10-01, Not started) — the platform owner has not decided it: a self-hosted OSRM as the first adapter behind the `RoadDistancePort`, an answer that carries distance and duration with the dataset that produced them, and a `ROUTING` provider category. | w9-decision-adrs |  |
| `3.8 †` | Dispatch rules | P | PARTIAL | An operator can now decide how dispatch behaves, and the platform applies it when it plans a delivery. Built (`OperationsDispatchRulesController`: `GET` and `PUT /dispatch-rules` at tenant, brand or location scope with `If-Match` and `Idempotency-Key`, `/options`, `/usage`, `POST /simulations`, `GET` and `PUT /sourcing-policy`; `delivery.dispatch_rules.read` and `.write`): one ordered rule document per scope in a closed vocabulary (replace, not merge), validated at publish (the partner is this tenant's active delivery installation, no rule is shadowed by an enabled rule above it, a start leaves a partner time, a brand's options and checks reach only that brand's zones), evaluated once when the delivery plan is created (`DeliveryPlanningService#open` → `DispatchRuleEvaluator`, the pure function the simulator calls too) with the matched rule, the skipped installations and the pinned document version stored on the plan (V0465), so an edit never reroutes an order in flight, and applied by `DeliverySourcingService` (exclude, order, selection `LADDER` or `CHEAPEST`). `PARTNER_FIRST` is the fifth sourcing mode (the partner lane first, the fleet only after a definite answer, an uncertain attempt still escalating first). The missing writer for `fulfillment.sourcing`'s timing numbers exists; `ordering.payment_window` is a policy `OrderPaymentProcess` reads in place of the deploy property (flagging only); `DeliverySourcingRunner` publishes the `DISPATCH_BOARD` signal the board (`dispatch-board-page`) already subscribes to; each publication leaves a field-level audit fact naming rules by id. The console: Delivery → Dispatch rules (`dispatch-rules-page` with the rule list on `q-rule-list`, `dispatch-conditions-form`, `dispatch-action-form`, `dispatch-simulator`, `sourcing-timings-card`, `payment-window-card`) and a read-only summary on the order policy's Automation card (`dispatch-rules-summary-card`, `10.3b`). Tested and green in this audit's run: `DispatchRuleEvaluatorTests` (17), `DispatchRulesValidatorTests` (21), `SourcingPlannerPartnerFirstTests` (16), `DispatchRulesSourcingTests` (21), `OperationsDispatchRulesEndpointTests` (21), `OperationsPaymentWindowEndpointTests` (7), and the seven `delivery` specs for the page, forms, simulator, timings card, payment-window card and model plus `dispatch-rules-summary-card.spec.ts`. Why PARTIAL: courier order grouping and its merge radius, which the row names, cannot be switched on. A rule with `grouping` is refused at publish unless `horecaos.fulfillment.dispatch.grouping-enabled` is set, which it is not, and nothing populates the data the ranking bias reads (`FleetCandidate.groupableWithMetres`). The unpaid-order cancellation timeout is not built either: `CANCEL` on the window is refused at publish (ADR 0019's open input) and the window only decides when an order reaches the stuck list. `holdBeforeConfirm` is reserved and refused; publishing is not behind an approval; there is no default rule set beyond the built-in default. Moved NOT BUILT → PARTIAL. | XL | Courier order grouping: finance's answer to how a courier is paid for one run carrying several orders (ADR 0142 open input) and a feed for the ranking bias. Unpaid-order cancellation: ADR 0019's open input (product). ADR 0142 is Accepted. | w7-dispatch-rules |  |
| `3.9` | Courier policy | 2 | PARTIAL | A resolution-scope selector, the inherited value beside each overridden field, a one-sentence consequence line and policyId/policyVersion (unchanged from batch 10) are joined this batch by a real write path: `PUT .../courier-policy` now requires If-Match against the scope's resolved version (both GET and PUT carry it as an ETag) and refuses a stale write with `STALE_VERSION`, tested (`courier-policy-page.spec.ts`'s publish-with-If-Match case). Re-verified against code rather than the prior note: four of the "six switches with no backing field" already existed on `CourierCompensationPolicy` since P38 (GPS master toggle + two radii, kitchenReadyOnly, revealCustomerLocationTiming, postDeliveryPaymentCheckRequired) and the telemetry collection-gate default is already a registered, enforced ADR 0030 key — none of the five were actually missing a field. What was genuinely missing was an enforcement point for four of them, and batch 18 (`w7-courier-app-deliveries`, with `fix18-g-courier-app`) builds one. The courier app's own surface, `/api/v1/courier/tenants/{tenantId}/brands/{brandId}/locations/{locationId}` (`CourierDeliveryController`), lists offers and the courier's deliveries, accepts or declines an offer, advances a delivery carrying the courier's own position, reveals the customer's location and records the cash the courier confirms, under six new capabilities (`courier.offer.accept`, `courier.offer.decline`, `courier.delivery.read`, `courier.delivery.advance`, `courier.delivery.payment.confirm`, `courier.delivery.location.reveal`). Everything resolves from the caller's own courier row, so an id belonging to anyone else answers as one that does not exist, and a tenant administrator is not a courier. The gate logic is a pure class (`DeliveryGate`); a refusal is `422 UNPROCESSABLE_STATE` with a stable `reason` (`GateRefusal`): the acceptance GPS gate (`GPS_POSITION_REQUIRED`, `GPS_ACCURACY_INSUFFICIENT`, `GPS_REFERENCE_UNAVAILABLE`, `TOO_FAR_FROM_PICKUP`), the status-change radius for arrival and pickup against the branch and for handover against the customer's door (`TOO_FAR_FROM_DROPOFF`), «only kitchen-ready orders» (an unfinished order is not listed and is refused, `KITCHEN_NOT_READY`), the reveal timing (`LOCATION_NOT_YET_REVEALED`) and the post-delivery payment check (`PAYMENT_CONFIRMATION_REQUIRED`, `PAYMENT_AMOUNT_MISMATCH`, recorded on the shipment by V0484). The position a courier sends is measured and discarded: it is in no response, no audit fact, no log and no table; every reveal is a SECURITY audit fact naming the courier, the order and the offer or shipment, written before the address is read and never containing it. [couriers.md](operations-spec/couriers.md) §11 says where each switch is enforced. The policy screen now renders the four as ordinary rows and fields with a consequence line each instead of locked rows (`courier-policy-page`), and the review round made the page refuse to send a radius that is not a real distance. Tested and green in this audit's run: `CourierDeliveryEndpointTests` (27, over HTTP: the GPS gate with its two radii and its boundaries, a coarse fix and an unplaced branch; kitchen-ready-only; reveal timing, audited with the address never in the audit fact or the idempotency record; the payment check; step order; ownership; a suspended engagement; replay and a stale `If-Match`), `DeliveryGateTests` (11), `CourierJobsServiceTests` (15, against the database: the double-tap race, the post-commit board signal, the decline that wakes sourcing, and that the customer's door is decrypted only for its carrier), `PlatformRoleTests` (40), `JdbcAuthorizationServiceTests` (32), and `courier-policy-page.spec.ts` (17 cases). **Why the row stays PARTIAL: nothing calls any of it.** There is no courier client in this repository: `mobile/` is the customer ordering app and is on hold (ADR 0055), `apps/courier` is not scaffolded (ADR 0022: built only after courier workflow scope is approved), and neither console, the Telegram bot nor either storefront has a caller for `/api/v1/courier/...`; the courier's shift and telemetry routes have the same absence. An operator can set the four switches and the platform will refuse correctly, and nothing in production presents an offer, accepts it or advances a delivery from a handset to be held to them. Also unchanged: the soft mode ADR 0042 describes for the status steps (record `GEO_UNVERIFIED` instead of refusing) is not a setting on this policy document and is not built, and billing mode and the telemetry gate stay refused or platform-only by their own ADRs. | L | A courier client: `apps/courier` is unscaffolded (ADR 0022) and the Flutter app is on hold (ADR 0055). | w7-courier-app-deliveries |   |
## §4 — Catalog

25 rows — 20 built · 3 partial · 2 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `4.1` | Products — the tenant product library | P | BUILT | The product library is unchanged. The Availability tab's «not listed at N branches» banner (batch 13) covered only the product's default variant; batch 14 reads it for every variant in parallel (`InventoryApi.unlistedLocations` per variant, so one failed read hides only that variant's row), lets each variant's «list everywhere» run on its own (a per-variant pending state, and one `Idempotency-Key` per intent so a retry replays instead of re-running), and says what a click left undone instead of dropping the row — a partial list («listed 2 of 3») and an unreadable recount each show a notice. Tested (`product-editor-page.spec.ts`'s every-variant and what-list-at-every-branch-reports cases, `inventory-api.spec.ts`'s per-intent key cases). Batch 15 (row `10.12`): the create-product dialog writes the name in the catalog locale the product list itself resolves in (`listResolutionLocale`: the brand's own default, the server's `uz` for a brand that has chosen none) instead of the operator's console language, and says which language that is; before, a Russian-speaking operator in an Uzbek-default brand created a product whose list row showed its bare code. The fix round found the editor then opened on the platform `ru` and greeted the operator with a blank name right after typing one; it now opens on the language the name was written in. Tested (`products-page.spec.ts`, `product-editor-page.spec.ts`; green in this audit's run). | L | — | w3-locale-remaining-forms |  |
| `4.1a` | Bulk edit of ИКПУ / package code / name, bulk delete, copy-id, share slugs | P | BUILT | — | L | — | P21 |  |
| `4.2` | Product editor — the seven-tab core | P | BUILT | — | M | — | P22 |  |
| `4.2a †` | Combo groups with per-variant price map | 3 | BUILT | A merchant can now sell a set meal as one choice-set, and an order keeps it as its components. Authoring: `CompositeProductAuthoringController` (`catalog.author`, `If-Match` and `Idempotency-Key` as ADR 0031 asks) creates a combo group on a container variant with its heading, minimum, maximum and repeat rule and adds member variants, and `PUT .../price-books/{id}/combo-component-prices/{componentId}` prices each component per unit inside the pairing (a `COMBO_COMPONENT` price, zero allowed, never inferred from the variant's own price, so the same drink can be free in one combo and 3 000 in another). The product editor's Combo tab (`product-combo-panel`, `product-combo-group-card`) drives both and shows the price at the operator's own branch. Publication carries the groups to the menu and blocks an unpriced component (`COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE`, also on a channel's own price plane in the preview, `4.6a`). Selling: the New order composer (`combo-picker-dialog`), both storefronts (`combo-choices`), the cart and order API, the operator phone-order endpoint and the Telegram bot's repeat-order take the picks; the order stores one ordinary line per picked component sharing a `combo_selection_id` (V0443 to V0447), so `ck_order_total_reconciles` and every flat reader are unchanged, a refusal is checked on an open cart and a component outside its sale window is refused. Reading: the order detail, the kitchen queue, the expo screen and the ticket (`kitchen-ticket.ts` groups by the selection id) show a combo under its heading, the change-quantity dialog counts whole combos, an amendment keeps the combo price and the POS export sends the live component lines only. Tested and green in batch 17's run: `CompositeProductAuthoringEndpointTests` (30), `CompositeProductRulesTests` (21), `CompositePublicationTests` (8), `CompositeMenuTransportTests` (10), `CompositePricingEngineTests` (25), `CompositeQuoteTests` (22), `ComboComponentPriceEndpointTests` (11), `ComboOrderFlowEndToEndTests` (24, cart to till over HTTP) and `AmendmentBasketTests` (10), and the `product-combo-panel`, `product-combo-group-card`, `combo-picker-dialog`, `combo-selection`, `order-change-quantity-dialog`, `order-detail-lines` and `kitchen-ticket` specs and both storefronts' `combo-choices` specs. Batch 18 (`w4-combos-completion`, with `fix18-d-combos`) closes in code the four gaps the batch-17 audit named. (1) The add-lines amendment dialog (`order-add-lines-dialog`) opens the composer's own `q-combo-picker-dialog` for a search result that is a combo container, for the groups of the menu the order was placed through, and sends the picks with the line as `comboPicks`: two different combos stay two lines, the same picks twice are one line of two combos, and a menu that cannot be read says so. An added combo, or a grown one, reaches its stations through `KitchenAmendmentListener` (V0477) even when the ticket is already open, as does a line an amendment replaced; the fix round made an amendment spend the food already on the pass once, so a later addition of the same dish is cooked. (2) Reporting: V0478 gives `reporting.fact_order_line` the combo grouping (selection, container, quantity, name), `GET /reporting/combo-sales` returns one row per combo container (combos sold, purchases, orders, gross, discount, net, delivery and pickup counts, share of the rows shown) over completed orders only, and Product analytics has a Combos tab over it with the empty, error and retry states; the day close no longer builds facts from lines an amendment closed (an amended order was counted twice) and the migration deletes the stale rows and recounts the orders they sat on, and the review round made per-product sales count completed orders only, as the combo read says, and a renamed combo read as the name of its latest purchase. (3) Duplicating a product copies its combo groups, headings and components, not their prices. (4) The quote and the cart's selection check read the combo's structure from the live publication and not from the authoring rows, so a combo edited and not republished sells what was published and one authored after the last publication is not sold. Tested and green in this audit's run: `ComboOrderFlowEndToEndTests` (31, cart to till over HTTP, among them an amended combo through the day close), `CompositeQuoteTests` (25), `CompositeMenuTransportTests` (14), `CompositePublicationTests` (8), `StorefrontCatalogQueryTests` (18), `CatalogAuthoringServiceP21Tests` (16, a combo duplicated with its groups, headings and components), `AmendmentBasketTests` (14), `OrderAmendmentAndOutcomeTests` (92), `KitchenExecutionTests` (65; the kitchen cases of an amendment are in `ComboOrderFlowEndToEndTests`: an added combo, a grown combo with a made burger credited, a no-op replay, and the food on the pass spent once), `ComboSalesReportingTests` (8), `ComboSalesReportControllerHttpTests` (4), `ComboFactsMigrationTests` (1), and the Combos tab, add-lines dialog and combo-picker specs in the operations suite (376 files, 4,391 tests, green). Residue, none of it a reason to hold the row: ADR 0136 says a terminal that does not understand `combo_selection_id` prints N ordinary lines, which is correct, and that is what the platform exports; a terminal that does could print a grouped receipt, and the Clopos wire fields that would carry the grouping are not sent. The per-component fiscal receipt line belongs to `8/X.2`, where no receipt is built from order lines for any order. The guest's dine-in bill carries totals and order ids and no lines, which is a dine-in gap for every order and not a combo one. A combo container cannot itself be a member of another combo, by decision, and a combo line takes no modifiers, by decision. ADR 0136's own implementation status stays Partial for these and for the items on `4.2b`. Moved PARTIAL → BUILT. | XL | — | w4-combos-completion |  |
| `4.2b †` | Modifier depth — nested variant-modifiers, hidden modifiers auto-selected by order type, modifier-level fallback to group values | 3 | BUILT | Three of the row's named things are authorable, priced and enforced where the cart reads them, and, since batch 18, offered to everyone who orders. Built (`CompositeProductAuthoringController`, `catalog.author`; `product-modifier-policy-panel` under the product editor's Modifiers tab, V0445): a hidden group, one whose single active option the server applies by itself to every order of the chosen fulfilment modes (DELIVERY, PICKUP, DINE_IN), so a delivery box reaches the receipt without anyone choosing it; `PUT .../products/{productId}/modifier-groups/{groupId}/overrides` sets this product's own required, minimum and maximum, and a field left null falls back to the shared group's own value, which answers «min/max cannot be authored from this console» per product (a group created from the console is still always required with exactly one choice, and a shared group's own range has no editor, on purpose: editing it from one product's screen would change another's). The hidden charge is itemised on the priced cart in both storefronts, on the placed order, on the console's order detail (`order-detail-line-auto`), through an amendment and a weight capture, and reaches the till only when its option is mapped (`anUnmappedHiddenOptionRefusesTheExport`). One level of nested variant-modifiers can be attached to a variant (`PUT .../variants/{variantId}/modifier-groups/{groupId}`), is priced by the quote and is stored under its parent option when the cart API is sent it (`aNestedChoiceIsStoredUnderItsParent`); a third level is refused at publication. Tested and green in batch 17's run: `CompositeProductAuthoringEndpointTests` (30), `CompositeProductRulesTests` (21), `CompositeQuoteTests` (22), `ComboOrderFlowEndToEndTests` (24: `aHiddenBoxIsAppliedByOrderType`, `anAmendedLineStillItemisesItsHiddenBox`, `weighingALineKeepsTheBoxOfADeliveryOrder`), and `product-modifier-policy-panel.spec.ts`. Batch 18 (`w4-combos-completion`, with `fix18-d-combos`) makes the second level choosable and the variant-level rules real. The menu now publishes a variant's own groups and the rules that variant holds the customer to (a group only a variant carries is a `MODIFIER_GROUP` item listed on the variant) and the choices an option opens, one level down, under the option; the cart holds the customer to a variant's own override of a shared group, reads the options of a group and the choices an option opens from the live publication, and a combo container offers none. Four screens ask for it. The New order composer and the item-modifier dialog open a portion's own groups (the product's, then the portion's own, under the product's and the portion's rules), ask the choices an option opens under its name with the rules published for them, hold confirm back until a required one is answered, forget them when the option is taken back, and send them under their parent as `nestedModifiers`, priced like any modifier and kept out of `modifierOptionIds`; the main storefront's product page and storefront-milliy's product page, table picker and dish card do the same, and the cart finds a line by its answers (the same choice is bumped, another is a line of its own). A storefront keys a line that carries such answers by a short hash, because the platform stores a line key in sixty-four characters and the first cut wrote seventy-three (the insert was refused for any dish with an option; `1dffbe80`), so `CartLineResponse.modifierOptionIds` now echoes the first-level options a line holds and a line can be edited from any device (a table's phone and tablet). The console's `product-modifier-policy-panel` attaches a group to a variant and lists what a variant already offers. Tested and green in this audit's run: `CompositeMenuTransportTests` (14: a variant's own groups and rules are published with the variant, an option that links a variant carrying groups is published with the choices it opens, a group on a combo container is not published), `ComboOrderFlowEndToEndTests` (31: a variant-level override through the cart, a reorder, `aNestedChoiceIsStoredUnderItsParent`, `aCartLineEchoesItsOptions`, the hidden box by order type), `CompositeQuoteTests` (25), `AmendmentBasketTests` (14), and `new-order-page.spec.ts`, `product-modifier-policy-panel.spec.ts`, the storefront `product.component.spec.ts` and `ui-cart.service.spec.ts`, and storefront-milliy's `modifier-picker.component.spec.ts` and `dine-in-table.component.spec.ts`. Residue: the second-level chooser takes one pick per option (the platform accepts a repeat and no screen draws one); the operations reorder starts from the product's plain state; a variant may not apply by itself (`HIDDEN_AUTO_SELECT`) a group its product offers as a choice, because the publication says what a variant adds and overrides and not that it withdraws a product's group (authoring refuses the pairing and publication blocks it); a combo line takes no modifiers, by decision; and the wording that discloses a hidden charge to a customer is still a neutral placeholder pending product and legal: the charge is itemised on the priced cart, the placed order and the order detail, but what the line is called is not decided. The row is held BUILT with that last item on the record, because every capability the IA names is real and reachable; ADR 0136's own status stays Partial. Moved PARTIAL → BUILT. | XL | The wording of a hidden charge waits on product and legal; nothing else on the row waits on a decision. | w4-combos-completion |  |
| `4.2c †` | Physical & nutritional attributes — weight, measure, catchweight + quantum, splittable, portions as a decimal, КБЖУ | 3 | PARTIAL | A weighed, splittable or nutritionally labelled dish can be described, sold, weighed and reconciled; what keeps the row from BUILT is money. Described: `PUT .../variants/{variantId}/physical-attributes` (`catalog.author`, V0448) stores weight or volume with its unit, catchweight with its quantum and estimated weight, splittable with a portion size, and КБЖУ; the product editor's weight-and-nutrition tab (`variant-physical-attributes`) edits it and warns about marking, and `PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING` blocks publishing a weighed or splittable marked good (ADR 0038). Published and read: the attributes ride in the variant's publication payload, and both storefronts (`physical-facts`) show weight, КБЖУ per 100 g and per serving, the price per quantum with the estimate and the «final weight is determined at handover» notice, and order by the portion. Portions as a decimal: V0449 widens `order_lines.quantity` and every column it feeds to `numeric(10,3)` through cart, quote, order, kitchen ticket and the reporting facts, a whole quantity still written `2`, not `2.000`, on the wire (`ReportingQuantityWireTests`, `quantity.spec.ts`), with the OpenAPI type change admitted by one narrow named allowance (`AcceptedContractWidenings`), New order stepping a splittable line by its portion, and the pass, wallboard and tablet board writing `0,5`. Catchweight: V0450 snapshots the facts on quote and order lines, and `PUT .../orders/{orderId}/lines/{lineId}/actual-weight` (`order.advance`, `If-Match`) reconciles the charge at pick or handover as an order revision with source `CATCHWEIGHT`, priced as the order was bought (its mode, delivery point and promotions; a combo as the combo; the hidden box once), restating the promotion ledger and the loyalty flags; `CATCHWEIGHT_NOT_RECONCILED` holds the order at the pass until it is, and `q-order-weighing-panel` is on the order detail and the expo screen. Tested and green in batch 17's run: `PhysicalAttributesTests` (9), `PhysicalAttributesPublicationTests` (10), `PhysicalAttributesEndpointTests` (12), `PhysicalAttributesMigrationTests` (2), `DecimalAndCatchweightPricingTests` (18), `CatchweightReconciliationServiceTests` (6), `CatchweightAndDecimalOrderHttpTests` (18), `AcceptedContractWideningsTests` (13), `OpenApiContractTests` (6), and the `variant-physical-attributes`, `order-weighing-panel`, `order-weighing-api` and `physical-facts` specs. Batch 18 (`w5-attributes-fiscal-tab`, with `fix18-e-attributes-fiscal`) closes two of the things that list named. **An amendment changes a line by a fraction.** The order read carries `portionSize` for a line of a splittable dish while the order can still be amended; `order-change-quantity-dialog` starts one portion above the line, steps by it, sends a decimal and refuses what is not a whole number of portions, and a dish with no portion step keeps whole units; the platform names the rule the cart names (a fraction of a can, a fraction off the portion step, a quantity the column cannot hold are each refused with nothing written, and the refusal carries the step so the operator is told what to type). The review round made the amendment's quantity constraints run and refuse an absurd exponent before it is expanded, and made `CHANGE_PAYMENT_METHOD` refuse a method that takes the money first for an order holding a weighed line (see `1.2c`). **A weighed price is worded per its quantum everywhere a price is shown.** The price-book matrix, the bulk price change, the menu grid and the product editor's price column say «за 100 г» for a catchweight variant (`per-quantum.ts`, the same `formatWeight` the storefronts use), where the bare figure invited the operator to read 150 000 as the price of a whole cake; `catchweightQuantumGrams` is now on the price reads (`PriceQueryService`, `PriceBookMatrixEndpointTests`). Tested and green in this audit's run: `DecimalAmendmentHttpTests` (10), `OrderAmendmentQuantityBoundsTests` (4), `CatchweightAndDecimalOrderHttpTests` (22), `PhysicalAttributesPublicationTests` (10), `PriceBookMatrixEndpointTests` (18), `AcceptedContractWideningsTests` (13), `OpenApiContractTests` (6), and `order-change-quantity-dialog.spec.ts` (17 cases), `price-book-matrix-page.spec.ts`, `bulk-price-change-page.spec.ts`, `menus-page.spec.ts`, `product-editor-page.spec.ts`, `quantity.spec.ts`. Still missing: the till is told the nominal-weight amount at confirmation and never the weighed one (no `pos`, `fiscal` or `integration` code reads a catchweight fact; `OrderSettlementService` in `payments` is the one hit); an order already paid through a provider refuses a weight that moves its total (`PAYMENT_ALREADY_TAKEN`), so a basket holding a weighed line is sold only for a method that settles at handover: the payment step stops offering the others, and checkout and a payment-method amendment (`CHANGE_PAYMENT_METHOD`) refuse them (`WEIGHED_LINES_PAY_AT_HANDOVER`), and selling one for an online payment is ADR 0153; fiscal receipt lines are not built from order lines, so Payme and Click integer counts are untouched; a fractional stock reservation is out of scope; no КБЖУ accuracy disclaimer is worded (legal, product). Batch 17: NOT BUILT → PARTIAL; batch 18 leaves the status. | XL | A provider-paid weighed order: ADR 0153 (Proposed 2026-10-03, Not started) — the platform owner has not decided it. The till being told the weighed amount needs a POS adapter that can take one; the КБЖУ accuracy wording waits on legal and product. | w5-attributes-fiscal-tab |  |
| `4.2d` | Fiscal data on every priceable node — ИКПУ/MXIK, package code, marking, excise, VAT, alcohol % and age gate | P | BUILT | — | M | — | P22 |  |
| `4.2e †` | ИКПУ/MXIK reference lookup (typeahead behind the classification field) | P | PARTIAL | A tenant-scoped ИКПУ/MXIK typeahead (`q-combobox`) is now built and wired against a new BRAND-scoped alias of the reference search — but it returns empty for every query, because the official ИКПУ/MXIK dataset has never been imported. | M | The official ИКПУ/MXIK dataset has never been imported; its source and refresh cadence are an unanswered finance/owner input, and the existing search endpoint is PLATFORM-scoped so a tenant operator could not call it even if it were loaded. | P22 | BLOCKED  |
| `4.2f †` | Ordered images with per-aggregator overrides | P | BUILT | The product editor's Photos tab now has a channel picker (wave 9 w7) — this tenant's own active sales channels plus the universal ALL_CHANNELS default — and upload/reorder/detach all act on whichever gallery is selected, against the existing channel-aware attach/detach endpoints (no backend change needed; `catalog.media_relations` already carried the channel dimension). Two latent collision bugs in `reorderPhoto`/`detachPhoto` (matching a media item by `(mediaAssetId, role)` alone, dropped by V0223's wider key) were caught and fixed alongside it. Tested: `product-editor-page.spec.ts` gains coverage for the channel picker and both fixed bugs, plus a dedicated regression test for the `detachPhoto` collision. Batch 17 (`w3-marketplace-preview`, ADR 0138) adds a second, per-channel layer beside this one: `catalog.channel_media_overrides` (V0451), `PUT` and `GET .../channels/{channelId}/media-overrides` (`catalog.author`, audited, the set's version as `If-Match`), edited from the channel preview (`q-channel-image-override-panel`: the channel's photo is picked from the product's own photos and saved as that channel's primary), applied by `ChannelMediaLayers` in the preview and in `publish` for the channel it publishes to, so that channel's live menu serves it, an image another channel owns is never published here, and a withdrawn image refuses the publication. A variant's or category's channel photo is stored and drawn by the preview but no published menu carries an image for either. Tested and green in this audit's run: `ChannelPreviewEndpointTests` (`anOverrideIsPublishedToItsChannel`, `publishRefusesAWithdrawnChannelImage`, `overrideSetIsVersioned`, `concurrentFirstWritesAreSerialised`) and `channel-image-override-panel.spec.ts`. | L | — | w3-marketplace-preview |  |
| `4.2g †` | Per-item sale schedule and kitchen department on the product | P | BUILT | Kitchen department routing (batch 8 w3) and, this batch, per-item sale-window enforcement are both wired end to end: `CatalogAuthoringService.isOnSaleNow` now has real callers through a new `CartSaleWindowRules` port, refusing `ITEM_OUT_OF_SALE_WINDOW` three times — `CartService.putLine` (adding an out-of-window variant), `CartService.price` (an in-window line whose schedule has since excluded it, flagged by name rather than silently dropped), and `CheckoutEligibilityGuard` (a schedule change in the gap between an accepted quote and checkout). `StorefrontCatalogQuery` also marks a variant `onSaleNow=false` live in the published menu, independent of the `orderable` (86'd) flag. Tested both ends: `CartCheckoutAndOrderTests`, `StorefrontCatalogQueryTests`, plus the pre-existing kitchen-department coverage. | L | — | w3-catalog-presets-schedule-xlsx | NOT BUILT |
| `4.2h` | Recommended products / cross-sell (Рекомендованные товары) | P | BUILT | `catalog.product_recommendations` (directional, source→target, no combo semantics) exists with attach/detach/reorder and a RECOMMENDATIONS tab in the product editor, filtered server-side to active+in-menu+not-stopped (fix4 corrected the filter, which originally checked the wrong stop-list column). `CatalogApi.effectiveRecommendations` now has a real caller too (fix4): the RECOMMENDATIONS tab marks each attached target "eligible at my current location". Storefront/aggregator rendering of recommendations remains out of scope and unbuilt. | M | — | P47 |  |
| `4.3` | Categories — hierarchical taxonomy | P | BUILT | — | L | — | P23 |  |
| `4.4` | Menus — the per-location offering matrix (Layer A) | P | BUILT | — | L | — | P23 |  |
| `4.4a †` | The named Menu entity — bind menu to branch, copy menu, add products with filtered select-all | P | BUILT | `catalog.menus`/`catalog.menu_items`/`catalog.branch_menu_bindings` (V0389/V0390) give a brand-owned, versioned, named assortment, additive to ADR 0016's `location_offerings` model rather than a replacement for it — no ADR amendment turned out to be needed. `MenuAuthoringService`/`MenuController` give create/rename/archive, copy (independent membership), single add/remove, filtered select-all (category/search → membership in one statement) and binding a menu to a branch (one channel, or as the branch's default). The console's new `/catalog/menu-sets` screen (`menu-sets-page.ts`) wires all of it. A fix-round bug (binding a still-DRAFT menu went live immediately, since `StorefrontCatalogQuery` reads a bound menu's membership with no publish step) was caught and fixed before merge — bind now requires `ACTIVE`. Tested both ends: `MenuAuthoringServiceTests`, `MenuControllerTests`, three `StorefrontCatalogQueryTests` regression cases proving no behaviour change for an unbound branch, and `menu-sets-page.spec.ts` (list/create/copy/filtered-add/bind/DRAFT-refusal). Batch 17: the channel preview (`4.6a`) is a second reader of this entity: a named menu bound to the branch replaces the branch's offerings in the projection a channel would receive (`boundNamedMenuReplacesTheBranchOfferings`, green in this audit's run). | XL | — | w6-menu-entity |  |
| `4.4b †` | Per-channel offering and price overrides — offered_on_channel separate from price_on_channel, mass enable for aggregator | P | BUILT | `catalog.channel_offering_exclusions` (V0020) has a writer now — `CatalogAuthoringService.setChannelOffering`/`bulkSetChannelOffering`, three new control-plane endpoints — and the Menus page gets a channel selector that re-resolves the price column via `PricingApi.resolvedVariantPrices(channelId)` against `PriceAuthoringController.assignToChannel`'s existing CHANNEL precedence, plus mass-enable/mass-disable reusing the existing bulk-selection bar. Editing a channel's price still requires a price book already assigned to that channel via the Price List page first — a deliberate `canEditChannelPrice` guard against silently rewriting the hall book, not a gap. Batch 17: the channel preview (`4.6a`) is the first screen that shows what an exclusion does to a channel's menu: it removes the dish from that channel at that branch and from no other (`exclusionIsPerChannelAndPerBranch`, green in this audit's run). | XL | — | P45 | NOT BUILT |
| `4.4c †` | Per-item stock quantity with daily default and auto-reset, per-aggregator stop threshold | 2 | BUILT | QUANTITY tracking is now the enforced branch of ADR 0017's reservation lifecycle behind `catalog.use_stock_logic`: `InventoryService.evaluateAvailability`/`reserveForQuote` check real on-hand/reserved per stock item in deterministic order (`JdbcInventoryStore.tryReserveQuantity`, the authoritative concurrency-safe gate), and commit/release/expiry move reserved/on-hand per ADR 0017's own rules. The console's new `/catalog/stock` page shows every QUANTITY item's on-hand/reserved/remaining, the daily default (blank clears the scheduled reset, applied by `InventoryQuantityResetScheduler` at the tenant's own business-day boundary) and per-channel-type stop thresholds with add/remove, linked from the product editor's Availability tab. Tested both ends (`InventoryQuantityLifecycleTests`, `stock-page.spec.ts`). Two of ADR 0017's open inputs (never-negative refusal, no restock-on-cancel-after-commit) are resolved conservatively rather than decided, documented on `InventoryService`'s own class doc. Batch 14 adds the branch-level counterpart of the auto-listing fix behind `4.1`: the stock page opens with an «unlisted offered dishes» report — `GET .../inventory/unlisted-offerings` (`INVENTORY_READ` at location scope; the exact `totalCount`, up to 200 named items in the operator's own console language with a locale fallback and then the product code, over `UnlistedOfferingsPort`) — naming every dish the branch offers `AVAILABLE` that inventory has never listed, which reads `NOT_STOCKED_AT_LOCATION` whatever the stock-logic setting, and a one-click «list all» through the existing idempotent `POST .../inventory/listing-backfill` that re-reads the report, says how many it listed and asks for another click when the call hit its per-call cap. The report reads independently of the QUANTITY table, so a failed report never hides it. Tested both ends (`InventoryUnlistedOfferingsReportTests` — including that the report count equals what the backfill lists, and that list-all needs an idempotency key and the adjust capability — `stock-page.spec.ts`, `inventory-api.spec.ts`). | XL | — | w4-catalog-listing-ux |  |
| `4.4d †` | Catalog base settings (use stock logic; QR/kiosk take hall prices) | P | BUILT | Both base settings this row names are now real, editable, enforced fields. `catalog.qr_kiosk_price_plane` has been enforced since batch 9 (`QrKioskHallPricing.resolve`). `catalog.use_stock_logic` is now enforced by `InventoryService`'s QUANTITY branch (row 4.4c) and editable at `/settings/catalog` through the same `q-inherited-field` edit/save/revert shape as its sibling, tested both ends (`InventoryQuantityUseStockLogicGateTests`, `catalog-settings-page.spec.ts`). A merchant can turn stock logic on for the company today. | M | — | w2-quantity-stock |   |
| `4.5a †` | Catalog sync from POS — mapping table and run history | P | BUILT | — | L | — | P24 | NOT BUILT  |
| `4.5b †` | Excel import/export — templates, dry run with row-level results, image-by-URL fetch | P | BUILT | The backend has produced and consumed `.xlsx` since batch 10 (`org.dhatim:fastexcel`/`fastexcel-reader`, no Apache POI, bounded against a decompression-amplification upload). This batch closes the console's own "built, no consumer" gap: `q-import-wizard` now accepts `.csv,.xlsx`, a chosen `.xlsx` is read via `file.arrayBuffer()` and Base64-encoded into the same JSON `content` field CSV always used — never routed through `readAsText`, which would corrupt its binary bytes — and a new client-side `xlsx-preview.ts` (ZIP central directory + `DecompressionStream('deflate-raw')` + `DOMParser`) drives the wizard's row preview for `.xlsx` too. A "Download template (.xlsx)" button sits beside the existing CSV one. Tested both ends: file-input accept, exact-bytes-preserved upload, template download (`catalog-import-page.spec.ts`). | XL | — | w1-quick-closes |   |
| `4.6` | Publication & channel readiness | 2 | BUILT | Residue, narrowed in batch 17: the channel preview (`4.6a`, reached from each channel card on the publication screen) carries the owning product's id on every finding (`ChannelPreviewResponse.Finding.productId`, `variantFindingsNameTheirProduct`) and deep-links a variant-scoped finding to its product. This screen's own readiness list (`publication-page`, `findingLink`) still links only PRODUCT-scoped findings and renders VARIANT, MODIFIER_GROUP and MODIFIER_OPTION findings as text, because the readiness report's `ValidationFinding` has no product id. The publication screen now compares each channel's last publication with the draft as that channel would publish it, since channels no longer publish identical items once one carries a photo of its own. | M | — | w3-marketplace-preview |  |
| `4.6a †` | Aggregator preview and the per-channel projection/override layer | 2 | PARTIAL | A merchant can see what a channel would receive at a branch before anything is pushed. `GET .../catalogs/{catalogId}/channels/{channelId}/preview` (cursor-paged, `catalog.read`) and `GET .../channels/{channelId}/preview-targets` run the draft through the very `StorefrontCatalogQuery.assemble` a customer's menu read uses, so the offering (or the bound named menu, `4.4a`), the channel exclusion (`4.4b`), the price plane and the image gates are one implementation, and add findings tagged with who raised them (the catalog, the projection, a marketplace ruleset): an externally priced channel states no price rather than zero, and a modifier option or a combo component with no price on the channel's plane blocks as a variant does. The console's `/catalog/preview` (`channel-preview-page`, reached from each channel card on the publication screen) draws the result in the `X.28` frames, pages with a cursor, names each branch with its marketplace binding, and deep-links a variant-scoped finding to its product. The per-channel layer is `catalog.channel_media_overrides` (V0451): `PUT` and `GET .../channels/{channelId}/media-overrides` (`catalog.author`, audited, the set's version as `If-Match`, writers serialised) and `ChannelMediaLayers`, which `publish` applies for the channel it publishes to, so a photo chosen on the preview is what that channel's live menu serves (`4.2f`) and a withdrawn image refuses the publication. Tested and green in this audit's run: `ChannelPreviewEndpointTests` (23, among them `projectionEqualsTheLiveStorefrontMenu`, `exclusionIsPerChannelAndPerBranch`, `priceBookPrecedence`, `boundNamedMenuReplacesTheBranchOfferings`, `variantFindingsNameTheirProduct`), `MarketplaceRulesetTests` (6), and `channel-preview-page.spec.ts`, `channel-preview-api.spec.ts` and `channel-image-override-panel.spec.ts`. Still missing: any per-marketplace rule: `MarketplaceRulesets` is empty in production by decision and no ruleset code can be set on a binding (V0452 only stores one), so «why a marketplace will reject it» is not answered, only why the platform's own catalog rules would; no mobile and desktop modes and no rendering in the aggregator's own language (the preview speaks the operator's console language); a variant's or category's channel photo is stored and drawn by the preview, but neither `MenuVariant` nor `MenuCategory` has an image field, so no menu serves it; the outbound push that would consume the projection (ADR 0040) does not exist, nor does the `CatalogDraftChanged` event the record names for an override write (an override write is audited and nothing more). Moved NOT BUILT → PARTIAL. | XL | The per-marketplace content rules (image resolution, description length, category depth) — product and partnerships; ADR 0138 is Accepted and ships the plug point with an empty registry. The outbound push is ADR 0040. | w3-marketplace-preview |  |
| `4.7` | Reference data — attributes, tags, ingredients, kitchen departments, product comment presets (brands link out) | 2 | BLOCKED | There is no controlled vocabulary for tags, ingredients, attributes or kitchen departments, so anything that needs one (cross-sell, storefront filters, R-Keeper comment transport, ticket routing) has nowhere to draw from. Product comment presets are the exception and are not waiting on this: `catalog.comment_presets` has its settings screen (`/settings/comment-presets`, worded per locale since batch 14) and its round trip to the KDS and the POS export (`2.1b`) — corrected by this audit; the row title still lists them, but the blocker it names does not apply to them. | L | An owner/product decision: ADR 0016's open input on legacy merchandising disposition — in particular whether Атрибуты are the variant axis or a spec-sheet vocabulary — is unanswered, and the spec forbids building any of it first. | deferred |  |
| `4.8a †` | Price books — the named price planes and their assignment | 2 | BUILT | Price-book assignment (BRAND/LOCATION/CHANNEL scope, priority/validFrom/validUntil) and the tax-profile read endpoints (batch 11) are unchanged. This batch closes the row's last named gap: `q-price-book-matrix-page` at `/catalog/prices/:priceBookId` (a "Matrix" link on each price-book row) shows every variant in the brand's draft catalog, this book's price, the brand's live base price and the delta in one table, filterable by category and by "differs from base," inline-editable through `PricingApi.setVariantPrice` with the row's own `bookPriceVersion` as If-Match (a 409 reloads the row rather than leaving a stale value on screen). `GET .../price-books/{id}/matrix` is cursor-paginated and brand-scoped. A merge-time fix round found and closed two real concurrency bugs the matrix's own inline edit exposed before either shipped: a check-then-act race in `PriceAuthoringService.setPrice` (two operators reading the same version could both pass the check and both write, the second silently overwriting the first) and a fallback insert that poisoned its own transaction on a losing concurrent first write. Tested both ends: `PriceBookMatrixEndpointTests`, `price-book-matrix-page.spec.ts`. Residue: exporting the matrix through the audited export centre is not built. | M | — | w8-pricebook-reservations |  |
| `4.8b` | Bulk price change across a filtered selection (Прейскурант) | 2 | BUILT | — | L | — | T04 |  |
| `4.9` | Auto-add rules — plain, product-triggered, portion-band | 2 | BLOCKED | Packaging, cutlery and gift items cannot be injected into a cart by a rule, so web, bot and operator entry each get a different cart and staff add such lines by hand on every order. One narrow case has a mechanism since batch 17: a hidden, order-type-scoped modifier group attached to a product (`4.2b`) puts a delivery box on the receipt for the products that carry it. That is a charge on a product, not the injection of a cart line by a condition, so plain rules, product-triggered rules and portion-band rules are still unbuilt. | XL | No ADR owns cart injection: the IA and catalog.md both record it as unowned and needing a product decision before design. The portion-band kind no longer waits on a portions attribute (decimal portions exist since batch 17, `4.2c`); it waits on the same decision. | deferred |  |

## §5 — Customers

19 rows — 15 built · 2 partial · 1 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `5.1` | Customer list — grid, search, status filter, manual create | P | BUILT | Nothing an operator needs on the grid itself. Residue: registration dates render against a hardcoded PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent' in customers-page.ts, so a tenant in another zone sees the wrong calendar day; MERGED accounts are unreachable from the filter by design. | S | — | — |  |
| `5.1a` | Header counters (total, ordered today, registered today) from the same metric layer as the dashboard | P | BUILT | — | M | ADR 0043 has no customer metric definitions and no agreed business-day boundary; registering them is a metric-registry version bump that cannot be backfilled silently. | P26 |  |
| `5.1b` | Bulk CSV import with retained provenance | P | BUILT | — | L | Needs the PART 4 pilot-blocker ImportWizard (FileDropzone + row-level preview + dry-run diff + JobProgress + ResultSummary), which does not exist — the same component 4.5 and 3.6 need. | P26 |  |
| `5.1c` | Filtered export as an audited PII egress event | P | BUILT | The export is silently capped at EXPORT_LIMIT = 2000 rows in CustomerListQueryService and neither the response nor the console says the set was truncated, so a marketer exporting a 5,000-customer base gets 2,000 rows and no warning. | S | — | — |  |
| `5.2` | Customer detail — profile and date of birth | P | BUILT | Nothing for the profile itself. The IA's 'the screen Delever does not have' claim is real here — but five of its named sub-features are separate rows below and four of them are not built. | S | — | — |  |
| `5.2a` | Contact points on the customer record | P | BUILT | — | M | — | P25 |  |
| `5.2b` | Consent state and marketing eligibility, separated from record existence | P | BUILT | — | M | There is no tenant-wide consent-type/policy-version registry anywhere (settings/data-privacy/data-privacy-page.ts says so in its own doc: 'a decision log exists, a type registry does not'); the purpose vocabulary is an ADR 0015/0044 decision, not a screen fix. | P25 |  |
| `5.2c` | Saved addresses, operator-visible and editable | P | PARTIAL | `coordinateSource` is now rendered on every address card, and an existing pin is now preserved through a text-only edit (previously a regression risk, now guarded by a test). An operator still cannot create a new address with real coordinates from this screen — there is still no suggest, no pin-drop and no map, blocked on the same MapCanvas/AddressPicker (`X.4`) and unmade map-provider decision as `3.2`/`3.6`. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. | M | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `5.2d` | Order history + reorder | P | BUILT | «Повторить» now points at `P14`'s staff reorder-plan wrapper instead of the not-built page, and order status renders through the console's own label map rather than the raw enum. | M | — | P40 |  |
| `5.2e †` | Cashback balance and ledger | P | BUILT | The manual adjustment dialog now calls `POST .../loyalty/adjustments` (`LOYALTY_ADJUST`, ADR 0027 approval above threshold). `entry.amountMinor` and `balanceAfterMinor` render formatted in the enclosing balance's currency, alongside `reasonCode` and the linked order; each balance card is labelled with its brand. | S | — | P40 | BUILT |
| `5.2f` | Deposit (stored-value) balance ledger | P | BLOCKED | A customer cannot hold a prepaid balance and an operator cannot see or move one; Delever's deposit payment method has no counterpart here. | XL | Named legal input, listed in /Users/admin/Developer/HorecaOS/platform/docs/frontend-and-parity-plan.md's blocked table: 'ADR 0046 — Whether HorecaOS may hold customer prepaid balances at all — Legal'. | deferred |  |
| `5.2g` | Promo redemptions on the customer record | P | BUILT | The store query, port method, service and operations endpoint over `ix_redemptions_customer` are built, with a pane section listing which codes this customer redeemed, when and on which order. | M | — | P40 |  |
| `5.2h` | Reviews left by this customer | P | BUILT | `OperationsReviewController.list` takes a `customerAccountId` parameter and the customer pane's Reviews tab consumes it, so this customer's rating history no longer needs scrolling the whole brand-wide list and matching account ids by eye. | S | — | P40 |  |
| `5.2i †` | Blacklist / suppression with reason, actor, expiry and enforcement point | P | BUILT | The history card now renders the actor (`q-actor-chip` against `actorType`/`actorId`) alongside reason, status, created-at, `liftedByActorId` and `liftReason`, for the paid-for purpose-stamped decrypt. The reason is still free text with no tenant reason registry, so blacklist reasons cannot be reported on — a smaller residue than the row's own title, not counted against it. | S | — | P40 | BUILT |
| `5.2j` | Identity merge for aggregator-masked identities | P | BUILT | No duplicate detection — an explicit non-goal in CustomerIdentityService's own doc ('this call is the write, not the search') — so finding the two accounts is still the operator's eye, and the merge search is a plain text box, not the async Combobox PART 4 asks for. | S | — | — |  |
| `5.3` | Segments — RFM builder, saved segment entity, segmentation table, birthday window | 2 | BUILT | — | M | — | T05 |  |
| `5.4` | Reviews — service-recovery board | 2 | PARTIAL | An operator cannot work a bad review: there is no New -> In progress -> Resolved board, no handling history, no courier or four-dimension (meal/operator/courier/delivery-time) breakdown, and no compensation action — issuing a promo code or bonus means leaving for the order-remedy console with the order id copied by hand. One scalar 1-5 rating is all the data model holds. | XL | Reopening this needs a new ADR: ADR 0071 rejected the kanban and the scored dimensions on the record, so the IA's §5.4 row and the accepted decision currently disagree. | deferred |  |
| `5.5` | Feedback settings — review tag library, prompt timing, public visibility | 3 | NOT BUILT | A tenant cannot offer one-tap feedback tags, cannot choose when a customer is asked for a review per channel or order type, and cannot decide whether ratings are public. | XL | Needs a moderation/tagging decision that ADR 0071 explicitly declined, plus LocalizedFieldGroup and MediaUploader (3:2 tag icons) from PART 4's pilot-blocker list. | deferred |  |
| `X.1 †` | Data-subject erasure on the customer record (raise / withdraw / execute) | ? | BUILT | The customer pane's Erasure section now exposes raise, withdraw and execute (`CustomerController:567/:586/:602/:631`, V0178, `CustomerErasureService`) to a merchant, gating execute on `CUSTOMER_ERASURE_EXECUTE` — no longer control-plane-only. | S | — | P40 | NOT BUILT |

## §6 — Marketing

15 rows — 4 built · 5 partial · 5 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `6.1 †` | Promotions — the discount/markup/promo-code rule engine | 2 | PARTIAL | A marketer can now author, validate, simulate, activate and order automatic discounts and markups, and the cart applies them. Authoring: `PromotionController` (`/api/v1/operations/tenants/{tenantId}/brands/{brandId}/promotions`; `pricing.read` for the reads and `pricing.promotion.manage` for the writes, `If-Match` and `Idempotency-Key` as ADR 0031 asks) with `validate`, `activate`, `suspend`, `resume`, `archive`, a whole-set `PUT /priority` and `POST /simulate`; the console's Marketing → Promotions (`promotions-page`, `promotion-editor`, `promotion-simulator`, lazy-loaded) builds the AND-only condition list on `q-condition-builder` over the closed vocabulary (subtotal and quantity at least, product, category and variant, channel and channel type, branch, fulfilment mode, payment method, delivery zone by id, customer segment, weekday, time of day, first order, first order on a channel, Nth and every-Nth order), the actions (item percentage, fixed and fixed-price discounts, a bounded free item, an order percentage or fixed discount, free or reduced delivery, item markups), per-promotion limits, and the priority order on `q-rule-list`, one list per stacking group; the simulator prices a synthetic cart through the real engine and shows each promotion's verdict on `q-rule-simulator`, writing no ledger row. Engine: comparative exclusivity, per-line clamping, definition history (V0458), an atomic redemption ledger whose claim leaves nothing behind when checkout refuses and whose per-customer cap is the promotion's current one, `PromotionInputResolver` recording the inputs an order was priced with so an amendment is repriced under them (V0459), payment method as a quote input with `PRICE_CHANGED` when it is switched, and an activation above the configured percentage or amount thresholds, or any markup, raising a maker-checker request (V0461, `9.4`). Aggregator orders are priced by the aggregator and never reach the engine. Both storefronts show which kinds of benefit stand behind a price (a discount, a typed code, a delivery offer, a surcharge) with the platform's own amounts. Tested and green in this audit's run: `PromotionControllerEndpointTests` (8), `PromotionAuthoringTests` (12), `PromotionValidatorTests` (12), `PromotionEvaluatorRuleEngineTests` (22), `PricingEngineRuleEngineTests` (6), `PromotionQuotePathTests` (15), `PromotionLedgerTests` (14), `PromotionSimulatorDeliveryHttpTests` (2), `CartPaymentMethodHttpTests` (6), and the `promotions-page`, `promotion-editor`, `promotion-draft`, `promotion-simulator` and `chip-picker` specs. Still missing (ADR 0140 is Partial): order-level markup and a service charge (markups are item-level only); named-customer targeting and a polygon geozone (a zone condition matches by zone id); a gift is a line the cart already holds, `FREE_ITEM` prices it free and nothing adds it, so no storefront offers or auto-adds the gift; an approval threshold on a free item's value; the delivery-fee threshold basis (pre- or post-discount subtotal), which no Accepted ADR decides; the scheduled-order re-quote at the checkpoint; benefit grants as a redemption source (`GRANT` exists with no producer); a customer-facing title per locale (the storefronts show kinds of benefit, never a promotion name); the main storefront has no code entry, so its «your offers are already better» message is not built; `promo.*` metrics. Moved NOT BUILT → PARTIAL. | XL | Order-level markup and a service charge: the fiscal and legal input of ADR 0140 (finance, legal); named-customer targeting needs a static audience ADR 0044 and ADR 0112 do not offer; the checkpoint instants are ADR 0019's open input, and the delivery-fee threshold basis no Accepted ADR decides. ADR 0140 is Accepted; its configuration defaults are provisional and finance is to confirm them. | w3-promotions-completion |  |
| `6.2` | Promo codes — shared campaign codes, lifecycle and limits | 2 | BUILT | — | M | — | T05 |  |
| `6.2a` | Per-instance unique promo codes (the late-order apology code) | 2 | BUILT | — | L | — | T05 |  |
| `6.3` | Loyalty — accrual rate, redemption cap, point expiry | 3 | PARTIAL | `expiryWarningDays` now fires a real pre-expiry warning (`LoyaltyMaintenanceService.warnExpiringLots`, logged until ADR 0020's template exists) before `LoyaltySweeper.expireLots` destroys the lot, and a LOCATION/CHANNEL accrual rule with a bad `scopeId` is refused (422) instead of silently falling back to the brand rule. Deposit accounts and POS balance sync remain not built (out of this wave's scope; see PART B `6.3a`/`6.3b`). | M | — | T18 |  |
| `6.3a` | Loyalty — deposit accounts (customer stored value) | 3 | BLOCKED | An operator cannot top up, refund or spend a customer cash balance, and deposit is not selectable as a payment method on an order — the Депозит half of the Delever loyalty pair simply does not exist. | XL | A Central Bank of Uzbekistan e-money authorisation (or the owner's decision to hold funds under a tenant's own licence). ADR 0046 §'What would bring stored value back' requires a new ADR, not an implementation task. | deferred |  |
| `6.3b` | Loyalty — POS balance sync | 3 | NOT BUILT | A tenant whose till also holds bonus balances runs two ledgers: points earned or spent at the POS terminal never reach the platform, and a platform redemption is invisible to the till, so a cashier and the app disagree about what a customer has. | XL | No ADR covers which side owns the balance; each POS vendor (iiko, R-Keeper) needs its own capability adapter. | deferred |  |
| `6.4 †` | Campaigns — lifecycle, audience targeting, suppression | 2 | BUILT | `isWired`-gating, the scheduler-driven path, suppressions, audience read/redefine, the halted banner/re-schedule button and the History & statistics section are all unchanged from batch 10. This batch closes the row's last named gap: `CampaignDetailPane` gains an "Export recipients as CSV" action beside History & statistics, shown once the campaign has a snapshot, downloading pseudonymous account ids through the same already-audited, already-built `SegmentsPage.exportCurrentSnapshot`/`OperationsMarketingController.export` endpoint (`AUDIENCE_EXPORT`) Customers → Segments already used — no new backend endpoint. Tested (`campaign-detail-pane.spec.ts`'s export/hidden-with-no-snapshot/refused cases). | M | — | w8-marketing-automations |   |
| `6.4a` | Campaigns — SMS, email and push delivery channels | 2 | NOT BUILT | A marketer choosing SMS, email or push gets a campaign that drafts, estimates and passes approval and then cannot launch; the IA row's push cover (3:1, scheduled send, recipient and read counts) and SMS per-recipient delivery receipts have nowhere to come from. Batch 17 drafts the SMS decision, ADR 0146 (Proposed, Not started): one adapter per gateway serving every SMS purpose, and delivery receipts as authenticated, monotonic, best-effort evidence that never causes a resend. Drafting is not deciding; no SMS leaves the platform for anything but a sign-in code. | L | A real SMS gateway contract — integration/camel/notification/SmsGatewayAdapter.java is deliberately generic because 'no SMS contract exists yet'; email and push have no provider named at all. ADR 0146 (Proposed 2026-10-01, Not started) proposes generalising the VAS contract the platform has read; marketing and courier SMS stay refused until the owner answers which gateway accounts exist and what each may carry. Email and push still have no provider named. | w9-decision-adrs |  |
| `6.4b` | Campaigns — couriers as a separate SMS audience | 2 | PARTIAL | A dispatcher can now draft and target a courier SMS broadcast (`marketing.courier_broadcasts`, V0307) — every active courier or one group — from the console, with a real recipient count resolved at send. Actual delivery is refused visibly: no ADR 0020 SMS adapter exists anywhere in this build (`isWired("SMS")` is false everywhere), a platform-wide gap this wave made visible rather than caused. ADR 0146 (Proposed) is the decision this refusal waits on. | M | ADR 0146 (Proposed 2026-10-01, Not started) — the platform owner has not decided it; courier SMS stays refused until the owner answers whether a VAS account may carry courier traffic. | w9-decision-adrs |  |
| `6.5 †` | Automations — birthday, cashback change, late-order apology, inactivity/abandonment triggers | 2 | PARTIAL | BIRTHDAY, INACTIVITY and CART_ABANDONMENT (batch 11) are unchanged. This batch adds CASHBACK_CHANGE, the fourth of five named triggers: `LoyaltyAccrualService#accrue`/`PointsRedemptionService#reserve` publish a PII-free `LoyaltyBalanceChanged` Modulith application event whenever a customer's points balance moves, and `LoyaltyBalanceChangeAutomationTrigger` consumes it with `@TransactionalEventListener(AFTER_COMMIT)` — best-effort, deliberately not the `BEFORE_COMMIT` join `OrderCompletionAccrualTrigger` uses, since a firing must never be able to roll back the balance move that produced it — reusing the same eligibility/quiet-hours/guard-claim path the swept triggers get, one send path, not a second one. V0421 adds `CASHBACK_CHANGE` as a fourth `AutomationTriggerType`, reusing `trigger_config`'s existing `minimumChangeMinor`/`cooldown_days` shape, no new column. Tested (`AutomationTests`' three new CASHBACK_CHANGE cases). LATE_ORDER_APOLOGY is the one named trigger correctly still not offered: ADR 0044 (Accepted) states it is "deliberately absent," a second, unreconciled compensation path alongside ADR 0013's existing recovery case, and the ADR that would reconcile them, ADR 0112, is still Proposed — building the trigger now would re-decide an Accepted ADR's explicit exclusion without the owner, not a decision this batch can make on its own. | XL | ADR 0112 (Proposed 2026-09-13) must be decided — ADR 0044 explicitly excludes LATE_ORDER_APOLOGY until it is. | w7-marketing-triggers |   |
| `6.6` | Referrals — the referral programme and its rewards | 3 | BUILT | Nothing an operator needs on this screen. The residual gap is outside it: frontend/storefront and the ADR 0075 Telegram bot do not call ReferralStorefrontController, so a customer can only see and share their code from storefront-milliy. | S | — | — |  |
| `6.6a` | Referrals — trackable acquisition links and the Mini-App/BotFather setup flow | 3 | PARTIAL | `marketing.attribution_links` (V0309) exists exactly as ADR 0044 specified — a marketer can mint, list and archive a website `?ref=` link or a Telegram deep link and see its click count, from the referrals page. The guided Mini-App/BotFather setup flow, and recording which link actually brought a given account or order (needs new columns on other modules' tables plus a storefront/bot redirect surface), remain not built — both explicitly deferred in ADR 0044's own text. | L | — | T18 |  |
| `6.7` | Content — banners, stories, pop-ups, promotion content cards | 2 | NOT BUILT | An operator cannot put anything in front of a customer except products: no banner (image or video, priority, per-channel placement, active period), no story group with ordered slides and view counts, no entry pop-up with frequency capping, and no promo card linked to a promotion rule — so every storefront home screen shows whatever the app hardcodes. | XL | PART 4 pilot blockers that do not exist: MediaUploader with aspect-ratio crop and video support, LocalizedFieldGroup for the {ru,uz,en} strings every creative carries, plus SortableList for story slide order. | deferred |  |
| `6.7a` | Content — editorial surfaces (news, gallery albums, recipes, vacancies) | ? | NOT BUILT | An operator cannot publish news, a photo gallery, a recipe or a job listing from the console and must use their own website or a Telegram channel. This is a recorded exclusion, not a gap, and the amendment this row used to ask for is done in [the frontend information architecture](frontend-information-architecture.md): §6 row `6.7` shows the editorial sub-features struck through (2026-09-11, citing the parity matrix's exclusion and ADR 0044), the recruitment-ATS exclusion no longer keeps vacancies as content, and PART 4's RichTextEditor row no longer lists news and vacancy bodies. An earlier note said the IA row «still lists these sub-features»; it does not, and that note and the `amend` Wave marker were stale from 2026-09-11 (cleared here, as the batch-16 docs pass said the next re-audit would). This legend defines no DECLINED status, so the row keeps NOT BUILT. | XL | An owner decision is required to reinstate scope the parity matrix and ADR 0044 both excluded; PART 4's RichTextEditor (block-based, sanitized) does not exist either. | w8-declines-and-docs |  |
| `6.8` | Storefront merchandising — home groups, offer carousel, brand switcher, marketplace layout | 2 | NOT BUILT | An operator cannot decide what a channel's home screen shows — no manual or by-category product groups with priority ordering, no offer carousel, no multi-brand switcher entries, and no marketplace layout mode — so ordering of the storefront landing surface is a frontend code change, not a merchandiser's decision. | L | Depends on the same unbuilt merchandising-slot tables as 6.7; PART 4 has no SortableList/TreeView with drag-reorder for group ordering. | deferred |  |

## §7 — Reports

39 rows — 33 built · 2 partial · 4 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `7.1 †` | Business overview — KPI counters (revenue, orders, average check, cook/delivery/pickup time, distance) | P | BUILT | The distance tile is now built (wave 9 w4): `delivery_distance.average.v1` (mean `reporting.fact_order.delivery_distance_meters` over closed delivery orders, V0387/V0388) renders as Band A's sixth tile in km with the same published-formula panel and week-over-week delta as its siblings, null rendering as em-dash rather than a fabricated zero. A fix-round bug (the metric counted a still-open order the instant its delivery plan resolved a distance, not when the order actually closed) was caught and fixed before merge, proven with a seeded open-vs-closed pair. Tested both ends: `DayCloseDeliveryDistanceTests`, `business-overview-page.spec.ts`'s distance-tile cases. | L | — | w4-reports-distance-crm |  |
| `7.1a †` | Business overview — sales funnel by status with cancel reasons and late completions | P | BUILT | A real `q-funnel-chart` (new chart-family primitive, IA X.19) now renders Всего → Завершено → Вовремя, with CANCELLED/REJECTED/EXPIRED/PAYMENT_FAILED branching off Всего and a Late branch (`orders.late.v1`) off Завершено, replacing the flat two-box summary this row used to carry; the per-reason cancellation table (cost, write-off vs returned stock, who bore it) stays underneath for the detail the funnel itself does not carry. Batch 8 w7, tested (8 chart-level specs plus the page wiring). | S | — | w7-reports |  |
| `7.1b` | Business overview — source mix (channel and fulfilment-type mix) | P | BUILT | Nothing an operator cannot do. The bars are hand-rolled CSS rather than the PART 4 Chart family, so there is no legend, tooltip or drill-through, and a channel with no orders in range is absent rather than shown as zero. | S | — | — |  |
| `7.1c` | Business overview — payment mix | P | BUILT | `reporting.fact_order_tender` (V0304) is produced inside `DayCloseService`'s existing transaction; `GET .../reporting/payment-mix` and a donut-chart card on the business-overview page answer it, split by `overview` (branch-folded) and `byLocation`. The payment-method filter chip additionally narrows the same report end to end (fix4: `paymentMethodCodes` threaded from `ReportsFilterState` through to `JdbcReportingStore.readPaymentMix`'s new predicate). `payment_mix.amount.v1` is provisional pending Finance's sign-off, same as every other unsigned metric. | L | — | P39 |  |
| `7.1d` | Reports — the shared global filter bar applied to every widget | P | BUILT | Custom range, granularity, branch/channel/legal-entity controls, arrow-key stepping and URL-shareable state (`reports-filter-state.ts`) are all real now, and `channelCodes`/`legalEntityIds` have both a writer and a reader. Granularity is consumed by the overview's trend charts only, not yet every widget. | M | — | P27 |  |
| `7.2` | Order reports — per-stage duration report («Этапы») | P | BUILT | `secondsToAccept` (CONFIRMED→PREPARING, "branch acceptance") is now split from `secondsPreparing`, so waiting no longer reads as cooking, and the tab stops discarding the server's `maybeMore`. | M | — | P27 |  |
| `7.2a †` | Order reports — commercial/CRM order log («Заказы») | P | BUILT | The CRM half (wave 9 w4, unchanged) is joined this batch by the screen's own remaining gap: «Заказы» now renders through `q-data-table` (`viewId="reports.orders.commercial"`) instead of the hand-rolled `q-order-rows-table`, giving the tab a real column chooser and saved views for the first time — via per-column `qCell` templates that reproduce the existing cell content (including the CRM-joined customer/operator/courier columns), so cursor paging and the CRM join keep working unchanged. «Этапы»/«Опоздания» stay on `q-order-rows-table` (read-mostly duration tables with a per-row severity-rail hook `q-data-table` does not have). Tested: `order-reports-page.spec.ts`. | L | — | w5-reports-exports |  |
| `7.2b` | Order reports — daily operations report («Посуточно»: gross vs net, cancellations, per-fulfilment count+sum, per-3PL counts) | P | BUILT | A per-aggregator column now answers via `groupBy=['CHANNEL']` against the already-live endpoint — no new endpoint needed. | M | — | P27 |  |
| `7.2c †` | Order reports — daily summary reports 1 and 2 («Сводка») | P | BUILT | «Сводка» is now the two real roll-ups this row names: report 1 by `Тип заказа` (count, delivery-fee-exclusive sum via `delivery_fee.v1`, delivery-fee-inclusive sum, net) and report 2 the true branch×channel pivot (row/column/grand totals) — both pure functions (`order-summary-grid.ts`) over the same already-legal-entity-folded dataset the earlier ADR 0038 fix produced, replacing the flat table the prior wave shipped. Batch 8 w7, tested (`order-summary-grid.spec.ts`, 11 cases). | S | — | w7-reports |  |
| `7.2d` | Order reports — delayed-orders report («Опоздания») | P | BUILT | Nothing an operator cannot do, beyond the shared 200-row cap: a wide range shows the worst 200 late orders rather than all of them, and lateness cannot be exported. | S | — | — |  |
| `7.2e †` | Order reports — Excel/CSV export as an audited PII egress (plus the export centre) | P | BUILT | The audited chain and all four registrations (`CUSTOMER_DIRECTORY`, `ORDER_CRM_LOG`, `ORDER_REPORT_LOG`, `ORDER_REPORT_SUMMARY`) are unchanged from batch 10. This batch closes the row's own "built, no consumer" gap: `ExportCentrePage` gained a report picker (`<select>`) alongside the column chooser, one `ReportOption` per registry entry with its own column set and its own filters (`CUSTOMER_DIRECTORY` keeps status/query, the other three get a from/to date range per `ReportExportService#requiresRange`'s own split) — an operator can now actually trigger any of the four exports from `/statistics/exports`, not only a hand-crafted `ReportingApi.requestExport` call. Tested both ends (`export-centre-page.spec.ts`). | XL | — | w1-quick-closes |   |
| `7.3 †` | Branch & SLA reports — branch leaderboard | 2 | BUILT | The delivery/pickup/aggregator count triple, on-time percentage, secondary sort and the collapsed prep-time fan-out are unchanged from batch 10. This batch closes the row's last named gap: a new `GET .../reporting/delivery-transit-time-by-location` endpoint (`delivery_transit_time.average.v1`, its own endpoint rather than `/queries` since an average cannot compose from `agg_branch_day`) reads `reporting.fact_delivery`'s `transit_seconds` (carried since T11/V0337) grouped per branch, and the leaderboard gets its Ср. время доставки column and secondary-sort key. Deliberately courier-leg-only (acceptance to delivery), never the door-to-door `delivery_time.median.v1` — the registry entry's `openQuestion` says so. Tested both ends (`BranchLeaderboardReportingTests`, `branch-sla-report-page.spec.ts`). | M | — | w5-fulfillment-destination |   |
| `7.3a` | Branch & SLA reports — SLA time-bucket distribution with tenant-configurable boundaries | 2 | BUILT | `buildSlaRows`'s arithmetic defect is fixed (`sharePercent` is now the whole-range share, not the last day's printed beside a whole-range count), the `Медиана` column reads `handover_time.median.v1`, and the tint ramp + bucket-set version are present and correct. Tenant-configurable boundaries stay refused per ADR 0043, as designed — not a build gap, see Blocked by. | S | Deliberate refusal: ADR 0043 fixes the bucket set in code and statistics.md §2.3 calls the IA stale here; making it tenant-configurable needs an owner decision to overturn that | T06 |  |
| `7.3b †` | Branch & SLA reports — branch report with per-channel counts and the payment-method split for cash collection | 2 | PARTIAL | Both halves are built and tested. Table C is a per-(branch, channel) list of counts and average check (one query, aggregator channels resolved through `SalesChannelsApi.systemType`). Table D is the payment-method split, a reader of `GET .../reporting/payment-mix` `byLocation` rows (`REPORTING_READ`, `payment_mix.amount.v1`, ADR 0115): one row per (branch, payment method), with the legal entity as a column of its own when a branch trades under more than one, because ADR 0115 never sums two taxpayers' takings into one figure, and the same provisional and open-question note the overview's payment card carries (batch 19; `branch-sla-report-page.spec.ts`, `ReportingControllerCapabilityHttpTests`). **Decided 2026-10-07: the flat lists are the specified shape.** What kept this row PARTIAL was never a missing build but a layout position: Tables C and D are flat lists rather than a branch × channel matrix, no ADR decides it, and a documentation wave had amended it into [statistics.md](operations-spec/statistics.md) §2.3 and the [IA](frontend-information-architecture.md) §7 row `7.3` to fit the build without the owner ruling on it. The owner has now ruled: the acceptance sweep of 2026-10-07 confirms §2.3 as written, and §2.3 and the IA row now carry the date. The matrix the earlier notes called missing was never in §2.3, because the branch × channel pivot is «Сводка 2» in §2.2, built as `7.2c`; the two code comments that said otherwise are corrected. Nothing is left to build for this row. What a re-audit has to do is read the screen against §2.3, which now agrees with it, and move the row. | L | — | w8-declines-and-docs | NOT BUILT |
| `7.4` | Courier reports — courier leaderboard and efficiency (min/avg/max/total distance, transit hours) | 2 | BUILT | The root cause is fixed: `CourierAccrualService.recordDelivery` now has a production caller (`DeliveryAccrualOrderCompletionTrigger`), so `reporting.fact_delivery` is actually written. The leaderboard (distance min/avg/max/total, avg transit, on-time share) reads it, courier-id-only, with `P19` name resolution client-side. | L | — | T11 |  |
| `7.4a` | Courier reports — courier SLA time buckets | 2 | BUILT | The `COURIER` scope of `agg_sla_bucket_day` (the CHECK already allowed it; `DayAggregator` hard-coded `LOCATION`) now has a producer and a reader, over the same fixed six buckets as the branch distribution. | M | — | T11 |  |
| `7.4b †` | Courier reports — delivery-sum-by-tariff audit | 2 | BUILT | The delivery-sum-by-tariff audit, live and tested since batch 10, no longer reads `fulfillment`/`ordering` schema live on every request — the ADR 0023 violation an adversarial review flagged and batch 10's fix round only documented rather than fixed. `readTariffAudit` now answers from a new closed fact, `reporting.fact_delivery_fee_resolution` (V0411), projected by `DayCloseService` at business-day close beside `fact_delivery`'s own producer, in the same transaction. Tested: `DayCloseTariffAndExternalDeliveryCostFactTests` (content, idempotent-on-source-id, tenant isolation) and `JdbcReportingStoreLiveReadBoundaryTests` (a source scan proving the read no longer touches `fulfillment`/`ordering`). | M | — | w6-reporting-facts |   |
| `7.4c †` | Courier reports — external-delivery cost report (order amount vs charged delivery vs provider cost vs provider status) | 2 | BUILT | Per-order charged/billed/variance/reconciliation-status, `UNBILLED` production and the per-line reconcile action, live since batch 10, no longer read `fulfillment`/`ordering` live either — the same ADR 0023 fix as `7.4b`, over a second new closed fact, `reporting.fact_external_delivery_cost` (V0412), also projected at business-day close. It is a snapshot as of close, not a live reconciliation state — a partner invoice matched after its business day closed is not reflected in that day's row, the trade ADR 0023 asks the report to make, documented on the migration, the fact record and the read itself. Tested: `DayCloseTariffAndExternalDeliveryCostFactTests`, `JdbcReportingStoreLiveReadBoundaryTests`, `CourierTariffAuditAndExternalCostTests` (updated to close() before reading). | L | — | w6-reporting-facts |   |
| `7.5` | Staff reports — operator leaderboard and performance (orders, revenue, average handling time, average check, per-channel, delivery vs pickup; machine principals as pseudo-operators) | 2 | BUILT | Batch 17 puts names on it (ADR 0139): the operator leaderboard (`GET .../reporting/operator-leaderboard`) and the per-operator drill-down name a staff row from the tenant's own staff record through `StaffDirectory`, a name from another tenant never appears, and pseudo-operator rows (a bot, a website, a channel) carry no name; `staff-report-page` prints the name where it printed a shortened Keycloak subject and falls back to the short subject for a person the tenant keeps no name for. Tested and green in this audit's run: `ReportingControllerOperatorNamesTests` (3), `staff-report-page.spec.ts`. Not covered: the report export (`ReportExportService`) still carries the subject, and the per-operator telephony rows keep it (ADR 0139's open items). | L | — | w4-staff-identity |  |
| `7.5a` | Staff reports — operator product/upsell report with receipt depth | 2 | BUILT | — | L | — | T12 |  |
| `7.5b †` | Staff reports — telephony KPIs (calls handled, answer speed, conversion), when 1.6 is installed | 2 | PARTIAL | A Telephony tab now wraps `CallStatsController` (calls offered/answered/missed/transferred, talk seconds/hour/operator) — but answer speed and call-to-order conversion still render an explicit 'not built' notice rather than a number: `V0149` has no ring/wait-time column and conversion needs `W01`'s call-provenance wiring. | M | Answer speed needs a ring/wait-time column added to `V0149` (none exists); call-to-order conversion needs `W01`'s call-provenance wiring. ADR 0064's live-VOICE-provider gap (both adapters proven only against a fake PBX) no longer blocks the built half, which reads whatever the configured adapter reports. | T12 | BLOCKED  |
| `7.6` | Customer analytics — KPI tiles with published formulas (new customers, basket depth, order frequency, customer value, LTV) and the cancelled/refunded rule | 2 | BUILT | Eight customer-grain metrics are registered and published through `GET /reporting/metrics`'s «?» panel; `CustomerAnalyticsPage` renders six KPI tiles from `GET .../reporting/customer-kpis`. `customers.new.v1`/`customers.distinct.v1` now count a customer whose only order is cancelled (fix6, was a confirmed high-severity miscount against the metric's own registered definition). LTV stays `sourceAvailable=false`, disclosed with a note. | L | — | T13 |  |
| `7.6a` | Customer analytics — registrations vs orders trend, repeat-purchase distribution, cohort views, new vs returning revenue and share | 2 | BUILT | `GET .../reporting/customer-cohorts` (monthly cohorts, retention curve, refuses a range over 12 months) and a new-vs-returning revenue split (`groupBy=CUSTOMER_TYPE` off `fact_order.is_first_order`) are both real and tested; `CustomerAnalyticsPage` renders a `HeatmapChart` cohort view. `revenue.new_vs_returning.v1` publishes GROSS revenue only — `reporting.fact_refund` carries no customer attribution, so a refund cannot be assigned NEW vs RETURNING; documented on the metric's own `refundTreatment` field. | L | — | T13 |  |
| `7.6b` | Customer analytics — RFM cross views | 2 | BUILT | `GET .../reporting/customer-rfm` (a platform-fixed 3×3 Recency×Frequency grid, member counts + revenue per cell) renders as a table on `CustomerAnalyticsPage`. Now refuses a combined revenue total across legal entities per ADR 0038, matching the sibling `customerKpis` method (fix6 — was a confirmed finding, reported four times, of `revenueSom` silently summing money across entities with no refusal). | M | — | T13 |  |
| `7.6c` | Customer analytics — acquisition-source chart and the product funnel (visits → registrations → cart adds → orders) | 2 | NOT BUILT | There is no visibility into anything before the order — visits, registrations and cart adds are recorded nowhere, so the funnel will start empty on the day it is finally built, and acquisition source is unknown. | XL | ADR 0043 open input: legal must confirm lawful basis and retention for behavioural telemetry (running on a provisional default today, so a risk rather than a hard stop) | deferred |  |
| `7.7 †` | Product analytics — product report («Продажи») | 2 | BUILT | `Категория`/`СТОП` and the filter-bar reactivity fix (unchanged) are joined this batch by both of the row's remaining named gaps: a qty/revenue/name segmented sort control (`GET .../reporting/variant-sales` gains `sort` + a keyset cursor — `afterQuantity`/`afterRevenueSom`/`afterProductName`/`afterVariantId`, backward-compatible with the old 6-arg overload) and a "Load more" button that pages past the previous hard 200-row cap, appending rather than replacing rows, recomputing `revenueSharePercent` over the full accumulated set each time. Changing the sort restarts from page one. Tested both ends: `VariantSalesReportingTests`, `ReportingControllerCapabilityHttpTests`, `product-analytics-page.spec.ts`. | S | — | w5-reports-exports | BUILT |
| `7.7a` | Product analytics — ABC by revenue share with the cumulative column | 2 | BUILT | `reporting.classification_run`/`classification_result` (V0364-V0365) persist the window, thresholds and metric per run; the ABC tab renders the cumulative-share column and the run's own printed caption line. A run now refuses rather than combines revenue across more than one legal entity (fix6 — was a confirmed finding, reported three times, that ranking had no legal-entity concept at all). | L | — | T14 |  |
| `7.7b` | Product analytics — XYZ (stdev / coefficient of variation) and the ABC×XYZ matrix as filters | 2 | BUILT | The XYZ tab (quantity/mean/stddev/coefficient of variation/class) and a shared 3×3 AX..CZ matrix as a click-through filter over both tables are real and tested; the ≥28-day floor is enforced server-side and surfaced in the console as a named refused state rather than a silent short read. Same legal-entity refusal fix as `7.7a`, since both ranks come from the one persisted run. | L | — | T14 |  |
| `7.7c` | Product analytics — kiosk sales report | 2 | NOT BUILT | A decline, not a gap — nothing an operator loses today. [statistics.md](operations-spec/statistics.md) §2.7 (with §5 «Skip»; the parity matrix grades Delever's report ○, peripheral) declines a kiosk-only report ('a separate report for one channel is a precedent that ends in eleven reports'), and [the frontend information architecture](frontend-information-architecture.md) §7 row `7.7` was amended to say so on 2026-09-11 (it now reads «kiosk readable as a channel slice of the sales report»); §2.7 was amended again 2026-09-30 to state it as a decision and to say what the replacement lacks. The replacement is kiosk as a channel slice, and the accurate state is narrower than "the filter bar cannot express it": the shared filter bar has a channel picker (P27) that 7.1 and 7.2 read, but the product tab reads only the period and fulfilment type and `GET .../reporting/variant-sales` takes no `channelCode`, so a kiosk product slice cannot be read there yet. That is the buildable half and it is not a kiosk report. No DECLINED status exists in this legend, so the row keeps NOT BUILT. | S | Deliberately declined in statistics.md §2.7; reinstating it is an owner call, not engineering | w8-declines-and-docs |  |
| `7.8` | Demand forecast — forecast vs actual by hour and branch | 3 | BUILT | `reporting.forecast_run`/`fact_forecast` (V0367) back a seasonal-naive model with an 80% confidence interval, generated daily per location by `ForecastScheduler` and backfilled against actuals once a forecasted date closes. `demand-forecast-page.ts` gained a branch selector, the operating-day-relative hour axis, and a Forecast section (model version, interval, forecast-vs-actual trend) — all real and tested. | XL | — | W02 |  |
| `7.8a` | Demand forecast — by department and product | 3 | BUILT | An opt-in department/product breakdown renders on the forecast page. «Department» is implemented as catalog category (`catalog.category_products` via `JdbcReportingStore#readSourceLines`'s new join), not kitchen station/routing (`kitchen.stations` + routing rules) — a kitchen cannot yet plan prep per station from this screen, only per menu category; breakdown rows also show the raw category UUID, not a resolved name. | L | — | W02 |  |
| `7.8b` | Demand forecast — holiday-aware modelling (calendar owned in 10.10) | 3 | BUILT | `HolidayCalendar`/`HolidayAwareness` join the existing `tenant.public_holidays` (keyed by the location's country) into `/reporting/demand-history` and the forecast model; a holiday-mode segmented control (INCLUDE/EXCLUDE/WEIGHT) and a per-date flag are both wired on `demand-forecast-page.ts` and tested. | L | — | W02 |  |
| `7.9 †` | Marketing reports — promo-code summary and per-code redemption detail (customer, channel, timestamp) | 2 | BUILT | The report this row names is built and, since batch 18, answers «who redeemed it». `GET /api/v1/tenants/{tenantId}/reporting/promotions/summary` and `.../redemptions` (`REPORTING_READ` at tenant scope) read `reporting.fact_promotion_redemption` (V0460): one row per order and promotion, written by `DayCloseService` from pricing's `PromotionRedemptionSource` (the ledger and the coupon redemptions, so a typed code and an automatic promotion are in one report, told apart by `sourceKind`), idempotent when a day is closed twice, moved and not failed when the business-day boundary changes, and compared by the recut. The summary gives, per promotion, the redemptions, the unique customers, the discount given, the markup, the revenue with it and the average check with and without it, the counterfactual the spec exists for (the brand's other completed orders in the period; cancelled, rejected, expired and payment-failed orders leave the summary and stay in the log). The log is newest first, at most 500 rows, each with the channel, the order's status and the time. The console's Reports → Marketing → Promotions tab (`marketing-report-page`) shows both, narrows the log to a clicked promotion, offers a retry and says so when nothing was redeemed. Per-code detail names no account on any screen; the reveal below is the one way to it. Tested and green in batch 17's run and again in this one: `PromotionRedemptionFactTests` (10), `PromotionReportControllerHttpTests` (4), `PromotionLedgerTests` (14, the real source over the real ledger), `marketing-report-page.spec.ts` (21 cases). Batch 18 (`w3-promotions-completion`, with `fix18-c-promotions`) closes both reasons this row stayed PARTIAL. **Who redeemed it.** `POST /api/v1/operations/tenants/{tenantId}/brands/{brandId}/promotions/{promotionId}/redemptions/{redemptionId}/customer-reveal` (`customer.read` at brand scope and a stated purpose) writes a security audit fact targeted at the customer account, carrying the redemption, the promotion and the source and never an amount or a name, and answers with the account id to open the customer card with, or with no account for a guest order, which is recorded all the same. It works for an automatic promotion's ledger row and for a promo code's redemption alike and is scoped to the brand and the promotion in the path. The log's redemption rows offer «show customer» to an operator who holds `customer.read` (and say it is recorded); the row then links the revealed account to its customer card, puts no name or contact on the row, says a guest order has no card, says plainly when the platform refuses and offers a retry, and does not look the same row up twice. The review round closed the bypass the first cut left: the two redemption lists (`GET promotions/{id}/redemptions` and `GET promo-codes/{id}/redemptions`) returned the same redemption-to-account linkage under `pricing.read` with no purpose and no record, so anyone who could read promotions could walk every redemption to its customer and the control gated nothing. `customerAccountId` is now null for every caller (deprecated in the v1 contract, because ADR 0031 allows no field to be removed within a major version) and the promo-codes screen's customer column is gone. **The chain.** `PromotionLifecycleHttpTests` (10, green in this audit's run) goes from a real checkout through the production day close to the 7.9 summary and log over HTTP and then to the reveal, for an automatic promotion (`aFiringPromotionTravelsFromCheckoutThroughDayCloseToTheReportAndCanBeTraced`) and for a typed code (`aTypedCodeIsReportedAndTracedTheSameWay`), on the test's own clock. Tested and green in this audit's run, beyond the list above: the reveal's capability and purpose checks, the redemption lists naming no customer (the test reads both as `TENANT_FINANCE`, which lacks `customer.read`, and as an owner), `marketing-report-page.spec.ts` (the reveal cases: nothing is offered without `customer.read`, a row asks for itself under its own brand and promotion, the card link, the guest case, the refusal and the retry). **Decided, not a gap: today's redemptions appear in this report after the business day closes.** The fact is written by the day close, which is the platform's one definition of a redemption in a report: ADR 0043 builds day-grain facts after each business day ends and re-derives them after the settle window, and ADR 0140's own Consequences name the price, that «the 7.9 report lags by up to a business day, in exchange for one definition of a redemption». The page already says so when it matters (the provenance banner's «settling» band for a day not yet closed, and the red band when a recut disagreed). The platform owner's acceptance sweep of 2026-10-07 leaves it as decided; there is no intraday read to build and none is owed. What remains a gap, and is not this row's: benefit grants (`source_kind = GRANT`) have no producer, and the ADR 0043 `promo.*` reporting metrics are not built (`6.1`). ADR 0140's status line still says the per-promotion list returns account ids unrecorded; the code no longer does. Moved PARTIAL → BUILT. | XL | — | w3-promotions-completion | NOT BUILT |
| `7.9a` | Marketing reports — per-customer discount history | 2 | BUILT | A per-customer discount history read (`CustomerDiscountHistoryController`, `PRICING_READ` at `TENANT` scope) is built and has a real consumer — the Marketing reports page's customer-search + discount-history tab, showing every reservation/redemption/release plus a per-currency total. The customer-detail-pane consumer named in the original brief belongs to `P40`, not yet merged; the endpoint is ready for it. | L | — | T15 |  |
| `7.9b` | Marketing reports — campaign delivery and read statistics | 2 | BUILT | `GET .../campaigns/{campaignId}/recipients/counts` and the report's campaign tab render pending/queued/deferred/refused/total, with an explicit note that read receipts have no data source (`NotificationStatus` has no READ state) rather than showing a false zero. Promo-code summary/per-code redemption detail (`7.9`) stays deferred, per ADR 0023 — not a gap in this row. | L | — | T15 |  |
| `7.10` | Geography — order-density heatmap over the delivery zones | 3 | NOT BUILT | Nobody can see where orders actually come from against the zone boundaries they drew, so a badly cut zone or a missing branch catchment is undetectable from the console. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. ADR 0145 also names what this row needs besides the map: a zone dimension in the reporting facts (ADR 0037 keeps coordinates out of `DeliveryFeeResolved`), not a coordinate. | XL | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. It also needs a zone dimension in the reporting facts. | w9-decision-adrs |  |
| `7.10a` | Geography — today's orders as pins | 3 | NOT BUILT | A dispatcher cannot see the day's orders on a map, so clustering and outlier drops have to be inferred from the order list. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. ADR 0145 treats the pins as a dispatcher-scope read with an audited purpose and never as a reporting fact, since they need doorsteps. | L | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `7.10b †` | Geography — delivery-time and distance histograms | 3 | BUILT | The duration histogram (unchanged) is joined this batch by its distance sibling: a new `DistanceBucketSet` (platform-fixed, versioned, six half-open meter ranges, mirroring `SlaBucketSet`) backs `GET .../reporting/distance-buckets` (a live, zero-filled bucket count over `reporting.fact_delivery` for the selected branch) and `GET .../reporting/distance-bucket-set` (the published boundaries and version). The geography page renders it beside the duration chart, with a caption listing the published ranges fetched from the bucket-set endpoint rather than hard-coded client text. Tested both ends: `DistanceHistogramReportingTests`, `ReportingControllerCapabilityHttpTests`, `geography-page.spec.ts`. | L | — | w5-reports-exports |  |
| `7.10c †` | Geography — day-of-week × hour cohort grid | 3 | BUILT | Seven `GET .../reporting/demand-history` calls (one per weekday) for the selected branch, coloured by `averageOrders` via `q-heatmap-chart` (gained an optional `cellClicked` output for this), plus cell drill-down through a bounded `GET .../reporting/orders` call per sampled date filtered to the clicked weekday/hour, capped at 300 rows with an honest "there may be more" note when a date's own read came back full. A review round fixed a branch-switch race (a load-generation counter now discards a superseded response), operating-hour math untested against a non-midnight `businessDayStart`, and the drill-down originally fetching by date-range rather than the sampled weekday. | M | — | W04 | NOT BUILT |

## §8 — Finance

11 rows — 7 built · 2 partial · 1 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `8.1` | Payments & settlements | P | BUILT | A granted future discount still cannot be redeemed anywhere, since ADR 0018 pricing never calls `RemedyEntitlementPort`, and `DeliveryFeeBasisPort` stays unwired — both pre-existing, deliberately not fixed by this wave. | M | — | P29 |  |
| `X.1 †` | 8.1 — split tender: the payment[] array (cash + cashback + deposit) | 3 | PARTIAL | The `payment[]` array — every settlement tender, in sequence, with status and refunded amount — is now on the order-payment read and rendered in `payments-page.html`. Recording a redemption leg after checkout is deliberately not built: doing so would mean shrinking an already-created `PaymentIntent` and renegotiating it with the provider mid-flight, a real design decision, not an oversight. Deposit tender remains out of scope. | XL | — | W05 | NOT BUILT |
| `8.2` | Fiscal receipts | P | BUILT | — | M | — | P29 |  |
| `X.2` | 8.2 — the fiscal evidence itself: receipt URL/codes, INN used, fiscalized payment types, delivery IKPU, marking codes | P | NOT BUILT | An operator handed a customer complaint or a tax query cannot produce the receipt: no fiscal sign or receipt URL, no statement of which INN issued it, no fiscalized payment types, no delivery-line IKPU and no marking codes anywhere in the console — and no correction or void command exists to fix a wrong one. | XL | ADR 0038's legal open input — which party is the legal fiscal agent per settlement path (legal, finance), per platform/docs/frontend-and-parity-plan.md:168 — governs correction/void obligations and marked-goods handling. | deferred |  |
| `8.3` | Cash reconciliation | 2 | BUILT | `CourierAccrualService.recordDelivery` now has a production caller (`T11`), so `cashCollectedDuringShift` is non-zero and a handover opens on a real tenant. The worklist carries identity, shift, location and declared-at, with status and location filters, five money columns including bonus-paid, and per-shift/fleet payment-method totals. | L | — | T07 |  |
| `8.4` | Delivery cost reconciliation | 2 | BUILT | The import form and match action are wired (`match()` idempotency fixed so a second call is safe), with `UNMATCHED_LINE` resolution, `VARIANCE` accept/dispute, whole-invoice dispute, a charged-vs-cost margin tile, and `matchStatus` — the per-line reconciliation state — localized in all three catalogues. | M | — | T07 |  |
| `X.5 †` | 8.4 — partner invoice import and variance matching (operator workflow) | 2 | BUILT | Same import+match+resolution work as `8.4` (the two rows share one screen and one backend surface): an operator can load a partner's monthly file, resolve an `UNMATCHED_LINE`, and accept or dispute a `VARIANCE`. | M | — | T07 | NOT BUILT |
| `8.5` | Courier payouts | 2 | BUILT | Km, hours, bonus and penalty now bind to the salary report table (bonus/penalty split computed at read time off the ledger); statement download is wired end-to-end (the endpoint existed, nothing called it before); ledger balance currency reads off the response instead of hard-coded UZS. Gross earnings are non-zero now that `T11` wired `CourierAccrualService.recordDelivery`'s production caller. | L | — | T07 |  |
| `8.6` | Subscription & billing | 2 | PARTIAL | Plan, entitlements, usage, statements, the purchasable-module catalogue with inline purchase (behind a confirm dialog that names the module, its total price, its billing unit and what it switches on, batch 15), the tenant-visible arrears state and the restricted-feature banner are built (batch 10, ADR 0088). Batch 16 (`w6-billing-self-service`, with `fix16-f-billing`, ADR 0127) builds the tenant's own «end». V0442 records on `tenant_modules` which door a module came through (`acquired_via`: PLATFORM or SELF_SERVICE, backfilled from the fixed self-service purchase reason). `POST /api/v1/tenants/{tenantId}/commercial/modules/{id}/end`, under `COMMERCIAL_SUBSCRIPTION_MANAGE` at tenant scope, ends only a SELF_SERVICE row: a platform-assigned module is refused 422 `MODULE_ASSIGNED_BY_PLATFORM`, another tenant's module is not found, a second end is 409, the end is audited with a before and after, and the answer carries `lastBilledPeriod`, because nothing is prorated (ADR 0087 and 0088): the month it ends in still bills the module and no later one does. The Subscription page gains a «Your modules» table (source: bought by you, or assigned by HorecaOS) with End offered only where the server says `endableByTenant`, behind the same confirm dialog in the destructive tone, which says the module switches off now and that nothing is prorated; after a successful End it re-reads the list and the entitlements, and the fix round marks the row ended from the server's own answer when that re-read fails, so a module that ended is not shown with an End button that would only answer 409. The review also found `StatementService.draft` billed a module twice when it was ended and bought again in the same month, which is now a two-click path (and the way to change a PER_UNIT quantity); the draft folds a month's rows into one charge per module. Tested and green in this audit's run: `CommercialSelfServiceEndpointTests` (17), `TenantModuleAcquisitionTests` (8), `ModulesStatementsAndArrearsTests` (17), `commercial-api.spec.ts`, `subscription-page.spec.ts`. Still absent: the prepaid wallet, its top-up and the credit-expiry warning, which the IA row names (`8/X.3`, blocked on ADR 0095). | L | `8/X.3`: ADR 0095 (the prepaid wallet) is not started (PART B) | w6-billing-self-service |  |
| `X.4 †` | 8.6 — invoices the merchant can read | 2 | BUILT | — | M | — | T19 | NOT BUILT |
| `X.3` | 8.6 — prepaid wallet and top-up via Click/Atmos | 2 | BLOCKED | A merchant cannot hold a prepaid balance, top it up with a card, see bonus money kept apart from money it paid, or be warned before a credit lapses — the activation deposit is billed as an ordinary statement line instead of being held. | XL | ADR 0095 is not-started with 'Date decided: —'; its Deciders line records that the platform owner answered the inputs on 2026-09-11 but that Ayubkhon Abbosov (platform owner) has yet to decide the proposed structure. No schema, service or endpoint may be written before that acceptance. | deferred |  |

## §9 — Staff: grants, roles, telegram links, shifts

20 rows — 13 built · 5 partial · 2 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `9.1 †` | Users & roles — accounts and role assignment (grants) | P | PARTIAL | Capability search and the dead holder-count button are now real, so a manager can find a grant by name or role — but the TENANT-scope `IAM_GRANT_MANAGE` limit that keeps this screen unreachable by a branch manager is only raised as a proposal in ADR 0103, not fixed: the spec's «Chilonzor manager sees Chilonzor's team» view is still unreachable by the person it was designed for. | S | — | P30 | BUILT |
| `9.1a` | Users & roles — accounts: invite a staff member (Пригласить) | P | BUILT | `POST .../staff/invitations` creates a Keycloak account (phone-first, `StaffAccounts#create`), links the tenant, grants the chosen job, and returns a one-time link the console shows with a copy button — no email/SMS sending required, sidestepping ADR 0097's still-unconfigured sending provider entirely. Duplicate phone is refused (409, tenant-scoped — fix4). Resend now captures its own response and reopens the invite dialog to show the fresh link (`staff-page.ts`'s `resentLink`, tested in `staff-page.spec.ts`'s "shows the fresh one-time link a resend returns" and `staff-invite-dialog.spec.ts`'s `resent` input) — before fix4 the link was discarded and never shown. Revoke and the outstanding list are wired too, each with its own capability-refusal HTTP test; the «Приглашён» pill shows on `staff-page.ts`. | L | — | S01 |  |
| `9.1b` | Users & roles — capability grid, capability search, role templates (Должности) | P | PARTIAL | An owner cannot author a job of her own, cannot tick or untick a single permission, and cannot search the permission list — she can only read what the eight fixed jobs happen to carry and pick the nearest one. | L | ADR 0025 closed input: «no tenant-defined roles in v1» — reopening that decision is the trigger for the permission grid. | deferred |  |
| `9.1c †` | Users & roles — permission-gated navigation | P | BUILT | — | M | — | P30 | NOT BUILT |
| `9.1d †` | Users & roles — locked-by-plan vs denied-by-permission, with inline upsell | P | BUILT | — | M | — | P30 | NOT BUILT |
| `9.2 †` | People — the staff person record (operator records: name, phone, photo, employment) | 2 | BUILT | The People screen names its people. The list (`staff-page`, `GET .../staff/members` at tenant, brand or branch scope, each its own route because a grant covers only the routes whose path names its level, ADR 0025; a branch manager sees only people with an active job at their branch, and a branch with none answers as if it did not exist) and the person card (`staff-member-detail-pane`) read the tenant's own staff record (`iam.staff_members`, V0453: one row per tenant and Keycloak subject, name and phone sealed under ADR 0029 with a masked phone in lists and the full number only on a single-person read, a private photo, interface and spoken languages, employee number, employment status and dates; V0454: the `S-0142` reference, never reused). A manager holding `staff.profile.manage` edits the record and ends employment (`PUT .../staff/members/{memberId}`, `POST .../end-employment`, `If-Match`), which ends the person's grants after the status flips, and an ended member who still holds a job shows as access drift, computed at read time. The record is created inside the invitation, promoted to ACTIVE on acceptance and in the owner's `completeSetup`, and `StaffMemberReconciler` (scheduled) backfills accounts that predate it. Every audit actor, the order detail's created-by and accepted-by lines (`9.2d`) and the leaderboards read names through `StaffDirectory`, a `(tenant, subject)` cache evicted on write that replaces the deleted Keycloak-only `StaffDisplayNames`; a removed or replaced photo leaves the store (`StaffPhotos.discard`, `MediaAssetDeletionWorker`). (Corrected from the earlier note: a colleague's name had resolved on two screens before this batch, from an interim Keycloak read cached under the subject alone; that read is gone.) Tested and green in this audit's run: `StaffMemberServiceTests` (42), `StaffMemberEndpointTests` (20), `StaffMemberReconciliationTests` (14), `StaffEmergencyContactServiceTests` (8), `StaffResponseClassificationTests` (2), and `staff-page.spec.ts`, `staff-member-detail-pane.spec.ts`, `staff-members-api.spec.ts`, `staff-profile-form.spec.ts`, `staff-row.spec.ts`. Residue (ADR 0139 is Partial): the drift half of ADR 0009 (the reconciler promotes PENDING members and backfills, it does not detect drift in `iam.principals` or `iam.tenant_membership_links`); the retention sweeper ships in report-only mode, so an ended employee's personal data is counted and logged and never anonymised until legal answers (ADR 0029's provisional 24 months stands); ending a PENDING member does not cancel its outstanding invitation, and ending employment is a status change followed by per-grant revokes, not one transaction; a manager cannot set another person's photo (the person sets their own); the CRM log's «Оператор» column, the call log, the presence view and the report export still print the Keycloak subject; and the responses other modules replay under `@Idempotent` were not audited for personal data. Moved NOT BUILT → BUILT. | XL | — | w4-staff-identity |  |
| `9.2a` | People — branch bindings (who works where) | 2 | BUILT | Nothing substantive for the binding itself; it inherits 9.1's ceiling — only a tenant-scope administrator can see or change it, and a person with no grant at all has no row to bind. | S | — | — |  |
| `9.2b †` | People — contact persons | 2 | BUILT | A branch has named contact persons and a staff member has emergency contacts. Branch: the Основное tab of a location (`location-detail-pane` → `q-location-contact-persons`) lists up to ten people, each with a role and either a colleague (picked from the people who work at that branch, shown by the name the tenant keeps or by the non-personal `S-0142` reference alone, with a call link) or an outside person typed in as a name and a phone; a colleague who has left shows as a reference marked `formerColleague`, with no number, and the editor will not save while one is still on a row. `GET` and the replace-the-set `PUT .../locations/{locationId}/contact-persons` (`LOCATION_READ`, `LOCATION_WRITE`, the location's version as `If-Match`) store it sealed (V0455). Emergency contacts: the person card's panel (`q-staff-emergency-contacts`, at most three) reads nothing until asked, hides again on «Скрыть», and replaces the set under the member's version (`GET` needs `STAFF_EMERGENCY_CONTACT_READ`, the `PUT` `STAFF_PROFILE_MANAGE`; V0456; each read and write is audited). Tested and green in this audit's run: `LocationContactPersonEndpointTests` (12), `StaffEmergencyContactServiceTests` (8), `StaffMemberEndpointTests` (`emergencyContactsAreGatedAndAudited`), `location-contact-persons.spec.ts` and `staff-emergency-contacts.spec.ts`. Residue: `LOCATION_MANAGER` holds `LOCATION_READ` and not `LOCATION_WRITE`, so a branch manager reads the branch's contacts and cannot edit them; the lawful basis and the notice for holding a third party's contact details are an open ADR 0139 input (legal), so the panel is built and the policy around it is not; `tenant.locations.contact_phone` still carries the plain branch phone. Moved NOT BUILT → BUILT. | M | — | w4-staff-identity |  |
| `9.2c` | People — operator ↔ POS operator ID mapping | 2 | PARTIAL | The writer now exists, and the value it writes is still sent to no till. Built: `MappingEntityType.OPERATOR`; the POS mapping tab on an installation's detail (`installation-detail-panel`, `10.8b`) offers an `OPERATOR` pairing, the till's id for a colleague typed in by hand because no adapter lists a POS's operators, against the people the tenant keeps a record of, chosen by name (`PosMappingService`, the same mapping endpoints, each side claimed once, a member of another tenant no oracle); `PosOrderExportService#resolveOperatorExternalId` resolves it off `accepted_by_actor_id` and puts it on `OrderExport#operatorExternalId`, so a mapping written in the console resolves on the next accepted order (`PosOperatorMappingEndToEndTests` (5): `theMappingResolvesOnTheNextAcceptedOrder`, `eachSideIsClaimedOnce`, `aForeignMemberIsNoOracle`, `theCapabilitiesAreThePanesOwn`). Why it stays PARTIAL: `CloposAdapter`, the only POS adapter, deliberately never reads `operatorExternalId`, because Clopos's documented `CreateOrderRequest` (`docs/providers/clopos-api.md` §6.5) names `sale_type_id`, `venue_id`, `customer` and `products` and no waiter or user field, and the adapter does not guess one (the comment at `CloposAdapter` line 499 says so). The field has a writer and a reader on the port and no sender, which by the standing rule is not a finished row; it becomes BUILT with the first provider whose documented order API carries an operator. | L | A POS adapter whose documented order API carries an operator: Clopos's does not (docs/providers/clopos-api.md §6.5), and it is the only POS adapter. | w4-staff-identity |  |
| `9.2d †` | People — created-by / accepted-by attribution | 2 | BUILT | The order-detail half (`OrderDetailResponse` resolving `createdByActorId`/`acceptedByActorId` to a display name) is unchanged from batch 10. This batch closes the row's last gap: a new `GET .../orders/operators/{subject}/today-counts` endpoint (`OperatorTodayCountsController`, over the existing `created_by_actor_id`/`accepted_by_actor_id` columns and `ix_orders_created_by`, for the tenant's own ADR 0043 business day) backs "Created today"/"Accepted today" on the staff member's own detail card. Tested both ends (`OperatorTodayCountsControllerEndpointTests`, `staff-member-detail-pane.spec.ts`). Batch 17 (ADR 0139): the names these lines show, and the activity log's actor column (`9.3`), come from `StaffDirectory`, the tenant's own staff record, and the Keycloak-only read (`StaffDisplayNames`, `CachedStaffDisplayNames`) is deleted. | M | — | w4-staff-identity | PARTIAL |
| `9.3 †` | Activity & audit — the activity log screen (История изменений) | 2 | BUILT | Cursor paging past 200 events, four backend-ready filters and a person-picker datalist are unchanged from batch 10. This batch closes both of the row's remaining gaps: the action-code dictionary now covers all 208 `AuditFact.of("<code>", ...)` literals enumerated straight from the platform's own Java sources, in en/ru/uz-Latn, generated by a script with its own coverage test (`activity-log-action-codes-coverage.spec.ts`) that fails the build if a new literal-coded action ships with no sentence — up from 12 hand-named codes with a humanized fallback for the rest; and a person's staff card now carries a "View activity log" link that deep-links into the activity log pre-filtered to that person via a new `?actor=` query param. A handful of producers that build their action code from a variable rather than a literal (documented in the coverage test's own doc) are out of the scan's reach and still named by hand. | M | — | w7-audit-people |   |
| `9.3a †` | Activity & audit — field-level before/after diff | 2 | BUILT | The migration to `ChangeDocuments.diff/change/created` (about 200 sites through batch 13, the regression guard `ChangeDocumentUsageTests`) is unchanged in shape. Batch 15 closes the three services batch 13 named as writing no audit fact. `OrderAcceptancePolicyService#author` records `ordering.acceptance-policy.authored` in the publishing transaction with a before/after diff of the resolved policy (mode, approval channel, timeout, timeout action, the rejection-reason and notify flags, version); `OrderOutcomeReasonService` create, update, archive and reorder record `ordering.outcome-reason.created/updated/archived/reordered` the same way. The review fix round found that an outcome reason's category edit answered 200 while the UPDATE never wrote `system_category`, so the fact's 'after' — built from the request — recorded a change that never happened; the UPDATE now writes it and the 'after' is the row read back. `OrderLatenessPolicyService` writes nothing and needs nothing: it only overlays two scalars a tenant sets through the ADR 0030 configuration surface, whose value author already records a before/after (`tenant.configuration_value.set`; `OrderLatenessTenantSettingsHttpTests` reads that fact for the late colour — actor, reason, before and after), and the `ordering.lateness` document itself had no authoring endpoint then. Batch 16 gives it one (`X.39`), and `OrderLatenessPolicyAuthoringService` records `ordering.lateness-policy.authored` in the publication's transaction with a before/after diff of the per-mode numbers (`OrderLatenessPolicyAuthoringServiceTests` (12) and `OrderLatenessPolicyEditorHttpTests` (19), green in this audit's run, which reads the fact). Batch 16's other new writes leave facts too: `payment.checkout_reissue_requested` and `payment.checkout_reissued` (never the payable link or the phone), `brand.regional_formats_revised`, `catalog.fiscalClassification.bulkSet` and `commercial.tenant_module.ended`; `ChangeDocumentUsageTests` and the activity log's code-coverage spec are green, and the 34-candidate list below is untouched by batch 16. The activity log names the five new codes (`activity-log-action-sentences.ts`; `activity-log-action-codes-coverage.spec.ts`, which enumerates literal `AuditFact.of` codes from the source, is green). Tested and green in batch 16's run: `OrderPolicyAuditHttpTests` (9), `OrderAcceptancePolicyServiceTests`, `OrderOutcomeReasonServiceTests`, `OrderLatenessTenantSettingsHttpTests` (9), `ChangeDocumentUsageTests` (allow-list unchanged at 3: `KitchenTicketService:1143` and `MigrationAudit:63`/`:108`, the documented parameter-forwarding false negatives; non-comment `.changed(` sites in `src/main` went from 244 to 246 by a source grep). Batch 18 (`w2-audit-approvals-privacy`, with `fix18-b-audit`) finishes the list that batch 16 left as a lead. It gave every configuration-authoring service that scan named an audit fact written in the service's own transaction, so a refused or stale write leaves no fact and a recorded fact is never for a write that rolled back (`ConfigurationAuthoringAuditTests`, 11, and `OperationalConfigurationAuditTests`, 8, both green in this audit's run; they assert, among other things, that no stored fact carries a promo code's word, a DNS token, notification wording or a credential reference). The facts: promo codes (`promo.code.drafted`, `.activated`, `.retired`); sales channels (`channel.created`, `.updated`, `.deactivated`, `.reactivated`, `.archived`, the payment-method, fulfilment-mode, location and social-link matrices recording what was on and what is on now, a hostname's claim, verification, challenge rotation and clearing, the search presentation, and `channel.page.published`); loyalty accrual rules and redemption policies (`loyalty.accrual_rule.*`, `loyalty.redemption_policy.*`, the cap before and after); referral programmes (`referral.program.*`); kitchen stations, their capacity and routing rules (`kitchen.station.created`, `kitchen.station_capacity.*`, `kitchen.routing_rule.*`); notification templates (`notification.template.created`, `.version_added`, `.version_activated`: the version and the size per language, never the wording); merchant bindings (`payment.merchant_binding.registered`, `.activated`, `.suspended`, `.archived`, class SECURITY, never a merchant reference or the credential reference); and catalogue publication (`catalog.published`, `catalog.publication.rolled_back`, naming the person who published or, when no signed-in person did, the job). The authoring services now ask `CurrentActor` who changed what, which refuses a caller with no token, so two fixtures that reached a service without a request were changed to run as a signed-in staff member. The activity log has a sentence in ru, uz-Latn and en for every new code (`activity-log-action-codes-coverage.spec.ts` fails the build for a literal code with none, and the operations suite is green), and its drawer renders `{before, after}` through `q-diff-viewer`. `ChangeDocumentUsageTests` (2) is green and its allow-list gained no entry (the same three parameter-forwarding false negatives, `KitchenTicketService` moved from line 1154 to 1433 and `MigrationAudit:63`/`:108`). **The scan, classified as asked.** This audit re-ran a source scan of the same shape (application services that write through a store or SQL and name no `AuditFact`, `AuditRecorder` or `ChangeDocuments`; a regex over the `application` packages, so its count is its own and not batch 16's 34): 39 candidates on `main`, 31 on this branch, the eight fewer being eight of the ten services above that were converted (the regex does not see `KitchenStationService` or `NotificationTemplateService` on `main` either, so their conversion does not move its count). Read one by one (the class's own header and a search for where its callers leave a fact), none of the 31 is operator-authored configuration. Fifteen are ledgers, accruals and derived facts whose row is the record: `LoyaltyAccrualService`, `LoyaltyMaintenanceService`, `PointsRedemptionService`, `ReferralGrantService`, `PromoCodeRedemptionService`, `PromotionRedemptionService`, `QuoteService`, `ReferralCodeService`, `ReferralQualificationService`, `ReferralRedemptionService`, `CourierAccrualService`, `DayCloseService`, `ForecastService`, `ProductClassificationService` and `ConsentService` (an append-only decision log by design, ADR 0015). Seven are ingestion by a customer, a partner, a handset or a provider, or an order an operator keys in whose row names its creator: `MarketplaceIngestionService`, `MarketplaceLivenessService`, `PartnerAuthenticationService`, `TelemetryIngestService`, `VoiceEventIngestionService`, `ReviewSubmissionService` and `AggregatorOrderIntakeService`. Two are job runners whose run row names the submitter and whose per-row writes go through an audited authoring service: `CatalogImportService` (rows through `CatalogAuthoringService`) and `CustomerImportService` (accounts, not configuration). And seven are audited by a mechanism the scan cannot see: `GrantManagementService` by the audit module's listener, `SupportSessionService` by `SupportSessionAuditListener`, `DeviceEnrolmentService` by the kitchen module's ADR 0027 fact, `PasswordResetService` by `StaffSecurityAudit`, `QrEntryService` (a table's QR rotation is `dinein.qr.rotated`, written by `FloorPlanService`), `PosApplyService` (`pos.catalog_sync_started`, `_review_decided`, `_applied` and `_resumed`, written by `PosSyncRunController`) and `BusinessDayService` (its one caller, `BusinessCalendarService`, writes `reporting.business-day-boundary.changed` with a before/after diff). Residue, so the next reader does not take BUILT for a guarantee: the scan is a heuristic and nothing fails when a new configuration writer ships with no fact, because `ChangeDocumentUsageTests` guards the shape of a fact (no new flat `.changed(...)`) and not its existence; and the ADR 0029 seam in the next paragraph. Moved PARTIAL → BUILT. | L | — | w2-audit-approvals-privacy |  |
| `9.3b` | Activity & audit — a named human actor, even for background paths | 2 | BUILT | — | M | — | T08 |  |
| `9.3c †` | Activity & audit — a bulk action produces N records, not one | 2 | BUILT | — | S | — | T08 | PARTIAL |
| `9.4` | Approvals — the maker-checker worklist | 3 | PARTIAL | A Decided tab and decided-history read are unchanged from batch 10. Above a tenant-configurable row threshold `CustomerListQueryService#exportFiltered` raises a `customer.pii.export` maker-checker request through the existing `ApprovalService` machinery before decrypting anything, and the console's export button reads the new status headers instead of silently downloading (batch 11). Batch 17 added the second producer: activating a promotion (`6.1`) above the configured percentage or amount thresholds, or any markup, raises a `pricing.promotion.activate` request (V0461) that reaches this worklist with its own label, and the promotions screen tells the author the activation is pending. Batch 18 (`w2-audit-approvals-privacy`) gives the thresholds the screen the row said was missing. `customers.pii_export_approval_threshold_rows` stops being only a deployment property and becomes an ADR 0030 key a tenant sets (platform and tenant levels, declared in the customers module and in the tenancy registry with a drift test; `horecaos.customers.pii-export-approval-threshold-rows` remains the deployment default the service falls back to while a tenant has set nothing), and `CustomerControllerEndpointTests` (9, green in this audit's run) shows a tenant-raised value lets an export through that the deployment default would have held and a tenant-lowered one holds an export it would have let through. Settings → Approvals (`approvals-settings-page`, lazy-loaded, `TENANT_CONFIGURATION_WRITE`, linked from the worklist, the settings index and find-a-setting) edits the three `pricing.promotion.approval.*` limits at the tenant or at a brand, following the scope bar, and the export limit at the tenant whatever the bar says, through `q-inherited-field` with the resolution trace, the version it reports sent back as `expectedVersion`, a required reason and revert-to-inherited; the form refuses a number outside the key's range before asking the server (the export limit is a 32-bit key) and keeps itself open and says why when another tab saved first. Tested and green in this audit's run: `approvals-settings-page.spec.ts` (15 cases), `approvals-texts.spec.ts`, `approvals-page.spec.ts`, `settings-nav.spec.ts`, and the whole operations suite. **What the card does not do, and the page says so in words:** a limit only decides whether to ask. Whether a signature is then required, and who may give it, is the tenant's published approval policy for the action (`approval.policy.manage`, ADR 0050), and no console authors one: `ApprovalPolicyController` (`GET` and `POST /api/v1/control-plane/tenants/{tenantId}/approval-policies`, `POST .../{policyId}/expiry`) is in the generated clients and has no caller in `frontend/operations` or in `frontend/control-plane`. Without a policy a customer export proceeds on one signature, and a promotion activation that needs approval is refused until the platform floor policy says otherwise (`APPROVAL_POLICY_REQUIRED`), so a tenant that sets a threshold from the new card still cannot make a second signature required from any screen. There is still no discretionary order-line discount producer, which has no ADR behind it, and the three promotion limits' provisional defaults are still for finance to confirm. | M | A discretionary order-line discount producer has no ADR. The approval policy screen is console work and waits on no decision. | w2-audit-approvals-privacy |   |
| `X.1` | Telegram staff links (person ↔ Telegram account) | 2 | BUILT | Batch 15 fixes a scope bug that made the self-service card work only for a tenant-scope holder. `INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE` is carried by the brand-manager, location-manager and location-staff bundles at their own scope, but the only issue route declared it at TENANT scope, and a grant covers only the routes whose path names its level (ADR 0025), so all three were refused — the row read BUILT for an owner and was denied to the roles the capability was designed for. A LOCATION-scoped route now exists (`POST .../brands/{brandId}/locations/{locationId}/staff/telegram/link-codes`, the audit fact lands at the branch), the My-profile Telegram card issues its code at the operator's own branch and falls back to the tenant route only when no branch resolves (`staff-api.ts`), and the tenant route is not widened. `TelegramStaffLinkCodeControllerHttpTests` (7 cases, green in this audit's run) drives both routes with real location-staff, location-manager, brand-manager and tenant-owner grants: success at the caller's own branch, refusal at a sibling branch and at another brand, a forged brand/branch pair is a 404; the commit reports four of the seven failing before the fix. The console half is covered by `staff-api.spec.ts` and `my-profile-page.spec.ts`. | S | — | w5-readiness-and-scope-fixes |  |
| `X.2` | Смены — staff shifts and attendance (spec §7, non-courier staff) | 2 | NOT BUILT | A branch manager cannot roster or record a single hour for kitchen or floor staff, so the «часы» a payroll conversation needs exist only for couriers. | XL | Needs a decision: amend ADR 0042 or give staff shifts a record of their own — ADR 0139 (Accepted 2026-10-01) leaves them out of the staff member record on purpose; the spec is explicit it must not become a third shift model. No ADR has been drafted for either. | w4-staff-identity |  |
| `X.3` | Терминалы — shared devices and staff PINs (spec §8) | 2 | NOT BUILT | A kitchen tablet has no way to say which human pressed «ready», so the first tenant with a shared login called `kuxnya` silently empties the audit log of meaning. | XL | No owning ADR: ADR 0139 (Accepted 2026-10-01) splits terminals and PINs from the staff member record rather than folding them in, because ADR 0079 already argued against a PIN security policy; they wait on a decision of their own. | w4-staff-identity |  |
| `X.4` | Проверка доступа — access check («can she do this, and why») | 3 | BUILT | — | M | — | W03 |  |
| `X.5` | Мой профиль — staff self-service (spec §10) | 2 | PARTIAL | «Личные данные» is real now (`0.2c`): name, contact phone, photo and languages are edited from the staff member record and the shell chip reads that profile in place of the token claim. «Мои должности» (thin wiring over the already-loaded `GET /api/v1/session/context`) and the Telegram self-link card are built as before. Still a named-absence note: the rest of «Безопасность», password or PIN, sign-in history, active sessions and MFA, and UI personalization (`0.2d`). The password and the sign-in identifier are Keycloak's by ADR 0139 and are not edited here; ADR 0148 (Proposed, Not started) would add a first-party TOTP step and enrolment screen and give `q-otp-input` (`X.38`) its first consumer, and nothing of it is built. Tested and green in this audit's run: `my-profile-page.spec.ts`, `own-profile.spec.ts`, `StaffMemberEndpointTests` (20). | M | ADR 0148 (Proposed 2026-10-01, Not started) for MFA — the platform owner has not decided it; sign-in history and active sessions have no decision record (staff-and-access.md §11.6 and §11.7). | w4-staff-identity |  |

## §10 — Settings: brands, locations, channels, legal entities, payments, delivery, notifications, terms, policies

36 rows — 26 built · 9 partial · 1 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `10.0` | Settings home & "Find a setting" | P | PARTIAL | The readiness panel (`settings-home-page.ts`, over `OnboardingController.validate` through `readiness-api.ts`) names every offending item for the four VALIDATING-phase steps (wave 9 w3) and the SMS/notification-template check (batch 13), all unchanged. Batch 15 adds the three conditions this row named as absent: `OnboardingService.validate` also runs ad hoc `OnboardingReadinessChecks` beside the steps — no `OnboardingStep`, no migration, nothing persisted, the same `failedWithFindings` shape, one row per offending item. `FISCAL_CLASSIFICATION_COVERAGE` (per sellable brand, menu nodes lacking any of the four ADR 0038 fields) is advisory, because `CatalogValidator` still only warns and the publish gate lets the same menu through. `CHANNEL_PAYMENT_COVERAGE` (an ACTIVE sales channel with no enabled payment method) is blocking, tenant-scoped, and the console links it to the sales-channels list. `SECRET_ROTATION_AGE` (installation and merchant-account credentials older than `horecaos.integration.credential-rotation-interval`, default 180 days) is advisory and follows the rule `CredentialRotationController` applies; the review found the control plane's credentials-due list, unlike the panel, still counted retired merchant bindings, fixed in `cbdadff2`, and `CredentialRotationControllerTests` now reads one fixture through both so they cannot disagree. The per-location fiscal-assignment condition already existed as `NO_LEGAL_ENTITY`. Severity plumbing: `ValidationResult.advisory` (an advisory row still fails itself but is left out of `allPassed`); the panel sorts blocking rows before advisory ones, keeps server order inside each group, tags advisory rows and deep-links the new codes (fiscalization, sales channels, integrations). The fix round found N offending brands, channels or credentials read as N identical rows sharing one `@for` track key (NG0955, a view reused for the wrong row); each row now shows the server's detail beneath its localized sentence and is tracked by its own fields plus an occurrence ordinal, and a merchant-account finding names its legal entity, never the account or the secret reference. The control plane (`frontend/control-plane`, not tracked here) renders advisory findings as advice. Find-a-setting is unchanged (a `q-combobox` over `ConfigurationKeys.all()` and the reference-data lists). Batch 16 (`w3-readiness-fiscal-backfill`) adds two more blocking checks beside them, the two that settings.md §10.0 lists and batch 15 lacked. `CHANNEL_FULFILLMENT_COVERAGE`: an active sales channel with no enabled fulfilment mode (`CHANNEL_NO_FULFILLMENT_MODE`), or whose enabled modes have no schedule bound at an active location the channel serves (`CHANNEL_NO_SERVICEABLE_MODE`). `LOCATION_SERVICE_BINDING_COVERAGE`: an active location with no `location_service_bindings` row for a mode a channel sells there, or none at all when no channel reaches it (`LOCATION_NO_SERVICE_SCHEDULE`, one finding per location naming the modes it lacks, carrying its location id so the console opens the location where its hours are set). A finding gains an optional `subject` (`FindingSubject`: a type from a closed vocabulary plus an id, never a name), so two like-worded rows for two channels stay two rows; the fix round pointed all three channel codes at the sales-channels screen, where the matrices and the channel's location list are edited, because the per-channel setup hub the first cut linked to has no control that clears them. Tested and green in batch 16's run: `OnboardingReadinessChecksTests` (48), `OnboardingReadinessEndpointTests` (7, over HTTP), `OnboardingServiceTests` (50), `OnboardingServiceValidationResultsTests` (9), `settings-home-page.spec.ts`. Batch 18 (`w6-settings-readiness-shell`) closes every gap that sentence listed against the table. `OnboardingReadinessChecks` gains `LocationForcedClosedNoExpiry` (`LOCATION_FORCED_CLOSED_NO_EXPIRY`: an active location someone closed by hand and never said when it reopens, read from `tenant.location_service_state`; advisory, the spec's «single most valuable row», longest-closed first, the detail carrying the reason's code and how long it has been shut and never the free-text note an operator typed, ADR 0029), `LocationChannelReach` (`LOCATION_NO_SALES_CHANNEL`: an active location no active sales channel is switched on for; blocking, one finding per location carrying its id, linked to the sales-channels screen where the link is made and not to the branch) and `FiscalAssignmentExpiry` (an active location whose current fiscal assignment ends within `horecaos.readiness.fiscal-assignment-expiry-window`, default thirty days, with nothing after it; the first member of a new `EXPIRING` tier between blocking and advisory, carrying the date and the days left). `ReadinessSeverity` is the three tiers the spec names, and the panel lists blocking, then expiring, then advisory (tagging the middle one), puts the condition with the most offending items first within a tier, summarises the three counts above the list, and carries the same numbers on the tiles the findings link to and on no other, and none anywhere when everything is set up. Tested and green in this audit's run: `OnboardingReadinessChecksTests` (60), `OnboardingReadinessEndpointTests` (11, over HTTP), `settings-home-page.spec.ts` (44 cases, the branch closed by hand, the branch no channel reaches, the tiers, the order by count and the tile numbers among them), `readiness-order.spec.ts`. Still narrower than settings.md §10.0, found by this audit and counted by none before it: the readiness panel now meets the spec's table, but «Find a setting» does not meet the spec's description of it. Its results read the key's description and name the owning screen; they do not say where the value is set or what it is (the spec's example reads “задано: бренд «Rayhon» (15 мин)” and “наследуется от компании (12%)”), and choosing one navigates to the screen without setting the scope bar to the level the value was found at, which is what the spec says Enter does. The combobox does not resolve a key's value at all. Settings home is the pilot's front door and the rest of the row is built, so this is the row's one reason to stay PARTIAL. | M | — | w6-settings-readiness-shell |  |
| `X.1` | Scope bar + InheritedField (settings.md §1.1/§1.2 — the pattern every row renders through) | P | PARTIAL | The scope bar and InheritedField now exist and are live across Settings, so a merchant can set a value at brand level and override it for one branch, and see which level they are about to change. Two statements the earlier note made are out of date. The bar does offer the tenant level: a tenant-wide toggle beside the per-brand one (`?level=tenant`, `SettingsScope`, added for `10.3b`'s order-policy cards). And the resolution-trace popover no longer shows only scope and outcome: since batch 18 (`w6-settings-readiness-shell`) it names, for every level where something is stored, the version in force, who set it and when (`ResolutionTrace.Level.provenance`: for a configuration value the row's version, `set_by` and `updated_at`, for a versioned policy its published version, `approved_by` and `valid_from`). The platform sends the subject and never a name; the console resolves the name through the tenant's staff record at the moment it draws the line, and says «a person with no staff record here» when there is none. There is no free-text reason on a rung, on purpose: an operator can type anything into one (ADR 0029). A brand picked in the header while Settings is open re-points the Settings scope (`9517ee05`), and a settings save names the level it landed at (`settings-saved.ts`), the order pane confirming what applied. Tested and green in this audit's run: `ScopeResolutionTests` (12), `JdbcConfigurationResolverTests` (11), `OperationsConfigurationControllerTests` (14), `OrderLatenessPolicyEditorHttpTests` (24), and `inherited-field.spec.ts`, `settings-scope.spec.ts`, `settings-saved.spec.ts`, `brand-choice.spec.ts`, `current-brand.spec.ts`. Still missing: 'Locked by plan', the fifth InheritedField state, is not wired, since no ADR 0021 entitlement data reaches this surface (`inherited-field.ts` says so). | XL | ADR 0021 entitlement data does not reach the console. | w6-settings-readiness-shell |  |
| `10.1 †` | Brand profile | P | BUILT | Logo/banner through `q-media-uploader` (wave 9 w2, unchanged) is joined this batch by the row's last named gap: `GET /api/v1/operations/tenants/{tenantId}/profile` (`BRAND_READ` at TENANT scope) answers country/default currency/default timezone, read-only, and the brand-profile screen loads it as a third, best-effort read beside `getBrand` and the media previews — a failure there leaves the rest of the profile rendering. Nothing writes these fields from this screen; a market change stays ADR 0090's residency board. Tested both ends: `TenantProfileControllerEndpointTests`, `brand-profile-page.spec.ts`. Batch 16 adds a Форматы card to the same screen (where the unit sits on an amount, how thousands are grouped, the phone pattern; row `10.12`), which writes through its own `PUT .../regional-formats` and leaves the market fields above read-only. | L | — | w6-settings-locations-policy |  |
| `10.2a †` | Locations — branch list | P | BUILT | The three remaining gaps are all built (wave 9 w3): a channel filter and an INN (legal-entity) filter, each over a new brand-batched read (`locationChannels`/`locationLegalEntities` on `OperationsBrandController`, one join rather than one call per row); a severity sort that always places a forced-closed branch first; and a selection + bulk close/open bar over a new `POST .../locations/service-states` (`bulkChangeServiceState`, gated `LOCATION_SERVICE_STATE_CHANGE` at BRAND, applying N independent per-location writes and reporting one outcome each). A fix-round bug (the bulk write's self-invocation bypassed the `@Transactional` proxy, so no `LocationServiceStateChanged` ever reached the outbox for a bulk-applied change) was caught and fixed before merge. Tested both ends: `OperationsBrandControllerBulkServiceStateTests`, `OperationsBrandControllerEndpointTests`, and five new `locations-page.spec.ts` cases (severity sort, channel filter, INN filter, bulk reopen, bulk close). | M | — | w3-branches-settings-readiness |  |
| `10.2b` | Locations — detail Tab 1 (Основное: address, phone, landmark, map pin, venue attributes, tags, sort order) | P | PARTIAL | The map-pin data-loss fix and editable `draftLandmark` (unchanged) are joined this batch by three of the four remaining named gaps: sort order (`tenant.locations.sort_order`, driving the branch list's own ordering server-side), venue attributes (seats, average cheque + currency, parking/playground flags, a virtual-tour link — `LocationVenue`, a sibling to `LocationPlace` on the aggregate) and localized branch content (`tenant.location_content`, a ru/uz-Latn/en display-name/description grid mirroring the brand-profile locale editor) all now save through the same Tab 1 form and `PUT .../place`, with a fix round closing a defect where a cleared seats/average-cheque/virtual-tour field silently failed to clear. Tested: `LocationVenueEndpointTests`, `location-detail-pane.spec.ts`. Still missing: a map-pin *editor* — the pin can be lost or kept, never drawn or moved from this screen, blocked on the same unmade map-provider decision as `X.4`. Batch 17 drafts the decision this row waits on, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing on this screen changed. | L | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs |  |
| `10.2c †` | Locations — detail Tabs 2–3 (Hours, prep-time bands, order limit) | P | BUILT | Hours weekly-grid editing, dated-exception add/edit, the schedule rebind picker and the preparation-band editor (unchanged) are joined this batch by the row's one remaining gap: `ServiceScheduleController` gains `DELETE /{scheduleId}/exceptions/{date}` (`SERVICEABILITY_MANAGE`, If-Match against the schedule's own version), and the Hours tab now deletes every row removed from the grid's local draft before it saves rules/exceptions, instead of only hiding it until reload. Tested: `ServiceScheduleControllerEndpointTests` (5 HTTP cases), `location-detail-pane.spec.ts` (delete-then-save, version chaining across two removed rows). | L | — | w6-settings-locations-policy |  |
| `10.2d †` | Locations — Floor plan tab (sections and tables) | 3 | BUILT | `floor-plan-pane.ts` under Settings → Locations creates sections and tables and rotates a table's QR token, over `PUT /tables/{tableId}` (which also returns `layoutX`/`layoutY`, closing the write side `FloorPlanController` was missing). Batch 15 adds seating from the plan (ADR 0047): selecting a table opens a panel with «Seat walk-in» for an operator holding `dinein.session.manage` — party size and a reason, both prefilled and editable, opened with no booking through `TableSessionController#open`. The pane reads the room (`GET .../dine-in/sessions` for live parties, `table-availability` for tables a booking holds within 90 minutes), marks occupied tables on the canvas through `TableToken`'s new optional `occupied` state, offers no second party at a seated or out-of-service table, warns — advisory only, nothing refuses — about a booking soon or a party larger than the table, turns a lost race (`TABLE_OCCUPIED`) into a plain sentence and re-reads the room, and hides the action when the room cannot be read rather than guessing the table is free. Tested: `floor-plan-pane.spec.ts`, `table-sessions-api.spec.ts`, `table-token.spec.ts`, and over HTTP `TableSessionControllerHttpTests` (13 cases in batch 15's run, 18 now: a waiter seats a walk-in and the answer names the table; a table of another branch is refused through this branch's path). Batch 17 named a gap this opened and narrowed it; batch 18 (`w1-order-entry`, with `fix18-a-order-entry`) closes it. A party seated by staff from here, from a booking (`1.5a`) or from New order (`1.3`) could be released only by an API call, because the console's one caller of a session's `state-actions` released a guest's unconfirmed self-seated claim and nothing called `force-closures`. «Закрыть стол» (`q-party-close`) is now in the floor plan's table panel, as well as in New order's picker. It reads the bill first (`GET .../dine-in/sessions/{id}`) and, if the bill cannot be read, offers nothing: a table is never assumed empty. A party with nothing on its bill is closed with one confirmation, `state-actions` to `CLOSED`, which needs only `dinein.session.manage`. A party that owes is closed through «Гости оплатили» (the same `CLOSED`; the confirmation names the amount, because the server then records it as the settled total and the audit trail carries it) or through «Гости ушли, не заплатив» (`force-closures`, `dinein.session.force_close`, a reason code, and an audit record that carries the unsettled amount, offered only to a principal who holds that capability). A party whose guests asked for the bill (`BILL_REQUESTED` has no edge to `CLOSED` in ADR 0047's machine) is taken through `SETTLING` first. A close settles only the bill the operator saw: it is read again at the second confirmation, a round that arrived since withdraws the confirmation and shows the new figure, and the close is conditional on that read's version, which the server moves whenever a round is attached (`TableSessionService.addRound`, `f04921b3`), so a round landing in the last instant answers a stale-version refusal rather than being closed over. A party someone else closed first tells the screen to re-read the room. The table is then free to seat again. Tested and green in this audit's run: `OperatorOrderEntryHttpTests` (14, four of which close a party over HTTP and read the audit row: an empty party is closed and its table freed, a walkout is force-closed with the unsettled amount on the record, an ordinary close of a party that owes records the bill as settled, and a close against a stale version closes nothing), `TableSessionControllerHttpTests` (18), `DineInTests` (46), `party-close.spec.ts` (24 cases), `floor-plan-pane.spec.ts`, `dine-in-table-picker.spec.ts`. Residue: the console cannot check that a party paid, because payment is not recorded against a session, so «Гости оплатили» is the operator's statement and the confirmation says so; a party staff seated is still closed by a person and never by a sweeper (`TableSessionClaimSweeper` lapses only guest-opened claims); and the running bill with a settlement against a payment, which has no IA row, is not built. Batch 17 also adds the self-seating switch and its limits to this pane's settings (`10.5b`) and the host's keep-or-release of a guest's claim (`1.5a`). | L | — | w1-order-entry | NOT BUILT |
| `10.3a` | Order policy — Card 1 Приём заказа (acceptance mode, approval channel, timeout, timeout action) | P | BUILT | Only that it publishes straight through with no draft → diff → activate step and no version history read, and it can only be written at brand level (no per-branch override, see X.1). Courier-first vs branch-first acceptance is not expressible — settings.md §4 calls it design-forcing on ADR 0019's state machine, not configuration. | S | — | — |  |
| `10.3b †` | Order policy — Cards 2–5 (timings & SLA, automation/auto-accept, conditions, operator order entry) | P | PARTIAL | The authoring surface is built. Cards 2–5 render thirteen scalar fields through `q-inherited-field` (set here, inherited from, trace; the version read as the concurrency check; a required reason) over `OperationsConfigurationController`, at tenant, brand or branch scope, and `SettingsScope.level` takes TENANT (`?level=tenant`, a company-wide pill in the scope bar). Batch 15 added two of the fields (the at-risk minutes and the late colour); batch 16 (`w2-lateness-policy-editor`) adds `q-lateness-policy-card` under Timing and SLA, which edits the `ordering.lateness` document per fulfilment mode (see `X.39`). Tested and green in this audit's run: `order-policy-page.spec.ts`, `lateness-policy-card.spec.ts`, `settings-scope.spec.ts`, `scope-bar.spec.ts`, `settings-shell.spec.ts`. Moved BUILT → PARTIAL by this audit, not by a wave. A search of `src/main` for a reader of each key these cards write finds three: `ordering.minimum_order_amount_minor` (`CheckoutEligibilityGuard`, pickup and dine-in) and `ordering.at_risk_before_minutes` and `ordering.late_colour` (the lateness policy). The other ten (working-day start hour, average order time, maximum order time, «Order is late after», VAT rate, routing poll interval, auto-accept eligible channels, auto-accept minimum prior orders, pre-order branch selection and «Operator may apply a promo code») are registered, validated, inherited and traced, and read by nothing: `OrderingConfigurationKeys` says so in its own Javadoc («none of them has a consumer yet»). Two say so on the screen («Not applied yet», «Not yet enforced»); the other eight read as settings that work. Where a neighbour exists it is the one in force: the business day is read from the reporting calendar (`10.10`), not from the working-day field, and an operator-keyed order takes a promo code whatever the toggle says (`1.3e`). Earlier audits recorded the first two dead fields under `X.39` and kept this row BUILT. A screen whose controls change nothing is what «no call site = PARTIAL» is for, one level down from an endpoint nobody calls. Each reader is the work of the ADR that owns its slice (0002 acceptance, 0019 lifecycle, 0037 routing, 0018 promotions), not of a settings wave. The lateness editor, like the card's scalars, needs `TENANT_CONFIGURATION_WRITE` at tenant scope for every level, and only the tenant-owner and tenant-administrator roles hold it, so a brand or branch manager cannot publish their own scope's document. Batch 17 adds, in card 3 (Automation), a read-only summary of the dispatch rules in force at the scope bar's level and the way to the screen that edits them (`dispatch-rules-summary-card`, `3.8`); the auto-dispatch, provider-cascade, merge-radius and unpaid-timeout fields that card never had are not settings here on purpose. It also drafts the decision on three of the dead fields: ADR 0150 (Proposed, Not started) gives «Order is late after» the one job nothing else does (see `X.39`) and proposes the «not applied yet» hint for `ordering.average_order_minutes` and `ordering.maximum_order_minutes`, whose fields do not say so today, until a record names a reader or removes them. Drafting is not deciding: a search of `src/main` for each of the ten keys still finds no reader outside its declaration. | XL | The readers belong to ADR 0002, 0019, 0037 and 0018 (see What is missing), and ADR 0150 (Proposed 2026-10-01, Not started) for «Order is late after» and the two order-time fields beside it | w7-dispatch-rules · w9-decision-adrs |  |
| `10.4a †` | Sales channels — channel registry | P | BUILT | Icon, brand colours and social links are all built (wave 9 w2): `tenant.sales_channels` gains `icon`/`brand_color_primary`/`brand_color_secondary` (V0385, hex-validated), and a new `tenant.channel_social_links` table holds one https-only link per checked platform, replaced whole-set under the channel's own version like the payment/fulfilment/location matrices. `sales-channels-page.ts`'s edit panel gains the icon field, two `q-color-input` swatches and a social-links list (add/remove, one per platform). A fix round caught and fixed two real bugs before merge: a non-https link only refused at save time, after the two earlier writes had already landed, leaving stale data on screen; `saveEdit`'s catch block now reloads so the list always reflects whatever subset of the three sequential writes actually committed. Tested both ends: `SalesChannelAndServiceabilityTests`, and nine new `sales-channels-page.spec.ts` cases. | L | — | w2-brand-channel-presentation |  |
| `10.4b` | Sales channels — capability matrix (payment method × channel, order type × channel) | P | BUILT | Residue: a channel with locations but none of them carrying a live fiscal terminal is not yet hatched as UNAVAILABLE (only a channel with zero locations is) — the fuller fiscal-terminal cross-reference is explicitly deferred to `P34`/`P35`. | M | — | P33 |  |
| `10.5 †` | Channel setup (Telegram bot, website/domain, mobile apps, kiosk device registry, aggregator, call centre) | P | PARTIAL | A per-channel setup hub (`/settings/channel-setup/:channelId`) dispatches by channel type; the hostname claim, SEO and versioned static pages (batch 10) are unchanged in shape. Batch 13 closed the gap the earlier audit named — a custom hostname 'stored unverified pending out-of-band confirmation': claiming one issues a per-hostname DNS-TXT challenge token (`ChannelSetupService#setCustomHostname`, rotatable via `#rotateChallenge`), `POST .../hostname/verify` resolves the real `_horecaos-challenge.<hostname>` TXT record over DNS (dnsjava, never the JDK's own banned JNDI provider) and flips verified only on a match, and a `ChannelHostnameVerificationSweeper` periodically re-resolves every verified hostname and un-verifies it the moment the record stops matching. The setup hub shows the DNS-TXT record with copy and rotate actions. TELEGRAM links to the existing integrations connect flow instead of a duplicate; QR_TABLE links out to the already-built per-location QR dine-in setting (`10.5b`, ADR 0047 keeps it per-location, not per-channel); KIOSK renders honestly locked — no device-pairing flow exists, confirmed against `DevicePrincipalClass` (only `KITCHEN_KDS`). Tested both ends: `ChannelHostnameVerificationSweeperTests`, `ChannelSetupControllerEndpointTests`, `channel-setup-page.spec.ts`. Batch 14 gave a verified hostname a downstream consumer: `QrChannelSource#storefrontHostname` reads only `verified` rows of `tenant.channel_hostnames`, so an un-verified hostname is never offered for a new table card (`10.5b`). The storefront half of the row's dine-in facet (ADR 0047) moved again in batch 15, in the customer apps this document does not track. `frontend/storefront-milliy` gained the sign-in route (`/auth/login`, `/auth/code`; before, `authGuard`'s redirect fell into the catch-all and an anonymous visitor to the cart landed on the home screen), which ends at the allow-listed, token-free `/dine-in/table` when the visit began at a table, and ordering at the table: an `ORDER_AND_PAY` table gets a DINE_IN basket of its own on the table's `QR_TABLE` channel, checkout, a round attach queued on the device until the platform confirms it, the bill and the bill request, with the guest token held only inside `DineInService` and sent only as `X-Dine-In-Token`; `VIEW_ONLY` stays a menu. The app's specs (38 files, 1,249 tests) and its production build pass in this audit's run. The defect batches 15 and 16 recorded is closed in batch 17: `storefront-milliy` now sends the guest's `X-Dine-In-Token` with checkout (`DineInService.checkoutAtTable` builds the header, `CartService.checkout` accepts it, the table screen's checkout goes through it, and `dine-in-table.component.spec.ts` asserts the header), so a bound basket meets the platform's `TABLE_TOKEN_REQUIRED` guard with the token it asks for. Each side's tests still mock the other (the platform's `CartTableBindingHttpTests` (10) and `StorefrontOrderingControllerCheckoutTokenTests` (4) are green in this audit's run), so the pair is proved at the header and not against a running stack. The walk-in facet is new in batch 17 (ADR 0143, `w8-walk-in-sessions`): `POST /api/v1/storefront/dine-in/sessions` seats a signed-in guest at a free table whose branch has turned self-seating on (off by default; the floor-plan settings, `10.5b`), as a provisional claim that lapses at `claimExpiresAt` unless a round the restaurant accepted lands on it or staff keep it (`1.5a`); `TableSessionClaimSweeper` gives the table back; both storefronts have the «sit at this table» flow, the hold countdown and the strings; the route sits in the Caddyfile's guest dine-in rate-limit group; the per-token limit is checked before the token is looked up; and the settings-row lock comes before the table-row lock (`WalkInSeatingTests` (39), `WalkInSeatingHttpTests` (20), both green in this audit's run). Not built, because the record defers each input to a named owner: whether a self-seated table may place a cash round, or whether a QR table cart must carry the table binding (an unbound DINE_IN cart still checks out with no seating check); a realtime `FLOOR` signal to the host stand; and a presence proof stronger than a printed code, so the phantom-cash-order exposure is exactly as the record describes it. Still missing: kiosk device pairing, and website colours/menu ordering as a merchandising layer (brand colours themselves are covered separately by `10.4a`). | XL | Kiosk device pairing has no owner decision or hardware/device integration named anywhere. | w8-walk-in-sessions |  |
| `10.5b †` | Channel setup — QR dine-in modes and table QR codes | P | BUILT | The settings form (turnaround minutes, guest-session TTL, service-charge rate, reason) and the per-table issue/rotate action, rendering the token as a printable table card via `P17`'s `q-qr-code`, are built. `SETTLE_OPEN_TICKET` renders disabled with its reason, per ADR 0042. Batch 14 closed a functional hole in that card, which encoded the bare token — something a phone camera cannot open: `FloorPlanController`'s settings response carries `storefrontHostname` (`QrChannelSource#storefrontHostname`: verified hostnames only, one candidate per tier), and `TablePrintCard` encodes `https://<hostname>/dine-in/<token>` — the scan route both storefront apps register — with the host shown under the mark; with no verified hostname, or an address past the encoder's 106-byte symbol, the mark falls back to the bare token and the card says so in a visible warning. Tested both ends (`OrderTableVisibilityHttpTests`' hostname cases, `table-print-card.spec.ts`, `floor-plan-pane.spec.ts`). Batch 15 adds the guest half of the binding on the platform (ADR 0047): `PUT .../carts/{cartId}/table` binds a guest's cart to the table its `X-Dine-In-Token` was minted for — never to a table named in the request; V0435 widens `ordering.cart_fulfillment` to a second kind of row, a DINE_IN cart's `dinein_table_id`, exactly one kind per row. Checkout (`CheckoutEligibilityGuard`) refuses a bound cart before anything is written unless the guest's live token is presented at the bound table (`TABLE_TOKEN_REQUIRED`, `TABLE_TOKEN_ENDED`, `TABLE_BINDING_STALE` — the first cut trusted the stored binding, so a party that left or a guest who moved billed whoever sat at the old table for the cart's TTL) and unless somebody is seated there (`TABLE_NOT_SEATED`), then puts the order on the seated party's bill in the transaction that creates it (`TableBindingPortAdapter#attachRound`), so the attach cannot be lost between two calls. The operator side's session reads and writes are now scoped to the branch in the path, not only the tenant (`TableSessionController`: a branch-A grant no longer reads, attaches to, moves or closes branch B's session). Tested and green in this audit's run: `CartTableBindingHttpTests` (10), `StorefrontOrderingControllerCheckoutTokenTests` (the controller hands the header to the guard and the command never prints it), `CartCheckoutAndOrderTests`, `DineInTests`, `TableSessionControllerHttpTests`; ADR 0143 and ADR 0047 now say which half of the phantom-order risk is closed. It closes for a client that binds; an unbound DINE_IN cart (a client that skips the PUT, an operator keying an order) still checks out with no seating check. Both storefronts bind and send the token (`storefront-milliy` since batch 17, see `10.5`). The table card and the issue and rotate action are unchanged. Not verifiable from the tree: which storefront app a tenant's `WEB` hostname is served by (both register `dine-in/:tableToken` here). Batch 17 adds the settings that carry self-seating (ADR 0143, V0467) to this form: the switch (off by default), the claim window, the walk-in horizon, the cap on unconfirmed claims, the daily claims per account and the payment-defer minutes, saved against the settings version with a reason (`floor-plan-pane.spec.ts`: it ships off and says so, it turns on with its numbers and a reason against the version it read, and it reports a stale version and keeps the editor open). The guest's route and the lapse are described under `10.5`, the host's keep-or-release under `1.5a`. | M | — | w8-walk-in-sessions | NOT BUILT |
| `10.6` | Payment methods (registry, localized name, icon, base type, acquirer binding) | P | BUILT | Batch 15 (row `10.12`): the localized names are edited in the tenant's own languages, not the pinned brand's. A payment method is a tenant-level row every brand's customers read, but the page took its tabs and default marker from `LocaleSet`, the operator's single pinned brand, so a tenant whose brand A offers ru and brand B offers uz-Latn and en could not author B's names. `GET .../payment-methods/locale-set` (`payment-method.read`, so a manager pinned to one brand still sees the tenant's set) answers the union of the tenant's brands' supported languages, the first brand's default leading (`BrandLocaleLookup`, the same decision comment presets and regions follow); the page's tabs, default marker and opening tab follow it and fall back to the platform triple when it cannot be read. `PUT .../translations` replaces the whole set, so a name in a language the tabs do not offer goes back unchanged with every save and the operator is told it is kept. Tested and green in this audit's run: `PaymentMethodControllerEndpointTests` (owner, finance and foreign-tenant locale-set cases), `payment-methods-page.spec.ts`. | L | — | w3-locale-remaining-forms |  |
| `10.7a †` | Fiscalization — Tab 1 Юридические лица (legal entities, INN, per-branch assignment) | P | BUILT | — | S | — | P34 | BUILT |
| `10.7b` | Fiscalization — Tab 2 Фискальные терминалы | P | BUILT | Terminal register/manage capabilities (`FISCAL_TERMINAL_MANAGE`/`READ`) are BRAND-scoped, so a location-level operator needs a brand-level grant to register or service their own branch's terminal. ADR 0038's 'cash requires a bound fiscal-capable terminal' precondition is still not enforced as a hard checkout-time refusal — deliberately left for the payment-method-registry wave (row `1.2c`). | L | — | P34 |  |
| `10.7c †` | Fiscalization — Tab 3 Классификация товаров (IKPU/MXIK coverage, delivery-line IKPU + package code, marking, VAT defaults) | P | PARTIAL | `CatalogQueryService.fiscalCoverage` (how much of the menu is unclassified) and a delivery-fee ИКПУ/marking write were wired in wave P34 to the previously-uncalled fiscal-classification endpoint. Batch 16 (`w3-readiness-fiscal-backfill`, with `fix16-c-readiness-fiscal`) adds the bulk backfill the row said did not exist anywhere. The classification tab lists every dish (variant) short of an ИКПУ or a package code as an editable table, `q-fiscal-backfill-editor`: type or paste (a column, or a tab-separated pair, fills down), per-cell format checks (17-digit ИКПУ, digits-only package code; client side only, because ADR 0038 keeps the shape out of the server), «copy category default» per row or for all (a default the coverage read derives from the pair most of a category's classified dishes carry, not a stored setting), and a save that goes out in batches of 100 with a per-row outcome (`CLASSIFIED`, `UNCHANGED`, `SKIPPED_EMPTY`, `NOT_FOUND`, `CONFLICT`). It writes through the control-plane-surface `POST .../brands/{brandId}/catalog/fiscal-classifications/bulk` in a new MERGE mode (REPLACE stays the catalog workbench's default). `JdbcCatalogStore.mergeFiscalClassification` locks the node's row and sets only the two code columns, the stored value first, so a backfill cannot undo a marking, excise or age restriction set meanwhile, and an item that supplies a value differing from a stored one comes back `CONFLICT` with nothing written for that node. A cell that already holds a code is read-only in the editor, because the first cut let a pasted column that started one row too low overwrite correct codes. One audit fact (`catalog.fiscalClassification.bulkSet`) covers a bulk classification and the activity log has a sentence for it. Tested and green in batch 16's run: `FiscalBackfillEndpointTests` (15, real Postgres, including a second connection filling an ИКПУ while a merge waits on the row lock), `CatalogAuthoringServiceP21Tests`, `CatalogAuthoringControllerEndpointTests`, `fiscal-backfill-editor.spec.ts`, `fiscalization-api.spec.ts`, `fiscalization-page.spec.ts`. Batch 18 (`w5-attributes-fiscal-tab`, with `fix18-e-attributes-fiscal`) closes the three gaps that sentence named. (1) The editor lists modifier options beside dishes and sends each with its node type (`VARIANT` or `MODIFIER_OPTION`) through the same MERGE request, the same stored-value-first rule and the same audit fact (`FiscalBackfillEndpointTests`, 20 in this audit's run: a modifier option is listed with its codes and backfilled through the same path as a dish, a stored code is never replaced by a backfill and a repeat is quiet, a mixed batch leaves one audit fact). (2) The ИКПУ cell is the typeahead over `catalog.mxik_reference` (`q-mxik-picker`, a new field over the same reference search as the product editor's `4.2e` typeahead; the editor still drives a combobox of its own, so the two are not yet one component): a code can still be typed or pasted, a name searches, and a chosen row brings its `default_package_codes`: one fills the open cell, several are offered as a choice, none leaves the cell to be typed; a package code the operator typed is left alone, and re-picking another ИКПУ takes back the package code the previous pick filled, where the first cut sent a mismatched pair that the gap-fill-only MERGE could not correct afterwards (`75077829`). The delivery fee's ИКПУ uses the same field. (3) Below the editor the tab draws, read-only, the VAT rate of each tax profile with the legal entities that use it and the payment-method registry grouped by the fiscal responsibility each method was registered under (every responsibility is drawn, an empty one included, and a disabled method is left out); each read answers on its own, so a role without one capability sees a sentence about that one and the coverage stands. A tax profile is superseded by a new row on every rate change and a legal entity holds the id it was given with no foreign key, so after a change the table cannot resolve an entity to its successor: the entities are named in a line under the table instead of the table reading as «no entity has a VAT treatment» (`657a8f0a`). Tested and green in this audit's run: `FiscalBackfillEndpointTests` (20), `fiscal-backfill-editor.spec.ts` (59 cases, the picker and package-code behaviour among them), `fiscalization-page.spec.ts` (24), `mxik-picker.spec.ts` (10), `fiscalization-api.spec.ts`. Still missing against settings.md §10.7 Tab 3, found by this audit and counted by none before it: «bulk assign across a filtered selection: pick a code from `catalog.mxik_reference`, pick a package code, apply». The editor is table-driven: a code is picked, typed or pasted per row, a pasted column or tab-separated pair fills down, and a category's default can be copied to a row or to every row; there is no filter and no way to pick one ИКПУ once and apply it to a chosen set of rows, so the same code on forty dishes is forty picks or a forty-line paste. The VAT table is a projection of Catalog → Prices and the registry, not an editor, as the spec says. | L | — | w5-attributes-fiscal-tab |  |
| `10.8a †` | Integrations — hub: provider installs, connect fields, secret door, rotation, bindings | P | BUILT | The per-branch install model (wave 9 w8, unchanged) is joined this batch by the capability-assignment picker POS/DELIVERY were missing: a new read-only `GET .../{installationId}/capability-catalogue` (`PosProviderCapabilityCatalog`, mirroring the delivery-side one) exposes the vendor ceiling a POS/DELIVERY installation's provider declares, and the "Bind to a branch" dialog now renders a checkbox picker over it, defaulted to every declared capability, for those two categories — submit is refused, both client-side and server-side, whenever nothing is checked, so a binding can no longer go out with the empty `capabilities: []` that made it invisible to `effectiveBindings`'s own INNER JOIN. A fix round added the same refusal to `bind()`/`activateBinding()` server-side as a second line of defense. PAYMENT/NOTIFICATION keep binding with an empty array, unchanged. Retiring an installation stays out of scope per ADR 0065. Tested: `PosProviderCapabilityCatalogTests`, `ProviderCapabilityReconciliationServiceTests`, `ProviderInstallationControllerTests` (including the two new refusal cases), `integrations-page.spec.ts` (rewritten POS-exclusion specs proving the opposite). | M | — | w7-integrations-pos-fixpath |  |
| `10.8b †` | Integrations — mapping tables (products, payment types, discounts, couriers, cancellation reasons, channel → POS codes) | P | BUILT | Residue: only PAYMENT_TYPE and DISCOUNT have an adapter-discovery method at all, and Clopos's own API has neither endpoint, so it answers `NOT_SUPPORTED` for both; COURIER, CANCELLATION_REASON and CHANNEL_POS_CODE have no discovery method by design. All six types can still be mapped by hand through the same dual-list pane, which now allows free-text entry exactly where a side can't be sourced. | XL | — | P24 | NOT BUILT  |
| `10.8c †` | Integrations — health & errors (last successful inbound per branch × provider, error taxonomy, merchant-scoped replay) | P | BUILT | The three remaining liveness watermarks this row named are all wired now (batch 8 w8): `ProviderActivityRecorder`/`JdbcProviderActivityRecorder` give POS export (`PosOrderExportService.send`), fiscal submission (`PaymentFiscalService.submit`) and notification dispatch (`CamelNotificationTransport.dispatch`) each their own write against the same `integration.provider_activity_watermarks` table marketplace liveness already used — no new table, no new endpoint, no frontend change: `MarketplaceOperationsController.liveness` and the console's `LivenessPanel`/`FailureInboxPanel` already render generically by provider category. `MarketplaceLivenessHttpTests` proves all four categories (MARKETPLACE, POS, PAYMENT, NOTIFICATION) surface over one `GET .../marketplace/liveness` call, verified failing first by re-adding the old single-category filter. | L | — | w8-integrations-notifications | NOT BUILT |
| `10.8d` | Integrations — partner API credentials as an OAuth client with a rotatable secret | P | BUILT | Provisioning the real `horecaos-partner-provisioning` Keycloak service-account credential in each environment is an infrastructure step outside this repository, owned by platform operations. | M | — | P35 |  |
| `10.8e †` | Analytics installs (GTM, GA4, Search Console), the GA4 ecommerce event contract, and the telephony provider install | P | BUILT | The install shape, storefront analytics injector and versioned GA4 ecommerce event contract (unchanged) are joined this batch by the two remaining contract-ready-but-uncalled events: `ProductComponent` now fires `view_item` once per product load and `add_to_cart` once per `increaseVariant()` call that actually commits (inside `run()`, after the session/modifier guards, so a refused add is never counted), resolving the chosen variant's own id/name/price or falling back to the product's for a variant-less product — no PII on either event. Tested: `product.component.spec.ts` (view_item's payload, both add_to_cart paths, that a refused add fires nothing). | L | — | w7-integrations-pos-fixpath |  |
| `10.9a †` | Notifications — Tab 1 order-status and OTP templates | P | BUILT | A template can now be keyed to fulfilment mode and/or channel source (wave 9 w8): `notifications.templates` gains nullable `fulfillment_mode`/`channel_source` (V0392, `NULLS NOT DISTINCT` identity), `JdbcTemplateStore.activeTemplate` resolves the most specific ACTIVE variant at send time, and `notifications-page.ts`'s create form gains a fulfilment-mode/channel-source picker (both defaulting to "Any") plus a Variant column on the templates table. The fulfilment-over-channel tie-break between two equally-specific single-dimension variants is deliberate-but-arbitrary column order, not a product rule — documented and pinned by a regression test rather than decided, since no ADR covers it. Tested both ends: `TemplateVariantResolutionTests`, two new `notifications-page.spec.ts` cases. | L | — | w8-notification-templates-installs |  |
| `10.9b` | Notifications — Tab 2 Маршрутизация (Telegram chat IDs per event class per branch, topic IDs) | P | BUILT | — | L | — | P36 |  |
| `10.9c` | Notifications — provider moderation state and the send block it implies | P | BUILT | — | S | — | P36 |  |
| `10.9d †` | Notifications — payment-link auto-send and aggregator shift open/close notifications | P | BUILT | Both switches now have a real trigger (batch 8 w8): `PaymentLinkAutoSendTrigger` reads `notifications.payment_link_auto_send` at `BRAND` scope after a `PaymentIntentCreated` commit (never for cash or the aggregator's own MARKETPLACE tender) and mints the same `PAYMENT_LINK` checkout a customer-initiated checkout would, handing the link to the order's own customer. `MarketplaceShiftNotificationService` reads `notifications.aggregator_shift_notifications_enabled` off the new `POST .../marketplace/shift-events` fact (`MARKETPLACE_SHIFT_RECEIVE`) and fans out through `OperationsAlertPort` with the binding's own local business date. Both tested, including the per-business-day idempotency key and the two partner-surface refusals. | M | — | w8-integrations-notifications |  |
| `10.10a †` | Reference data — cancellation and completion reasons | P | BUILT | `P37`'s versioned edit path (unchanged) is joined this batch by the row's last gap: `V0400` adds `ordering.order_outcome_reasons.display_order` (backfilled from the prior alphabetic order), and `PUT .../order-outcome-reasons/reorder` (whole-set, If-Match'd against the highest version among the reordered rows) backs drag-and-drop plus an explicit move-up/move-down pair on both reason tables in the console — the cancel/complete picker on the order-outcome dialog also stops re-sorting alphabetically and now renders the tenant's own order. Tested: `OrderOutcomeReasonReorderEndpointTests`, `reference-data-page.spec.ts` (4 cases). | S | — | w6-settings-locations-policy | BUILT |
| `10.10b` | Reference data — business calendar (holidays, business-day boundary across midnight, weekend definition) | P | BUILT | `P37` built the tenant's own weekend declaration, its own holiday list (`BusinessCalendarController`), and the boundary editor `BusinessDayService.setBoundary` never had a caller for, gated by a new ADR 0027 approval action. A boundary change marks a recut outstanding rather than performing one; `DayCloseService` still does the actual recut. Not built: offering to create `tenant.service_schedule_exceptions` rows from a holiday — settings.md says "offer", never "silently create", and no UI builds the offer yet. | L | — | P37 |  |
| `10.10c †` | Reference data — SLA bucket boundaries | 2 | PARTIAL | ADR 0107 resolves the contradiction this row named: SLA buckets stay platform-fixed and versioned (ADR 0043), full stop — tenant configurability is declined, not deferred. `P37` ships the honest half instead: a tenant-readable version card (`ReportingController.slaBucketSet`) naming the active bucket set so a report can be read against the definition it was computed under. An operator still cannot change the reporting buckets, by design. Where an order counts as late on the boards is a different thing: since batch 16 a tenant sets it per fulfilment mode on the order-policy lateness card (`X.39`), and the reports' buckets do not follow it. The deciding record is ADR 0043 (Accepted); ADR 0107 records the correction and built the card, but its own Decision status is still Proposed. The contradiction is now removed from every document that carried it: settings.md §10.10 «Границы SLA» and its two table rows (wave P37), statistics.md §2.3 (2026-09-30), and — the last holdouts, which ADR 0107 had assumed corrected — [the frontend information architecture](frontend-information-architecture.md) rows `7.3` and `10.10` (each now strikes the tenant-configurable clause through with a dated note) and item 7 of its «where operations beats Delever» list (corrected to «versioned SLA buckets»), all 2026-09-30. The loose end that amendment recorded is closed in batch 18 (`w6-settings-readiness-shell`): the branch and courier reports now read the bucket-set version through `SlaBucketSetApi`, the same `GET` the Settings card reads, and print no version when it cannot be read, where they printed a constant (`slaBucketSetVersion = 1`) that would have gone on saying v1 the day the platform published v2 (`branch-sla-report-page.spec.ts`, `courier-report-page.spec.ts`, `reference-data-page.spec.ts`, green in this audit's run with the rest of the operations suite). The status stays PARTIAL for the reason it always had: the capability the row named, tenant-configurable boundaries, is declined (ADR 0043, Accepted) and this legend has no DECLINED status, so what is built is the version card; and ADR 0107, the record that built the card, still has Decision status Proposed, so the owner has not accepted it. | M | — | w6-settings-readiness-shell | BLOCKED |
| `10.10d` | Reference data — branch tags | 2 | BUILT | `P37` built the registry (`tenant.branch_tags`), the per-branch assignment (`tenant.location_branch_tags`), and the settings screen's location × tag matrix — the filter-and-group affordance this row named as the reason to have tags at all. | M | — | P37 |  |
| `10.11` | Data & privacy (retention schedules, consent definitions, DSAR/erasure, export audit) | 3 | PARTIAL | Retention periods for three categories, a tenant consent-type registry, and a tenant-wide DSAR erasure worklist are all live now (the worklist's own URL bug — a missing `/customers` segment — is fixed). Export and correction of a customer's own data remain not-built, per ADR 0029 — unchanged, and explicitly out of scope. | L | — | W03 |  |
| `10.12` | Languages & regional formats (supported languages, default language, currency/phone/timezone formats) | 2 | PARTIAL | A brand's supported-locale set and default language are stored and edited inside the `10.1` brand-profile screen, the shared `LocaleSet` service carries them to the authoring forms, and per-locale tables for comment presets, delivery regions and delivery zones (V0430/V0431, read by `CatalogQueryService` in the brand default) hold wording beyond ru/uz/en. Batch 15 made the readers that put a name in front of a customer or an import follow the brand default (catalog import and export, the onboarding sample, the storefront menu and its ETag, the menu item list, the price-book matrix, add-by-filter), sent the brand's list locale from three more console forms, let terms of service (`X.2`), payment-method names (`10.6`) and the new-product dialog (`4.1`) author in the brand's or tenant's own languages (ten of roughly 40 forms now do), and froze a preset's wording in every locale onto the order line (V0433). Batch 16 (`w4-locales-tail`, with `fix16-d-locales-tail`) closes three of the four things batch 15 left open and adds the regional-format editor the row's title names. (1) Readers that took one literal locale: `GET .../catalog/locations/{id}/variants` and `.../availability-counts`, behind the stop list, New order's item search and the bulk price change, now take a name from the first of [requested locale, brand default, `horecaos.catalog.default-locale`] that has one, the search and the tab badges match the name that is shown, and a caller that sends no locale gets the brand default; the fix round pinned the fallback to the chain's order (it had been the alphabet) with a product written in uz and ru and one in ru and en. (2) Preset wording in the console: the order detail, the kitchen ticket, the New order dialog and the New order basket line read one shared `presetLabelFor` (the `labels` map for the console language, then that language's column, then the platform-resolved label, then the triple), so a wording beyond ru/uz-Latn/en is shown (`2.1b`). (3) Regional formats: V0441 adds `money_symbol_placement`, `money_grouping` and `phone_display_pattern` to `tenant.brands` (defaults reproduce the old output, CHECK constraints), `PUT .../brands/{brandId}/regional-formats` (`BRAND_WRITE`, audited as `brand.regional_formats_revised`) is the only writer, and the brand profile gains a Форматы card with a live preview and the tenant's and branch's timezones read-only. `formatMoney`, `formatCount` and a new phone pipe read one signal that `RegionalFormatSync` fills from the operator's own location, `GET .../brands/{brandId}/locations/{locationId}/regional-formats` (`LOCATION_READ`: the first cut read the brand, which needs `BRAND_READ`, and left the cashier, the kitchen lead and the branch manager on the defaults, exactly the people who read amounts all day); the fix round also routed the courier policy, dispatch board, delivery tariffs, rate cards, adjustment-reason rules, zone free-delivery and minimum-basket text and the campaign cost figures through `formatMoney` where they had printed a raw integer. The phone pattern reaches the order detail, the reports customer column and the brand and branch contact phone; a search for the pipe finds no other screen. Tested and green in this audit's run: `BrandRegionalFormatsEndpointTests` (9), `CatalogAvailabilityReadModelTests` (22), `CatalogAuthoringControllerEndpointTests`, `LocaleTranslationTablesMigrationTests`, `regional-format.spec.ts`, `regional-format-sync.spec.ts`, `money-regional.spec.ts`, `phone.spec.ts`, `locale-labels.spec.ts`, `brand-profile-page.spec.ts` and the specs of the order detail, kitchen queue, item-modifier dialog and the courier, delivery and campaign pages. Still missing: the remaining forms (notification templates, outcome/reject/branch-override reasons in Reference data, data-privacy consent labels, the brand-profile language chooser itself) stay on the platform triple; the three legacy columns are not yet dropped; and currency, date and time formats stay derived and read-only by decision (settings.md §10.12, amended in batch 16: only where the unit sits, how thousands are grouped and the phone pattern are a brand's choice). Batch 17 drafts the decision behind a fourth language: ADR 0149 (Proposed, Not started) counts the platform's closed locale lists at more than sixty independent declarations on the integrated tree (its first draft counted twenty-seven; the staff member record added one more, `ck_staff_member_locale` in V0453, spelled with a bare `uz` beside the five sets that already were) and proposes one `PlatformLocales` registry in which `kk` and `ka` are declared and active in no tier until a tenant needs one. Drafting is not deciding; adding a language is still a change in dozens of places. | M | ADR 0149 (Proposed 2026-10-01, Not started) for any language beyond ru, uz-Latn and en — the platform owner has not decided it. | w9-decision-adrs |  |
| `10.13` | Delivery policy in Settings (out-of-zone addresses, what the courier sees and may do, GPS action checks) | P | BUILT | `PUT .../courier-policy` (via `PolicyAuthor`) and the `/delivery/courier-policy` screen now exist, with the five previously fieldless switches added (GPS master toggle + radii, show-only-kitchen-ready, reveal-customer-location timing, post-delivery payment check) and `delivery.out_of_zone_policy` registered as an ADR 0030 key. | L | — | P38 |  |
| `10.14` | Printing & receipts (POS printing, fiscal receipt presentation, tape width) | ? | NOT BUILT | An operator cannot choose what prints where, on what tape width, or what the customer's receipt looks like; every print today is whatever the POS adapter happens to do. | XL | No ADR owns printing or receipt presentation — one has to be written before any screen can be specified (settings.md §4). | deferred |  |
| `X.2` | Terms of service (storefront terms per brand, per locale, versioned) | P | BUILT | Nothing an operator needs for the pilot. Only a rich-text editor is absent (plain textareas per locale) and the route/api comments still cite 'ADR 0067', which is the referral-programme ADR — the real record is ADR 0068. Batch 15 (row `10.12`): the editor and the history preview no longer hard-code ru/uz-Latn/en; each picked brand offers its own supported languages, default first and marked (`resolveLocaleSet`, the rule `LocaleSet` applies to the pinned brand), and a language the brand does not offer is sent back unchanged on every publish, because a version is a whole new document and the server does not carry an omitted language forward. The fix round closed a real hazard the picker introduced: a newly picked brand's form still held the old brand's text while its two GETs were in flight, so Publish in that window published the old brand's text as the new brand's terms of service; the form now empties and shows a loading line, the picker and Publish stay disabled until the reads land, and a reply for a brand no longer picked is dropped. Tested (`terms-page.spec.ts`, green in this audit's run). | S | — | w3-locale-remaining-forms |  |
| `X.3 †` | HorecaOS support visits (who from support entered this account, and ending a live visit) | 2 | BUILT | — | S | — | P31 | BUILT |

## §X — Shells and design system

40 rows — 33 built · 7 partial

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `X.1` | Operator console shell (dense, sidebar, keyboard-first) | P | PARTIAL | `service-status.ts` has a fetch of its own (`P08`'s `COUNTERS` accelerator, via `RealtimeClient`) instead of waiting on `order-queue.ts` to `set()` it, so the rail's open/late badges no longer read zero-then-freeze; `updatedAt` is rendered (`q-refresh-indicator`), and `shell.ts` mounts `ConnectionStateBanner` and `LiveBadge`. The toast host this row said was missing has been mounted since the primitives wave (`q-toast-host`: a confirmation raised from anywhere is drawn, a failure goes to the interrupting region and not beside the success, and every toast is cleared on sign-out so the next operator at the terminal sees none of the last one's), so that part of the note was out of date. Batch 18 (`w6-settings-readiness-shell`, with `fix18-f-settings-shell`) builds the rest of what the row said was missing. A brand picker is drawn for a tenant with more than one brand and for nobody else; it names the brand in effect, so it is the scope readout, and picking one re-points the console, remembers the choice (`BrandChoice`) and builds the open screen afresh (`CurrentBrand`, `CurrentLocation`, which waits for the picked brand's branches instead of reading the previous brand's). And there is a keyboard layer: a `ShortcutRegistry`, one dispatcher, and a cheat-sheet on `?` (and from a header button) that lists the keys of the screen that is open ahead of the shell's own, in the language the operator chose, stands down for a field being typed in and for any `aria-modal` dialog (`42a8aed0`), and is not drawn for a key nothing handles. The order board is the first screen to register a scope, with orders.md §2.12's keys (`j`/`k` and the arrows, Enter, Space, `1` to `7`, `a`, `x`, `c`, `n`, `r`, `/`, Esc): each acts only through the server's own `actions[]` for the focused order, `x` opens a dialog and never cancels by itself, and a key does nothing behind a dialog; `p` (print to POS) is not bound, because a board row carries no print action. The settings home registers `/`, and `F2` starts an order from anywhere. Tested and green in this audit's run: `shell.spec.ts`, `shortcut-registry.spec.ts`, `order-board-keyboard.spec.ts` (23 cases), `settings-home-page.spec.ts` (44), and the whole operations suite (376 files, 4,391 tests). Still missing: the layer is adopted by two screens, so «keyboard-first» holds for the order board, the settings home and `F2`, and nowhere else. New order (orders.md §5.8: `Ctrl+Enter` creates, `Esc` returns to a pane's first field, `Esc` twice prompts to discard), the dispatch board, the kitchen screens and every other table register nothing. | M | — | w6-settings-readiness-shell |  |
| `X.2 †` | KDS / kitchen device fullscreen shell (touch, no chrome, offline banner) | P | BUILT | A top-level `/device` route (`device-shell.ts`), chrome-less and touch-scaled, with the ADR 0079 pairing handshake (rendering `q-qr-code`), a device-token auth path (`device-session.ts`, never the staff Keycloak session), and an offline banner driven by `navigator.onLine`/the `online`/`offline` events. | L | — | P17 | NOT BUILT |
| `X.3` | Wallboard shell (TV-distance, oversized counters) | 2 | BUILT | — | M | — | T23 |  |
| `X.4 †` | MapCanvas + PolygonEditor + BoundingBoxEditor + MapPin/AddressPicker | P | PARTIAL | An operator cannot draw or edit a real delivery polygon, cannot set a region's SW/NE bounding box to constrain the geocoder, and cannot place or correct an order's address pin — a zone is a radius in metres typed into a form. Batch 17 drafts the decision, ADR 0145 (Proposed, Not started); drafting is not deciding, and nothing in the console, the control plane or the platform changed: the only map in the repository is the customer storefronts' own Yandex Maps script on the address screen (`locations-add`). | XL | ADR 0145 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. It closes ADR 0015's open input «geocoder/map provider selection», which ADR 0037 inherits, by proposing a measured bake-off with Yandex Maps as the first adapter; the licence terms, paying a foreign licence from Uzbekistan and the bake-off thresholds are open inputs (legal, finance, product). No provider is chosen, so there are no tiles, no geocoder and no credential. | w9-decision-adrs | BLOCKED |
| `X.5` | LocalizedFieldGroup (language tabs, default marker, completeness) | P | BUILT | A tenant still cannot auto-translate a field across locales — deferred as a platform-owner decision on a translation provider (PART B), not a build gap of this component. | M | — | P02 |  |
| `X.6` | DataGrid (inline edit, fill-down, keyboard nav, virtualization) | P | BUILT | — | L | — | P03 |  |
| `X.7` | MatrixGrid (editable cross-tab with row/column bulk toggle) | P | BUILT | — | M | — | P03 |  |
| `X.8` | ActionMenu + Drawer + Modal + ConfirmDialog | P | BUILT | Residue: `q-action-menu` and `q-drawer` reached their first real call sites only in batch 3 (`notifications-page`, wave `P36`; `fiscal-workbench-panel`, wave `P21`) and `q-confirm-dialog` in `categories-page`/`menus-page` (wave `P23`) — P05's own order-row overflow menu, one of `q-action-menu`'s two originally-intended first consumers, was hand-rolled instead of migrated onto it. | M | — | P01 |  |
| `X.9 †` | Combobox (async search, create-on-miss, multi-select chips) | P | BUILT | — | M | — | P02 | NOT BUILT |
| `X.10 †` | MoneyInput / PercentInput / MoneyOrPercent | P | BUILT | — | S | — | P02 | NOT BUILT |
| `X.11 †` | DateRangePicker + TimeInput + DayOfWeekToggle + ScheduleGrid | P | BUILT | TimeInput and DayOfWeekToggle are live at `capacity-page` (unchanged). This row's remaining claim — that DateRangePicker and ScheduleGrid "have no call site yet" — was already stale before this batch: ScheduleGrid has been live in the settings hours editor (`location-detail-pane.ts`) since `P43` shipped, credited on `10.2c` but never corrected here. This batch closes DateRangePicker's own gap for real: the reports filter bar's "Custom" period used a second pair of raw `<input type="date">` fields instead of `q-date-range-picker`; `reports-shell.ts` now maps the real component's `{start,end}` onto `ReportsFilterState`'s `{from,to}`. Tested: `reports-shell.spec.ts`. | L | — | w5-reports-exports |  |
| `X.12` | MediaUploader with aspect-ratio crop (+ video) | P | PARTIAL | Logo/banner through `q-media-uploader` (wave 9 w2, unchanged). This batch closes the client-side half of the video gap: a selected video is now refused inside the uploader itself (a new `videoNotSupported` reason, surfaced through a single exported `mediaUploaderRejectionMessageKey` both callers share) the moment it is picked, before any crop step or network call — the file picker's `accept` still lists `video/mp4`/`video/webm` on purpose, so a video stays selectable and this refusal is what an operator sees, not a mysteriously absent OS-dialog option. The server stays image-only; no video pipeline exists yet. Still no upload path anywhere for kiosk idle media, story slide or review-tag icon. Tested: `media-uploader.spec.ts` (rewritten video case, a new webm case, the accept-attribute and rejection-key mapping). | M | — | w7-integrations-pos-fixpath |  |
| `X.13 †` | ImportWizard (dropzone, row preview, dry-run diff, job progress, results) | P | BUILT | — | L | — | P26 | NOT BUILT  |
| `X.14` | SecretInput (masked, reveal-once, copy, rotate, last-used) | P | BUILT | — | S | — | P35 |  |
| `X.15` | StatusPill extensions (lateness overlay; dual-state order+cooking pill) | P | BUILT | — | S | — | P01 |  |
| `X.16` | LockedState + DeniedState (distinct from EmptyState) | P | BUILT | — | S | — | P01 |  |
| `X.17 †` | Toast + InlineAlert | P | BUILT | — | M | — | P01 | NOT BUILT |
| `X.18 †` | DataTable extensions (saved views, persisted filters, column chooser, server paging + infinite scroll, selection with bulk-action bar, row action menu) | P | BUILT | Persisted filters, saved views, a column chooser, cursor paging/infinite scroll, a selection model with a bulk-action bar and a row action menu are all built; selection+bulk bar is live at `stop-list-page`, cursor paging at `products-page`, and, this batch, saved views and the column chooser get their first live call site too: `order-reports-page.ts`'s «Заказы» tab (see `7.2a`), swapped from the hand-rolled `q-order-rows-table` onto `q-data-table` with `viewId="reports.orders.commercial"`. This row's claim that "the order board's own filter/bulk toolbar (P07) still depends on this component, not merged" was already stale before this batch — P07 merged long ago and the board uses its own hand-rolled toolbar (`1.1c`/`1.1f`), not this component; corrected on verification. | L | — | w5-reports-exports |  |
| `X.19` | Chart family (line, bar, stacked, donut, funnel, histogram, cohort heatmap, sparkline, geo heatmap) | 2 | PARTIAL | Line, bar, stacked-bar, donut, histogram and the hour×weekday heatmap are unchanged from batch 10. This batch adds the ABC cumulative-revenue-share curve: a new `GET .../reporting/abc-curve` read (`reporting.fact_order_line`'s own variant sales, sorted revenue-descending and walked into a running cumulative share, classed A/B/C against `ClassificationThresholds.DEFAULT`'s published 80/95 split — a chart's data source, not ADR 0134's persisted, capability-gated classification run beside it) backs `AbcCurveChart` (`q-abc-curve`) on the ABC tab of product analytics: one point per product, coloured by class, two dashed threshold lines, a crosshair tooltip and the chart family's usual accessible table. Tested both ends (`AbcCurveReportingTests`, `abc-curve-chart.spec.ts`). Funnel, cohort heatmap and geo heatmap remain unbuilt — no data source exists yet for any of them. | L | — | w6-reporting-facts |   |
| `X.20` | KpiTile (value + delta + sparkline) and WallboardTile | 2 | BUILT | — | M | — | T09 |  |
| `X.21 †` | Board / BoardColumn / BoardCard with drag-drop | 2 | BUILT | — | M | — | P18 | NOT BUILT  |
| `X.22 †` | DragDropAssign (drag a card onto a target card) | 2 | BUILT | — | M | — | P18 | NOT BUILT  |
| `X.23` | SortableList / TreeView with drag-reorder | 2 | BUILT | — | M | — | P23 |  |
| `X.24` | MappingPane (dual list, link/unlink, unmapped filter, bulk auto-match) | 2 | BUILT | — | L | — | P24 |  |
| `X.25 †` | ConditionBuilder + RuleList with priority reorder + RuleSimulator | 2 | BUILT | All three primitives have a screen, a real endpoint behind it and a spec. `q-condition-builder` is on the segments page; `q-rule-list` is on `/marketing/automations` (row `6.5`, batch 11), its drag or keyboard reorder persisted by one whole-set `PUT .../automations/reorder`; and `q-rule-simulator`, which until batch 16 had no call site anywhere in `frontend/operations`, now leads the automations preview dialog (`w5-milliy-frontend-hygiene`). An automation rule has no condition tree, but each trigger kind is exactly one typed condition (`AutomationTriggerType.configKey()`), so `automation-conditions.ts` mirrors the four kinds one for one (BIRTHDAY as a distance from the birthday, either side; INACTIVITY, CART_ABANDONMENT and CASHBACK_CHANGE at or past their threshold). The operator types the figures of a hypothetical customer and the simulator says whether the rule would fire and what it would then do (template, channel, cooldown); it makes no request, and it simulates the rule as enabled whatever its armed state, because a preview asks whether it would fire once armed. A trigger kind or configuration this build cannot express is not simulated. The server's masked sample of today's real matches (`AutomationRulePreviewService`, `GET .../automations/{ruleId}/preview`, batch 15) stays beside it. Tested and green in this audit's run: `automation-conditions.spec.ts` and `automations-page.spec.ts` (the page cases were run red first with the element removed). Moved PARTIAL → BUILT because the one component the row named that had no caller now has one. Batch 17 gives the primitives their second consumers: promotion authoring (`6.1`, ADR 0140) draws its conditions on `q-condition-builder` (extended with time windows, flags, restricted operators and searchable lists, `condition-types.spec.ts`), keeps its priority order on `q-rule-list`, one list per stacking group, and prices a synthetic cart on `q-rule-simulator` in a new engine mode that shows the engine's verdicts and not the client-side dry run; the dispatch rules (`3.8`) keep their ordered list on `q-rule-list`. Tested and green in this audit's run: `condition-builder.spec.ts`, `rule-list.spec.ts`, `rule-simulator.spec.ts`, `promotion-editor.spec.ts`, `promotion-simulator.spec.ts`, `promotions-page.spec.ts`, `dispatch-rules-page.spec.ts`. | L | — | w5-promotions-engine |  |
| `X.26 †` | Timeline + DiffViewer + ActorChip | 2 | BUILT | `q-timeline` (order-detail's lifecycle lane, staff activity log) and `q-actor-chip` (replacing three duplicated `actorLabel` implementations) are unchanged from batch 10. This batch closes the row's last gap: `activity-log-page`'s drawer now renders its diff through `q-diff-viewer` itself, deleting the hand-rolled `<div class="diff">` markup and its dead CSS that had reintroduced the exact 'three ideas re-implemented per screen' problem this row exists to fix. Tested (`activity-log-page.spec.ts`'s own `q-diff-viewer` case). | M | — | w7-audit-people |   |
| `X.27` | TemplateEditor with VariableChip + live preview in PhoneFrame | 2 | BUILT | — | M | — | P36 |  |
| `X.28 †` | PhoneFrame siblings (AggregatorCardFrame, KioskFrame 9:16, TelegramMiniAppFrame) | 2 | BUILT | `q-phone-frame`, `q-aggregator-card-frame`, `q-kiosk-frame` and `q-telegram-mini-app-frame` each have a live consumer now: the channel preview (`4.6a`, `/catalog/preview`) picks the frame from the previewed channel's type and fills it with the draft's real menu, not a storefront-shaped sample. An aggregator's shelves are drawn as cards (`q-aggregator-card-frame`) inside the phone frame, a kiosk in the 9:16 kiosk frame, Telegram in the mini-app frame, anything else in the phone frame. Tested through the page for the aggregator-in-phone and kiosk cases (`channel-preview-page.spec.ts`); the Telegram arm of the switch is covered only by the frame's own spec, no page test selects it. Moved PARTIAL → BUILT: the one thing the row lacked was a consumer. | M | — | w3-marketplace-preview |  |
| `X.29` | NumberStepper | 2 | BUILT | — | S | — | P02 |  |
| `X.30` | SplitPane (master-detail) | 2 | BUILT | — | S | — | P01 |  |
| `X.31` | RichTextEditor (block-based, sanitized) | 2 | BUILT | The storefront still renders terms content as plain text via a numbered-point regex split (`TermsSectionsPipe`); a tenant using a heading or a list in the new sanitized editor will show literal tags to customers until the storefront is updated to render the emitted HTML with its own sanitizer pass. | L | — | T22 |  |
| `X.32 †` | ColorInput | 2 | BUILT | This row's claim of "no call site anywhere in the console" was already stale before this batch: `sales-channels-page.ts`'s channel edit panel has used two `q-color-input` swatches for a channel's primary/secondary brand colour (`tenant.sales_channels.brand_color_primary`/`brand_color_secondary`, V0385) since wave 9 w2 — the row's own originally-intended consumer (a brand-colours field) never picked it up, but a different, equally real one did, and this document never credited it. Corrected on verification, not new work this batch. | S | — | w2-brand-channel-presentation |  |
| `X.33` | Steps / ProgressRail | 2 | BUILT | — | S | — | T22 |  |
| `X.34` | LiveBadge / RefreshIndicator / StaleIndicator / ConnectionState banner | 2 | BUILT | Wired into `shell.ts` (live badge, connection banner, refresh indicator against `service-status.ts`'s own `updatedAt`) and into `order-queue.ts`/`today-page.ts` via the shared `RealtimeClient`. The KDS boards themselves (kitchen-queue-page, expo, VDU, buffer, dispatch board) still hand-roll their own 10s poll and do not consume this set — out of `P08`'s brief, which scoped the accelerator to order-queue and today-page only. | M | — | P08 |  |
| `X.35 †` | QRCode display + DataMatrix scan input | 2 | PARTIAL | The display half is built (`q-qr-code`): device pairing, the payments `qrPayload` field, and (after a batch-5 review fix to the zigzag data-placement bug, `qr-encode.ts`) the printable table QR card. The DataMatrix marked-goods scan-input half has no endpoint, model or ADR — split out, not a gap of this wave. | M | — | P17 | NOT BUILT |
| `X.36 †` | FloorPlanCanvas + TableToken + TimelineScheduler (1.5 Reservations) | 3 | BUILT | `FloorPlanCanvas` and `TableToken` (batch 11) — a host can see the room, section layout and table adjacency, and drag-reposition a table; seat/complete for a booking is `W01`'s (`1.5a`). Batch 12 gave `TimelineScheduler` its first real consumer: a Grid/Timeline toggle on the existing IA `1.5` reservations day screen, reading the same `tables()`/`reservations()` the grid already loads — one timeline row per table, one block per (booking, table) pair, a party seated at joined tables shown on every row it holds; clicking a block opens the same detail pane the grid's cells open, and two bookings holding the same table over an overlapping window are flagged as a capacity conflict (`reservations-page.spec.ts`). Batch 15: `TableToken` gains an optional `occupied` state that the floor-plan pane sets from the live sessions, so a seated table is drawn as such on the canvas (`table-token.spec.ts`); `FloorPlanCanvas` and `TimelineScheduler` themselves are untouched (their diff is one spec line). The seat action is a panel in the floor-plan pane (`10.2d`), not part of the canvas. | L | — | w6-dine-in-operator-flows |  |
| `X.37` | CallBar + CallScreenPop (1.6 Telephony) | 3 | BUILT | `shell/call-bar.ts` is mounted in `shell.html` (`<q-call-bar />`) and shows the ringing card, claim, and start-order on every screen, reading the same `VoicePresence` poll the call-centre page starts from — the screen pop now follows the operator across the console. | L | — | W01 |  |
| `X.38` | OtpInput (staff MFA) | 3 | PARTIAL | The component (6-cell code entry, paste-fill, auto-advance) is built and tested but has no consumer and no backend: staff MFA has no endpoint anywhere, so nothing in the console can render it yet. ADR 0148 (Proposed, Not started) would give it its first consumer, a second step on the staff sign-in and an enrolment screen; nothing of it is built (`StaffDirectGrantClient.signIn` still sends a password and nothing else). | S | ADR 0148 (Proposed 2026-10-01, Not started) — the platform owner has not decided it: TOTP only, held in Keycloak; a first-party second step on the sign-in page and an enrolment screen; an attempt limit the platform owns; recovery as an audited administrator action. | w9-decision-adrs |  |
| `X.39 †` | Semantic status colour ramp (SLA green/amber/red at bucket boundaries + tenant late colour) | P | BUILT | Both boards agree by construction: a named `--q-sla-*` token ramp replaces ad hoc `--q-warning/--q-error` reuse, a shared TS evaluator computes LATE/AT_RISK from one resolved `ordering.lateness` policy document, and batch 13's `evaluateHighlightColourContrast` checks a candidate colour for WCAG AA 4.5:1 against the four surfaces it can land on. Batch 15 let a tenant set the at-risk minutes (`ordering.at_risk_before_minutes`, the default of every mode with no window of its own) and the late colour (`ordering.late_colour`, `#rrggbb` validated at write and again where served, with the contrast warning under the swatch, a warning and never a refusal); the order board rows, the order-detail header, the kitchen queue, the kitchen VDU and the wallboard VDU draw a LATE order in it, and BLOCKED, the approval deadline and the at-risk step keep the platform ramp. Batch 16 (`w2-lateness-policy-editor`, with `fix16-b-lateness`) closes the gap that kept the row PARTIAL, the late line itself. The `ordering.lateness` document, which every board read and nothing could write, has an editor: `GET` and `POST /api/v1/operations/tenants/{tenantId}/order-lateness-policy` (`TENANT_CONFIGURATION_READ`/`WRITE`) read and publish it at TENANT, BRAND or LOCATION scope. The whole document is replaced as one unit (ADR 0030), the version the form was opened at travels back as `expectedVersion` (a second operator's save in between is `STALE_VERSION`), a reason and an `Idempotency-Key` are required, and every publication leaves an `ordering.lateness-policy.authored` audit fact, written through `ChangeDocuments`, with the per-mode numbers that moved. Per fulfilment mode a tenant sets the grace past the promise (`lateAfterSeconds`, up to a day), the no-promise fallback (whole minutes, 1 to 1,440) and the at-risk window (whole minutes, 0 to 1,440, or none). Order policy → Timing and SLA gains `q-lateness-policy-card`: one Edit opens all three modes, in the same `q-inherited-field` vocabulary (set here, inherited from, trace), with a stale-version reload and no «revert to inherited», because a published version is never withdrawn. The fix round made a publication reach the boards, which the first cut did not: it drops every cached resolution it changes (`PolicyCurrentCacheEvictor`; before, a board served the old numbers until the cache aged out), publications are serialised per key and scope, the editor reads the table and not the cache, the card drops the previous scope's form when the scope bar moves, and the always-on screens (the kitchen VDU, the wallboard VDU, the boards) re-read the policy on their poll instead of once per page load. Tested and green in this audit's run: `OrderLatenessPolicyEditorHttpTests` (19; the console's real body through the write to the `GET .../orders/lateness-policy` the boards poll, a location override beating the tenant, a stale version refused, a retried request publishing once, an unauthorised caller refused), `OrderLatenessPolicyAuthoringServiceTests` (12), `OrderLatenessDocumentTests`, `OrderLatenessPolicyServiceTests` (15), `PolicyCurrentCacheEvictorTests`, `JdbcPolicyAuthorVersionCheckTests`, `CacheEvictionIntegrationTests`, `lateness-policy-card.spec.ts`, `lateness-policy-api.spec.ts`, `lateness-policy-tracker.spec.ts`, `order-policy-page.spec.ts`, `order-queue.spec.ts`, `kitchen-queue-page.spec.ts` and `vdu-page.spec.ts` (a ticket warns by its own mode's window and paints by its own mode's grace, and a wall display follows an edit). Moved PARTIAL → BUILT. What it does not cover: the colour is drawn on five surfaces and specified on two (the queue and the kitchen queue); a search of the specs for the order-detail header, the kitchen VDU and the wallboard VDU finds none for it. `ordering.late_order_threshold_minutes` («Заказ опаздывает с») is still stored and read by nothing, and the page now says so beside the value (see `10.3b`). The reporting SLA buckets are platform-fixed and versioned by decision (ADR 0043 and 0107, row `10.10c`). Batch 17 drafts the decision on the setting left over: ADR 0150 argues that the scalar read as a line from acceptance contradicts ADR 0036's clock (the promise starts at checkout) and draws a second late line, that read as a grace past the promise it duplicates `lateAfterSeconds` and would move every tenant's line by 45 minutes the day a reader shipped, and proposes the one job nothing else does: the tenant-wide default for the no-promise fallback measured from `created_at`, whose platform default (2700 seconds) equals the scalar's registered 45 minutes. Drafting is not deciding; the field is still read by nothing. | M | ADR 0150 (Proposed 2026-10-01, Not started) for `ordering.late_order_threshold_minutes` — the platform owner has not decided it. | w9-decision-adrs |  |
| `X.40` | dir/script-safe type stack (uz-Latn, Cyrillic ru, Latin en; kk and ka on the roadmap) | 2 | PARTIAL | All 261 raw `font-size: Npx` declarations found across 65 files are fixed via 11 `--q-type-*` custom properties, and P02's ESLint rule now enforces no regression. Kazakh and Georgian faces are still not bundled and no `dir`/RTL handling exists — adding kk or ka still means a font and layout change, not just a message file. ADR 0149 (Proposed) would declare `kk` and `ka` in a registry with a face identifier and a direction (`LTR` for every entry) and activate neither until a tenant needs one, so the font and layout work this row names follows its acceptance; it is not something this row can decide. | S | ADR 0149 (Proposed 2026-10-01, Not started) — the platform owner has not decided it. | w9-decision-adrs |  |

---

# PART B — What is not in the plan, and why

## Blocked: six rows waiting on an input nobody in engineering owns

| # | Row | Tier | Size | The input | Who owns it |
|---|---|---|---|---|---|
| `2.6a` | Cook headcount output | 3 | XL | A portions-per-cook-hour policy (it varies by station and cuisine, and nobody has stated it) and a demand-forecast decision. ADR 0041 L661-666 says so in as many words. `GET /reporting/demand-history` could stand in for the forecast in a first cut once the ratio exists. | Owner / product |
| `X.5` auto-translate | The auto-translate button on LocalizedFieldGroup | P | M | The component itself ships in `P02`; the **button** has no service to call. `ProviderCategory` declares POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION, GEOCODING and VOICE and no translation category, and every `translate` hit in `platform/src/main/java` is the manual `PUT /translations` path. The parity matrix grades trilingual content **with** auto-translation ●, so this is a real parity item, not a nicety. | Owner / product — pick a provider (and answer whether machine output may be published unreviewed, which is a brand-voice question, not an engineering one). |
| `4.7` | Reference data — attributes, tags, ingredients, kitchen departments, comment presets | 2 | L | ADR 0016's open input on legacy merchandising disposition, in particular whether Атрибуты are the variant axis or a spec-sheet vocabulary. The spec forbids building any of it first. Note kitchen-department routing already exists by another route (`KitchenStationController` routing rules), so only the vocabularies wait; product comment presets already have their own table, screen and KDS/POS round trip (`2.1b`). | Owner / product |
| `4.9` | Auto-add rules | 2 | XL | No ADR owns server-side cart injection; catalog.md and the IA both record it as unowned and needing a product decision before design. The portion-band kind no longer waits on a portions attribute (decimal portions exist since batch 17, `4.2c`); it waits on the same decision. | Owner / product |
| `5.2f` | Deposit (stored-value) balance ledger | P | XL | «Whether HorecaOS may hold customer prepaid balances at all» — named in the blocked table of `frontend-and-parity-plan.md`. | Legal |
| `6.3a` | Loyalty — deposit accounts | 3 | XL | A Central Bank of Uzbekistan e-money authorisation, or the owner's decision to hold funds under a tenant's own licence. ADR 0046 withdrew stored value outright; its return is a new ADR, not an implementation task. | Legal / owner |
| `8/X.3` | Prepaid wallet and top-up | 2 | XL | ADR 0095 is `not-started` with «Date decided: —». The inputs were answered on 2026-09-11; the platform owner has not yet accepted the proposed structure. No schema, service or endpoint may be written before that acceptance. | Platform owner |

## Deferred: rows an agent could not finish in an isolated worktree

Each of these is XL, or depends on a decision that has to be written down first. They are
not scheduled as waves; they are scheduled as **decisions**.

| # | Row | Tier | Size | Why it is not a wave | What unblocks it |
|---|---|---|---|---|---|
| `0.1d` | Live operator leaderboard | P | XL | Even a counting endpoint would print Keycloak subject UUIDs. | Cleared by batch 17 (`w4-staff-identity`): ADR 0139 was Accepted on 2026-10-01 and the row is BUILT in PART A. |
| `0.2c` | Personal data (own profile) | 2 | XL | Nowhere for a staff name, phone or email to live. | Cleared by batch 17 (`w4-staff-identity`): ADR 0139 Accepted, the row is BUILT in PART A; the password and the sign-in identifier stay Keycloak's by that decision. |
| `0.2d` | UI personalization (user-scoped) | 2 | L | ADR 0030 has no user scope. | An ADR 0030 amendment and an owner ruling on what Персонализация contains. |
| `1.2c` | Amendments — `REMOVE_LINES`, the one financial command still refused | P | S | Batch 10 built six of the seven financial commands (`1.2c`/`2.1d` in PART A). Only `REMOVE_LINES` stays refused: releasing or writing off already-committed stock needs a fourth ADR 0017 primitive (return-to-stock or write-off) beyond the port's deliberate three (hold, commit, release). | An ADR 0017 amendment naming the fourth primitive — that module's decision, not a wave's. |
| `1.3d` | Pre-order time with out-of-hours warning | P | M | ADR 0019 names scheduled orders as still open; V0056 deliberately leaves the requested-time column out. | An ADR 0019 decision on scheduled orders. The out-of-hours half is already servable from `ServiceabilityResolver`. |
| `2.5a` | Stop scope and channel propagation | P | XL | A data-model change: availability must become `(product × scope)` with a source. | ADR 0141 was Accepted on 2026-10-01 and built in batch 17 (PARTIAL in PART A). What still waits: a marketplace adapter, which depends on product's answer to which aggregators expose an availability write API to a third party, and ADR 0040's outbound adapters. Batch 18 built the items of that record which need no adapter (the partner pull, the markers, the two events and the stale-channel alert, the read-switch decommission with its materialisation run, the explainer dialog); none of them replaces the adapter. |
| `3.1b` | `/deliveries` variant for courier-service tenants | ? | XL | The discriminator exists; what does not is a decision to let business type drive routing — ADR 0090 explicitly rejected that. | Owner answer to «is HorecaOS one product or several product shapes?» |
| `3.8` | Dispatch rules | P | XL | No decision record covers an operator-authored, provider-agnostic rule engine; racing and grouping semantics are open. | ADR 0142 was Accepted on 2026-10-01 and built in batch 17 (PARTIAL in PART A). What still waits: courier grouping (finance: how a courier is paid for one run) and `CANCEL` on the unpaid window (ADR 0019's open input, product). |
| `4.2a` `4.2b` | Combos · modifier depth | 3 | XL ×2 | New columns on `catalog.variants`/`modifier_groups`, a `combo_selection_id` grouping on order lines, and a publication transport format. | ADR 0136 was Accepted on 2026-10-01 and built in batches 17 and 18 (both BUILT in PART A since the batch-18 re-audit, with their residue written on the rows: the hidden-charge wording waits on product and legal, a terminal that groups a combo's lines is not sent the grouping, the per-component fiscal receipt is `8/X.2`). The ADR's own implementation status stays Partial. |
| `4.2c` | Physical attributes | 3 | XL | A new `variant_physical_attributes` table, and a decimal-quantity fork on `order_lines` the record does not resolve on its own. | ADR 0137 was Accepted on 2026-10-01 and built in batch 17 (PARTIAL in PART A; batch 18 added the decimal amendment and the per-quantum wording of a weighed price). What remains: the till is told the nominal weight, and a weighed basket cannot be paid online until ADR 0153 (Proposed 2026-10-03) is decided. |
| `4.6a` | Aggregator preview and per-channel projection | 2 | XL | There is no channel projection to render. | ADR 0138 was Accepted on 2026-10-01 and built in batch 17 (PARTIAL in PART A). What remains: per-marketplace rulesets (product, partnerships) and the outbound push (ADR 0040). |
| `5.4` | Reviews — service-recovery board | 2 | XL | ADR 0071 rejected the kanban and the scored dimensions on the record; the IA and the accepted decision disagree. | A superseding ADR, or an IA amendment. The `review → remedy` link is small and can ship regardless. |
| `5.5` | Feedback settings | 3 | XL | The moderation and tagging decision ADR 0071 declined. | The same ADR. |
| `6.1` | Promotions rule engine | 2 | XL | The engine and the conditions exist; authoring endpoints do not, and parity needs markup, geozone-polygon and named-customer condition types — a schema change. | ADR 0140 was Accepted on 2026-10-01 and built in batch 17 (PARTIAL in PART A). What remains after batch 18 (the gift is offered and the main storefront takes a code): order-level markup (fiscal, legal), named-customer targeting, per-locale titles, and a console screen for a scheduled order's re-quote findings with what a changed one leads to. |
| `6.3b` | Loyalty — POS balance sync | 3 | XL | No ADR covers which side owns the balance; each POS needs its own capability adapter and a `pos_provider_capabilities` ceiling row. | An ADR plus a vendor. |
| `6.4a` | SMS, email and push campaign channels | 2 | L | `SmsGatewayAdapter` is deliberately generic because no SMS contract exists; email and push have no provider at all. | A gateway contract: ADR 0146 (Proposed 2026-10-01, Not started) proposes generalising the VAS contract the platform has read, and keeps marketing and courier SMS refused until the owner answers which gateway accounts exist and what each may carry. The `isWired` gate on the channel picker shipped in `T18`. |
| `6.7` `6.8` | Content slots · storefront merchandising | 2 | XL, L | No merchandising-slot tables exist on either side, and the creatives need MediaUploader with crop and video. | ADR 0044's slot tables; then one wave each. |
| `7.6c` | Acquisition source and the product funnel | 2 | XL | Visits, registrations and cart adds are recorded nowhere; the funnel starts empty whenever it is built. | Legal confirmation of lawful basis and retention for behavioural telemetry (ADR 0043 open input). |
| `7.9` | Promo-code summary and redemption detail | 2 | XL | ADR 0023 forbids a report reading a module schema, so the redemption fact is the required path and its grain needs an owner. | ADR 0140 was Accepted on 2026-10-01 and the summary, the log and the fact are built in batch 17; the row was PARTIAL in PART A until the batch-18 re-audit, which found the customer now resolved by an audited reveal and a test that crosses checkout, day close and report, and moved it to BUILT. |
| `7.10` `7.10a` | Order-density heatmap · today's orders as pins | 3 | XL, L | No map primitive, no geographic dimension in the facts. | `X.4` (map provider) and a reporting geo aggregation. ADR 0145 (Proposed 2026-10-01) is the provider decision and names the zone dimension the density view needs. |
| `8/X.2` | The fiscal evidence itself | P | XL | Receipt URL, fiscal sign, issuing INN, marking codes and the correction/void commands are all absent, and the obligations differ by settlement path. | ADR 0038's legal open input: which party is the legal fiscal agent per settlement path. |
| `9.1b` | Capability grid, search, role templates | P | L | ADR 0025's closed input, «no tenant-defined roles in v1». | Reopening that decision. Capability **search** alone is frontend-only and rides in `P30`. |
| `9.2` · `9/X.2` · `9/X.3` | Staff person record · staff shifts · terminals and PINs | 2 | XL ×3 | No ADR owns staff identity: 0025 is authorization, 0009 is Keycloak reconciliation, 0042 is couriers. | ADR 0139 was Accepted on 2026-10-01 and built in batch 17: `9.2`, `9.2b`, `0.1d` and `0.2c` are BUILT in PART A and `9.2c` and `X.5` PARTIAL. It keeps staff shifts (`9/X.2`) and terminals and PINs (`9/X.3`) as separate records with their own decisions; no ADR has been drafted for either, so those two stay blocked. |
| `10.5` | Channel setup — kiosk device pairing | P | M | Batch 10 built the hostname/domain claim, SEO, static pages and a per-channel setup hub (`10.5` in PART A). Only kiosk device pairing remains: no device registry, no pairing flow, and `DevicePrincipalClass` carries no kiosk principal to pair against. | A decision on the kiosk device registry — hardware, pairing UX, and the principal class it authenticates as. |
| `10.14` | Printing and receipts | ? | XL | Settings §4: «A print capability port on ADR 0011, and a decision that owns printing and receipt presentation at all. Nothing owns it today.» | An ADR. |
| `X.4` | MapCanvas · PolygonEditor · BoundingBoxEditor · MapPin | P | XL | No provider means no tiles, no geocoder, no credential. Region CRUD is the one genuinely backend-missing piece and ships in `P20`. | ADR 0145 (Proposed 2026-10-01, Not started) carries ADR 0015's «approve address/geocoder provider», which ADR 0037 inherits; the platform owner has not decided it. |

## Amend the IA, do not build

Five rows describe behaviour a decision record has already refused. Building against them
would be building against a superseded design.

| # | Row | The decision that refuses it | Done |
|---|---|---|---|
| `1.2d` | Multi-step (multi-branch) composition | orders.md:52 and :1342 — Delever's `steps[]` is declined, «two nested state machines for a behaviour no evidence shows anyone using». | 2026-09-11 — IA §1 row `1.2`: the clause is struck in place in the **Owns** list with a dated note citing orders.md §0.1 and the parity matrix. |
| `1.6a` | Call centre — softphone and click-to-call | ADR 0064's Alternatives refused WebRTC inside operations. The IA's «Owns: softphone» line should be struck with the ADR cited. | 2026-09-11 — IA §1 row `1.6`: «Owns: softphone» struck with a dated note quoting ADR 0064's Alternatives; click-to-call is removed from the screen's purpose line in the same note, because dialling needs the same client. |
| `6.7a` | Editorial surfaces (news, gallery, recipes, vacancies) | The parity matrix excludes the general-purpose CMS with tenant-authored raw HTML; ADR 0044 repeats it. The IA still lists them in three places. | 2026-09-11 — struck in all three: IA §6 row `6.7`'s **Owns** list, the «Deliberately excluded from operations» bullet on a recruitment ATS (which had kept vacancies as content), and PART 4's **RichTextEditor** component row. Each keeps a dated note citing the parity matrix's exclusion and ADR 0044. The control-plane exclusion bullet in PART 1 still names consumer content generically and was left alone. |
| `3.6` free geozone | «Бесплатная геозона» as a geometry layer beside zones and branch geozones | `fulfillment/domain/zone/ZoneRole.java` — «a 'free geozone' is not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as a layer is what left three layers with no documented interaction». The IA's 3.6 bullet and the parity matrix's ○ row should both read «a zone whose tariff resolves to zero», and the buildable half is `3.6`'s tariff binding, tracked as `3.6d`. | 2026-09-11 — IA §3 row `3.6`'s **Owns** list and the parity matrix's ○ «Free geozone (Бесплатная геозона)» row both now read «a DELIVERY zone whose tariff resolves to zero, shown as free», citing `ZoneRole.java` and ADR 0037. The buildable half stays `3.6d` in `P20`. |
| `7.7c` | Kiosk sales report | statistics.md §2.7: «a separate report for one channel is a precedent that ends in eleven reports». Kiosk must be readable as a channel slice instead. | 2026-09-11 — IA §7 row `7.7`: «kiosk sales report» replaced by «kiosk readable as a channel slice of the sales report», citing statistics.md §2.7. |

Two further IA defects the verification surfaced, both documentation rather than build:
the **operator inbox** (`§1/X.1`, shipped, ADR 0059) has no IA row at all, and **KDS device
enrolment** (`§2/X.2`, backend complete) has none either — IA 10.5 registers kiosks, not
kitchen devices. `1.5a`'s «auto-create client on unknown phone» and «external reservation ID
as the displayed identifier» contradict a deliberate ADR 0047 decision and should be struck
from the row rather than scheduled.

**Done, 2026-09-11.** The operator inbox is now IA §1 row `1.7` and KDS device enrolment is
IA §2 row `2.7`, each marked as already existing — `1.7` shipped under ADR 0059 stage 2,
`2.7` backend-complete under ADR 0079 with no console caller. Both strikes in `1.5a` landed
in IA §1 row `1.5`, with one dated note citing ADR 0047: a booking for a guest with no
account creates no customer record and no consent, and the external reservation id lives
once in `integration.provider_entity_mappings` as a lookup key rather than as the displayed
identifier. No other IA row was touched; `settings.md`, `orders.md` and `statistics.md`
already carried the refusing wording and needed no edit. IA PART 3's pilot screen lists
were deliberately left alone: they enumerate what the pilot has to **build**, and `1.7` is
already shipped while `2.7`'s console half is scheduled here as `2/X.2` in `P17`.

**Second pass, 2026-09-30 (batch 16, `w8-declines-and-docs`).** This document's status legend defines
BUILT, PARTIAL, NOT BUILT and BLOCKED and **no DECLINED status**, so a declined row keeps the status its
evidence earns and is listed here instead. Re-reading each row's own text against the documents found that
the 2026-09-11 pass had struck the IA lines for `1.2d`, `1.6a` and `7.7c` but that their PART A rows still
carried an "amend" marker or said the IA "should be amended", and that three more rows (`10.10c`, `3.4c`,
`7.3b`) described a decline the owning specification did not state in its own words. Each now does, and each
row's note points at the amendment. Reading them against the code showed that two of the six (`3.4c`, `7.3b`)
are not declines at all and a third (`1.6a`) was only half of one; the list says which:

| Row | Where it is now stated | What it says | Clean decline? |
|---|---|---|---|
| `1.2d` | [orders.md](operations-spec/orders.md) §0.1 | One order, one location; no per-step status, tab or money. Reason: two nested state machines for a behaviour no evidence shows. Schema agrees. | Yes |
| `1.6a` | [orders.md](operations-spec/orders.md) §0.5; IA §1 row `1.6` | No softphone (ADR 0064, Accepted: the platform never carries audio). | The softphone, yes. **Click-to-call, no:** ADR 0064 does not name it; it is a provider call-control capability no shipped VOICE adapter declares, so it is *not built* and waits on an adapter. The 2026-09-11 note had folded it into the decline. |
| `7.7c` | [statistics.md](operations-spec/statistics.md) §2.7 and §5; IA §7 row `7.7` | No kiosk-only report; kiosk is a channel slice. Decided in the spec, no ADR. | Yes. The replacement is half-built: the filter bar has the channel picker, the product tab and `variant-sales` do not use it. |
| `10.10c` | IA rows `7.3`, `10.10` and item 7 of the beats-Delever list; [statistics.md](operations-spec/statistics.md) §2.3; [settings.md](operations-spec/settings.md) §10.10 | SLA buckets are platform-fixed and versioned; a read-only version card is the whole affordance. Deciding record ADR 0043 (Accepted); ADR 0107 (Proposed) built the card. | Yes. The IA was the last document still promising configurability. |
| `3.4c` | [couriers.md](operations-spec/couriers.md) §11 | Seven closed bases; `ORDER_UNDELIVERED` and `ORDER_DAMAGED` manual-only until something reads `fulfillment.delivery_exceptions` (ADR 0108, Proposed). | **No.** Not built, and no ADR refuses it. The API also accepts a wired rule on either basis that then never fires. (The console's wireable list omitted `CASH_VARIANCE` until batch 18, which added it.) |
| `7.3b` | [statistics.md](operations-spec/statistics.md) §2.3 Tables C and D | The channel counts and payment split are flat lists. | **Not a decline, and decided.** A layout position: no ADR decides it and the building wave recorded the grid as deferred. The "2D matrix §2.3 draws" was never in §2.3; it is «Сводка 2» in §2.2, already built as `7.2c`. The platform owner confirmed the flat lists as the specified shape on 2026-10-07; nothing is left to build. |

No status moved and no count in this document changed; whether `7.3b` and `3.4c` should move is the next
re-audit's call, not this pass's. (Batch 18 re-read `3.4c` after adding `CASH_VARIANCE` to its form: it stays PARTIAL, because two of the seven bases still have no reader. `7.3b` was not touched by the batch.)

**Re-audit, 2026-10-01.** Neither moved: `3.4c` is not a decline and no courier code changed, and `7.3b`'s flat
lists are what the IA now describes only because this pass amended it to match the build, so closing it waits for the
owner. Each of the six rows now links the amended IA row from its PART A note.

The pass left two things behind, cleared in the batch-16 review fixes: the `amend` Wave value on `1.2d`,
`1.6a` and `7.7c` (the amendments were done, so it is now `—`), and the tail of row `1.6`, which still called
click-to-call struck per ADR 0064 (it now says the softphone is the recorded decline and click-to-call is not
built). Row `6.7a` still carries `amend` and its note still says the IA lists the sub-features; IA row `6.7`
was struck on 2026-09-11, so that row is stale too and is the next re-audit's to correct.

Cleared by the 2026-10-01 re-audit: `6.7a`'s Wave marker is now `w8-declines-and-docs` and its note says the IA row
was struck on 2026-09-11 and links it.

---

# PART C — The build plan

## How this plan is meant to be run

**One wave, one worktree, one agent.** Each wave below is written to be handed to a fresh
agent with no other context. The brief names the rows, what *done* means for each, the
files, the endpoints and **their capability constants by name**, the migration numbers it
may use, the ADR to write or amend, **the tests it must add**, and the traps that have
already caught a reader. A brief that leaves the agent a choice is a defect: where two
implementations were arguable, this revision picks one and says which wave owns the file.

**Row ids are section-qualified wherever they repeat.** Five ids — `X.1`, `X.2`, `X.3`,
`X.4` and `X.5` — name a different row in each of up to six IA sections (`X.1` alone is
the operator inbox in §1, customer erasure in §5, split tender in §8, Telegram staff links
in §9, the settings scope bar in §10 and the console shell in §X). Wherever one of those
appears in the wave index or a brief it is written `<section>/<id>` — `8/X.1`, `10/X.1`,
`X/X.5`. An unqualified `X.n` in this document is an id that occurs exactly once.

**Sizing is scored, not asserted.** Each row carries `S`=1, `M`=2, `L`=4, `XL`=8
size-points; the **Points** column in the index is their sum. A wave may carry **at most
eight points per declared agent-day**, and **a wave containing an `XL` row is never 1d** —
an XL row is a week's worth of consequence compressed into one line and it drags whatever
sits beside it. That rule is what split `P05`/`P41`, `P12`/`P42`, `P22`/`P47`,
`P23`/`P45`, `P31`/`P46` and `P32`/`P43`, and what moved `X.38` out of `W05` and `10.2d`
out of `P32`. `2d`–`4d` waves are groups that share a file set and cannot be split without
two agents fighting over the same file; run them as one agent over several days.

**Parallelism is bought with file discipline.** Two waves may run at the same time only if
they do not write the same file. Where the IA's sections collide with the repo's folders
the plan splits by *file*, not by folder, and says so: `features/orders/` carries several
waves that each own a different file set (`order-queue*`, `order-detail-pane*`,
`new-order/*`, `reservations-page*`, `call-centre-page*`), and the settings section is cut
along its sub-folders so `locations`, `sales-channels`, `fiscalization`, `integrations`,
`notifications` and `reference-data` never meet. Where that was impossible the waves are
**sequenced** and the `After` column says which wave must merge first. Every entry in that
column is a collision, not a preference.

**Nine shared surfaces are sequencing hazards.** Anything touching them must go through the
wave that owns them:

1. `frontend/operations/src/app/shared/ui/` **does not exist yet.** `P01` creates it. Every
   later component wave adds its own files and never edits another wave's — which is why
   `P08` (`X.34` indicators), `P17` (`q-qr-code`), `P18` (board and drag-assign), `P22`
   (`q-media-uploader`), `P24` (`q-mapping-pane`) and `P26` (`q-import-wizard`) are all
   sequenced behind `P01` and none of them is «after nothing».
2. `OperationsOrderController` and its `OrderSummaryResponse` / `OrderDetailResponse`
   records are the hottest file in the backend. `P04` owns the list shape, `P05` owns
   `actions[]`, `P09` owns the detail shape, and **nothing else edits those records**.
   `P11` and `P42` therefore add *sibling order-keyed endpoints* —
   `GET .../orders/{orderId}/delivery` and `GET .../orders/{orderId}/pos-export` — rather
   than fields on the detail record, which is the only way both can be true at once.
3. The order-board file family — `order-queue.ts/.html/.css`, `order-severity.ts`,
   `order-tabs.ts`, `order-counts.ts`, `order-money.ts` — is **strictly serialised**:
   `P01` (status pill) → `P05` (action menu) → `P06` (lateness constants) → `P07`
   (toolbar, selection, class doc) → `P08` (live counters, ADR 0045 comment). None of
   these five may run beside another. `order-severity.ts` is imported by six production
   files, so `P06`'s deletion of its constants reaches `order-detail-pane.ts`,
   `order-tabs.ts`, `order-counts.ts`, `order-money.ts` and `kitchen-ticket.ts`.
4. `kitchen-ticket.ts` and `KitchenBoardController.TicketResponse`: `P06` owns the
   lateness constants, `P16` owns the board query and its tab counts, `P11` owns the ETA
   field on `TicketResponse`, `P17` owns the device client. The order is `P06` → `P16` →
   `P11` → `P17` and the index enforces it.
5. `MetricRegistry` is append-only by ADR 0043 discipline. `P27` owns the first additions;
   `T06`, `T12`, `T13`, `T14` and `P39` add theirs on top of it. Add, never edit.
6. `frontend/design-tokens/tokens.css` is **generated, not authored** — a token is added
   upstream or not at all. `P06` adds the SLA ramp, `T09` the dataviz palette, `T23` the
   TV type step, `T22` the type-scale corrections. Treat it like `MetricRegistry`: append a
   contiguous block, never reformat, no serialisation needed.
7. `frontend/operations/package.json`: `@angular/cdk` is **absent** and two waves want it.
   `P03` adds the dependency and the lockfile entry; `P18` consumes it and is sequenced
   behind `P03` for that reason alone.
8. `frontend/operations/src/app/core/api/*-paths.ts` are shared barrels. Add entries, never
   reformat the file, and keep each wave's additions in its own contiguous block.
9. `platform/src/main/resources/db/migration` is **strictly ordinal** and `V0222` is the
   current head, so two parallel waves would both write `V0223`. Numbers are allocated
   below; a wave uses its own block or none.

**Migration numbers are allocated, not chosen.** Head is `V0222`: `V0211`–`V0222` were
taken while this map was being written — the wallet (ADR 0095: `V0211`, `V0212`, `V0214`,
`V0222`), the password reset (ADR 0098: `V0213`), the invitation history (ADR 0100:
`V0215`) and P19 (`V0220`, `V0221`) — so P11, P14 and P16 moved to the end of the table
and `V0216`–`V0219` stay unused rather than reallocated. Each wave that adds schema owns
the block below and may use fewer numbers than it reserves, never more; a wave not listed
here adds no migration and must raise it rather than take a number.

| Wave | Reserved | What it creates |
|---|---|---|
| **P19** | `V0220`–`V0222` (used: `V0220`–`V0221`; `V0222` went to the wallet's own ADR 0095 allocation) | courier compliance columns under the ADR 0029 envelope; `courier_groups`; `courier_branch_bindings` |
| **P22** | `V0223`–`V0225` (used: `V0223` only; `V0224`–`V0225` not needed — the widened media allowlist named alongside the channel dimension turned out to have no DB-level representation to migrate) | a channel dimension on `catalog.media_relations`; the widened media allowlist |
| **P47** | `V0226`–`V0228` | the per-item sale-schedule binding V0020 withdrew; `catalog.product_recommendations` |
| **P24** | `V0229`–`V0231` (used: `V0229`–`V0230`; `V0231` not needed) | indexes for the mapping reads; the `OPERATOR` mapping source has no schema change |
| **P26** | `V0232`–`V0234` (used: `V0232`–`V0233`; `V0234` not needed) | the import-job entity and its per-row report |
| **P28** | `V0235`–`V0237` | `reporting.report_exports` |
| **P39** | `V0238`–`V0240` | `reporting.fact_order_tender` |
| **P32** | `V0241`–`V0243` (used: all three) | brand logo, banner, localized description, contact phone, Telegram handle; the per-brand supported-locale set; the brand media relation |
| **P33** | `V0244`–`V0246` (used: `V0244`–`V0245`; `V0246` not needed) | payment-method localized names, `provider_installation_id`, `contract_reference` |
| **P34** | `V0247`–`V0249` (used: `V0247` only; `V0248`–`V0249` were not needed — the legal-entity fields already existed since `V0053`) | `fiscal.fiscal_terminals`; legal-entity short name, VAT certificate, tax profile, registered address, contact phone |
| **P35** | `V0250`–`V0252` (used: all three) | partner API client last-used; provider-installation last-used watermarks |
| **P36** | `V0253`–`V0255` (used: none — the order-type/source template keying that needed them was deferred, see row `10.9a`) | the per-version variables schema; the template unique index widened past `(tenant, brand, key, channel)` |
| **P37** | `V0256`–`V0258` (used: all three) | branch tags and their location assignment; the tenant business calendar and its boundary |
| **T16** | `V0259`–`V0261` (used: `V0259`–`V0260`; `V0261` not needed) | courier-type starting minute and work mode; the adjustment-reason amount and condition columns |
| **T17** | `V0262`–`V0264` (used: `V0262` only) | `courier_roster_entries` |
| **T05** | `V0265`–`V0267` (used: `V0265` only; `V0266`–`V0267` not needed) | `pricing.benefit_grants` |
| **T18** | `V0268`–`V0270` | `marketing.attribution_links`; a foreign key on `accrual_rules.scope_id` |
| **T11** | `V0271`–`V0273` | `reporting.fact_delivery`; the `COURIER` scope of `agg_sla_bucket_day`; an index on `delivery_fee_resolutions (tenant_id, created_at, tariff_id)` |
| **T12** | `V0274`–`V0276` (used: `V0274` only; `V0275`–`V0276` not needed) | `operator_principal_id` on `reporting.fact_order` |
| **T14** | `V0277`–`V0279` | `reporting.classification_run` and `classification_result` |
| **T15** | `V0280`–`V0282` | `notification_deliveries.read_at` and the `READ` status |
| **T08** | `V0283`–`V0285` | a correlation column on the audit fact |
| **W02** | `V0286`–`V0288` | `reporting.forecast_run` and `fact_forecast`; a holiday flag per sample date |
| **W03** | `V0289`–`V0291` (used: `V0289` only; `V0290`–`V0291` not needed — retention periods reuse `tenant.configuration_values` via ADR 0030 keys) | the consent-type registry; tenant retention periods |
| **P11** | `V0292`–`V0294` | courier ETA persisted into the domain and joined onto the kitchen ticket |
| **P14** | `V0295`–`V0297` | `customer_accounts.origin` and `created_by_actor_id` |
| **P16** | `V0298`–`V0300` | a structured stop source on the availability read model |

**Sizing.** `1d` means one agent-day at eight size-points. **Every wave ends the same way.**
`mvn spotless:apply` before the gate, never after — a formatting-only gate failure costs a
full slot. Java tests run against the migrated schema, not a mock. Angular specs are
mandatory for every component the wave touches, and a wave that touches a screen with no
`.spec.ts` adds one. Do not commit a running wave's tree; gate first, merge second.

**Stale comments are part of the work.** Several waves below are told to delete a specific
code comment. Those comments have already produced wrong gap reports twice; leaving them
in place is how this document goes out of date.

## Wave index

Seventy-five waves. **Points** is the size-point sum defined above; **After** is the merge
order the file-collision analysis forces.

| Wave | Title | Tier | Size | Points | Rows | After | Merged |
|---|---|---|---|---|---|---|---|
| **P01** | Console primitives: overlays, feedback, status | P | 1d | 7 | `X.8`, `X.30`, `X.17`, `X.16`, `X.15` | — | 2026-09-12 |
| **P02** | Console primitives: value, time and text inputs | P | 2d | 11 | `X.10`, `X.11`, `X.29`, `X.32`, `X.9`, `X/X.5` | P01 | 2026-09-13 |
| **P03** | Console primitives: DataGrid, DataTable extensions, MatrixGrid | P | 2d | 10 | `X.6`, `X.18`, `X.7` | P01, P04 | 2026-09-13 |
| **P04** | Order list read model and query surface | P | 1d | 6 | `1.1`, `1.1d` | — | 2026-09-12 |
| **P05** | Server-driven actions: actions[] as a capability array | P | 1d | 2 | `1.2a`, `1.1e` | P01, P04 | 2026-09-13 |
| **P41** | Backward status transitions and the override policy | P | 2d | 8 | `1.1h` | P05 | 2026-09-13 |
| **P06** | Lateness policy and the SLA colour ramp | P | 2d | 10 | `1.1g`, `X.39` | P05 | 2026-09-13 |
| **P07** | Order board: filters, search, selection, bulk | P | 1d | 6 | `1.1c`, `1.1f` | P03, P06 | — |
| **P08** | Realtime push and the shell's own counters | P | 2d | 8 | `1.1b`, `0.1f`, `X/X.1`, `X.34` | P07 | — |
| **P09** | Order detail: the record and its outcomes | P | 2d | 10 | `1.2`, `1.2o`, `1.2p`, `1.2m`, `1.2j`, `1.2k` | P05 | 2026-09-13 |
| **P10** | Operator notes and the first amendment client | P | 1d | 4 | `1.2h` | P09 | — |
| **P11** | The order-to-fulfilment seam | P | 2d | 14 | `1.2e`, `1.2n`, `2.1a`, `1.2b` | P09, P16 | — |
| **P44** | Provider cancel cascade and quote-delta confirmation | P | 1d | 6 | `1.2g`, `1.2f` | P11 | — |
| **P12** | Money on the order: payment and fiscal panels | P | 1d | 2 | `1.2l` | P11 | — |
| **P42** | Machines on the order: POS export and its errors | P | 2d | 8 | `1.2i` | P12 | — |
| **P13** | New order: the screen, the basket, the caller | P | 2d | 14 | `1.3`, `1.3c`, `1.3a` | P02 | 2026-09-13 |
| **P14** | New order: address, money, promo, repeat, aggregator | P | 2d | 16 | `1.3b`, `1.3e`, `1.3f`, `1.3g` | P13 | — |
| **P15** | Live board: period scoping, mixes, branch load | P | 2d | 10 | `0.1`, `0.1a`, `0.1b`, `0.1c` | — | 2026-09-12 |
| **P16** | Kitchen queue and stop list | P | 2d | 14 | `2.1`, `2.1c`, `2.1e`, `2.5`, `2.5b`, `2.5c` | P03, P06 | — |
| **P17** | KDS device shell, enrolment and QR | P | 2d | 8 | `X/X.2`, `2/X.2`, `X.35` | P01, P16 | — |
| **P18** | Dispatch board, board columns and drag-assign | P | 1d | 8 | `3.1`, `X.21`, `X.22` | P01, P03 | 2026-09-13 |
| **P19** | Courier roster: the compliance file | P | 1d | 4 | `3.3` | — | 2026-09-12 |
| **P20** | Zones, regions, free geozones and delivery tariffs | P | 2d | 11 | `3.6`, `3.6d`, `3.6b`, `3.7` | — | 2026-09-12 |
| **P21** | Product library and the fiscal workbench | P | 1d | 8 | `4.1`, `4.1a` | P03 | 2026-09-13 |
| **P22** | Product editor: variants, media and fiscal data | P | 2d | 12 | `4.2`, `4.2f`, `4.2d`, `4.2e`, `X.12` | P01, P02 | 2026-09-13 |
| **P47** | Per-item schedule, kitchen department and cross-sell | P | 1d | 6 | `4.2g`, `4.2h` | P02, P22 | — |
| **P23** | Categories and the per-location menu matrix | P | 2d | 10 | `4.3`, `X.23`, `4.4` | P03 | 2026-09-13 |
| **P45** | Menus: per-channel offering and price | P | 2d | 8 | `4.4b` | P23 | — |
| **P24** | POS catalog sync and the mapping pane | P | 2d | 16 | `4.5a`, `10.8b`, `X.24` | P03 | 2026-09-13 |
| **P25** | Customer record depth: contact, consent, address | P | 2d | 6 | `5.2a`, `5.2b`, `5.2c` | — | 2026-09-12 |
| **P40** | Customer record: ledger, promos, reviews, blacklist, erasure | P | 1d | 8 | `5.2d`, `5.2e`, `5.2g`, `5.2h`, `5.2i`, `5/X.1` | P25, P14 | — |
| **P26** | ImportWizard, customer CSV and the header counters | P | 2d | 10 | `X.13`, `5.1b`, `5.1a` | P02 | 2026-09-13 |
| **P27** | Reports: the filter bar, the overview and the order log | P | 2d | 16 | `7.1d`, `7.1`, `7.1a`, `7.2`, `7.2a`, `7.2b`, `7.2c` | P02, P07 | — |
| **P28** | The export centre | P | 2d | 8 | `7.2e` | P27 | — |
| **P29** | Finance: payments and fiscal, per order | P | 1d | 4 | `8.1`, `8.2` | — | 2026-09-12 |
| **P39** | Payment mix and the tender fact | P | 2d | 4 | `7.1c` | P33 | — |
| **P30** | Staff: gated navigation, locked versus denied | P | 1d | 5 | `9.1`, `9.1c`, `9.1d` | — | 2026-09-12 |
| **P31** | Settings: the scope bar, readiness and support visits | P | 2d | 11 | `10/X.1`, `10.0`, `10/X.3` | — | 2026-09-12 |
| **P46** | Settings: order policy cards and catalog base settings | P | 2d | 10 | `10.3b`, `4.4d` | P31 | 2026-09-13 |
| **P32** | Settings: brand, languages and the branch list | P | 2d | 12 | `10.1`, `10.12`, `10.2a`, `10.2b` | P02, P31 | 2026-09-13 |
| **P43** | Settings: hours, prep bands and the order limit | P | 1d | 4 | `10.2c` | P32 | — |
| **P33** | Settings: channels, the capability matrix and payment methods | P | 2d | 10 | `10.4a`, `10.4b`, `10.6` | P03, P31 | 2026-09-13 |
| **P34** | Settings: fiscalization | P | 2d | 9 | `10.7a`, `10.7b`, `10.7c` | P31 | 2026-09-13 |
| **P35** | Settings: integrations, analytics and partner credentials | P | 2d | 13 | `10.8a`, `10.8c`, `10.8d`, `10.8e`, `X.14` | P31 | 2026-09-13 |
| **P36** | Settings: notifications | P | 2d | 13 | `10.9a`, `10.9b`, `10.9c`, `10.9d`, `X.27` | P02, P31 | 2026-09-13 |
| **P37** | Settings: reference data | P | 2d | 9 | `10.10a`, `10.10b`, `10.10c`, `10.10d` | P31 | 2026-09-13 |
| **P38** | Settings: delivery policy, dine-in QR and the floor plan | P | 2d | 14 | `10.13`, `10.5b`, `10.2d`, `X.36` | P17, P31 | — |
| **T01** | My work | 2 | 2d | 14 | `0.2`, `0.2a`, `0.2b` | P08 | — |
| **T23** | The wallboard shell | 2 | 1d | 6 | `0.1e`, `X/X.3` | P15 | 2026-09-13 |
| **T02** | Kitchen buffer, expo gate, VDU and capacity | 2 | 2d | 7 | `2.2`, `2.3`, `2.4`, `2.6` | P16 | — |
| **T03** | Live map and courier policy | 2 | 1d | 8 | `3.2`, `3.9` | P19 | 2026-09-13 |
| **T16** | Courier types, rate cards and adjustment rules | 2 | 2d | 9 | `3.4a`, `3.4b`, `3.4c` | P19 | 2026-09-13 |
| **T17** | Shifts, attendance and bulk geozone upload | 2 | 1d | 8 | `3.5`, `3.6c` | P19 | 2026-09-13 |
| **T04** | Publication readiness, price books and bulk pricing | 2 | 2d | 8 | `4.6`, `4.8a`, `4.8b` | P03 | 2026-09-13 |
| **T05** | Segments and promo codes | 2 | 2d | 8 | `5.3`, `6.2`, `6.2a` | P02 | 2026-09-13 |
| **T18** | Campaigns, loyalty and acquisition links | 2 | 2d | 10 | `6.3`, `6.4`, `6.4b`, `6.6a` | T05 | — |
| **T06** | Branch and SLA reports | 2 | 1d | 7 | `7.3`, `7.3a`, `7.3b` | P27 | — |
| **T11** | Courier reports and the delivery fact | 2 | 2d | 12 | `7.4`, `7.4a`, `7.4b`, `7.4c` | T06 | — |
| **T12** | Staff and telephony reports | 2 | 2d | 10 | `7.5`, `7.5a`, `7.5b` | T08 | 2026-09-13 |
| **T13** | Customer analytics | 2 | 2d | 10 | `7.6`, `7.6a`, `7.6b` | P27 | — |
| **T14** | Product analytics: ABC and XYZ | 2 | 2d | 9 | `7.7`, `7.7a`, `7.7b` | P27 | — |
| **T15** | Marketing reports | 2 | 1d | 8 | `7.9a`, `7.9b` | T05 | — |
| **T07** | Finance: courier money | 2 | 2d | 12 | `8.3`, `8.4`, `8/X.5`, `8.5` | P29, T11 | — |
| **T19** | Finance: subscription and invoices | 2 | 1d | 6 | `8.6`, `8/X.4` | P29 | 2026-09-13 |
| **T08** | Staff: the audit log | 2 | 2d | 11 | `9.3`, `9.3a`, `9.3b`, `9.3c`, `9.2d` | P30 | 2026-09-13 |
| **T20** | Staff: contacts, POS ids, Telegram links, self-service | 2 | 2d | 9 | `9.2b`, `9.2c`, `9/X.1`, `9/X.5` | T08 | 2026-09-13 |
| **T09** | Chart family and KPI tiles | 2 | 2d | 6 | `X.19`, `X.20` | P01 | 2026-09-13 |
| **T21** | Rule authoring components | 2 | 2d | 4 | `X.25` | P01 | 2026-09-13 |
| **T22** | Timeline, frames, rich text, steps, OTP, type stack | 2 | 2d | 11 | `X.26`, `X.28`, `X.31`, `X.33`, `X.38`, `X.40` | P01 | 2026-09-13 |
| **T10** | Drafts and abandoned carts | 2 | 1d | 1 | `1.4` | P03 | 2026-09-13 |
| **W01** | Reservations, dine-in sessions and telephony | 3 | 2d | 11 | `1.5`, `1.5a`, `1.6`, `X.37` | P02, P32 | — |
| **W02** | Demand forecast | 3 | 2d | 16 | `7.8`, `7.8a`, `7.8b` | P27 | — |
| **W04** | Geography: histograms and the week grid | 3 | 1d | 6 | `7.10b`, `7.10c` | W02 | — |
| **W03** | Privacy self-service, access check and approvals | 3 | 2d | 8 | `10.11`, `9/X.4`, `9.4` | P30 | 2026-09-13 |
| **W05** | Split tender: the payment[] array | 3 | 2d | 8 | `8/X.1` | P29 | 2026-09-13 |

**Batch 8 (post-plan, 2026-09-22).** Eight further waves ran after PART C's own 75-wave
plan had already closed (see "This closes PART C's wave plan" above) and are not part of
the table above or its `P`/`T`/`W` numbering — no brief for them exists in **The waves**
below. Recorded here only for traceability: `w1-order-board` (`1.1`, `1.1d`, `1.1e`),
`w2-new-order` (`1.3`, `1.3a`, `1.3d`), `w3-kitchen` (`2.1b`, `4.2g`; `2.1d` was in the
same area but not actually touched), `w4-catalog-import` (`4.5b`), `w5-amendments-ui`
(no row moved), `w6-audit-followups` (cross-cutting idempotency and PII-redaction fixes,
none of them a PART A row — see the batch-8 re-audit paragraph above), `w7-reports`
(`7.1a`, `7.2c`) and `w8-integrations-notifications` (`10.8c`, `10.9d`), merged onto
`wave8-integration` at `2d93ef95`.

**Batch 9 (post-plan, 2026-09-23).** Eight further waves, same posture as batch 8 — not
part of the `P`/`T`/`W` table above, no brief in **The waves** below. Recorded here only
for traceability: `w1-queue-override-filters` (`1.1h`, `1.1c`), `w2-brand-channel-presentation`
(`10.1`, `X.12`, `10.4a`), `w3-branches-settings-readiness` (`10.2a`, `10.0`),
`w4-reports-distance-crm` (`7.1`, `7.2a`), `w5-external-dispatch` (`1.2e`, `2.1c`),
`w6-menu-entity` (`4.4a`), `w7-catalog-channel-controls` (`4.2f`, `4.4d`) and
`w8-notification-templates-installs` (`10.9a`, `10.8a`), merged onto `wave9-integration` at
`90d742c1`. `10.8a` is the one row a wave's own report read fuller than the code proved —
see the batch-9 re-audit paragraph above for why it stayed PARTIAL.

**Batch 10 (post-plan, 2026-09-24).** Eight further waves, same posture as batches 8 and 9
— not part of the `P`/`T`/`W` table above, no brief in **The waves** below. Recorded here
only for traceability, and only as of the batch-11 audit — batch 10's own re-audit never
added this entry, unlike batches 8 and 9 before it: `w1-order-board-actions` (`1.1`, `1.1c`,
`1.3a`/`1.3f`, `9.2d`), `w2-financial-amendments` (`1.2c`, `2.1d`),
`w3-catalog-presets-schedule-xlsx` (`2.1b`, `4.2g`, `4.5b`), `w4-dispatch-couriers` (`3.1`,
`3.4c`), `w5-reports-exports` (`6.4`, `7.2e`, `7.7`, `7.10b`, `X.11`, `X.18`/`7.2a`),
`w6-settings-locations-policy` (`4.8a`, `10.0`, `10.1`, `10.2b`, `10.2c`, `10.3b`,
`10.10a`), `w7-integrations-pos-fixpath` (`1.2i`, `10.8a`, `10.8e`, `X.12`) and
`w8-channel-setup` (`10.5`), plus a review-fix round (`fix10-a-board`,
`fix10-b-amendments`, `fix10-c-catalog`, `fix10-e-reports`, `fix10-f-settings` and
`fix10-g-integrations`, all merged), landed on `wave10-integration` at `1bb167d5`. See the
batch-10 re-audit paragraph above for what verification moved and why.

**Batch 11 (post-plan, 2026-09-25).** Eight further waves, same posture — not part of the
`P`/`T`/`W` table above, no brief in **The waves** below. Recorded here only for
traceability: `w1-quick-closes` (`4.5b`, `7.2e`, `1.1`, `8.6`), `w2-quantity-stock` (`4.4c`,
`4.4d`), `w3-courier-policy-roster` (`3.9`, `3.3`), `w4-kitchen-realtime` (`2.1`, `2.4`),
`w5-fulfillment-destination` (`3.1`, `1.3g`, `7.3`), `w6-reporting-facts` (`7.4b`, `7.4c`,
`X.19`), `w7-audit-people` (`9.2d`, `9.3`, `9.3a`, `X.26`, `9.4`) and
`w8-marketing-automations` (`6.5`, `1.4`, `6.4`, `X.25`), plus a review-fix round
(`fix11-b-stock`, `fix11-c-courier`, `fix11-d-kitchen`, `fix11-e-fulfillment`,
`fix11-f-reporting`, `fix11-g-audit` and `fix11-h-marketing`, all merged), landed on
`wave11-integration` at `69b4e7f3`. See the batch-11 re-audit paragraph above for what
verification moved and why.

**Batch 12 (post-plan, 2026-09-28).** Eight further waves, same posture — not part of the
`P`/`T`/`W` table above, no brief in **The waves** below. Recorded here only for
traceability: `w1-storefront-inventory` (customer-facing `frontend/storefront`/
`frontend/storefront-milliy` inventory display — no PART A row; the rows its commits name,
`4.4c`/`4.4d`, are the already-`BUILT` `apps/operations` rows and are untouched here),
`w2-catalog-adrs` (`4.2a`, `4.2b`, `4.2c`, `4.6a` — ADR 0136/0137/0138 drafted and Proposed,
no row moves), `w3-i18n-lazy-load` (lazy-loads the console's own UI-string chunks and lowers
the initial-bundle budget to match — no PART A row), `w4-infra-followups` (ADR 0135 RustFS
deploy/runbook follow-ups — no PART A row), `w5-dispatch-realtime-actions` (`3.1`, `1.1e`,
`X.39`), `w6-audit-diffs-locales` (`9.3a`, `10.12`), `w7-marketing-triggers` (`6.5`, `X.25`)
and `w8-pricebook-reservations` (`4.8a`, `X.36`), plus a review-fix round
(`fix12-a-storefront`, `fix12-b-adrs`, `fix12-c-i18n`, `fix12-d-infra`, `fix12-e-dispatch`,
`fix12-f-audit-locales`, `fix12-g-marketing` and `fix12-h-pricebook-dinein`, all merged),
landed on `wave12-integration` at `ccd66cdb`. See the batch-12 re-audit paragraph above for
what verification moved and why.

**Batch 13 (post-plan, 2026-09-28).** Eight further waves, same posture — not part of the
`P`/`T`/`W` table above, no brief in **The waves** below. Recorded here only for
traceability: `w1-catalog-auto-listing` (pilot-critical inventory-listing fix behind `4.1` —
no PART A row of its own), `w2-new-order-branch-resolver` (`1.3`, `0.1c`),
`w3-storefront-table-qr` (customer-facing `frontend/storefront` dine-in ordering and
`frontend/storefront-milliy` error messages — no PART A row; its commits' own "(row 10.5,
ADR 0047)"/"(row 10.5b)" citations touch only the storefront apps this document does not
track), `w4-audit-diffs-a` and `w5-audit-diffs-b` (`9.3a`, split catalog/commercial/tenancy/
courier from reporting/integration/payments/ordering/pos/customers/dinein/audit/marketing/
fulfillment), `w6-locales-part-2` (`10.12`), `w7-hostname-verification-infra` (`10.5`, plus
an ADR 0135 RustFS deploy-credential follow-up with no PART A row) and `w8-test-health-fixes`
(test-infrastructure hardening — no PART A row), plus a review-fix round (`fix13-a-listing`,
`fix13-b-resolver`, `fix13-c-table-qr`, `fix13-d-audit-a`, `fix13-e-audit-b`,
`fix13-f-locales`, `fix13-g-hostname-infra` and `fix13-h-test-health`, all merged), landed on
`wave13-integration` at `ed430552`. See the batch-13 re-audit paragraph above for what
verification moved and why.

**Batch 14 (post-plan, 2026-09-29).** Seven further waves, same posture — not part of the
`P`/`T`/`W` table above, no brief in **The waves** below. Recorded here only for traceability:
`w1-dine-in-ops-link` (`1.1`, `1.2`, `2.1`, `10.5b` — the table beside a dine-in order on the board, the
detail and the kitchen ticket, and a printed table card that encodes a scannable storefront address),
`w2-locales-part-3` (`10.12` — comment presets, delivery regions and zones in the brand's or tenant's own
locale set, category list reads in the brand default; carried onto `3.6` and `3.6b`),
`w3-storefront-milliy-parity` (customer-facing `frontend/storefront-milliy` and `frontend/storefront` — cart,
checkout and fee refusals naming their reason, sale windows and sold-out on the menu grid, the table-QR scan
landing and `VIEW_ONLY` table menu, return to the table after sign-in — no PART A row; its commits' citations
of `4.2g`, `4.4c`/`4.4d` and `10.5` are customer-app ports of capabilities those console rows already track),
`w4-catalog-listing-ux` (`4.1`, `4.4c` — the per-variant «not listed at N branches» banner and the stock
page's unlisted-dishes report with list-all; the listing-backfill runbook it validated has no row),
`w5-infra-ci-shards` (the Java CI job in three parallel shards behind one aggregator, and the media
object-store credential minted by `deploy.sh` — deploy and CI infrastructure with no PART A row),
`w6-adrs-identity-promotions` (ADR 0139 and 0140 drafted and Proposed: `0.1d`, `0.2c`, `9.2`, `9.2b`, `9.2c`,
`X.5`; `6.1`, `7.9`) and `w7-adrs-stops-dispatch-walkin` (ADR 0141, 0142 and 0143 drafted and Proposed: `2.5a`,
`3.8`, and walk-in self-seating, which no row names), plus a review-fix round (`fix14-a-dine-in`,
`fix14-b-locales`, `fix14-c-milliy`, `fix14-d-listing-ux`, `fix14-e-ci-infra`, `fix14-f-adrs-a` and
`fix14-g-adrs-b`, all merged), landed on `wave14-integration` at `ed9bf9d0`. See the batch-14 re-audit
paragraph above for what verification moved and why.

**Batch 15 (post-plan, 2026-09-29).** Seven further waves, same posture — not part of the `P`/`T`/`W` table
above, no brief in **The waves** below. Recorded here only for traceability: `w1-ordering-money-audit` (`1.2c`,
`1.3e`, `9.3a`, `X.39` — amendments keep the order's promo-code discount, audit facts for the acceptance policy
and outcome reasons, the tenant's late colour and at-risk minutes; `2.1d` was named and is not a repricing
command), `w2-locale-consumption` (`10.12`, carried to `2.1b`; its regions-and-zones item changed nothing, so
`3.6` and `3.6b` did not move), `w3-locale-remaining-forms` (`10.12`, `X.2` terms of service, `10.6`, `4.1`),
`w4-milliy-dine-in-order` (customer-facing `frontend/storefront-milliy` — ordering at the table and the sign-in
route — no PART A row; its citations of `10.5` and `10.5b` are customer-app ports, and the defect this audit
found in it is recorded on `10.5`), `w5-readiness-and-scope-fixes` (`10.0` and the Telegram staff-link card,
`X.1` of §9; the Makefile `MVN` default and the listing-backfill runbook record have no row),
`w6-dine-in-operator-flows` (`1.3`, `1.1`, `1.2`, `2.1`, `1.5a`, `10.2d`, `X.36`, `10.5b`, and the branch-scoped
`TableSessionController`) and `w7-ci-frontend-hygiene` (CI and operations lint and format — no PART A row), plus
a review-fix round (`fix15-a-money-audit`, `fix15-b-locale-consumption`, `fix15-c-locale-forms`,
`fix15-d-milliy-dine-in`, `fix15-e-readiness-scope`, `fix15-f-dine-in-ops` and `fix15-g-ci-hygiene`, all
merged), landed on `wave15-integration` at `b1b39b93`. See the batch-15 re-audit paragraph above for what
verification moved and why.

**Batch 16 (post-plan, 2026-10-01).** Eight further waves, same posture — not part of the `P`/`T`/`W` table
above, no brief in **The waves** below. Recorded here only for traceability: `w1-order-board-completion` (`1.1`,
`1.1c`, `1.1e` — the brand-scoped board behind «Все филиалы», the aggregator-binding filter and the four secondary
toggles, `ISSUE_INVOICE` emitted and the re-issue audited), `w2-lateness-policy-editor` (`X.39`, `10.3b` — an editor
for the `ordering.lateness` document at tenant, brand and location), `w3-readiness-fiscal-backfill` (`10.0`,
`10.7c` — channel fulfilment-mode and location service-binding readiness checks with per-item subject links, and the
bulk ИКПУ and package-code backfill), `w4-locales-tail` (`10.12`, carried to `2.1b` — the Форматы card and
regional-format-aware formatters, preset chips on the labels map, the locale fallback behind the stop list, New
order's item search and the bulk price change), `w5-milliy-frontend-hygiene` (`X.25` — the automations preview runs
`q-rule-simulator`; customer-facing `frontend/storefront-milliy` ordering dishes with required option groups at a
table, and ESLint and Prettier for control-plane, storefront and storefront-milliy — no PART A row of their own),
`w6-billing-self-service` (`8.6` — a tenant ends a module it bought itself, ADR 0127), `w7-console-budgets-hygiene`
(component-style and initial-bundle budgets, the storefront's lazy routes, control-plane's on-demand catalogues and
the dead-key finder — no PART A row) and `w8-declines-and-docs` (`1.2d`, `1.6a`, `7.7c`, `10.10c`, `3.4c`, `7.3b`
and the specs, IA rows and ADR notes that state them), plus a review-fix round (`fix16-a-board`,
`fix16-b-lateness`, `fix16-c-readiness-fiscal`, `fix16-d-locales-tail`, `fix16-e-milliy-lint`, `fix16-f-billing`,
`fix16-g-budgets` and `fix16-h-docs`, all merged), landed on `wave16-integration` at `08481701`. See the batch-16
re-audit paragraph above for what verification moved and why.

**Batch 17 (post-plan, 2026-10-01).** Nine further waves, same posture — not part of the `P`/`T`/`W` table above, no
brief in **The waves** below. Recorded here only for traceability: `w1-combos-modifiers` (`4.2a`, `4.2b` — ADR 0136:
combo groups, per-component prices, hidden order-type charges, per-product group overrides and one nested level, sold
from New order and both storefronts and read on the order, the kitchen and the till), `w2-physical-attributes` (`4.2c`
— ADR 0137: weight, catchweight, splittable portions, КБЖУ, decimal line quantities and weighing at the pass),
`w3-marketplace-preview` (`4.6a`, `X.28`, with `4.2f`, `4.4a`, `4.4b` and `4.6` as consumers — ADR 0138: the channel
preview and the per-channel photo layer), `w4-staff-identity` (`0.1`, `0.1d`, `0.2c`, `7.5`, `9.2`, `9.2b`, `9.2c`,
`9.2d` and `X.5` of §9 — ADR 0139: the staff member record), `w5-promotions-engine` (`6.1`, `7.9`, `X.25`, `9.4` — ADR
0140), `w6-stop-scope` (`2.5a` — ADR 0141), `w7-dispatch-rules` (`3.8`, with `10.3b` — ADR 0142),
`w8-walk-in-sessions` (walk-in self-seating, which no row names, carried onto `1.5a`, `10.2d`, `10.5` and `10.5b` —
ADR 0143) and `w9-decision-adrs` (ADR 0145 to 0151 drafted and Proposed: `X.4`, `1.3b`, `3.2`, `3.6`, `3.6c`, `5.2c`,
`7.10`, `7.10a` and `10.2b` on the map provider; `6.4a` and `6.4b` on the SMS gateway; `3.7` on road distance; `X.38`
on staff MFA; `10.12` and `X.40` on a fourth language; `X.39` and `10.3b` on «order is late after»; `2.4` on a
wall-display device class), plus a review-fix round (`fix17-a-combos`, `fix17-b-attributes`, `fix17-c-preview`,
`fix17-d-identity`, `fix17-e-promotions`, `fix17-f-stops`, `fix17-g-dispatch`, `fix17-h-walkin` and
`fix17-i-decision-adrs`, all merged), landed on `wave17-integration` at `1aaaeafe`. See the batch-17 re-audit
paragraph above for what verification moved and why.

**Batch 18 (post-plan, 2026-10-03).** Nine further waves, same posture — not part of the `P`/`T`/`W` table above, no
brief in **The waves** below. Recorded here only for traceability: `w1-order-entry` (`1.3e`, `1.3`, `10.2d`, `1.1` — the
server's own price and the cash tendered on New order, closing a seated party from New order and the floor plan, and the
brand-wide order board's own stream), `w2-audit-approvals-privacy` (`9.3a`, `9.4` — configuration authoring audited in the
service's own transaction, the Settings → Approvals card, the classification scan descending into lists),
`w3-promotions-completion` (`6.1`, `7.9`, `1.2c` — the gift offered, the main storefront's code entry, the scheduled-order
re-quote as evidence, the `promo.*` counters, «who redeemed it»), `w4-combos-completion` (`4.2a`, `4.2b`, with `1.2c` — the
add-lines combo picker, the nested chooser on four surfaces, combo reporting, the publication as the source the cart and
quote read), `w5-attributes-fiscal-tab` (`4.2c`, `10.7c`, with `1.2c` — decimal amendments, per-quantum price wording, the
fiscal backfill tab's modifier options, ИКПУ picker, VAT defaults and fiscal responsibilities),
`w6-settings-readiness-shell` (`10.0`, `X.1` of §10, `X.1` of §X, `10.10c` — three more readiness checks and an expiring
tier, per-level trace provenance, a brand picker, a keyboard layer and its cheat-sheet), `w7-courier-app-deliveries`
(`3.9`, `3.4c` — the courier surface that enforces the four courier-policy switches, `CASH_VARIANCE` in the rule form),
`w8-marketplace-stops` (`2.5a` — ADR 0141's partner pull, markers, events and stale alert, the read-switch decommission and
the explainer) and `w9-frontend-hygiene` (no PART A row: the operations message catalogue split into 17 areas with only
`core` in the initial bundle, whole-tree prettier for both storefronts and CI on the plain `format:check`, two storefront
stylesheets under the component-style budget, every `*IntegrationTest` class in a CI shard), plus a review-fix round
(`fix18-a-order-entry`, `fix18-b-audit`, `fix18-c-promotions`, `fix18-d-combos`, `fix18-e-attributes-fiscal`,
`fix18-f-settings-shell`, `fix18-g-courier-app`, `fix18-h-marketplace` and `fix18-i-hygiene`, all merged), landed on
`wave18-integration` at `f313ec8c`. See the batch-18 re-audit paragraph above for what verification moved and why.

## The waves

### P01 · Console primitives: overlays, feedback, status
**Rows** `X.8` ActionMenu/Drawer/Modal/ConfirmDialog (M) · `X.30` SplitPane (S) · `X.17` Toast + InlineAlert (M) · `X.16` LockedState/DeniedState/EmptyState (S) · `X.15` StatusPill with a lateness overlay (S) — **1d**, after nothing.

Create `frontend/operations/src/app/shared/ui/` — it does not exist; the app has only `core/`, `features/` and `shell/`. This wave is the unblocker for eleven later waves, so its job is the components, not the migration of all their call sites.

Build, each with its own `.spec.ts`: `q-modal` / `q-drawer` / `q-confirm-dialog` with Escape-to-close, a focus trap and focus restore (the whole app has exactly one keydown handler today, `shell.ts:93`); `q-action-menu` with `role="menu"`/`menuitem`, `aria-haspopup`, arrow-key roving tabindex and outside-click close; `q-split-pane` with a drag handle and a width remembered in `localStorage` per section key; `q-toast` plus a toast host mounted once in `shell.html`; `q-inline-alert` with severity, optional dismissal and the correct `role="status"`/`role="alert"` choice; `q-empty-state`, `q-denied-state` (with the capability name and where to ask for it) and `q-locked-state` (plan lock, with a slot for a buy CTA); `q-status-pill` taking a status *and* an independent lateness overlay, plus the dual-state order+cooking variant.

Done means: two call sites migrated per component and no more — `order-reason-dialog` and `create-customer-dialog` for the dialog set, `orders-page` and `inbox-page` for SplitPane, the order queue's `.status-badge` for StatusPill. Fifteen files carry `aria-modal="true"` and ~65 templates carry a hand-written `denied` branch; migrating them is each owning wave's job, not this one's.

**Backend** none. **ADR** update ADR 0035's status line — it currently says «No shared Angular component library exists» and lists these components as «Required throughout» with an open task to author them.

**Capabilities** none — no endpoint is added. `q-denied-state` takes a capability *name* as a string input; it never resolves one.

**Tests** a `.spec.ts` per component and no shared fixture: Escape closes and focus returns to the invoker on all three dialog types; the action menu's roving tabindex wraps and outside-click closes; SplitPane restores its width from `localStorage` under a section key and survives a corrupt value; InlineAlert picks `role="alert"` for error and `role="status"` otherwise; StatusPill renders a lateness overlay **on a non-late status** without changing the status word. Plus one migration test per component proving the two migrated call sites still render.

**Traps** `tokens.css` is generated, not authored: do not add tokens locally, and do not introduce raw px. Do not rename existing per-feature CSS classes in this wave — a later wave will. `q-empty-state` does not exist either, despite the IA's framing of `X.16` as «distinct from EmptyState».

### P02 · Console primitives: value, time and text inputs
**Rows** `X.10` Money/Percent/MoneyOrPercent (S) · `X.11` DateRangePicker/TimeInput/DayOfWeekToggle/ScheduleGrid (L) · `X.29` NumberStepper (S) · `X.32` ColorInput (S) · `X.9` Combobox (M) · `X/X.5` LocalizedFieldGroup (M) — **2d**, after `P01`.

Six input primitives into `shared/ui/`, each with a spec. `q-money-input`: NBSP thousands grouping while typing, som suffix, paste tolerance for `125 000`, rejection of a decimal point (UZS has no minor unit), emitting an integer `…Minor`. `q-percent-input` owns the percent→basis-points conversion currently inlined at `promo-codes-page.ts:195`. `q-money-or-percent` renders the choice `TariffDiscount.Kind` already enforces. `q-number-stepper` with min/max display. `q-color-input`. `q-date-range-picker` with presets *and* a custom range plus arrow-key stepping; `q-time-input`; `q-day-of-week-toggle`; `q-schedule-grid` binding to the `{dayOfWeek, opensAt, closesAt}` + dated-exception shape `LocationServiceOperationsController`'s `ServiceSummaryResponse` already returns. `q-combobox`: async search, debounce, create-on-miss, multi-select chips, full listbox ARIA. `q-localized-field-group`: locale tabs, a default-language marker and a completeness indicator, over `{ru, uz-Latn, en}`.

Done means the components exist and exactly one call site each is migrated: the tariff form for money, `capacity-page` for the weekday/time set, `product-editor-page`'s locale strip for LocalizedFieldGroup, `customers-page` search for Combobox.

**Backend** none. **Traps** 249 raw `font-size` declarations already violate the closed type scale and there is no ESLint config in `frontend/operations` — add one in this wave with a rule banning raw `px` font sizes, or the drift wins. The catalog wire default locale is `uz` while the console default is `ru`; the default marker must read the entity's own default, not the UI locale. `delivery-zones` writes one name into all three locale fields — do not codify that.

**Capabilities** none.

**Tests** specs for: NBSP grouping while typing and a paste of `125 000`; rejection of a decimal separator and emission of an integer `…Minor`; a percent→basis-points round trip against `promo-codes-page`'s current inline arithmetic; the schedule grid binding to a dated exception as well as a weekly rule; combobox create-on-miss and multi-select chip removal by keyboard; and a LocalizedFieldGroup whose default marker follows the **entity's** default (`uz`) while the UI locale is `ru`. The new ESLint rule gets a fixture that fails on a raw `px` font size.

### P03 · Console primitives: DataGrid, DataTable extensions, MatrixGrid
**Rows** `X.6` DataGrid (L) · `X.18` DataTable extensions (L) · `X.7` MatrixGrid (M) — **2d**, after `P01` and `P04`.

Three table primitives. `q-data-grid`: inline cell edit, fill-down, cell-to-cell keyboard navigation, virtualization (**this wave adds `@angular/cdk` and owns the `package.json` and lockfile change** — it is absent today, so `cdk-virtual-scroll` is not available; `P18` consumes the same dependency and is sequenced behind this wave for that reason alone), and a batched save that reports per-row outcomes. `q-data-table` extensions on top of the hand-written `<table class="table">` pattern: persisted per-tab filters in `localStorage`, saved views, a column chooser, cursor paging *and* infinite scroll, a selection model with a bulk-action bar, and a row action menu using `P01`'s ActionMenu. `q-matrix-grid`: an editable cross-tab with row and column bulk toggles, shift range-select, and a hatched non-clickable «unavailable» cell state.

Done means the three components plus one migrated call site each: `stop-list-page` (the only screen with selection today) for the bulk bar, `products-page` for cursor paging, `sales-channels-page` for MatrixGrid. The bulk-write contract every grid should follow already exists and is proven: `POST .../orders/bulk-actions` (`OperationsOrderController.java:736`) with an `Idempotency-Key`, a `bulkOperationId`, a 200-item cap, per-item statuses and a 202. Model the grid's save on it.

**Backend** none of its own — it consumes `P04`'s cursor paging.

**Capabilities** none of its own; the grid's batched save inherits whatever capability its host screen's endpoint requires.

**Tests** specs for inline edit + fill-down + cell keyboard navigation; a batched save that reports a per-row failure without losing the other rows' successes; persisted per-tab filters surviving a reload; the column chooser; cursor paging **and** infinite scroll over the same source; MatrixGrid shift range-select and the hatched unavailable cell refusing a click. Add a regression spec for `menus-page` iterating a `Page<T>` envelope — the bug this wave fixes has no test today.

**Traps** tab counts computed over `items()` are wrong until the operator has paged to the end (`stop-list-page.ts:136-150`); the component must count server-side or refuse to show a count. `menus-page` fetches a `Page<T>` envelope with the non-paged `api.get` and iterates it as an array — a latent runtime break every spec misses because the API is stubbed wholesale; fix it here.

### P04 · Order list read model and query surface
**Rows** `1.1` Order board columns (L) · `1.1d` search across per-provider external IDs (M) — **1d**, after nothing. Backend only.

`OperationsOrderController.list` (`:121`) accepts `status` and `limit` and nothing else, and `OrderSummaryResponse` (`:1340-1366`) carries eleven fields. Widen both.

Add to the response, from data `JdbcOrderStore.OrderRow` already hydrates: `promisedAt` (and the promise basis — `SELECT_ORDER` at `:1323` already selects it and `OrderSummaryResponse.of` drops it), `paymentStatusProjection`, `customerAccountId`, `guestReferenceHash`, `feeMinor`, `discountMinor`, `createdByActorType/Id`, `acceptedByActorType/Id`, and a boolean derived from `ordering.order_process_states` for `MANUAL_ACTION_REQUIRED`/`FAILED_RETRYABLE`. Add to the query: `from`/`to`, cursor paging through the existing `web/api/Page`+`Cursor` primitives, `channelCode`, `fulfillmentMode`, `courierId`, `paymentMethodCode`, `createdByActorId`, and a `reference` search that normalises and joins `ordering.order_external_references` (V0038) — written today by `JdbcMarketplaceOrderIntake:221` and read only by the control-plane global lookup. Public order number and customer phone hash must match the same parameter.

Done means: the board can be narrowed and paged server-side, `77-A-3391` finds its order, and `GET .../orders/counts` grows the same `from`/`to` so the counters stop being lifetime totals (see `P15`, which needs the same parameter).

**ADR** none new; record the widening in orders.md §11.

**Capabilities** `ORDER_READ` at `LOCATION` scope throughout; the reference search must not widen scope.

**Tests** Java integration tests against the migrated schema for each new filter, for cursor stability under concurrent inserts, for the reference search finding an aggregator id and refusing a cross-tenant one, and for `orders/counts` honouring the same `from`/`to`. Assert that customer name and phone are absent from `OrderSummaryResponse` — that is the invariant a later wave will be tempted to break.

**Traps** customer name and phone stay off this response — they are ADR 0029 PERSONAL and belong behind the audited reveal.

### P05 · Server-driven actions: actions[] as a capability array
**Rows** `1.2a` actions[] as a capability array (S) · `1.1e` row action menu (S) — **1d**, after `P01` and `P04`.

The gap map graded `1.2a` PARTIAL and then wrote «nothing is missing» in its own cell; the cell was wrong and the grade was right. `actions[]` is a status+mode array pretending to be a capability array. `OrderActionsPolicy.availableFor(status, mode)` takes no principal and consults no grant, so `LOCATION_STAFF` — which holds `ORDER_READ`/`APPROVE`/`ADVANCE` but not `ORDER_CANCEL` — is offered «Отменить» on every open order and 403s at `OperationsOrderController:472`. `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER` and `COURIER_DISPATCHER` get the same false offer. Make the policy principal-aware: `availableFor(status, mode, grantedCapabilities)` keyed off the same `Capability` constants the mutating endpoints require, applied on both the list and the detail read. Add a test asserting a `LOCATION_STAFF` read yields no `CANCEL`.

Then widen `OrderActionCode` past its closed four. `OperationsOrderController` already serves completion (`:548`), amendments (`:594`), amendment confirmation (`:656`) and bulk actions (`:736`) with no action code to reach them; add `COMPLETE`, `AMEND`, `ASSIGN_COURIER`, `ISSUE_INVOICE`, `RESOLVE` and mark terminal orders as carrying a read-only menu rather than no menu (`order-queue.html:151` hides the trigger when the array is empty).

Backward transitions (`1.1h`) were split out into `P41`: they are an XL row, they need an ADR 0019 amendment, and they would have taken this wave to ten size-points in a day. `P41` runs straight after this one and inherits the principal-aware policy built here.

**ADR** none. Record the widened `OrderActionCode` set in `orders.md` §11.

**Capabilities** the policy is keyed off the same `Capability` constants the mutating endpoints already require — `ORDER_APPROVE`, `ORDER_ADVANCE`, `ORDER_CANCEL`, `ORDER_BULK_ACTION` — read from the principal's granted set. No new capability.

**Tests** extend `OrderActionsPolicyTests` per role: a `LOCATION_STAFF` read yields no `CANCEL`; `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER` and `COURIER_DISPATCHER` each get exactly the set their grants justify. A Java test that every widened `OrderActionCode` resolves to an endpoint that accepts it, so the array cannot offer an action with no route. An Angular spec that a terminal order renders a read-only menu rather than none.

### P41 · Backward status transitions and the override policy
**Rows** `1.1h` backward status transitions (XL) — **2d**, after `P05`.

The split-out ADR half of `P05`, and an XL row gets its own wave: an order advanced to READY
by mistake has no exit, because cancel is also refused past CONFIRMED (`1.2k`) and
`OrderStateMachine` declares no edge that goes back.

Decide in **ADR 0019** and `orders.md` §11 whether compensating transitions exist at all —
the answer this plan assumes is yes, narrowly — and then declare the edges in
`OrderStateMachine` as **compensating** rather than as literal reversals. A literal reversal
is what produces two nested state machines and an order whose history cannot be read; a
compensating edge is a new forward step that happens to restore an earlier status, and it
carries its own reason and its own timeline entry.

Add a state-actions branch gated on `Capability.ORDER_STATE_OVERRIDE` — already registered,
granted to nobody by default — with a **mandatory registry reason** drawn from
`ordering.order_outcome_reasons` rather than free text, and a timeline row naming the actor.
`P05` has already made `actions[]` principal-aware, so the override action appears only for a
principal that holds the capability; do not re-open that policy here.

**ADR** amend ADR 0019 (the decision, the edge list and the reason requirement) and tick the
orders.md §11 line.

**Capabilities** `Capability.ORDER_STATE_OVERRIDE`, already registered and granted to nobody by default; the reason is mandatory and the registry entry is the audit key.

**Tests** a Java test that an override transition writes **both** the audit fact and the timeline row and refuses without a registry reason; a state-machine test that each compensating edge is declared compensating and not as a literal reversal; and a test that a principal without `ORDER_STATE_OVERRIDE` is refused at the endpoint, not merely hidden in `actions[]`.

**Traps** do not model this as an `UNDO`. Every compensating edge is a named transition with
its own audit fact; a generic undo is what makes the fiscal and POS consequences of `1.2c`
unanswerable later. Terminal orders stay terminal — a cancelled order is not reopened by this
wave, and saying so in the ADR is part of the work.

### P06 · Lateness policy and the SLA colour ramp
**Rows** `1.1g` late-order highlight (XL) · `X.39` semantic status colour ramp (M) — **2d**, after `P05`.

Lateness is invented client-side, twice, with different numbers. `order-severity.ts` hard-codes `APPROVAL_DEADLINE_THRESHOLD_MS = 2min` and `NO_PROMISE_FALLBACK_MS = 45min`; `kitchen-ticket.ts:83` hard-codes `AT_RISK_THRESHOLD_MS = 5min`. The order board's amber tier is unreachable dead code because nothing gives it a promise, while the kitchen board paints amber freely. `ordering.lateness` does not exist server-side at all — it is spec prose in orders.md:389-392 and a React prototype constant.

Register an `ordering.lateness` policy document in `OrderingConfigurationKeys` (which registers exactly one key today) with `at_risk_before_seconds`, `late_after_seconds` and `no_promise_fallback_seconds` per fulfilment mode, resolved through ADR 0030's existing resolver, and serve it to the console. `OrderPromise.lateAt(Instant, OrderStatus)` (`domain/OrderPromise.java:177`) already implements the LATE predicate including «terminal orders are never late» and has no production caller — use it rather than writing a second one.

This wave **owns the lateness constants wherever they live** — `order-severity.ts` and `kitchen-ticket.ts:83` — which is why `P16`, `P11` and `P17` are all sequenced behind it, and why it sits inside the serialised order-board family (`P01` → `P05` → **`P06`** → `P07` → `P08`). `order-severity.ts` is imported by six production files; deleting its constants reaches `order-detail-pane.ts`, `order-tabs.ts`, `order-counts.ts`, `order-money.ts` and `kitchen-ticket.ts`, and no other wave may be running in that family while it does.

Frontend: delete both constant pairs, read the resolved policy, implement AT_RISK and LATE from `promisedAt` (arriving in `P04`) and BLOCKED from the process-state flag, and add a named SLA ramp to the design tokens instead of borrowing `--q-warning`/`--q-error`. The tenant-chosen late colour stays refused, per orders.md §2.7.

**Capabilities** `ORDER_READ` to read the resolved policy; authoring `ordering.lateness` is `TENANT_CONFIGURATION_WRITE` and lands with `P31`, so this wave ships the key and its defaults, not its editor.

**Tests** a Java test that the resolved `ordering.lateness` document falls back per fulfilment mode and resolves through ADR 0030's precedence; a test pinning `OrderPromise.lateAt` to its «terminal orders are never late» rule now that it has a production caller. Angular specs that the board and the kitchen ticket compute the same AT_RISK boundary from the same policy, which is the whole point of the row, and that BLOCKED comes from the process-state flag and not from a clock.

**Traps** `tokens.css` is generated — the ramp has to be added upstream in `frontend/design-tokens`, not patched in the app. Do not confuse this with `sla_bucket_set.v1`: those are ADR 0043 reporting histogram buckets, deliberately platform-fixed, and they do not serve this row.

### P07 · Order board: filters, search, selection and bulk
**Rows** `1.1c` filters persisted per tab (L) · `1.1f` bulk selection and courier assignment (M) — **1d**, after `P03` and `P06`. Frontend only.

Build the toolbar `order-queue.html` has never had: period, branch, aggregator, source, delivery type, courier, payment type and «мои заказы», plus the external-ID search box, all bound to `P04`'s query parameters and persisted per tab through `P03`'s DataTable extensions. The legacy dashboard persisted per-status filters in `localStorage` and merchants will notice the regression. §2.4 also requires the queue and the order reports to share one filter component. **This wave owns that extraction and the decision is made, not offered**: lift `features/reports/reports-filter-bar.*` into `shared/ui/` as `q-filter-bar` with a typed control set, leave a thin `reports-filter-bar` wrapper in place so the reports screens keep compiling, and build the order toolbar on the extracted component. `P27` is sequenced after this wave and re-points Reports at `q-filter-bar`, deleting the wrapper; do not let both waves extract it.

Then selection: a checkbox column, a bulk-action bar, and `POST .../orders/bulk-actions` — built, idempotent, capability-gated on `ORDER_BULK_ACTION`, 200 orders per call, per-item outcomes, 202 — which has no path constant in `operations-paths.ts` and no caller. Render §2.10's result panel and «Повторить проблемные» from the per-item outcome list the endpoint already returns.

Done means: an operator can narrow the board to a shift and a channel, the filter survives navigation and reload, and a peak can be advanced or cancelled in one gesture. **Bulk courier assignment is explicitly out of scope** — `BulkActionType` is `ADVANCE|CANCEL` only and ADR 0039 defers the courier case; say so in the UI rather than offering it.

**Capabilities** `ORDER_READ` for the board; `ORDER_BULK_ACTION` for the bulk bar, which the endpoint already enforces — render the bar only when the session context carries it.

**Tests** Angular specs for each filter mapping to its `P04` query parameter, for the filter surviving navigation and reload per tab, for the selection model across a page boundary, and for the bulk result panel rendering per-item outcomes including a partial failure. A spec asserting the UI does **not** offer bulk courier assignment.

**Traps** `order-queue.ts`'s class doc (~:96-104) claims there are no row actions, no bulk actions, no filters and no column picker; it is already wrong about row actions. Rewrite it, do not extend it. `attention` is counted client-side over a 200-row page and undercounts above that — with server paging it must be counted server-side or shown as «200+».

### P08 · Realtime push and the shell's own counters
**Rows** `1.1b` live per-status counts (M) · `0.1f` liveness and the COUNTERS stream (M) · `X/X.1` operator console shell (M) · `X.34` LiveBadge/Stale/ConnectionState (M) — **2d**, after `P07`.

The pipe is built and both faucets are shut. `OperationsStreamController:104` serves `text/event-stream` with heartbeats, `Last-Event-Id` resync and a 500-stream cap; `StreamChannel` declares `ORDER_QUEUE`, `ORDER_DETAIL`, `DISPATCH_BOARD`, `STOP_LIST`, `COUNTERS`. Nothing in `ordering` or `fulfillment` ever references `RealtimeSignalPublisher`, and `SseStreamRegistry:369` resolves snapshot channels through `SnapshotSource`, whose only implementation is `CourierPositionSnapshotSource`. So a correctly subscribed client receives keep-alives forever.

Backend: publish a signal on `ORDER_QUEUE`/`ORDER_DETAIL` from the order state transitions, implement a `SnapshotSource` for `COUNTERS` returning the same aggregate as `OrderCountsResponse`, and add a `DISPATCH_BOARD` producer in fulfillment. Version and classification-check the snapshot contract.

Frontend: one `EventSource` client in `core/` with `Last-Event-Id` resync, jittered reconnect and a degrade-to-poll fallback keeping the existing 10s interval; the `X.34` indicator set (LiveBadge, RefreshIndicator, StaleIndicator with an age threshold, ConnectionState banner) in `shared/ui/`; and `shell/service-status.ts` — three signals (`open`, `late`, `updated`), all three written only by `order-queue.ts:217` — given a fetch of its own against `GET .../orders/counts` so the rail's open/late badges stop reading zero until Orders is opened and then freezing. Render `updatedAt` in the shell — the signal exists and is displayed nowhere.

**Capabilities** `ORDER_READ` for `ORDER_QUEUE`/`ORDER_DETAIL`/`COUNTERS`; `DELIVERY_PLAN_READ` for `DISPATCH_BOARD`. The stream must re-check the capability per channel on subscribe, not once at connect.

**Tests** Java tests that an order transition publishes on `ORDER_QUEUE` and `ORDER_DETAIL`, that the `COUNTERS` snapshot equals `OrderCountsResponse` for the same scope, and that a subscriber without the channel's capability is refused. Angular specs for `Last-Event-Id` resync after a dropped connection, for the jittered reconnect, for the degrade-to-poll fallback, and for `service-status.ts` fetching its own counts on shell init rather than waiting for Orders.

**Traps** eleven screens each hand-roll the same visibility-gated 10s poll; extract one. There is no KDS stream channel — a live kitchen board is not in scope here. Delete the «until ADR 0045 live updates exist» comments in `order-queue.ts:56` and `today-page.ts` as part of the wave.

### P09 · Order detail: the record and its outcomes
**Rows** `1.2` order detail (M) · `1.2o` change-due (S) · `1.2p` revision chain (S) · `1.2m` handover-code state (M) · `1.2j` complete with reason (M) · `1.2k` cancel with reason and write-off (M) — **2d**, after `P05`. Mostly frontend.

The detail pane drops 13 of ~19 top-level response fields. Render them: the whole `OutcomeResponse` (kind, system category, reason, stock disposition, liability party, customer refund, occurred-at), `kitchenNote`, `callbackRequested`/`callbackResolvedAt`, `cashTenderedExpectedMinor` and the server-computed `changeDueMinor`, `createdBy`/`acceptedBy`/`acceptedAt`, `currentRevision`, `warnings`, `acceptanceMode`. Add a revisions view over `GET .../{orderId}/revisions` (`:310`, built over V0029, test-covered, no path constant in `operations-paths.ts`). Add a handover panel over `POST .../marketplace/orders/{orderId}/handover-verifications` and `/handover-bypasses` — both built, attempt-consuming, expected value never returned — plus the read projection for challenge state, which is the one backend addition here.

Outcomes: call `POST .../{orderId}/completion` (`:548`) instead of a generic advance, offering the COMPLETION reasons for the order's fulfilment mode from the tenant registry. Today every console completion books `DELIVERED_OWN_COURIER` for delivery, so an order handed to an external service is recorded as the branch's own courier — actively wrong, not merely blind. For cancellation, post `reasonId` from `ordering.order_outcome_reasons` instead of free text; `P05` supplies the reason-required CANCEL action that makes the dialog reachable past CONFIRMED.

**Capabilities** `ORDER_READ` for the record and the revisions; `ORDER_COMPLETE` and `ORDER_CANCEL` for the two outcome dialogs; `MARKETPLACE_HANDOVER_VERIFY` for the handover panel.

**Tests** Angular specs for each newly rendered band and for both outcome dialogs, including a completion that books the fulfilment mode's **own** reason rather than `DELIVERED_OWN_COURIER`. A Java test of the HTTP `/completion` path, which has none today, and one of the challenge-state read projection.

**Traps** the timeline prints a raw `reasonCode` and no actor although `actorType` is on the wire. `order-reason-dialog.ts`'s comment claiming the reason registry «does not exist yet» is stale.

### P10 · Operator notes and the first amendment client
**Rows** `1.2h` the three comment channels (L) — **1d**, after `P09`. Frontend-led.

There is no amendment client in the console at all: `operations-paths.ts`'s only `/amendments` builder is `reservationAmendments` (ADR 0047 table bookings), and nothing under `features/orders/` posts to `/orders/{orderId}/amendments`. Build it: an `orderAmendments` + `orderAmendmentConfirmation` path pair, an `OrderAmendmentsApi` with `If-Match` and `Idempotency-Key`, and the §3.6 «Комментарии» block on the detail pane keeping the customer's words separate from ours.

Wire the three commands the server actually accepts — `SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED`, `SET_CASH_TENDERED` — as the first three uses of that client. That closes the operator→kitchen channel (the note arrives on the payload and is never rendered, and the kitchen board shows it), the callback flag, and the «Сдача с» entry `1.2o` needs, including the acknowledgeable `CASH_TENDERED_INSUFFICIENT` notice when a later amendment pushes the total above the tendered amount. Add the amendment history view over `GET .../amendments` (`:713`).

Operator→courier and internal operator→operator notes have **no owning decision at all** (orders.md §11 calls adding two commands «a one-line ADR amendment»). Make that amendment in this wave: add the two command types to ADR 0039 and to `AmendmentCommandType` as non-financial and therefore `built=true`, with the same shape as `SET_KITCHEN_NOTE`.

**Capabilities** the amendment endpoints already gate correctly — `ORDER_AMEND` for the commands and the confirmation; the two new non-financial command types inherit it.

**Tests** an Angular spec per wired command, one for the `CASH_TENDERED_INSUFFICIENT` notice appearing when a later amendment raises the total, and one asserting the UI never renders the seven financial commands. Java tests for the two new non-financial command types, including that they are accepted with `If-Match` and refused without it.

**Traps** the seven financial commands are `built=false` and are refused by name at `OrderAmendmentService:497-510` — do not let the UI offer them; they are deferred (`1.2c`). The §3.11 POS-export interlock must hide amend affordances while an export is unacknowledged, which lands with `P12`.

### P11 · The order-to-fulfilment seam
**Rows** `1.2e` assign courier from the order (L) · `1.2n` both delivery money fields (M) · `1.2b` status timeline with per-stage clocks (L) · `2.1a` courier ETA on the kitchen ticket (L) — **2d**, after `P09` and `P16`.

Four rows, one missing idea: nothing maps an order id to its production and delivery facts. `kitchen.ticket_events` is written and `JdbcKitchenStore.eventsOf` reads it — with no production caller, only tests. `KitchenBoardController` is location+status scoped, so a finished order's ticket falls off the board entirely. `fulfillment.shipments` holds `assigned_at`/`picked_up_at`/`delivered_at` (V0054:135-137) and `DispatchController.ShipmentView` serialises none of them; `/dispatch/queue` excludes COMPLETED and CANCELLED plans.

Add an order-keyed read: `GET .../orders/{orderId}/delivery` returning plan id and version, current shipment, courier, `estimated_ready_at`, `promised_delivery_start/end`, `customer_delivery_fee_minor` and the provider-billed figure from `fulfillment.delivery_cost_subsidies`; and an order-keyed kitchen-events read. Then: the assign/unassign control on the detail pane against the existing `ManualDispatchService` (`DispatchController:100/:121`, `DELIVERY_MANUAL_ASSIGN`); the two Доставка rows plus the margin line in the Money panel; the three real timeline lanes with elapsed durations between stages, filled/hollow/muted marks, the actor resolved where possible and the losing `ordering.approval_decisions` shown; and the courier ETA chip on the kitchen ticket, which needs the provider `etaMinutes` — discarded today at the Yandex/Noor adapter boundary — persisted into the domain and joined onto `KitchenBoardController.TicketResponse`.

**ADR** ADR 0014 gains the ETA concept; tick its per-order tracking line.

**Migration** yes — reserved numbers **`V0211`–`V0213`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier ETA persisted into the domain and joined onto the kitchen ticket.

**Capabilities** `DELIVERY_PLAN_READ` for the order-keyed delivery read, `DELIVERY_MANUAL_ASSIGN` for assign/unassign, `ORDER_READ` for the timeline and the kitchen-events read.

**Tests** Java tests against the migrated schema for the order-keyed delivery read returning both money figures and the shipment timestamps, for the kitchen-events read of a **completed** order (the case that falls off the board today), and for the provider `etaMinutes` surviving the adapter boundary into `TicketResponse`. Angular specs for assign and unassign from the detail pane, for the three timeline lanes with elapsed durations, and for the losing approval decision being shown.

**Traps** «call an external courier» has no backend and is not in this wave — it is `P44`'s. Do not put a customer name or address on the dispatch contract; that is a deliberate `DispatchController` decision.

### P44 · Provider cancel cascade and quote-delta confirmation
**Rows** `1.2g` cascading cancel at the provider (M) · `1.2f` provider quote-delta confirmation (L) — **1d**, after `P11`.

Two rows where the adapter layer is finished and everything above it is missing, so a cancelled order can still have a courier en route with nothing visible in the console.

**Cascade.** `DeliveryCapability.CANCEL_SHIPMENT`, `DeliveryGateway.cancelShipment`, the `DeliveryProcessor` branch, Noor's `PATCH /orders/{id}/cancel`, Yandex's `claims/cancel` and its cancellation-cost probe all exist. Missing above them: a cancel method on `fulfillment.api.ShipmentBookingPort` and its Camel implementation; a fulfillment listener on `OrderCancelled` (or a call from `OrderStateService`/`OrderOutcomeService`) that finds the open plan and cascades; the ADR 0014 endpoint `POST /api/v1/operations/shipments/{shipmentId}/cancel` — named at ADR line 528 and listed as still missing at line 639 — including cancellation-cost classification and the UNCERTAIN path into `fulfillment.delivery_exceptions`; and, on the screen, a provider-cancellation outcome in the cancel dialog's response plus a delivery-exception band on the order detail.

**Quote delta.** There is no operator-invoked provider booking anywhere — `SourceType.EXTERNAL` appears nowhere in main — so there is not yet a moment at which a delta dialog could interpose. Build that moment: a «call an external courier» action on the order and on the dispatch board, a read exposing the last quote price against the customer estimate (`fulfillment.delivery_quotes` and `assignment_attempts` exist and `SourcingPlanner` runs), and an accept/abandon command on `DispatchController` so a human can refuse a price increase before the provider order commits. This is the Millenium pattern the IA names by name and the only seam at which a merchant controls its own delivery cost.

**ADR** tick ADR 0014's shipment-cancel and operator-sourcing lines.

**Capabilities** `DELIVERY_MANUAL_ASSIGN` for the operator booking and the accept/abandon commands; `Capability.SHIPMENT_CANCEL` (`shipment.cancel`) for the cancel endpoint — **it is already registered**, at `iam/api/Capability.java:362`, so name it rather than minting a second one.

**Tests** Java tests against the migrated schema for the cascade on an already-picked-up shipment, for an UNCERTAIN provider response landing in `fulfillment.delivery_exceptions`, and for accept and abandon on a re-quote. An Angular spec that the cancel dialog renders the provider-cancellation outcome and that a price increase cannot be accepted implicitly.

**Traps** no `orders.*quote*` or `delivery.*quote*` i18n key exists — this wave adds the vocabulary too.

### P12 · Money on the order: payment and fiscal panels
**Rows** `1.2l` payment panel and manual re-fiscalize (M) — **1d**, after `P11`.

Two panels on the order detail, both over backends that already answer. POS export (`1.2i`) was split into `P42`: it is an XL row with a backend of its own and it doubled this wave. Payment: `GET /api/v1/operations/tenants/{t}/orders/{orderId}/payment` and `POST .../re-presentations` (`OperationsPaymentController:62/:134`) give the tender, the attempt history and re-issue; `FiscalDocumentController:97` gives that order's fiscal documents and `:135` retries one. `FiscalApi.forOrder` already exists in `fiscal-api.ts:79` and is dead code — no component calls it, so today a FAILED document outside the blocked worklist cannot be inspected or retried from any screen. Render both panels with localized status labels, not the raw enum tokens the Finance page prints.

**ADR** none. `P42` carries the ADR 0011/0012 status lines with the POS half.

**Capabilities** `PAYMENT_READ` and `PAYMENT_REPRESENT` for the payment panel; `FISCAL_DOCUMENT_READ` and `FISCAL_DOCUMENT_RETRY` for the fiscal panel. All four already gate the endpoints this wave calls.

**Tests** Angular specs for both panels including the re-presentation and the fiscal retry paths, and one asserting the localized labels replace the raw enum tokens. A Java test that `FiscalApi.forOrder`'s endpoint returns a FAILED document that never reached the blocked worklist — the case that is unreachable from any screen today.

**Traps** this wave adds no field to `OrderDetailResponse` — `P09` owns that record (hazard #2), and both panels read their own endpoints.

### P42 · Machines on the order: POS export and its errors
**Rows** `1.2i` print to POS and its errors (XL) — **2d**, after `P12`.

The other half of the old `P12`, split out because it is an XL row with a backend of its own.

`integration.pos_order_exports` is written from the ordering side by `PosOrderExportTrigger`
on `OrderConfirmed`/`OrderAwaitingApproval`, and its only HTTP surface is control-plane
(`PosOrderExportController:59`). A merchant therefore cannot see that the order in front of
her never reached the kitchen's POS, and cannot push it again.

Add an operations-plane **sibling endpoint** — `GET .../orders/{orderId}/pos-export` — plus a
push/retry command. It is a sibling deliberately: `P09` owns `OrderDetailResponse` and nothing
else edits that record, which is hazard #2 in this plan and the reason `P11` took the same
shape. Suppress the affordance entirely when the tenant's POS adapter does not declare the
capability (ADR 0011), and add the §3.11 interlock that hides amendment affordances while an
export is unacknowledged — `P10` builds the amendment client that the interlock hides.

**ADR** ADR 0011/0012 status lines. Note that `orders.md`'s «recognised by the schema and
written by nothing» line refers to the dead `order_process_states.POS_ORDER_EXPORT` column,
not to POS export as such — correct it rather than quoting it.

**Capabilities** `pos.export.read` and `pos.export.resolve` — both already registered and already carrying console sentences at `capability-sentences.ts:679-689`, pointing at a screen that does not exist. Suppress the affordance when the tenant's POS adapter does not declare the capability (ADR 0011).

**Tests** Java tests against the migrated schema for the operations-plane export read, for push and retry, and for the endpoint refusing when the tenant's POS adapter does not declare the capability. An Angular spec for the §3.11 interlock hiding amendment affordances while an export is unacknowledged, and one for the affordance being absent entirely on an adapter without the capability.

**Traps** a failed export is not an order failure: the order is real, the kitchen copy is
missing. Say that in the UI, because the obvious wording («order failed») will send an
operator to cancel a paid order.

### P13 · New order: the screen and the basket
**Rows** `1.3` the call-centre order-entry screen (XL) · `1.3c` item search and basket (L) — **2d**, after `P02`.

The highest-value unbuilt pilot screen, and the backend is finished: `POST .../locations/{l}/orders` (`OperationsOrderController:145`, `OperatorOrderingService`, `ORDER_PLACE` at LOCATION scope, granted to `LOCATION_STAFF` and `LOCATION_MANAGER`) reuses `CartService` + `CheckoutService` end to end, so pricing, availability, promise and quote all apply unchanged. `app.routes.ts:68-73` renders `NotBuiltPage`.

Build `features/orders/new-order/` replacing the placeholder: the three-pane composer, a menu/item search using `P02`'s Combobox over the published menu, modifier-option selection, quantity via NumberStepper, a running total that reconciles against the server quote, and the submit with an `Idempotency-Key` followed by a route to the created order's detail. `F2` in the shell already points here.

Known backend constraint to respect, not fix: `OperatorOrderingService.java:94` refuses anything but cash in this release, by ADR 0039 decision. Render that as a stated limit.

One genuine backend gap to close in this wave: there is no text search over products or variants at location scope — `CatalogQueryController.products` and `CatalogAuthoringController.variantsAtLocation` take only `cursor` and `limit`. Add a `query` parameter to the location-variants read so the picker can search thousands of items rather than paging them.

**Capabilities** `ORDER_PLACE` at `LOCATION` scope, already granted to `LOCATION_STAFF` and `LOCATION_MANAGER`; `CATALOG_READ` for the picker's new `query` parameter.

**Tests** an Angular spec for search, modifier selection, quantity, total reconciliation against the server quote, and submit with an `Idempotency-Key` followed by the route to the created order. A Java test for the new `query` parameter on the location-variants read, including that it stays location-scoped.

**Traps** the console must not invent its own pricing; read the quote. Do not build the address pane here — `P14` owns it, and the map half is deferred behind `X.4`.

### P14 · New order: address, money, promo, repeat, aggregator entry
**Rows** `1.3b` address pane (XL, structured half) · `1.3e` payment/order types, promo, change-due (M) · `1.3f` repeat / re-order (M) · `1.3g` manual aggregator order (L) — **2d**, after `P13`.

The address pane's structured half is not blocked and ships now: list the resolved customer's saved addresses through the already-wired reveal (`GET .../customers/{accountId}/addresses`), send the chosen id as `DestinationRequest.customerAddressId`, and offer an inline «add address» reusing the дом/квартира/подъезд/этаж/ориентир form that already exists at `customer-detail-pane.html:277-360`, saving with `coordinateSource: NOT_GEOCODED` or `LANDMARK_ONLY`. Add the ADR 0037 zone-resolved branch selector with «по зоне» and the out-of-zone reason code. The pin, the suggest and the geocoder are deferred with `X.4`.

Money: thread `promoCode` from the place-order request through `OperatorOrderingService` into `CartService.applyPromoCode` — ADR 0072 is built and the wiring is cheap — and surface change-due at creation. The operator channel's allowed payment types are already enforced by `CheckoutEligibilityGuard:257-260`; read that matrix rather than hard-coding, and render the cash-only rule as the current state of the matrix.

Repeat: `ReorderPlanService` and `GET .../orders/{orderId}/reorder` are built but live on `StorefrontOrderingController:525` behind `@CustomerOwned`, so a staff token cannot call them at all. Add a staff-capability wrapper — `GET .../customers/{accountId}/orders/{orderId}/reorder` under `ORDER_READ` — delegating to the same service, and wire «Повторить» here and from the customer's order history (`5.2d`).

Aggregator entry: let an operator record an order as a named marketplace channel with externally-set pricing, writing the V0038 authority columns (`origin`, `pricing_authority`, `entry_mode`, `marketplace_binding_id`) that only `JdbcMarketplaceOrderIntake` writes today, so a phoned-through aggregator order does not corrupt the channel mix.

**ADR** ADR 0040 and ADR 0039 status lines. **Traps** `customer_accounts.origin` and `created_by_actor_id` still do not exist, so an operator-created account cannot be suppressed from marketing — add them here or record the gap on `1.3a`.

**Migration** yes — reserved numbers **`V0214`–`V0216`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `customer_accounts.origin` and `created_by_actor_id`.

**Capabilities** `ORDER_PLACE`; `CUSTOMER_PII_REVEAL` with a stated purpose for the saved-address list; `ORDER_READ` for the new staff reorder-plan wrapper, which must not inherit `@CustomerOwned`.

**Tests** Angular specs for choosing a saved address, for the inline add with `coordinateSource: LANDMARK_ONLY`, for the zone-resolved branch selector and its out-of-zone reason. Java tests for `promoCode` threading into `CartService.applyPromoCode`, for the staff reorder wrapper refusing a principal without `ORDER_READ`, and for an aggregator-entry order writing all four V0038 authority columns.

### P15 · Live board: period scoping, mixes and branch load
**Rows** `0.1` live board (L) · `0.1a` oversized counters (M) · `0.1b` source and type mix (M) · `0.1c` branch leaderboard (M) — **2d**, after nothing.

`JdbcOrderStore.counts` (`:582-614`) filters on tenant, brand and optional location and **nothing else** — no date, shift or business-day predicate — so «Отменено» is every cancelled, rejected and expired order the branch has ever had, and `completed` and `total` are lifetime figures too. One period parameter on `GET .../orders/counts` fixes all three; use ADR 0043's `BusinessDayService` boundary rather than UTC midnight (`reporting.business_day_start` already exists per tenant).

The two mixes are computed client-side over a `MIX_FETCH_LIMIT = 200` page and under-count silently above it, with no truncation flag on `LiveBoardSnapshot` — even though the branch band on the same screen already renders orders.md §2.11's «Показаны N из M» honesty. Either add a group-by mix aggregate to the live order store (there is no `group by` anywhere in the ordering package today) or plumb a truncation signal into the mix cards. Prefer the aggregate: it also serves the wallboard.

The branch leaderboard is real and tested; its cost is N+1 — one `orders/counts` call per active location every 10 seconds — because no endpoint returns counts for a whole brand in one read. Add a brand-scoped counts read under `LOCATION_READ`/`ORDER_READ` at BRAND scope and collapse the fan-out. The other half of `0.1c`, load shown in the branch picker at order entry, lands with `P13`.

**Done** a supervisor can read «cancelled today», the mix is either exact or visibly approximate, and a ten-branch tenant costs one request per tick.

**Capabilities** `ORDER_READ` for the period-scoped counts; the brand-scoped counts read is `ORDER_READ` at `BRAND` scope and must refuse a location-scoped principal.

**Tests** Java tests for the period predicate at a business-day boundary that crosses midnight, and for the brand-scoped counts read refusing a location-scoped principal. Angular specs for the truncation note, the period label, and the leaderboard issuing **one** request per tick rather than one per branch.

**Traps** the operator leaderboard band stays an honest locked note — it is deferred behind the staff-identity ADR. `order-status.spec.ts` asserts the in-progress set against the tab union, not against the SQL rule; do not claim it proves server alignment.

### P16 · Kitchen queue and stop list
**Rows** `2.1` kitchen queue (M) · `2.1c` dispatch from the pass (M) · `2.1e` counter sale from the kitchen (L) · `2.5` stop list (S) · `2.5b` stop source explainer (L) · `2.5c` stop-change digest (S) — **2d**, after `P03` and `P06`.

Sequenced behind `P03` because it migrates `stop-list-page`'s selection model to `q-data-table`, and behind `P06` because that wave owns the lateness constants in `kitchen-ticket.ts`. This wave owns `KitchenBoardController`'s board query and its tab counts; `P11` adds the ETA field to `TicketResponse` afterwards, and `P17` builds the device client after that.

Kitchen queue: a cook sees `line.hasNote` as a bare chip because the note text sits behind the audited `OrderRevealApi.revealLineNote`, which no kitchen file imports — so «без лука» never reaches the line. Call it, with the reveal's purpose recorded. Add the aggregator tab by typing `channelCode` against `sales_channels.system_type` instead of rendering a raw string, and make the tab counts exact rather than per-fulfilment-mode over one 200-ticket page. Then the two affordances the screen's own doc wrongly calls impossible: inject `DispatchApi` + `CouriersApi` and assign an in-house courier from a delivery ticket (`DispatchController:100/:121` is real and already client-proven on the dispatch board, joining plan to order by `orderId`), and link a counter sale to `P13`'s new-order screen from the kitchen shell.

Stop list: `applyBulk` is a sequential loop of one `PUT` per row with a failure count as the only report; add a server-side batch availability endpoint on `InventoryController` modelled on the ADR 0039 bulk contract, and make the tab counts and the ON_STOP tab server-side rather than over the 50-row page already loaded. Add a search box. Then the explainer: surface a structured stop source on the availability read model (`VariantAvailabilityResponse` carries no source, last-changed or reason), so an operator can tell a kitchen stop from a POS push — the audit facts already distinguish them (`OPERATIONS_STOP_LIST_TOGGLE` vs `POS_STOP_LIST`, actor `pos-availability-poll`) and are readable through the audit-events endpoint whose path helper already exists. Finally, coalesce the stop-change alert: `InventoryOperationsAlertTrigger` fires one message per item and never fires on return-to-sale; add a windowed digest and the back-in-stock direction.

**Migration** yes — reserved numbers **`V0217`–`V0219`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a structured stop source on the availability read model.

**Capabilities** `KITCHEN_BOARD_READ` for the queue; `CUSTOMER_PII_REVEAL` with a recorded purpose for `revealLineNote`; `INVENTORY_AVAILABILITY_MANAGE` for the batch stop/unstop; `DELIVERY_MANUAL_ASSIGN` for assigning from the pass.

**Tests** Angular specs for the revealed line note reaching the ticket, for the aggregator tab typed off `sales_channels.system_type`, for assigning a courier from the pass, and for server-side tab counts. Java tests against the migrated schema for the batch availability endpoint (idempotency, per-item outcomes, the 200-item cap), for the structured stop source distinguishing an operations toggle from a POS push, and for the digest coalescing a burst and firing on return-to-sale.

**Traps** scope (`2.5a`) and channel propagation are deferred — this wave stays location-scoped. Correct the stale comments in `kitchen-queue-page.ts:62-63` and `kitchen-api.ts:11-12` that claim per-line notes are already read.

### P17 · KDS device shell, enrolment and QR
**Rows** `X/X.2` KDS fullscreen shell (L) · `2/X.2` device enrolment and registry (M) · `X.35` QRCode display and scan input (M) — **2d**, after `P01` and `P16`. Frontend only.

ADR 0079 is done and unreachable: `iam.device_principals` and `iam.device_enrolment_requests` (V0192) exist, `PlatformRole.KITCHEN_DEVICE` exists, `KitchenDeviceController` (`:53` list, `:70` approve by user code, `:95` revoke, all `KITCHEN_STATION_MANAGE`) and `DeviceEnrolmentController`'s unauthenticated bootstrap pair are real — and no file under `frontend/` references any of them. A kitchen tablet has no application to run and no manager can accept the code it shows.

Build two things. First, a **device shell**: a top-level route sibling of the console `Shell` (the way `/login` and `/invite` are), chrome-less, with touch-scale typography and hit targets replacing `kitchen-queue-page.css`'s 6-12px paddings and 12/14px type, a device-token auth path instead of the staff Keycloak session, and an offline banner driven by `navigator.onLine` plus the online/offline events — nothing in the app reads either today. Second, a **pairing screen** under Kitchen: list enrolled devices, approve a typed user code, revoke a lost tablet with a reason. Per ADR 0079 the code is typed, not scanned.

`X.35` rides along: a `q-qr-code` display component in `shared/ui/` (no frontend package renders a QR bitmap anywhere), used here for device pairing and by `P38` for table QR cards, plus rendering the `qrPayload` the payments API already returns and the console drops (`payments-api.ts:53`).

**Capabilities** `KITCHEN_STATION_MANAGE` for list, approve and revoke on `KitchenDeviceController`; the device shell itself authenticates as `PlatformRole.KITCHEN_DEVICE` through the ADR 0079 device token, never through the staff Keycloak session.

**Tests** Angular specs for the device shell's offline banner driven by `navigator.onLine` and the online/offline events, for the device-token auth path rejecting a staff session, and for the pairing screen's approve-by-user-code and revoke-with-reason. A spec for `q-qr-code` rendering the `qrPayload` the payments API returns.

**Traps** ADR 0079's status line claims a kitchen device «can enrol, read the board and mark a line ready today» — true of the API, false of any shipped client; fix that sentence. Marked-goods DataMatrix scanning has no endpoint, no model and no ADR: split it out, do not grade this wave against it.

### P18 · Dispatch board, board columns and drag-assign
**Rows** `3.1` dispatch board (L) · `X.21` Board/BoardColumn/BoardCard (M) · `X.22` DragDropAssign (M) — **1d**, after `P01` and `P03`.

A dispatcher gets a text table where the IA promises the диспетчерский модуль. The server half is done and idempotent — `DispatchController` `/queue`, `/plans/{planId}/assign`, `/unassign`, `/plans/{planId}/exceptions`, with compare-and-set single-winner semantics and ADR 0027 audit facts, under `DELIVERY_PLAN_READ` and `DELIVERY_MANUAL_ASSIGN`.

Build `q-board` / `q-board-column` / `q-board-card` and `q-drag-drop-assign` in `shared/ui/` — **`P03` adds `@angular/cdk` and its lockfile entry; consume it, do not add it again**, which is why this wave is sequenced behind `P03` as well as `P01` — then re-lay `dispatch-board-page.html` as courier-keyed columns of order cards: drag a card onto a courier to assign, drag off to unassign, with the courier's `activeAssignments`/`concurrencyCeiling` rendered as the drop target's load. The optimistic-concurrency `expectedVersion` a drop must send is already in hand. Call `GET /plans/{planId}/exceptions` — built and never called — so a `MANUAL_ACTION_REQUIRED` plan shows its reason instead of a status word. Add click-to-filter.

Scope note for the planner: only `3.1` still forces this component — ADR 0071 withdrew the review kanban — so size it as dispatch-only.

**Out of scope, deliberately**: the map (deferred with `X.4`), «call an external courier» (no backend; `P44`), bulk assignment (`BulkActionType` is ADVANCE|CANCEL and ADR 0039 defers the courier case), and customer name or address on the row — `DispatchController`'s class doc makes that a contract decision, and the page already joins the public order number client-side.

**Capabilities** `DELIVERY_PLAN_READ` for the queue and the exceptions read, `DELIVERY_MANUAL_ASSIGN` for the drop.

**Tests** Angular specs for drag-to-assign sending the correct `expectedVersion`, for a stale version surfacing the single-winner refusal rather than silently reverting, for drag-off unassign, for the courier column rendering `activeAssignments`/`concurrencyCeiling` as load, and for a `MANUAL_ACTION_REQUIRED` plan showing its exception reason.

**Traps** `StreamChannel.DISPATCH_BOARD` is declared and unfed; live updates arrive with `P08`, not here. Keep the 10s poll until then.

### P19 · Courier roster: the compliance file
**Rows** `3.3` couriers (L) — **1d**, after nothing.

`fulfillment.couriers` carries id, type, subject, reference, a protected name and status — and the self-employment model the platform assumes needs a file. Add, behind the ADR 0029 envelope with the same protection the name already has: passport, PINFL, driving licence, vehicle registration, plate and fuel type, photo, address, emergency contact, referral and notes; plus courier groups and branch bindings as first-class relations (a courier is attached to a branch today only by an open shift). Extend `OperationsCourierController` (`GET /couriers:137`, `POST /couriers:299`) and `CourierRegistrationService` accordingly, with a reveal path for the protected fields carrying a purpose, exactly as customers do.

Frontend: a courier detail pane behind the roster, the register form widened, and the inline honesty notice at `couriers-page.html:76` deleted once its list is served. Render `vehicleClass` — it is in the DTO, in the component doc and in the spec fixture, and in no template.

Courier-app provisioning stays as it is: the register form asks for a Keycloak subject created outside the console, and ADR 0042 forbids deriving a password from a passport number. Online status and rating are read-only and out of scope until a source exists.

**Migration** yes — reserved numbers **`V0220`–`V0222`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier compliance columns under the ADR 0029 envelope; `courier_groups`; `courier_branch_bindings`.

**Capabilities** `COURIER_READ` for the roster, `COURIER_ENGAGEMENT_MANAGE` for the register and suspend paths, `COURIER_REGISTRATION_VERIFY` for verification. The compliance file needs a **new** `COURIER_PII_REVEAL` (`courier.pii.reveal`) modelled on `CUSTOMER_PII_REVEAL`: `COURIER_REGISTRATION_REVEAL` already exists but its Javadoc scopes it to the registration identifier for the accountant export, and passport, PINFL, licence, address and emergency contact are a wider set that must not ride in on it.

**Tests** Java tests against the migrated schema for envelope encryption of every new protected field, for the reveal writing its audit fact with a purpose, and for `PINFL` being absent from the list read. Angular specs for register, verify and suspend — none of which is covered today (`couriers-page.spec.ts` has two tests) — and one asserting `vehicleClass` renders.

**Traps** do not put PINFL on the list read; it is a reveal.

### P20 · Zones, regions and delivery tariffs
**Rows** `3.6` delivery zones (L) · `3.6d` free geozone (S) · `3.6b` regions and bounding boxes (M) · `3.7` delivery tariffs (L) — **2d**, after nothing.

The backends are further along than the screens. `ServiceZoneController.DraftVersionRequest` already accepts `geoJson` and validates self-intersecting rings, the region bounding box and an area ceiling; `DeliveryTariffController` already accepts many bands with named band sets, peak-hour time rules with day masks and multipliers, `AMOUNT` and `DISTANCE_ALLOWANCE` discounts, `feeSource` `TARIFF|PROVIDER_QUOTE`, `distanceMode` `RADIUS|ROAD`, min/max fee, rounding and road factor — and returns all of it. The console authors one flat band and one circle.

Zones: pass `deliveryTariffId` on the draft (the field exists on both sides and `submitDraft` simply never sends it, so every console-drawn zone carries a null tariff and the zone-beats-branch precedence cannot be exercised); offer the `CATCHMENT` role the form hard-codes away; author per-locale names instead of writing one string into all three; separate draft from activate and bind, and add a version list, a deactivate and an unbind — the console can only ever add today. Polygon drawing is deferred with `X.4`.

`3.6d` **free geozone** rides on that one fix and is smaller than the parity matrix makes it look. `ZoneRole`'s own Javadoc settles the design — «a 'free geozone' is not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as a layer is what left three layers with no documented interaction» — so do **not** build a third geometry layer. What this wave owes is the consequence of the tariff binding: a zone bound to a zero-resolving tariff, and a «бесплатно» marker on the zone list and on the fee evidence so an operator can see which zones are free without opening each tariff. The IA's free-geozone-as-a-layer wording is struck in PART B; cite ADR 0037 when you strike it.

Regions: build the CRUD that does not exist at any layer — `fulfillment.regions` (V0025) is written only by test SQL, so a fresh production database has no region at all and onboarding a second city needs hand-written SQL. Add create/update/archive/list with ADR 0027 audit facts, and a SW/NE bounding-box form.

Tariffs: author distance tiers, peak-hour windows, discounts, min/max fee and rounding; render the fields the detail panel drops (distance mode, reach, discounts, and time rules as more than a count); bind a tariff to a branch (`bindLocation` exists and has no caller). Point the page at the operations mirror — `delivery-paths.ts:66` builds the tariff base URL from `CONTROL_PLANE`, so the screen calls the wrong surface for the operator it serves.

**Capabilities** `DELIVERY_ZONE_READ`/`DELIVERY_ZONE_MANAGE` and `DELIVERY_ZONE_ACTIVATE` for zones (the activate split is ADR 0037's, keep it); `DELIVERY_TARIFF_READ`/`MANAGE`/`ACTIVATE` for tariffs; region CRUD reuses `DELIVERY_ZONE_MANAGE` — a region constrains a geocoder, it is not a separate authority.

**Tests** Java tests that a draft carrying `deliveryTariffId` activates with the tariff bound and that zone-beats-branch precedence resolves to it; that a `CATCHMENT` draft with a tariff is refused by `ck_zone_version_catchment_is_not_priced`; that region create/update/archive writes ADR 0027 audit facts; and that activating `ROAD` without a routing binding is refused while the fallback stamps `RADIUS_FALLBACK`. Angular specs for the zero-fee zone rendering as free (`3.6d`), for per-locale zone names, and for unbind — both pages' write paths are entirely untested today.

**Traps** activating ROAD without a routing binding is refused server-side, and a fallback stamps `RADIUS_FALLBACK` on the resolution — render that, do not hide it. Both pages' write paths are entirely untested.

### P21 · Product library and the fiscal workbench
**Rows** `4.1` products list (L) · `4.1a` bulk ИКПУ, delete, copy-id, share slugs (L) — **1d**, after `P03`.

Search and the status tabs are computed client-side over the pages already fetched, so on the 1000+ item catalogue the row is written for, searching a dish past the loaded pages returns nothing. Add server-side `query` and `status` parameters to `GET /catalogs/{catalogId}/products` and drive the tabs and the search box from them.

Then the row actions, none of which exist: duplicate (needs a new endpoint), archive / status transition (`Product` carries a `Status` and no mutation exposes it), add-to-category (the endpoint exists — `PUT /categories/{categoryId}/products/{productId}` — and has no caller anywhere), stop-in-all-branches (a product-level fan-out over the existing per-variant, per-location offering write), copy-id and the public share slug (no backend at all). Add the selection model and bulk-action bar from `P03`.

The fiscal workbench is the other half and the one that decides whether a 600-item menu can be onboarded in a day: a coverage read answering «N of M priceable nodes unclassified» over `catalog.fiscal_classifications` (V0028 has `ix_fiscal_classifications_incomplete` built for exactly this query and no endpoint asks it), an unclassified worklist including `MODIFIER_OPTION` and `FEE` nodes, and a bulk classify endpoint so ИКПУ and package codes can be filled down a `q-data-grid` column instead of one variant at a time in the editor.

**Capabilities** `CATALOG_READ` for the list, the status tabs and the coverage read; `CATALOG_AUTHOR` at `BRAND` scope for duplicate, archive, add-to-category, stop-in-all-branches and the bulk classify.

**Tests** Java tests for the server-side `query` and `status` parameters and for the node-level coverage read using `ix_fiscal_classifications_incomplete`; a test that the bulk classify is idempotent and reports per-node outcomes. Angular specs for the row actions and for the `NO_MXIK` tab showing the **node-level** figure, not the product-level one.

**Traps** the `NO_MXIK` tab count today is client-side, product-level `hasMxik` — it is not the node-level coverage figure and must not be presented as one. Every catalog controller is on the control-plane prefix; if this wave adds an operations mirror, do it once and move the paths file with it.

### P22 · Product editor: variants, media and fiscal data
**Rows** `4.2` the seven-tab core (M) · `4.2f` ordered images with per-aggregator overrides (L) · `4.2d` fiscal data on every priceable node — marking, excise, alcohol, age gate (M) · `4.2e` ИКПУ/MXIK reference typeahead (M) · `X.12` MediaUploader with crop (M) — **2d**, after `P01` and `P02`.

Sequenced behind `P01` because `q-media-uploader` lands in `shared/ui/`, and behind `P02` because that wave migrates this screen's locale strip to `q-localized-field-group` — two waves rewriting `product-editor-page` at once is the collision this ordering removes. The per-item schedule, the kitchen department and cross-sell moved to `P47`.

Three defects on one screen. **Variants**: «Add variant» posts only `sortOrder` and an `UNCLASSIFIED` fiscal block, creating a nameless, SKU-less, unit-less variant the console then cannot name because the translation form writes `entityType PRODUCT` only — `AddVariantRequest` accepts sku, unitCode and name, so send them and add `VARIANT` translations. The variants tab is otherwise read-only apart from the price input: add name, SKU, unit, default-variant and deactivate. **Status**: `Черновик↔Активен` is read-only text; add the status endpoint (none exists) and a remove-from-category/catalog path (there is no `@DeleteMapping` anywhere in the catalog package).

**Media**: the photo grid renders a role label and no image — there is not one `<img>` tag in the entire console — although `GET /media/assets/{assetId}/download-url` exists and `operations-paths.ts:529` already declares the helper. Render thumbnails, add reorder by re-`PUT`ting the attach endpoint with a new `sortOrder`, add a detach endpoint (missing at every layer, so a wrong upload cannot be undone), and build `q-media-uploader` with aspect-ratio crop (1:1, 3:2, 3:1, 9:16), size caps and progress. Per-aggregator variants need a channel dimension on `catalog.media_relations` and are the backend half of `4.2f`; video needs the image-only allowlist and 10MB cap widened.

**Migration** yes — reserved numbers **`V0223`–`V0225`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a channel dimension on `catalog.media_relations`; the widened media allowlist.

**Capabilities** `CATALOG_AUTHOR` at `BRAND` scope for the variant, status and media writes; `CATALOG_READ` for the editor's reads. The media detach endpoint gets `CATALOG_AUTHOR`, not a media-specific capability — the asset is catalog content.

**Tests** Java tests against the migrated schema for `AddVariantRequest` persisting sku, unitCode and name; for `VARIANT` translations; for the status endpoint; for the detach endpoint removing exactly one relation; and for the channel dimension on `media_relations`. Angular specs for the thumbnail grid actually rendering an `<img>`, for reorder by re-`PUT` with a new `sortOrder`, and for the uploader's aspect-ratio crop and size cap.

**Traps** derivatives are rendered and stored and never served — neither download-url accepts a variant, so a thumbnail grid loads full-size originals unless you add one.

### P47 · Per-item schedule, kitchen department and cross-sell
**Rows** `4.2g` per-item sale schedule and kitchen department (L) · `4.2h` recommended products / cross-sell (M) — **1d**, after `P02` and `P22`.

Two things that hang off an item rather than describing it, split out of the product editor so
`P22` can stay inside its day and so this wave can wait for `P02`'s `q-schedule-grid`.

**Kitchen department** is pure wiring: the existing `POST .../kitchen/routing-rules` already
routes a product to a station, and the editor renders a «not built — ADR 0016 open input»
caption over it. Delete the caption and wire the picker.

**Per-item sale schedule** genuinely has no binding — V0020 withdrew
`location_offerings.sales_schedule_id` — so breakfast-only items are stopped and unstopped by
hand twice a day. Add the binding table and the `q-schedule-grid` editor, resolving at order
time against the location's timezone and not the operator's.

**Cross-sell** (`4.2h`) is new to this map and unbuilt at every layer: no
`catalog.product_recommendations` table in any of the 159 migrations, no read model carrying a
recommendations list, and `grep -ari recommend frontend/operations/src` returns nothing. Add
the table, the attach/detach/reorder endpoints and the block in the product editor. **The
filter is the row**: IA 4.2 asks for recommendations «filtered by active + in-menu +
not-stopped», and a recommendation that points at a stopped variant is worse than none —
resolve the filter server-side at read time, not by pruning the stored set, so a stop that
lifts restores the recommendation.

**Migration** yes — reserved numbers **`V0226`–`V0228`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the per-item sale-schedule binding V0020 withdrew; `catalog.product_recommendations`.

**Capabilities** `CATALOG_AUTHOR` for the schedule binding, the kitchen-department routing rule and the recommendation set; `CATALOG_READ` for the pickers.

**Tests** Java tests against the migrated schema for the per-item schedule binding resolving at order time and for a recommendation set filtered to active + in-menu + not-stopped — the filter is the row, so test the stopped case explicitly. Angular specs for the schedule grid editor and for the kitchen-department picker writing a routing rule.

**Traps** a recommendation is directional and the temptation is a symmetric link table; do not
build one — «chips with this burger» is not «this burger with chips». Cross-sell does not
imply a combo: combos are `4.2a` and are deferred behind an ADR 0016 amendment.

### P23 · Categories and the per-location menu matrix
**Rows** `4.3` categories (L) · `X.23` SortableList/TreeView with drag-reorder (M) · `4.4` menus matrix (L) — **2d**, after `P03`.

Categories are write-once: there is no `PUT` that changes `parentCategoryId`, `sort_order`, `status`, `code` or description, and `JdbcCatalogStore` has only `INSERT INTO catalog.categories`. Add the update and archive endpoints, then the tree the spec asks for: `q-tree-view` with drag-reorder and keyboard navigation (arrow keys, left/right collapse, Alt+arrow reorder and promote, Enter rename — none of which exists), child counts, status dots, the empty-category state, and the red cycle path the current `nodes()` silently skips past so `CATEGORY_TREE_HAS_CYCLE` is invisible. Rename and description go through the existing `PUT /translations`; the image through the existing `attachMedia`; the per-category product list is derivable from the products read.

Menus: the matrix shows two states for one location, so a variant hidden at the offering level is indistinguishable from one never added — widen the location-variants read to surface `lo.status` and drop its AVAILABLE-only join, and add filter/search parameters and a bulk stop/unstop endpoint. Add the per-order-type column: `SetOfferingRequest.fulfillmentModes` is already accepted and persisted, and only the read's projection drops it. Follow the cursor — the backend pages at 50 and `nextCursor` is never passed, so a location with more than 50 sellable variants is silently truncated.

The per-channel offering and price plane (`4.4b`) is an XL row and was split into `P45`, which runs straight after this wave over the same files. The named `Menu` entity itself is deferred (`4.4a`).

**Capabilities** `CATALOG_AUTHOR` for the category update, archive and reorder and for the bulk stop/unstop; `CATALOG_READ` for the tree and the matrix.

**Tests** Java tests for the category update and archive including a refused cycle (`CATEGORY_TREE_HAS_CYCLE` must surface, not be skipped), and for the widened location-variants read distinguishing «hidden at offering level» from «never added». Angular specs for tree drag-reorder, keyboard reorder and promote, and for the matrix following `nextCursor` past 50 variants.

**Traps** `catalog.md` and `menus-page.ts` both assert the exclusions table does not exist; V0020 built it. Fix both.

### P45 · Menus: per-channel offering and price
**Rows** `4.4b` per-channel offering and price (XL) — **2d**, after `P23`.

Split out of `P23` on size; sequenced behind it because both write `features/catalog/menus*`.

The **price plane is already complete server-side** — `PriceAuthoringController.assignToChannel`
and `JdbcPricingStore`'s CHANNEL precedence — with a frontend client that nothing calls. Add
the channel selector and let a hall price and an aggregator price coexist on one variant.

The **enablement plane** is the gap: `catalog.channel_offering_exclusions` (V0020) has a reader
and no writer anywhere, so «this dish is not on Uzum Tezkor» cannot be expressed. Add the write
path and a **mass-enable** for onboarding an aggregator — enabling 600 items one at a time is
the thing that makes an aggregator launch take a week.

Keep the two planes separate in the UI as the IA insists: `offered_on_channel` and
`price_on_channel` are different questions, and conflating them is the Delever behaviour this
row exists to avoid. The named `Menu` entity itself stays deferred (`4.4a`).

**Capabilities** `PRICE_AUTHOR` for `assignToChannel`; `CATALOG_AUTHOR` for the exclusion write and the mass-enable; `CATALOG_READ` for the matrix.

**Tests** Java tests against the migrated schema for `assignToChannel` precedence over the base plane, for the exclusion write and the mass-enable, and for `SetOfferingRequest.fulfillmentModes` surviving into the read projection. Angular specs for the channel selector and the per-order-type column.

**Traps** `catalog.md` and `menus-page.ts` both assert the exclusions table does not exist;
V0020 built it. Fix both — `P23` fixes the same pair of claims for the matrix, so check whether
it has already landed before editing.

### P24 · POS catalog sync and the mapping pane
**Rows** `4.5a` POS sync: mapping table and run history (L) · `10.8b` integration mapping tables (XL) · `X.24` MappingPane (L) — **2d**, after `P03`.

`/catalog/import` is a `NotBuiltPage` and the merchant-facing half of ADR 0012 does not exist, although staging, the difference engine, the durable scheduler and raw snapshots are all built behind `/api/v1/control-plane/tenants/{tenantId}/pos-sync-runs` (start, differences, review-decisions, apply, resume).

Backend: add a run-history list and a run detail (there is no listing `GET` at all), a read over `integration.pos_sync_apply_items` for per-item outcomes after the fact, import-language and price-re-import parameters on the run start, and — the larger piece — a tenant-facing mapping API over `integration.provider_entity_mappings`: list by binding and entity type, list unmapped external entities, create an `OPERATOR`-sourced mapping (the enum value is allowed by the CHECK and nothing ever writes it), retire one, and bulk auto-match by name. Five of the six entity types have no code path at all — `PAYMENT_TYPE`, `DISCOUNT`, `COURIER`, `CANCELLATION_REASON` and the channel→POS category code are never read or written, and no adapter method discovers a provider's payment-type or discount list, so the right-hand side of the pane has to be sourced too.

Frontend: `q-mapping-pane` in `shared/ui/` — dual list, link/unlink, unmapped-only filter, bulk auto-match, and the two-sided conflict card the spec requires instead of last-write-wins — plus the import screen (run list, per-item outcome report, mapping tab) and the installation-detail «Соответствия» tab.

**Migration** yes — reserved numbers **`V0229`–`V0231`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: indexes for the mapping reads; the `OPERATOR` mapping source has no schema change.

**Capabilities** the run reads and the mapping list are `POS_SYNC_READ`; starting a run, applying it and every mapping mutation are `POS_SYNC_MANAGE`. Neither may be satisfied by `PLATFORM_ADMIN` alone — the point of the row is that a merchant can do this without the platform.

**Tests** Java tests against the migrated schema for the run-history list and detail, for the per-item outcome read, for an `OPERATOR`-sourced mapping being accepted by the CHECK, for retire, and for bulk auto-match reporting conflicts rather than last-write-wins. Angular specs for the dual list, the unmapped-only filter and the two-sided conflict card.

**Traps** `catalog.md:1352` says the external-id mapping table with linked/unlinked/conflict states is unbuilt; V0013:145 built it and `JdbcPosApplyStore` writes it. Excel import (`4.5b`) is a different row and is deferred — do not let this wave grow into it.

### P25 · Customer record depth: contact, consent, address
**Rows** `5.2a` contact points (M) · `5.2b` consent and marketing eligibility (M) · `5.2c` saved addresses (M) — **2d**, after nothing.

Three rows, one screen, two of them with a live correctness bug.

**Contact points**: `CustomerController` has only `POST` and `GET` on `/contact-points` — no `PUT`, `PATCH` or `DELETE`, and `JdbcCustomerStore` has no update, delete or set-primary — so a mistyped number can only be shadowed by adding another. Add the three mutations. The other two complaints are screen-only: `ContactType` already accepts `EMAIL` and only `customer-detail-pane.ts:330` hard-codes `'PHONE'`, and `verificationStatus` is on the wire and never rendered although it is what `MarketingEligibility` uses to refuse with `NO_VERIFIED_ENDPOINT`. Render the masked summary too — it is fetched and displayed nowhere, so the section is blank before Reveal.

**Consent**: the console posts no `brandId`, a hard-coded `policyVersion: 'operator-recorded-v1'`, a lowercase `'marketing'` purpose and a null channel, while `JdbcCustomerStore.currentConsent` matches brand, purpose and channel exactly — so the default submission misses on all three axes and recording consent here cannot make anyone contactable. Send the brand, replace free text with a constrained picker aligned to the strings the platform actually matches, and stop faking the policy version. Add the missing backend read: nothing answers «is this customer contactable» — `MarketingEligibility.refusalFor` is reachable only from `AudienceService` and `CampaignSendService` — so add a per-customer eligibility endpoint and render the answer with its refusal reason.

**Addresses**: every address the operator saves is created without coordinates, so a phone order captured here cannot be zone-resolved or tariffed. The operator-pin half is frontend-only (`OPERATOR_PIN` with lat/long already passes `requireCoordinatesMatchSource`) and rides on `X.4`; what ships now is showing `coordinateSource` so an operator can see which addresses are landmark-only, and protecting the hand-written guard at `:493-495` that stops a text edit dropping a storefront pin — it has no test.

**Capabilities** `CUSTOMER_READ` for the record; `CUSTOMER_MANAGE` for the three contact-point mutations and the consent write; `CUSTOMER_PII_REVEAL` with a purpose for the unmasked values. The eligibility read is `CUSTOMER_READ` — it answers a yes/no and a refusal reason, never a contact value.

**Tests** Java tests for contact-point update, delete and set-primary, including that deleting the last verified endpoint changes the eligibility answer; and for the per-customer eligibility endpoint returning `NO_VERIFIED_ENDPOINT` and `CONSENT_WITHHELD` distinctly. Angular specs for the consent form sending a brand, a constrained purpose and a channel that `currentConsent` actually matches, and a spec pinning the `:493-495` guard that stops a text edit dropping a storefront pin.

**Traps** the `SendPulse` import records purpose `MARKETING` while campaigns default to `MARKETING_PROMOTIONS`; pick one and record it.

### P40 · Customer record depth: ledger, promos, reviews, blacklist, erasure
**Rows** `5.2d` order history + reorder (M) · `5.2e` cashback ledger (S) · `5.2g` promo redemptions (M) · `5.2h` reviews left (S) · `5.2i` blacklist (S) · `5/X.1` data-subject erasure (S) — **1d**, after `P25` and `P14`.

Five small rows on the same pane, one of which is an accountability hole.

**Blacklist**: the reveal is paid for — a purpose-stamped decrypt — and then most of the payload is discarded. `RevealedBlacklistEntry` carries `actorType`, `actorId`, `expiresAt`, `liftedAt`, `liftedByActorId` and `liftReason`; the history card renders reason, status and created-at. For a row whose own title is «reason, actor, expiry», render the actor. Same for `BlacklistStatus.expired` and `since`.

**Cashback**: `POST .../loyalty/adjustments` exists on the same controller with `LOYALTY_ADJUST` and an ADR 0027 approval above threshold, and nothing on the pane calls it — while `loyalty-page.ts:54-56` tells the reader the manual adjustment UI is «already built ... on Customer detail». It is not. Add it, format `entry.amountMinor` with the enclosing balance's currency (it prints raw beside a formatted balance), render `balanceAfterMinor`, `reasonCode` and the linked order, and label each balance card with its brand — per-brand separation is the endpoint's whole point and two brands render as two indistinguishable cards.

**Promo redemptions**: `pricing.coupon_redemptions` has `ix_redemptions_customer` built for exactly this query and no read path at any layer. Add the store query, the port method, the service and an operations endpoint, then the pane section.

**Reviews left**: add a `customerAccountId` parameter to `OperationsReviewController.list` and a tab. Today there is no path at all from a customer to their reviews — the review list never renders an account id, so even matching by eye is impossible.

**Erasure**: raise, withdraw and execute are fully built (`CustomerController:567/:586/:602/:631`, V0178, `CustomerErasureService`) and the only screen showing them is the control plane, which a merchant cannot reach. Add the section, gating execute on `CUSTOMER_ERASURE_EXECUTE`.

**Reorder**: point «Повторить» at `P14`'s staff reorder-plan wrapper instead of the not-built page, and render order status through the console's own label map rather than the raw enum.

**Capabilities** `CUSTOMER_READ` for the ledger, promo and review sections; `LOYALTY_ADJUST` for the manual adjustment, with the ADR 0027 approval above threshold; `CUSTOMER_PII_REVEAL` for the blacklist actor; `CUSTOMER_ERASURE_RAISE` for raise and withdraw and `CUSTOMER_ERASURE_EXECUTE` for execute.

**Tests** Angular specs for the blacklist history rendering actor, expiry and lift reason; for per-brand balance cards being distinguishable; for `entry.amountMinor` formatted in the enclosing balance's currency; and for the erasure section gating execute. Java tests against the migrated schema for the promo-redemption read using `ix_redemptions_customer` and for the `customerAccountId` filter on the review list.

### P26 · ImportWizard, customer CSV and the header counters
**Rows** `X.13` ImportWizard (L) · `5.1b` bulk CSV import with provenance (L) · `5.1a` header counters from the metric layer (M) — **2d**, after `P02`.

Build `q-import-wizard` in `shared/ui/`: FileDropzone, a row-level preview table, a dry-run diff, JobProgress and a ResultSummary with per-row outcomes. It is a pilot blocker for three rows (`4.5`, `5.1b`, `3.6c`) and the per-row outcome report is the IA's stated improvement over Delever's silent skip.

Its first consumer is the customer CSV. The reusable half already exists and must not be rebuilt: `Capability.CUSTOMER_IMPORT` is registered and granted to tenant-owner, `CustomerImportDirectory` (`accountsWithPhone` / `createAccountWithoutPrincipal` / `attachPhoneContact` / `record`) fixes the recorded consent source to `IMPORT`, and `JdbcSendPulseImportStore` already stores per-row run reports with a reject-reason vocabulary. What is missing is a non-Telegram parser (the only parser keys on `chat_id` and rejects any row without one), a tenant-scoped controller over that same port, and an async job surface — both existing imports are synchronous `POST`s returning a report, so a progress bar has nothing to poll until one exists.

`5.1a` rides along because it is the same team and the same screen: the header counters are a second, unregistered code path computed at UTC midnight, so between 00:00 and 05:00 Tashkent a row the grid dates «today» is excluded from «registered today». `reporting.agg_branch_day` already stores `distinct_customers` and `new_customers` on the ADR 0043 business-day boundary — register the metric definitions, move the counters onto `BusinessDayService`, and give `total` an agreed meaning (`countActive` counts every non-`MERGED` row including CLOSED and ANONYMIZED).

**Migration** yes — reserved numbers **`V0232`–`V0234`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the import-job entity and its per-row report.

**Capabilities** `CUSTOMER_IMPORT` — already registered and granted to tenant-owner — for the run; `CUSTOMER_READ` for the preview and the header counters.

**Tests** Java tests against the migrated schema for the non-Telegram parser accepting a row with no `chat_id`, for the per-row reject-reason vocabulary, for the async job surface reporting progress, and for the counters computed on `BusinessDayService` rather than UTC midnight — pin the 00:00–05:00 Tashkent case explicitly. Angular specs for the wizard's dry-run diff and result summary, for the search debounce, and for the export truncation flag.

**Traps** `customers-page.ts` has no search debounce and issues one request per keystroke; fix it here. The CSV export is silently capped at 2000 rows with no truncation flag — add one.

### P27 · Reports: the filter bar, the overview and the order log
**Rows** `7.1d` global filter bar (M) · `7.1` KPI counters (L) · `7.1a` funnel with cancel reasons (S) · `7.2` per-stage durations (M) · `7.2a` commercial order log (L) · `7.2b` daily operations (M) · `7.2c` daily summaries (S) — **2d**, after `P02` and `P07`.

Sequenced behind `P07`, which extracts `reports-filter-bar` into `shared/ui/` as `q-filter-bar` for the order board. This wave re-points every reports page at the extracted component and deletes the wrapper `P07` left behind; it does not extract it a second time.

The filter bar is not global: `fulfilmentType()` is read in one file and applied client-side over already-fetched rows, four of the five built pages read only `range()`, and the demand tab does not inject the state at all while the bar renders above it. Make every page consume the shared state, push the fulfilment axis down to the query (the backend already groups by it), add a custom range, granularity, branch, channel and legal-entity controls, arrow-key stepping and URL-shareable filter state — everything is in-memory signals today and resets on reload. `channelCodes` exists in the state with no writer and no reader; wire it. Legal entity needs a new request parameter — it exists only as a `groupBy` dimension.

Overview: add a pickup/delivery elapsed-time metric (derivable from `fact_order.seconds_total` + fulfilment type — a registry and endpoint gap, not a data gap), call the already-live `GET /reporting/metrics` so every tile carries the published-formula panel §1.2 requires, and plumb the cancellation cost: `ordering.order_outcomes` is built and written on every cancellation, and `JdbcReportingStore.insertOrderFact` simply never copies `stock_disposition` and `liability_party`, so V0031's two columns stay NULL. Resolve the reason code to its `internal_name` instead of printing a machine code.

Order log: add `Филиал`, `Предзаказ` and the public order number (the table prints eight characters of a UUID), a column chooser, saved views and cursor paging, and stop discarding the server's `maybeMore` on the stages tab. Split the stage clocks correctly — `seconds_to_ready` is CONFIRMED→READY, wider than the spec's «Приготовлен», and the branch-acceptance gap has no column at all, so eleven minutes of waiting reads as cooking. Add a per-aggregator column to «Посуточно» by asking for `groupBy=['CHANNEL']` — no new endpoint needed.

**Capabilities** `REPORTING_READ` at `TENANT` scope throughout. The `ORDER_READ` log that answers the CRM half of `7.2a` lives outside the reporting module and keeps `ORDER_READ` — do not widen `REPORTING_READ` to cover it.

**Tests** Java tests that the fulfilment axis is applied in the query rather than client-side, that a money metric grouped without `LEGAL_ENTITY` on a two-entity tenant surfaces `CombinedEntityTotalException` as a handled error rather than an errored page, and that `insertOrderFact` now copies `stock_disposition` and `liability_party`. Angular specs for URL-shareable filter state, for every page consuming the shared state, and for the stage clocks splitting branch acceptance from cooking.

**Traps** any money metric grouped without `LEGAL_ENTITY` throws `CombinedEntityTotalException` for a two-entity tenant and errors the whole page. The CRM half of `7.2a` cannot come from reporting — `SubjectPseudonym` exists to prevent that linkage; it needs an `ORDER_READ` log outside the reporting module.

### P28 · The export centre
**Rows** `7.2e` Excel/CSV export as an audited PII egress (XL) — **2d**, after `P27`.

Nobody can take a single figure out of Reports. The Export button is hard-coded `disabled`, there is no `/statistics/exports` route, no `POST /reporting/exports`, no `GET /reporting/reports/{id}`, no `reporting.report_exports` table, and `Capability` registers only `REPORTING_READ` — `report.export` and `customer.pii.export` do not exist. ADR 0043 puts this last deliberately, behind capabilities and an audited job queue; it is still a pilot row because ADR 0029 relies on it to catch a bulk egress.

Build the whole chain: the two capabilities; a `reporting.report_exports` migration with the job state, the requested columns, the row quota and the resulting artefact reference; an async export service writing through the existing media lifecycle; `POST /reporting/exports` and `GET /reporting/reports/{id}` under `report.export`; an `AuditFact` per export recording who exported what, with which filters, how many rows and which PII column group — the control ADR 0029 needs; and the export centre screen plus the per-report trigger, including a column chooser whose PII group is separately gated on `customer.pii.export`.

Model the job on what already works: the customers export (`CustomerListQueryService.exportFiltered`) decrypts behind exactly one `customer.list.exported` audit fact carrying `revealedCount` and the filters — reuse that shape, and fix its silent 2000-row truncation while you are in it so a marketer stops receiving a quietly cut set.

**ADR** ADR 0043's export line and ADR 0029's egress checklist.

**Migration** yes — reserved numbers **`V0235`–`V0237`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.report_exports`.

**Capabilities** two new constants: `report.export` at `TENANT` scope for the job, and `customer.pii.export` additionally for the PII column group. Neither exists today — `Capability` registers only `REPORTING_READ` — so register both in this wave with their sentences.

**Tests** Java tests against the migrated schema for the row quota, for the audit fact recording who exported what with which filters and which PII column group, for a principal holding `report.export` but not `customer.pii.export` receiving a file with the PII columns **omitted rather than a 403**, and for the customers export no longer truncating silently at 2000 rows. An Angular spec for the column chooser hiding the PII group when the capability is absent.

### P29 · Finance: payments and fiscal, per order
**Rows** `8.1` payments and settlements (M) · `8.2` fiscal receipts (M) — **1d**, after nothing.

Both screens are real and both are unreachable from the thing an operator actually has: an order number. `payments-page` takes a raw UUID in a text box; the fiscal blocked queue prints `document.orderId` as a mono UUID with no link. Add lookup by public order number on both — and a payment-status column on the order queue, which `P04` puts on the wire.

Payments: replace the free-text 48-character `reasonCode` with the provider-mappable vocabulary a void needs, localize the raw enum tokens (`orderStatus`, `intent.status`, `attempt.status` print unlocalized while every other status on the screen goes through `finance-labels`), and wire `POST /orders/{orderId}/future-discounts` — the third `RemedyType` exists, its path helper exists, and `payments-api.ts` narrows the form to two kinds so the endpoint is unreachable.

Fiscal: call `FiscalApi.forOrder` — it wraps `GET /fiscal/orders/{orderId}/documents` and has no caller, so a FAILED or unissued document outside the blocked worklist cannot be inspected or retried from any screen. Add `reasonNote`, `submittedAt` and `reportingDeadlineAt` to the queue (all on the wire) so a `PROVIDER_REPORT_OVERDUE` row can be triaged by urgency, and add an order-number projection to `BlockedDocumentResponse` — the response record carries no display number, so even a fully wired screen could not tie a row back to an order without it.

**Known limits to state, not fix**: delivery-fee reimbursements record a null basis because `DeliveryFeeBasisPort` is unwired; granted future discounts can never be redeemed because ADR 0018 pricing does not call `RemedyEntitlementPort`; the unverified-attestation worklist has no mechanical discharge path because no settlement-file import exists. Each is an ADR 0048/0013 checklist line, not this wave's work.

**Capabilities** `PAYMENT_READ` and `PAYMENT_VOID`/`PAYMENT_REPRESENT` on the payments screen; `FISCAL_DOCUMENT_READ` and `FISCAL_DOCUMENT_RETRY` on the fiscal queue; `ORDER_READ` for the new lookup-by-order-number.

**Tests** Angular specs for lookup by public order number on both screens, for the localized status labels, and for the third `RemedyType` reaching `future-discounts`. Java tests for the order-number projection on `BlockedDocumentResponse` and for `FiscalApi.forOrder`'s endpoint under a location-scoped principal.

**Traps** the fiscal evidence itself — receipt URL, fiscal sign, INN, marking codes — is `8/X.2` and is blocked on a legal input. Do not render a partial receipt.

### P39 · Payment mix and the tender fact
**Rows** `7.1c` business overview — payment mix (L) — **2d**, after `P33`.

The one figure a restaurant uses for cash-collection control is absent from every report, and the business overview renders a hard-coded locked card for it. This is not a screen gap: the chain is missing from the source column up.

`reporting.fact_order_tender` was deliberately not created (V0031 header line 9) and `ordering.orders` stores no payment method. The tender data does exist further down — `payments.order_settlements` and `payments.tenders` are populated in production by `CheckoutSettlementPlanner`, and V0048 records how much of a tender was refunded — so the work is a projection, not a capture.

Build: the `fact_order_tender` migration at the ADR 0043 grain; a producer inside `DayCloseService`'s own transaction, next to the existing order-fact write, reading the settlement's tenders; a `Dimension.PAYMENT_METHOD` in `Grain`; a registered `payment_mix.*` metric (the registry is append-only — add, never edit); and the overview card plus a per-branch split so `7.3b`'s cash-reconciliation half can be answered from the branch report. The reports filter bar already renders a locked «Payment type» chip naming exactly this blocker — unlock it in the same wave.

Sequenced after `P33` because the method vocabulary has to be a registry before a fact can carry it: `payments.payment_methods` exists but rows are created lazily at first checkout, so a mix keyed on it before `P33` seeds and lists the registry would be keyed on whatever happened to be tendered.

**ADR** ADR 0043 metric registration, plus a note on ADR 0013.

**Migration** yes — reserved numbers **`V0238`–`V0240`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.fact_order_tender`.

**Capabilities** `REPORTING_READ` for the mix; the fact producer runs inside `DayCloseService` as a system principal and needs none.

**Tests** a Java test that a split-tender order produces two `fact_order_tender` rows summing to the order total and that a refunded tender nets out; a `MetricRegistry` drift test for the new `payment_mix.*` definitions; and a test that the producer runs inside `DayCloseService`'s own transaction, so a failed fact rolls back the close rather than half-writing it.

**Traps** do not read `payments.*` from a report — ADR 0023 forbids a report reading a module schema, which is why the fact is the required path.

### P30 · Staff: gated navigation, locked versus denied
**Rows** `9.1` accounts and grants (S) · `9.1c` permission-gated navigation (M) · `9.1d` locked-by-plan versus denied-by-permission (M) — **1d**, after nothing.

Every operator sees all fourteen rail sections and discovers what she may not do by clicking into a screen and being refused — the opposite of the legacy dashboard, which hid what you had no rights to. The data is already in the browser: `GET /api/v1/session/context` returns per-scope capability lists and `core/auth/session-context.ts` reads them for usability only. Add a `capability` field to `NavItem`, filter in `Shell`, and add a capability-aware route guard beside `auth.guard.ts`, which today asks only «is anybody signed in». The sibling console already does exactly this (`frontend/control-plane/.../sections.ts` plus `console-shell.ts:47-49`) — port the pattern, and reuse `features/staff/scope-coverage.ts`, which already ports `ResourceScope.covers` client-side.

Then make refusal legible. `ENTITLEMENT_REQUIRED` and `INSUFFICIENT_CAPABILITY` are distinct error codes that both fall through `describeApiError` to flat one-line sentences. Use `P01`'s `q-denied-state` (naming the capability and who can grant it) and `q-locked-state` (naming the module). The buy CTA cannot be wired — there is no tenant-scoped purchase endpoint, all of `CommercialModuleController` is platform-scoped — so render the lock without a CTA and say why in the brief's follow-up, not in the UI.

`9.1` itself contributes two items: capability **search** on the Должности screen, which is frontend-only over the existing `capability-sentences.ts` table (the platform's own `CapabilityRegistryController` is `PLATFORM_ADMIN`-gated and unreachable), and the dead holder-count button at `staff-roles-page.html:35-37` that looks actionable and only stops propagation.

**Known limit to record, not fix**: every grant endpoint demands `IAM_GRANT_MANAGE` at TENANT scope, held only by tenant-owner and tenant-admin, so «the Chilonzor manager sees Chilonzor's team» is unreachable by the person it was designed for. That is an ADR 0025 scope decision, not a screen fix — raise it, do not work around it.

**Capabilities** this wave *reads* capabilities rather than adding one: the rail filter and the route guard consume `GET /api/v1/session/context`'s per-scope lists. The capability **search** on Должности is frontend-only over `capability-sentences.ts`; do not call `CapabilityRegistryController`, which is `PLATFORM_ADMIN`-gated.

**Tests** Angular specs for the rail hiding a section whose capability the session context lacks, for the route guard refusing a direct URL to that section, for `ENTITLEMENT_REQUIRED` rendering `q-locked-state` and `INSUFFICIENT_CAPABILITY` rendering `q-denied-state` with the capability named, and for capability search. A spec asserting the lock renders **without** a buy CTA.

### P31 · Settings: the scope bar, readiness and support visits
**Rows** `10/X.1` scope bar + InheritedField (XL) · `10.0` settings home and «find a setting» (M) · `10/X.3` support visits (S) — **2d**, after nothing.

The pattern every settings row renders through does not exist. ADR 0030's resolver, precedence, explicit-null and resolution trace are all built and filed under `built/` — and the only HTTP surface is `ConfigurationController` at `/api/v1/control-plane/configuration/**`, every method `PLATFORM_ADMIN` at PLATFORM scope. So a merchant cannot set anything at brand level and override it for one branch, cannot see where a value came from, and cannot tell which level they are about to change: the single worst settings mistake the spec names.

Build an operations-surface configuration API — read, write and resolution-trace over `ConfigurationResolver`/`ConfigurationValueAuthor` under the two new capabilities named below, which is what `ConfigurationController`'s own Javadoc anticipates when it says tenant self-service «would need its own narrower capability and its own conversation about who holds it» — then the §1.1 scope bar (brand picker, location picker, a «level being edited» readout, `?brand=`/`?location=` query state) and the §1.2 `q-inherited-field` wrapper with its trace popover. `settings-shell.html` prints a raw UUID pair today.

Its first consumer, the order policy cards, moved to `P46` on size — `10.3b` is XL and would have taken this wave to nineteen size-points in two days. `P46` runs straight after and is the acceptance test for everything built here: if a card cannot be authored at brand level and overridden for one branch through the surface this wave ships, the surface is not finished.

`10.0`'s readiness panel does not need a new cross-module aggregate: `OnboardingController.validate` dry-runs every validating-phase check against current configuration under `TENANT_READ` and already emits `NO_LEGAL_ENTITY` and `NO_MERCHANT_BINDING`. Reshape it into a countable, deep-linkable list (it returns on the first offending location today) and add the `/` search over the nav items.

`10/X.3` is a two-line template fix: support visits declares `principalSubject` and `ticketReference` and renders neither, so «who from support entered this account» is unanswered. Add the labels and the missing `.spec.ts`.

**Capabilities** two new constants, because `ConfigurationController`'s own Javadoc says tenant self-service «would need its own narrower capability»: `TENANT_CONFIGURATION_READ` (`tenant.configuration.read`) and `TENANT_CONFIGURATION_WRITE` (`tenant.configuration.write`), both at `TENANT` scope and both filtered to `ConfigurationKey#tenantVisible()` entries — a platform default a tenant may never see is not one it may author. `TENANT_READ` already covers `OnboardingController.validate`.

**Tests** Java tests against the operations configuration API for read, write and resolution trace at each of `TENANT`/`BRAND`/`LOCATION`; that a key without `tenantVisible()` is absent from the list and refused on write; that explicit-null overrides resolve as ADR 0030 specifies; and that `PLATFORM_ADMIN`-only keys stay unreachable. Angular specs for the scope bar's `?brand=`/`?location=` query state, for `q-inherited-field`'s trace popover, and for the readiness list counting **every** offending location rather than returning on the first.

### P46 · Settings: order policy cards and catalog base settings
**Rows** `10.3b` order policy cards 2–5 (XL) · `4.4d` catalog base settings (M) — **2d**, after `P31`.

`P31` builds the scope bar and the operations configuration API; this wave is its first
consumer, and it is two card sets rather than one because both are the same work: register an
ADR 0030 key, give it a default, expose it through the new surface, and render it in an
inherited field.

**Order policy cards 2–5** need registry entries that do not exist: business-day start,
average and maximum order time, the late threshold, minimum order sum, VAT, routing poll
interval, pre-order branch resolution, operator promo-code permission, the auto-accept
eligible-channel set and the minimum-prior-successful-orders gate. Card 1 already proves the
pattern end to end — extend it to `TENANT` and `LOCATION` scope, which the controller already
accepts and only `order-policy-api.ts` prevents by hard-coding `brandId`.

**Catalog base settings** (`4.4d`) is the same shape and has no row-level backend of its own:
two tenant-wide switches with no registered key anywhere. `catalog.use_stock_logic` turns
quantity tracking on for the whole company — today the only expression is a per-variant,
per-location `TrackingMode` argument, which is unusable on a 600-item catalogue, and `QUANTITY`
still throws `UnsupportedTrackingModeException`, so the switch must surface that refusal in
operator language rather than a 500. `catalog.qr_kiosk_price_plane` says «QR and kiosk sell at
hall prices», which is what stops those two channels either needing a full price plane authored
by hand or silently taking the wrong one.

**Capabilities** `TENANT_CONFIGURATION_READ`/`TENANT_CONFIGURATION_WRITE` from `P31` for every card, at whichever of `TENANT`/`BRAND`/`LOCATION` the scope bar is pointing at.

**Tests** a registry drift test naming every new key and its default; Java tests that each card writes at the scope the bar is pointing at and that card 1's existing TENANT and LOCATION support is now reachable; and a test that `catalog.use_stock_logic` turning on quantity tracking surfaces `UnsupportedTrackingModeException` as an operator-legible refusal rather than a 500.

**Traps** `4.4d` is a settings row, not a catalog row: it must not grow into `4.4c` per-item
stock quantity, which is deferred behind an ADR 0017 amendment. Registering a key whose
enforcement does not exist is worse than no key — if `use_stock_logic` cannot be honoured yet,
ship it disabled with the reason on the card.

### P32 · Settings: brand, languages and the branch list
**Rows** `10.1` brand profile (L) · `10.12` languages and regional formats (M) · `10.2a` branch list (M) · `10.2b` location tab 1 (L) — **2d**, after `P02` and `P31`.

One folder, four rows, and a live data-loss bug to fix first. Hours, prep bands and the order limit split into `P43`; the floor-plan tab moved to `P38`, which owns `X.36`'s canvas — building the floor plan in two concurrent waves was the worst collision in the first draft of this plan. `PUT .../locations/{id}/place` is a whole-place write: `DescribeLocationCommand.toPlace()` nulls the point when latitude is absent and defaults `coordinateSource` to `NOT_GEOCODED`, and `savePlace()` sends only address line, district, city and phone — so **every address or phone edit from the console silently erases the branch's map pin and landmark**, and ADR 0037 originates delivery zones from that point. Carry the existing coordinates and landmark through, add `draftLandmark` (the column, the command field and the DTO field all exist), and add a spec that asserts it.

Brand profile is a read-out of four of thirteen fields. Renaming is already built and shipped — `TenantControlPlaneService.reviseBrand` behind `PUT /control-plane/tenants/{t}/brands/{brandId}` with `BRAND_WRITE` and `If-Match`, used by the control-plane console — so the trade name needs an operations route and a form, not a backend. Logo, aggregator/QR banner, localized description, contact phone, Telegram handle and supported languages have **no columns at all** (`tenant.brands` is code/slug/display_name/status) and no logo or banner concept exists anywhere; add them with the media relation and `10.12`'s per-brand supported-locale set in the same migration, since `platform.default_locale` was deleted from the registry on 2026-09-10 and the console's locale list is a frontend constant mirrored by a hard-coded server list.

Locations: add the branch-list columns and filters (state, reason, channels, INN) over a brand-scoped batch read of `location_service_state` — per-row today is an N+1 — plus the close/open action from the row and a bulk bar; and sort order, tags and venue attributes as new columns.

**Migration** yes — reserved numbers **`V0241`–`V0243`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: brand logo, banner, localized description, contact phone, Telegram handle; the per-brand supported-locale set; the brand media relation.

**Capabilities** `BRAND_WRITE` with `If-Match` for the brand revision (already enforced by `TenantControlPlaneService.reviseBrand`); `LOCATION_READ` for the branch list and the batch service-state read; `LOCATION_MANAGE` for the place write, the close/open action and the new columns.

**Tests** a Java test — the one this wave exists for — that a phone-only or address-only `PUT .../locations/{id}/place` **preserves** the existing point, `coordinateSource` and landmark; plus tests for the brand revision under `If-Match`, for the new brand columns and the media relation, and for the per-brand supported-locale set replacing the hard-coded server list. Angular specs for the branch-list filters over the batch service-state read and for the close/open row action.

**Traps** the place write is the whole reason this wave is sequenced first inside its folder: fix it before adding a single column, because every later editor in `P43` and `P38` saves through the same screen.

### P43 · Settings: hours, prep bands and the order limit
**Rows** `10.2c` hours, prep bands, order limit (L) — **1d**, after `P32`.

Split out of `P32` on size, and sequenced behind it because both write
`features/settings/locations/`.

Three editors over endpoints that already answer. The **hours and holiday-exception**
editors use `P02`'s `q-schedule-grid` over the existing `PUT /service-schedules/{id}/rules`
and `/exceptions`, binding to the `{dayOfWeek, opensAt, closesAt}` plus dated-exception shape
`ServiceSummaryResponse` already returns. The **preparation-band** editor goes over the unused
`PUT /preparation-bands`. The **order limit** is an ADR 0030 key authored through `P31`'s
configuration surface at location scope.

Two corrections this wave owes. `ServiceScheduleController` has **no `GET`**, so rebinding a
fulfilment mode to a different timetable has no picker source — add one. And a schedule can
be shared: `sharedWithLocationCount` is on the wire, so editing one branch's hours can silently
change another's. Warn before saving, naming the count.

**Capabilities** `LOCATION_MANAGE` for the schedule rules, the dated exceptions and the preparation bands; `LOCATION_READ` for the new `GET` on `ServiceScheduleController`.

**Tests** Java tests for the schedule rules and dated exceptions written through `P02`'s grid, for the new `GET` on `ServiceScheduleController`, and for the preparation-band write. An Angular spec that editing a schedule with `sharedWithLocationCount > 1` warns before saving.

**Traps** the day window a schedule expresses is the location's, not the browser's — `W01`
depends on this being readable and correct, and gets it wrong today for exactly that reason.

### P33 · Settings: channels, the capability matrix and payment methods
**Rows** `10.4a` channel registry (L) · `10.4b` capability matrix (M) · `10.6` payment-method registry (L) — **2d**, after `P03` and `P31`.

These three are one wave because the matrix cannot be correct until the registry exists, and today the matrix ships a **live 500**: since V0175 the FK on `tenant.channel_payment_methods.payment_method_code` is enforced, `replacePaymentMethods` inserts a row for every key in the map, and the page sends a hard-coded `['CASH','CLICK','PAYME']` — so on a tenant whose `payments.payment_methods` lacks one of them, toggling raises an untranslated `DataIntegrityViolationException`. `replaceLocations` catches and explains its FK violation; this path does not.

`10.6` first: `payments.payment_methods` (V0042) has no controller on any surface — rows are created lazily by `JdbcSettlementStore.registerMethod` at first checkout, so «the registry only ever grows by accident». Add create, rename, localize, icon, order, activate and disable; a seeding path at onboarding (ADR 0038's checklist line «seed each tenant's methods» is still unticked); the acquirer-installation binding (`provider_installation_id`, `contract_reference` and a localized-name table are unbuilt); and a tenant-scoped `GET` so the matrix columns come from the registry instead of a frontend constant.

`10.4a`: seven of the eleven spec columns are absent although `ChannelView` already carries the data (branches, payment-method count, order types, price plane, externally-priced, guest orders, installation). Add them, plus an edit endpoint (none exists — nothing can be changed after create), the `ACTIVE→INACTIVE` transition (the status value is declared and unreachable), filters and the severity sort, and the branch-serviceability write (`PUT /{channelId}/locations` exists, its path helper exists, and `matrices().locationIds` is fetched and dropped).

`10.4b`: render it as `P03`'s `q-matrix-grid` — rows are channels, so a cross-channel comparison is possible — with row and column bulk toggles, shift range-select, hatched cells for a method the channel cannot fiscalise, a confirmation before turning off the last enabled method on an active channel, and the channel × location plane that is fully backed and entirely unrendered.

**Migration** yes — reserved numbers **`V0244`–`V0246`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: payment-method localized names, `provider_installation_id`, `contract_reference`.

**Capabilities** `SALES_CHANNEL_MANAGE` for the registry edit, the status transition and the matrix writes; `SALES_CHANNEL_READ` for the list; `PAYMENT_METHOD_MANAGE` for the new registry (register it — `payments.payment_methods` has no controller on any surface, so it has no capability either) and `PAYMENT_METHOD_READ` for the tenant-scoped `GET` the matrix columns read.

**Tests** the regression this wave owes: a Java test that toggling a payment method absent from `payments.payment_methods` returns an operator-legible refusal rather than an untranslated `DataIntegrityViolationException` — the live 500. Plus tests for the registry CRUD, the onboarding seed, the acquirer binding, the channel edit endpoint, the `ACTIVE→INACTIVE` transition and the branch-serviceability write. Angular specs for the matrix's bulk toggles, the hatched non-fiscalisable cell, and the confirmation before disabling the last enabled method on an active channel.

**Traps** icons, brand colours and social links belong to `10.5`, not here; the three per-channel order-entry toggles belong to the call-centre row. Delete the `PROVISIONAL_PAYMENT_METHODS` comment claiming no registry exists «until ADR 0038 lands».

### P34 · Settings: fiscalization
**Rows** `10.7a` legal entities (S) · `10.7b` fiscal terminals (L) · `10.7c` product classification coverage (L) — **2d**, after `P31`.

Tab 1 works and has two rough edges plus four missing fields: move the app off the control-plane path onto `OperationsLegalEntityController`, which publishes the identical six operations at `/api/v1/operations/tenants/{tenantId}/legal-entities`; expose suspend and archive, which exist in `LegalEntityService` and have no `@PostMapping` on either controller, so a retired company can only be left ACTIVE; add short name, VAT certificate reference, tax profile, registered address and contact phone with an edit form (there is none, so a registered entity can never be corrected); and render the assignment table with its effective window and the ADR 0027 approval reference the API already accepts.

Tab 2 is a pilot blocker with nothing beneath it. Create `fiscal.fiscal_terminals` — kind `POS|COURIER_TERMINAL|KIOSK|VIRTUAL`, location, legal entity, provider binding, terminal reference, capability snapshot, status, last health check, sketched at ADR 0038 lines 503-513 and never migrated — plus a store, a service and an operations controller for register, list and health. Until it exists `CheckoutSettlementPlanner.responsibilityOf()` cannot declare CASH as `TERMINAL` (it registers `OPERATOR` and the file flags that as wrong) and ADR 0038's activation precondition «cash requires a fiscal-capable terminal bound to the location» has nowhere to run.

Tab 3 is the coverage view `P21`'s workbench serves: a per-brand count of unclassified priceable nodes with the node list by type, name, category and offering breadth, ordered by how many locations sell them; the delivery-fee node's own ИКПУ and package code (the `catalog.fees` DELIVERY row and its `PUT .../fees/{feeCode}/fiscal-classification` already exist and have no caller); the marking-enablement control (storage exists, UI does not); and the read-only VAT-defaults projection.

**Migration** yes — reserved numbers **`V0247`–`V0249`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `fiscal.fiscal_terminals`; legal-entity short name, VAT certificate, tax profile, registered address, contact phone.

**Capabilities** `LEGAL_ENTITY_READ`/`LEGAL_ENTITY_MANAGE` for tab 1 including the new suspend and archive; a new `FISCAL_TERMINAL_MANAGE` for register/health and `FISCAL_TERMINAL_READ` for the list; `CATALOG_READ` for the coverage view and `CATALOG_AUTHOR` for the fee node's classification.

**Tests** Java tests against the migrated schema for `fiscal.fiscal_terminals` register/list/health, for `CheckoutSettlementPlanner.responsibilityOf()` declaring CASH as `TERMINAL` once a capable terminal is bound, for ADR 0038's activation precondition refusing a location with no terminal, and for legal-entity suspend and archive. Angular specs for the edit form and for the coverage view ordering nodes by offering breadth.

**Traps** settings.md lines 771-777 and 1400 state that `fiscal.fiscal_terminals` already exists. It does not; fix the spec in this wave. `FiscalReferenceController` is `PLATFORM`-scoped, so no tenant operator can search the ИКПУ reference — re-scope it or add a tenant alias, as `P22` needs too.

### P35 · Settings: integrations
**Rows** `10.8a` install hub, secret door, bindings (M) · `10.8c` health and errors (L) · `10.8d` partner API credentials (M) · `10.8e` analytics installs and the GA4 event contract (L) · `X.14` SecretInput (S) — **2d**, after `P31`.

The backend is ahead of the screen, and the screen's own doc comment says the opposite. Five operations-surface endpoints have no frontend caller at all: `GET /{installationId}/bindings` (landed 2026-09-10, real SQL, and its path helper already exists in `settings-paths.ts:215` with only a `POST` caller), `POST /bindings/{id}/activate` and `/suspend` — the controller documents bindings as «created suspended», so the console creates bindings it can never bring live — `POST /{installationId}/capability-reconciliation`, and `GET`/`POST /{installationId}/settings`, whose Javadoc names this very screen as its caller. Wire all five, and the merchant-binding activate/suspend the UI also never calls.

Build `q-secret-input` in `shared/ui/` — three bare `type="password"` inputs do the job today — with masked presence, a reveal-once **at entry** (never a readback: the secret door is write-only by ADR 0028/0065 and no surface returns a stored value; a spec promising reveal-once for a *stored* credential contradicts the security model. Two documents need the qualifier and one already has it: rewrite `platform/docs/frontend-and-parity-plan.md:67` («`SecretInput` — masked, reveal-once, rotate») and `platform/docs/frontend-information-architecture.md:342` to say **reveal-once at entry**, and leave `platform/docs/operations-spec/settings.md:900` alone — it already reads «masked, reveal-once at entry and never re-rendered», which is the correct sentence to copy), copy, rotate and slots for last-rotated and last-used. Last-used has no column, no field and no endpoint anywhere — that is the row's one genuine backend gap.

`10.8c`: a merchant cannot see that their aggregator stopped pushing orders two hours ago. `GET /api/v1/operations/tenants/{tenantId}/marketplace/liveness` is built and has zero callers — render it — then add the two tenant-scoped reads that do not exist: an operator-legible error taxonomy and a merchant-scoped replay, both of which live only at `/api/v1/control-plane/**` under PLATFORM scope today. Add liveness watermarks for POS, fiscal and notification providers, not only marketplace.

`10.8d`: `partner.api_clients` exists with rotation-pair and expiry constraints and `PartnerOrderController` authenticates against it, and no controller issues, lists, rotates or revokes a client — nor does anything create the Keycloak confidential client. Build all of it; this is the row that replaces `base64(login:password)`.

`10.8e` is new to this map and is two unequal halves. **Telephony is nearly free**: `ProviderCategory.VOICE` and `VoiceProviderCapabilityCatalog` already exist (ADR 0064), so a telephony install is just another card in the hub this wave builds. **Analytics has no model at all**: `ProviderCategory` declares POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION, GEOCODING and VOICE and no `ANALYTICS`, so a GTM container id, a GA4 property or a Search Console verification token cannot be stored against a tenant anywhere. Add the category, the install shape (a container id or measurement id plus the verification token, no secret) and the per-brand storefront injection. Today `frontend/storefront/src/index.html:58-68` carries a **commented-out** Yandex Metrika counter with one hard-coded platform-wide id — so the analytics that exists is both switched off and, switched on, the wrong tenant's.

The **GA4 ecommerce event contract** is the larger half and is a producer, not a settings row: a named, versioned event set (`view_item`, `add_to_cart`, `begin_checkout`, `purchase`) that the storefront emits into the tenant's own `dataLayer`. `dataLayer` appears exactly once in the whole storefront source, inside that dead comment, so nothing emits anything today. Version the contract and write it down — an unversioned event set is what makes a merchant's year-on-year comparison meaningless the first time a field is renamed. The tenant's own property is the tenant's own lawful basis, which is why this does not inherit `7.6c`'s legal block; do **not** let it grow into first-party behavioural telemetry, which does.

**Migration** yes — reserved numbers **`V0250`–`V0252`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: partner API client last-used; provider-installation last-used watermarks.

**Capabilities** `INTEGRATION_INSTALLATION_MANAGE` for bindings, activate/suspend, reconciliation and settings; `INTEGRATION_INSTALLATION_READ` for the hub and the liveness reads; a new `PARTNER_API_CLIENT_MANAGE` for issue/list/rotate/revoke, which must be separate from the installation capability — a partner credential is a different blast radius. Analytics installs reuse the installation pair once `ProviderCategory.ANALYTICS` exists.

**Tests** Angular specs for all five previously uncalled endpoints, including creating a binding and then activating it — the path the console cannot complete today. Java tests against the migrated schema for the partner API client issue/rotate/revoke pair with its expiry constraint, for the Keycloak confidential client being created and cleaned up, for the last-used column being written on use, and for the tenant-scoped error taxonomy and replay refusing another tenant's installation.

**Traps** the only real backend gap on `10.8a` is the absent `RETIRED` transition, which ADR 0065 assigns to owner and platform, not to a frontend task. Do not put an analytics measurement id behind the secret door: it is a public identifier, and hiding it teaches operators that the mask means nothing.

### P36 · Settings: notifications
**Rows** `10.9a` order-status and OTP templates (L) · `10.9b` Telegram routing (L) · `10.9c` provider moderation state (S) · `10.9d` payment-link auto-send and shift notifications (M) · `X.27` TemplateEditor with VariableChip (M) — **2d**, after `P02` and `P31`.

One folder, four rows and a silent failure mode. `NotificationTemplateService.addVersion` stamps a new SMS wording `PENDING` when the gateway requires moderation, `NotificationEligibilityService` withholds every `PENDING`/`REJECTED` send, and the screen activates immediately by default — so a merchant publishes an OTP wording, watches it go ACTIVE, and every message is suppressed with nothing anywhere saying so. The state is exposed only at `/api/v1/control-plane/template-reviews`. Add `provider_review` (plus note, reference and timestamp) to the tenant-facing `WordingResponse` — `JdbcTemplateStore` already selects it — render the state and the two suppression reasons, and warn at publish time.

The editor is create-only: `Изменить` (add version) and `Активировать` exist as API methods reachable only inside the create flow, and `notificationTemplateVersion` has no call site at all, so an author cannot read back the wording of a template that already exists. Add row actions, a version list, locale-completeness chips, the moderation column, a test send, and the sort the spec asks for. Build `q-template-editor` with VariableChip insertion and a live `q-phone-frame` preview — and fix the defect underneath it: the page hard-codes `variablesSchema: {}` on every version, and `TemplateRenderer.validate` refuses any placeholder not in the declared schema, so **the editor cannot author a variable-bearing template at all**. Publish a per-notification-class variable catalogue (none exists) and return the stored schema on `GET`.

Tab 2 is not built: no chat linking, no topic id, no per-event-class routing rows, no test send, no brand→location origin chip and no quiet-hours control, although the key is registered. Add the admin API over ADR 0058's bindings — list, set which event classes a chat receives (only a hard-coded `DEFAULT_SUBSCRIPTIONS` set exists), change topic, unbind — and the screen. `10.9d` adds the payment-link auto-send switch and the aggregator shift open/close notification in the tenant's timezone; neither has a configuration key today.

**Migration** yes — reserved numbers **`V0253`–`V0255`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the per-version variables schema; the template unique index widened past `(tenant, brand, key, channel)`.

**Capabilities** `NOTIFICATION_TEMPLATE_MANAGE` for versions, activation and the test send; `NOTIFICATION_TEMPLATE_READ` for the list, the version history and the moderation state; `NOTIFICATION_ROUTING_MANAGE` for the Telegram bindings. The moderation state is *read* on the tenant surface — approving it stays platform-side.

**Tests** a Java test that a version stamped `PENDING` is withheld by `NotificationEligibilityService` and that `provider_review` reaches the tenant-facing `WordingResponse`; a test that a template declaring a variables schema renders while one with `{}` and a placeholder is refused by `TemplateRenderer.validate` — the defect that makes the editor unusable; and tests for the Telegram binding list, per-event-class routing and unbind. Angular specs for the version list, the moderation column, the test send and the publish-time warning.

**Traps** the channel dropdown offers EMAIL and PUSH for which `isWired()` is false. Order type and source as key dimensions need a schema change — the unique index is `(tenant, brand, template_key, channel)`.

### P37 · Settings: reference data
**Rows** `10.10a` cancellation and completion reasons (S) · `10.10b` business calendar (L) · `10.10c` SLA bucket boundaries (M) · `10.10d` branch tags (M) — **2d**, after `P31`.

`10.10a` reads as finished and is not: the API exposes `list`, `categories`, `create` and `archive` and **no update**, although `PUT /{reasonId}` exists and returns a version. An operator cannot rename a reason, fix its customer text, or change its stock disposition, liability party or default refund after creation — the only recourse is archive-and-recreate. Add the edit path with the spec's «this creates a new version» warning. Add the `allowedFulfillmentModes` control, which the spec calls «the thing Delever lacks» and which is typed on both sides and rendered nowhere — it is what stops «Самовывоз выполнен» landing on a delivery order. Render the system category, default refund and status in the rows, and move the screen onto an operations surface (the pilot-blocker list names this one by name).

`10.10b`: a tenant cannot enter its own holidays or movable Islamic dates, declare its weekend, or set a business day that closes after midnight, so every report cuts the day at the platform's assumption. A platform-level per-country holiday list exists (ADR 0090, Uzbekistan seeded) and `reporting.business_day_policies` plus `BusinessDayService` exist with **no production caller for `setBoundary`**. Add the tenant-facing calendar and boundary editor, and surface the resolved `businessDayStart` read-only in the reports provenance banner.

`10.10c` is not blocked and is not what the IA promises. ADR 0043 decided that SLA buckets are platform-fixed and versioned, and says so in the controller's own Javadoc; the IA and settings.md promise tenant-configurable boundaries. Ship the small honest thing — a tenant-readable endpoint for the active bucket set and its version, and a read-only card naming the version the reports were computed under — and raise the contradiction as a doc correction (settings.md lines 1105 and 1325) or a superseding ADR. Do not build configurability in this wave.

`10.10d`: branch tags have no table, no endpoint and no screen; add the registry, the location assignment, and the filter-and-group affordance that makes them worth having.

**Migration** yes — reserved numbers **`V0256`–`V0258`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: branch tags and their location assignment; the tenant business calendar and its boundary.

**Capabilities** `REFERENCE_DATA_MANAGE` for the reason edit, the calendar and the branch tags; `REFERENCE_DATA_READ` for the lists and for the read-only SLA bucket set.

**Tests** Java tests for the reason edit creating a new version, for `allowedFulfillmentModes` refusing «Самовывоз выполнен» on a delivery order, for the tenant calendar and a business day that closes after midnight, and for branch tag assignment. An Angular spec that the SLA bucket card renders the active version read-only and offers no editor.

### P38 · Settings: delivery policy and dine-in QR
**Rows** `10.13` delivery policy (L) · `10.5b` QR dine-in modes and table QR codes (M) · `10.2d` floor plan tab (L) · `X.36` FloorPlanCanvas + TableToken + TimelineScheduler (L) — **2d**, after `P17` and `P31`.

The courier rulebook is readable and unwritable. `CourierPolicyResolver` resolves ADR 0042's document through ADR 0030 and one `GET .../courier-policy` reads it; there is no writer anywhere, because `PolicyAuthor` — the platform's only policy-document writer — has exactly two non-Javadoc consumers and no controller injects it. Add a write endpoint beside the read, then the `/settings/delivery-policy` screen, which does not exist even as a placeholder.

Five of the switches the spec names have no backing field at all and need adding to the policy document: the GPS master toggle with accept radius and status-change radius, show-only-kitchen-ready, reveal-customer-location timing, and the post-delivery payment check. Two corrections to carry: courier billing mode is **refused** by ADR 0042, not missing, and must not be counted as a gap; the telemetry collection gate **is** a registered ADR 0030 key today but is writable only at PLATFORM scope, so it belongs on this screen as «platform-only», not as «no field anywhere». `delivery.out_of_zone_policy` (`REJECT|OFFER_PICKUP|MANUAL_REVIEW`) is not a registered key — register it; the enforcement it switches already exists, since `DeliveryFeeResolver` returns `OUTSIDE_CATCHMENT` today.

The dine-in half is pure wiring over a finished backend: `FloorPlanController`'s `GET`/`PUT /dine-in/settings` persists `qrMode`, and `POST /tables/{tableId}/qr-token-rotations` mints a one-shot token, both audited and `If-Match`-guarded. Add the settings form (turnaround minutes, guest-session TTL, service-charge rate, reason), the per-table issue/rotate action rendering the token as a printable table card through `P17`'s `q-qr-code`, and the revoked-guest-session count. `SETTLE_OPEN_TICKET` is declared non-selectable and refused at both `QrMode.require` and the CHECK constraint — render it disabled with its reason, not as a missing feature.

`X.36` and `10.2d` are the same screen and are built **once, here**. The first draft of this plan had `P32` building a floor-plan tab over `FloorPlanController` while this wave built the canvas under it, both after `P31` and concurrent; `10.2d` now belongs to this wave and `P32` does not touch it.

`FloorPlanController` is complete on the read side — sections, tables, status, QR rotation — and has no caller. What is missing is the write: table coordinates are write-only (`TableRequest` takes `layoutX`/`layoutY`, `TableResponse` omits them) and there is no `PUT /tables/{tableId}`, so drag-to-reposition has nowhere to save. Add the response fields and the endpoint, then the canvas, then the tab that hosts it under Settings → Locations. Sequenced behind `P17` for `q-qr-code`, which prints the table cards.

**Capabilities** `DELIVERY_POLICY_READ` for the resolved courier policy and `DELIVERY_POLICY_WRITE` for the new write endpoint beside it; `DINE_IN_MANAGE` for the QR settings, the table writes and the token rotation, `DINE_IN_READ` for the floor plan. The telemetry gate stays `PLATFORM_ADMIN` and renders as «platform-only».

**Tests** Java tests against the migrated schema for the courier-policy write endpoint and each of the five new fields resolving through ADR 0030; for `PUT /tables/{tableId}` moving a table and `TableResponse` now returning `layoutX`/`layoutY`; and for the QR token rotation staying one-shot under `If-Match`. Angular specs for the floor-plan canvas drag-reposition, for the printable table card, and for `SETTLE_OPEN_TICKET` rendering disabled with its reason.

### T01 · My work
**Rows** `0.2` my work (XL) · `0.2a` personal statistics by channel (M) · `0.2b` revenue by payment method (L) — **2d**, after `P08`.

A signed-in operator has no personal page at all: `/today/my-work` is a `NotBuiltPage` whose spec string names the reason. Build the page against what exists rather than waiting for what does not. `0.2a` needs an actor-grouped read of `ordering.orders` over `created_by_actor_id`/`accepted_by_actor_id` — the columns and `ix_orders_created_by` exist since V0029 and nothing aggregates them — scoped to the caller's own subject, which is the one case that needs no staff directory because the console already knows who it is. `0.2b` rides on `P39`'s tender fact: without it, revenue by payment method cannot be split for anyone. **What «done» means for `0.2` is narrower than the row, and this is the boundary**: `0.2` is the umbrella row for a page that has four named capabilities, two of which — personal data (`0.2c`) and user-scoped personalization (`0.2d`) — are deferred with the staff-identity ADR. This wave discharges `0.2` when the page exists, is reachable at `/today/my-work`, renders `0.2a` and `0.2b`, and carries a `q-locked-state` band naming the staff-identity ADR for the other two. It does **not** discharge `0.2c` or `0.2d`, which keep their own rows and their own entries in PART B. Do not treat an empty profile section as done, and do not build a profile store to make the row look finished.

**Capabilities** `ORDER_READ` at the caller's own scope; the actor-grouped read must be **self-scoped by the token's subject**, not by a request parameter, so no capability can widen it to another operator.

**Tests** Java tests for the actor-grouped read being self-scoped — a request naming another subject must be refused, not merely empty — and for the aggregate using `ix_orders_created_by`. Angular specs for the two statistics bands and for the locked band naming the staff-identity ADR.

**Traps** do not print another principal's subject id; the personal page is the one actor view that is legible today precisely because it is self-scoped.

### T23 · The wallboard shell
**Rows** `0.1e` wallboard presentation of the live board (L) · `X/X.3` wallboard shell (M) — **1d**, after `P15`.

Every read a wallboard needs already answers — counts, the in-progress list and the brand roster — so this is a presentation wave. Add a chrome-less route outside the console `Shell` (a `WallboardShell` peer to `KitchenShell`), a TV-distance type step above the current 42px `.q-display` top of the scale, a `q-wallboard-tile`, an unattended/fullscreen entry (nothing calls `requestFullscreen` anywhere), and a wall-legible freshness state. The staleness stamp is not missing — it exists at caption size, which is the actual defect: a frozen board must be distinguishable from a quiet shift from across the pass.

**Capabilities** `ORDER_READ` and `LOCATION_READ` — the wallboard reads exactly what `P15` already serves and adds no endpoint.

**Tests** Angular specs for the wallboard route rendering outside the console `Shell`, for the fullscreen entry, and for the freshness state being legible at the TV type step rather than at caption size — assert the computed font size, since that *is* the defect.

**Traps** the operator leaderboard band stays a locked note. The TV type step must go into `frontend/design-tokens`, not into a feature stylesheet.

### T02 · Kitchen buffer, expo gate, VDU and capacity
**Rows** `2.2` buffer (M) · `2.3` expo handover (M) · `2.4` display board (M) · `2.6` capacity ceilings (S) — **2d**, after `P16`.

**Expo** is the sharp one: an aggregator order can be handed to the wrong courier because expo presses one unconditional button, while the server-side compare is built, audited and reachable — `POST .../marketplace/orders/{orderId}/handover-verifications` consumes an attempt before comparing and never returns the expected value, and `/handover-bypasses` needs `MARKETPLACE_HANDOVER_BYPASS` plus a reason. Wire both, with attempts-remaining and a supervisor bypass, and add the packing check and the per-department ready roll-up the row claims and the flat station table does not give.

**Buffer** ships one of ADR 0041's three actions. `PUT /kitchen/tickets/{ticketId}/release-schedule` covers both placing a ticket on manual hold and editing its fire time, and has no client; pulling a fire time earlier is an ordinary non-privileged act that is equally unreachable. Paid-only hold is unmodelled on both tiers and stays out until the payment fact that gates it is decided.

**VDU**: the one feature IA 2.4 names is the provider-assigned identifier, and `TicketResponse` carries none — `sequenceLabel` is HorecaOS's own number, so a courier quoting an aggregator code cannot match the wall. Add the external reference to the board response. The device class, station filter and dedicated projection are ADR 0041 rollout step 4 and are a separate, larger item.

**Capacity**: a mistyped ceiling is permanent — there is no update or delete on `KitchenStationController` — and because overlapping windows are refused it also blocks the correct window from ever being authored. Add update and delete. Fix the screen's own copy, which tells a manager the ceiling does nothing while `KitchenTicketService` actually shifts `release_at` on it.

**Capabilities** `KITCHEN_BOARD_READ` for the buffer and the VDU; `MARKETPLACE_HANDOVER_VERIFY` for the expo compare and `MARKETPLACE_HANDOVER_BYPASS` for the supervisor bypass; `KITCHEN_STATION_MANAGE` for the capacity update and delete.

**Tests** Java tests for the handover compare consuming an attempt and returning attempts-remaining, for the bypass requiring `MARKETPLACE_HANDOVER_BYPASS` and a reason, for capacity update and delete (including that deleting an overlapping window unblocks authoring the correct one), and for the external reference reaching `TicketResponse`. Angular specs for the buffer's hold and fire-time edit and for the per-department ready roll-up.

### T03 · Live map and courier policy
**Rows** `3.2` live map (L) · `3.9` courier policy (L) — **1d**, after `P19`.

Positions render as a table of decimal coordinates. The backend is complete — `GET .../operations/couriers/positions` with staleness and accuracy rules, and the audited `POST .../{courierId}/track-reveals` whose path helper exists with zero call sites, so a stored track cannot be opened for a dispute. Render `accuracyMeters`, `headingDegrees` and `speedMps`, which the table drops, add a branch filter, and wire the reveal. The map canvas itself is deferred with `X.4`; partner-courier pins are **not** a gap — ADR 0045 records them as an explicitly rejected alternative.

Courier policy is read-only by construction and its write path lands in `P38`; what belongs here is the screen the spec asks for: the resolution scope selector (tenant → brand → location) so a manager can compare an override against the default, the inherited value beside each overridden field, and the one-sentence consequence line per row. The page reads only `CurrentLocation` and prints a single static «Resolved at» line today, and renders neither `policyId` nor `policyVersion` although both are on the wire.

**Capabilities** `COURIER_POSITION_READ` for the live positions; `COURIER_TRACK_REVEAL` with a stated purpose for the stored-track reveal; `DELIVERY_POLICY_READ` for the resolution-scope selector.

**Tests** Angular specs for `accuracyMeters`, `headingDegrees` and `speedMps` rendering, for the branch filter, and for the track reveal sending a purpose. A Java test that the reveal writes its audit fact. Specs for the courier-policy scope selector showing the inherited value beside an overridden one, and for `policyId`/`policyVersion` rendering.

### T16 · Courier types, rate cards and adjustment rules
**Rows** `3.4a` courier types (S) · `3.4b` rate cards (L) · `3.4c` bonus/penalty rules (L) — **2d**, after `P19`.

Types are create-only at every layer: no update or archive on the store, no `PUT`/`DELETE` endpoint, so a mistyped code or a wrong offer TTL is permanent even though `courier_types.status` already has `ARCHIVED`. Add them, send `maxDistanceMeters` (accepted, enforced by `CourierDispatchGate`, never sent), and add the two attributes with no column anywhere — starting minute and work mode.

Rate cards: the backend accepts all four component types with band ladders, gap and overlap validation at activation and `AccrualCalculator` pricing them; the form emits two. Add the `PER_KM_BAND` ladder editor and `PER_ORDER_MINIMUM`, render a card's components at all (`GET /rate-cards/{cardId}` has no caller, so a manager activates a card whose terms the console will not show him), render effective dates, and allow a second version and a narrower scope — `submitCard` hard-codes `cardVersion: 1` and brand scope, so a code whose v1 is ACTIVE cannot be re-priced.

Rules: there is no rule catalogue. `fulfillment.courier_adjustment_reasons` has kind and outcome basis and no amount, no condition and no evaluation; `insertAdjustmentReason` is called only by tests; and `POST /couriers/{courierId}/adjustments` has **zero callers in the console**, so a manager cannot record even a one-off manual penalty. Build the manual entry form first, then the reason registry, then the rule columns and an evaluator emitting `AdjustmentOrigin.RULE` from delivery outcomes — note that origin is client-supplied today, so a caller sending `RULE` bypasses the four-eyes branch.

**Migration** yes — reserved numbers **`V0259`–`V0261`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier-type starting minute and work mode; the adjustment-reason amount and condition columns.

**Capabilities** `COURIER_TYPE_MANAGE` for types; `COURIER_RATECARD_MANAGE` for cards and versions; a new `COURIER_ADJUSTMENT_RECORD` for the manual entry with the ADR 0042 four-eyes branch above threshold — and the evaluator must stamp `AdjustmentOrigin.RULE` **server-side**, never from the request, because a client-supplied `RULE` bypasses four eyes today.

**Tests** Java tests against the migrated schema for type update and archive, for `maxDistanceMeters` being enforced by `CourierDispatchGate`, for a second rate-card version under a narrower scope, for band gap/overlap validation at activation, and — the security one — that `AdjustmentOrigin.RULE` cannot be set from the request and so cannot bypass four eyes. Angular specs for the `PER_KM_BAND` ladder editor and for a card's components rendering before activation.

**Traps** ADR 0042's checklist marks rule adjustments implemented while its own body describes the evaluator as unbuilt; correct it.

### T17 · Shifts, attendance and bulk geozone upload
**Rows** `3.5` shifts and attendance (L) · `3.6c` bulk geozone upload (L) — **1d**, after `P19`.

There is no roster: only the shift a courier opened himself is visible, so planned-versus-actual cannot be compared. V0040 deliberately omits `courier_roster_entries` and `ENFORCED_WITH_ROSTER` appears nowhere in Java. Add the planned-shift model, the roster grid over `P02`'s ScheduleGrid, a period filter (the page fetches the most recent 200 shifts with no `from`/`to`), and render what the view already carries and the template drops: `breakSeconds` and `dutyState` — time on break is the central attendance figure — plus a courier name instead of a raw UUID. The shift-enforcement gate is real in `CourierDispatchGate` and cannot be switched from the console because the policy write lands in `P38`; sequence accordingly.

Bulk geozone upload is more than a file picker: ADR 0037 requires imported legacy geometry to land as DRAFT versions and be rendered on a map beside its source before activation, because a coordinate-order error yields a hemisphere-flipped polygon no containment test catches. So this row needs a batch import endpoint, a review surface, and the shadow comparison — and the review surface needs `X.4`. Ship the endpoint and the dry-run report now; gate activation behind the map.

**Migration** yes — reserved numbers **`V0262`–`V0264`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `courier_roster_entries`.

**Capabilities** `COURIER_SHIFT_READ` for the roster and the period filter, `COURIER_SHIFT_APPROVE` for the planned-shift writes; `DELIVERY_ZONE_MANAGE` for the batch geozone import, whose activation stays behind `DELIVERY_ZONE_ACTIVATE` and the map.

**Tests** Java tests against the migrated schema for the planned-shift model, the `from`/`to` period filter and the roster-versus-actual comparison; and for the batch geozone import landing every polygon as DRAFT and refusing activation without the map. Angular specs for `breakSeconds` and `dutyState` rendering and for a courier name replacing the raw UUID.

### T04 · Publication readiness, price books and bulk pricing
**Rows** `4.6` publication and channel readiness (M) · `4.8a` price books (M) · `4.8b` bulk price change (L) — **2d**, after `P03`.

The readiness report is a histogram, not a report: `findingsByCode()` collapses every finding to `{code, severity, count}` and the template renders «PRICE_MISSING ×12» — while `entityType`, `entityId`, `entityCode` and `detail` are all on the wire and discarded. An operator sees twelve broken items and cannot learn which twelve. Render them and deep-link each to the tab that fixes it. Add per-channel state on the channel card (it shows a name, a type chip and a button — no hash, no last-published time) and the draft-versus-live comparison, which is backend-shaped: nothing computes a draft's would-be content hash without writing a snapshot.

Price books can only be applied brand-wide from the console. `assignToLocation` and `assignToChannel` exist as client methods with zero callers against real endpoints, so the hall-versus-base plane and aggregator propagation cannot be expressed. Wire both, add the VAT/tax-profile screen (`PUT /tax-profiles/{jurisdictionCode}` has no wrapper at all and no route), let `priority`, `validFrom` and `validUntil` be authored, and stop gating assign and activate on `status === 'DRAFT'` in the template when the endpoint permits more.

`4.8b` is the DataGrid's second consumer: a filtered selection joined against current prices (the read primitive exists — `GET /price-books/resolved/prices`, capped at 200 ids), a percent/absolute calculator with rounding using `P02`'s MoneyOrPercent, a preview of what the change costs, and a **new server-side bulk-apply with a dry run** — composing it client-side as N optimistic-locked `PUT`s gives no atomicity and no partial-failure story.

**Capabilities** `CATALOG_READ` for the readiness report; `PRICE_AUTHOR` for the price-book assigns, the tax profile and the bulk apply; `CATALOG_PUBLISH` for the publish action.

**Tests** Angular specs for the readiness report rendering `entityType`/`entityId`/`entityCode`/`detail` per finding and deep-linking each; for `assignToLocation` and `assignToChannel` being reachable; and for assign/activate no longer gated on `status === 'DRAFT'` in the template. Java tests for the bulk-apply dry run and for its partial-failure report.

### T05 · Segments and promo codes
**Rows** `5.3` segments (M) · `6.2` promo codes (M) · `6.2a` per-instance unique codes (L) — **2d**, after `P02`.

Segments has a correctness bug that makes the feature read as broken: `segments-page.ts:322` passes its own descriptive string as the **consent purpose**, and `MarketingEligibility` matches that string exactly against recorded consent, so every candidate is refused `CONSENT_WITHHELD` and the member count reads ~0. Send a real purpose. Then stop discarding the diagnostic: the snapshot returns `candidates`, `members` and `excluded` precisely so «a marketer who sees only the reach concludes the audience is broken», and the page keeps only `members`. Render all three with a refusal breakdown, and wire the snapshot export so a segment can be read and downloaded rather than only sized.

Promo codes: the draft form has no `validFrom`, `validUntil`, `channels` or `locationIds` fields at all, although the request accepts them, the service validates them and the eligibility lookup enforces them — so every code created from this screen runs forever across every channel and branch. Add the fields and an expiry column. Then the redemption ledger: `pricing.coupon_redemptions` is written only, with no `SELECT`, no port method, no service and no endpoint, so `redeemedCount` is a counter with no drill-down.

`6.2a` is the migration: `pricing.benefit_grants` does not exist, so a one-off code only one named customer can redeem — the late-order apology — cannot be minted, and service recovery falls back on a shared code word anyone can spend. Build the table, the minting path and the redemption check; it is also the prerequisite for automations (`6.5`).

**Migration** yes — reserved numbers **`V0265`–`V0267`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `pricing.benefit_grants`.

**Capabilities** `SEGMENT_MANAGE` for the builder and the snapshot export; `PROMOTION_MANAGE` for the promo-code draft and its new fields; `PROMOTION_READ` for the redemption ledger.

**Tests** the regression first: a Java test that a segment snapshot with a **real** consent purpose yields a non-zero member count, and an Angular spec asserting the page no longer sends its own descriptive string as the purpose. Then tests for `candidates`/`members`/`excluded` all rendering with a refusal breakdown; for promo-code `validFrom`/`validUntil`/`channels`/`locationIds` being sent and enforced; and, against the migrated schema, for `pricing.benefit_grants` minting a code redeemable by exactly one named customer and refusing a second.

### T18 · Campaigns, loyalty and acquisition links
**Rows** `6.3` loyalty policies (M) · `6.4` campaigns (M) · `6.4b` couriers as an SMS audience (M) · `6.6a` trackable acquisition links (L) — **2d**, after `T05`.

Campaigns: only Telegram can deliver, and the create form offers SMS, EMAIL and PUSH with no warning, so an operator can spend a four-eyes approval on a campaign that dies inside the expansion scheduler with an exception no operator ever sees. Add an `isWired` flag to the campaigns read model and disable the unwired options. Add scheduled send — `CampaignStatus` declares `SCHEDULED` and the `APPROVED→SCHEDULED→SENDING` edges and nothing ever writes that status, and the request has no `scheduledAt`. Wire the two endpoints the console has and never calls: `POST /suppressions` (an operator can lift a suppression and cannot record one) and `getAudience`/`redefineAudience` (once defined, an audience's predicates can never be viewed or changed).

Loyalty: points expire silently. `expiryWarningDays` is authorable, validated and persisted, and nothing sends the message while `LoyaltySweeper.expireLots()` destroys the points on schedule. Add the ADR 0020 trigger. Add the backend validation the row needs more than the picker: a non-BRAND rule is only checked for carrying *some* UUID, and `accrual_rules.scope_id` has no foreign key, so a bad id persists and resolution silently falls back to the brand rule. Author `allowedChannels` and the validity window — both accepted, both never sent.

`6.4b` needs an audience shape that is not a customer: every recipient is a `customerAccountId` with a marketing consent purpose, so an operational blast to couriers has no model. `6.6a` needs `marketing.attribution_links`, which has no migration — mint a trackable `?ref=` link and a Telegram `startapp` deep link and record which link brought an account or an order.

**Migration** yes — reserved numbers **`V0268`–`V0270`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `marketing.attribution_links`; a foreign key on `accrual_rules.scope_id`.

**Capabilities** `CAMPAIGN_MANAGE` for scheduling, suppressions and audience redefinition (with the ADR 0027 four-eyes approval unchanged); `LOYALTY_POLICY_MANAGE` for the accrual rules; `MARKETING_LINK_MANAGE` for the attribution links.

**Tests** Java tests that an unwired channel cannot be chosen (`isWired` false for SMS, EMAIL and PUSH), that `APPROVED→SCHEDULED→SENDING` is writable and `scheduledAt` honoured, that a suppression can be recorded as well as lifted, that an audience's predicates can be read back and redefined, that `expiryWarningDays` fires before `LoyaltySweeper.expireLots()`, and that a non-BRAND accrual rule with an unresolvable `scope_id` is refused rather than silently falling back to the brand rule. Angular specs for the campaign form and the attribution-link minting.

### T06 · Branch and SLA reports
**Rows** `7.3` branch leaderboard (M) · `7.3a` SLA bucket distribution (S) · `7.3b` per-channel counts and the payment split (L) — **1d**, after `P27`.

Two of the three missing leaderboard columns need no backend at all: the delivery/pickup/aggregator triple is `groupBy=['LOCATION','FULFILMENT_TYPE']` (plus `CHANNEL` for the aggregator slice) over an aggregate already keyed by fulfilment type, and on-time percentage needs one registry metric for the denominator — `orders.late.v1` is computed and `promised_count` is already stored on `agg_branch_day`. Average delivery time is the real gap and waits for `T11`'s delivery fact. Add a secondary sort control and collapse the per-branch preparation-time fan-out, which costs one request per branch.

`7.3a` carries an arithmetic defect worth more than its cosmetics: `buildSlaRows` sums counts across days while overwriting `sharePercent` per day, so for the 7-day and month presets the rendered percentage is the **last day's share printed beside a whole-range count**. Fix it and add the median column. The tint ramp and the median already exist, contrary to the row's own wording — do not rebuild them. Tenant-configurable boundaries stay refused per ADR 0043; render the version instead.

`7.3b` splits: the per-channel count-and-average-check block is one extra query and a third table, and the payment split waits for `P39`.

**Capabilities** `REPORTING_READ`.

**Tests** the arithmetic first: a spec pinning `buildSlaRows` so `sharePercent` is the whole range's share and not the last day's printed beside a whole-range count. Then Java tests for the `['LOCATION','FULFILMENT_TYPE']` grouping, for the on-time denominator metric over `promised_count`, and for the per-branch preparation-time fan-out collapsing to one request.

### T11 · Courier reports and the delivery fact
**Rows** `7.4` courier leaderboard (L) · `7.4a` courier SLA buckets (M) · `7.4b` delivery-sum-by-tariff audit (M) · `7.4c` external-delivery cost report (L) — **2d**, after `T06`.

`/statistics/couriers` is a placeholder because reporting has no courier-attributed fact at all. The root cause is upstream and must be fixed first: `CourierAccrualService.recordDelivery` has **no production caller** — only a test — so `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entries are never written, which is also why Finance's cash worklist is permanently empty on a real tenant. Wire the acceptance-to-delivery path that calls it. Then add `reporting.fact_delivery` and its projector, the `COURIER` scope of `agg_sla_bucket_day` (the CHECK already allows it and `DayAggregator` hard-codes `LOCATION`), a courier dimension on the reporting endpoints, and the screen.

`7.4b` does **not** need `fact_delivery`: `fulfillment.delivery_fee_resolutions` already holds tariff, tariff version, zone, band and final fee, so a range query grouped by tariff and joined through quote→order→shipment answers the audit today. Add it with a supporting index on `(tenant_id, created_at, tariff_id)` — the current indexes are quote- and location-keyed.

`7.4c` is the one report in §7 that finds money: per-order «order amount vs charged delivery vs provider billed vs variance vs reconciliation status», a per-line reconcile action, and `UNBILLED` actually produced and excluded from the variance total — it is a dead enum constant today. The invoice-scoped half already renders in Finance 8.4; this is the per-order cut, and `fee_charged_som`/`variance_som` exist in the schema with zero Java readers.

**Migration** yes — reserved numbers **`V0271`–`V0273`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.fact_delivery`; the `COURIER` scope of `agg_sla_bucket_day`; an index on `delivery_fee_resolutions (tenant_id, created_at, tariff_id)`.

**Capabilities** `REPORTING_READ` for every report; the accrual wiring runs inside the delivery path as a system principal. The courier dimension must not leak a courier's protected name into a report — join on id and resolve the display through `P19`'s reveal, never through the fact.

**Tests** the root cause first: a Java test that the acceptance-to-delivery path calls `CourierAccrualService.recordDelivery`, writing `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entry — today only a unit test reaches it. Then tests against the migrated schema for `fact_delivery` and its projector, for the `COURIER` scope of `agg_sla_bucket_day`, for the tariff-audit query over `delivery_fee_resolutions` using the new index, and for `UNBILLED` being produced and excluded from the variance total.

### T12 · Staff and telephony reports
**Rows** `7.5` operator leaderboard (L) · `7.5a` operator product/upsell (L) · `7.5b` telephony KPIs (M) — **2d**, after `T08`.

`/statistics/staff` is a placeholder for a stated reason: `fact_order` carries no operator attribution. `ordering.orders.created_by_actor_*` and `accepted_by_actor_*` are built and the close job copies neither. Add `operator_principal_id` to the order fact (ADR 0043's own physical model lists it and V0031 did not implement it), a `Grain.Dimension` for it, and the leaderboard — orders taken, revenue, average check, per-channel, delivery versus pickup — with machine principals typed as pseudo-operators so the bot and the website can be compared against people. Names come from the staff-identity ADR; until it lands, render the typed principal kind and the subject, not a bare UUID with no explanation.

`7.5a` follows from the same column plus an operator × product endpoint over `fact_order_line`, and an aggregated receipt-depth metric — `item_count` and `line_count` exist per order and are rendered in 7.2, and no metric aggregates them.

`7.5b` is not blocked on telephony existing: `CallStatsController` serves offered/answered/missed/transferred and talk seconds per hour per operator off `reporting.fact_call_hour`, written inside the day-close transaction, and has **zero consumers**. Build the tab. Two of the three named KPIs genuinely have no source: answer speed has no ring/wait column in V0149 at all, and call-to-order conversion has no fact — the write-once `call-provenance` column exists and `1.6`'s wiring (`T-W01`) is what starts populating it.

**Migration** yes — reserved numbers **`V0274`–`V0276`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `operator_principal_id` on `reporting.fact_order`.

**Capabilities** `REPORTING_READ`. `operator_principal_id` is a principal id, not a person — rendering it needs no PII capability precisely because no name is attached until the staff-identity ADR lands.

**Tests** Java tests against the migrated schema that the close job copies `created_by_actor_*`/`accepted_by_actor_*` into `operator_principal_id`, that a machine principal is typed as a pseudo-operator rather than dropped, and that `CallStatsController`'s existing figures reach the new tab. A registry drift test for the receipt-depth metric.

### T13 · Customer analytics
**Rows** `7.6` KPI tiles with published formulas (L) · `7.6a` trends, cohorts, new versus returning (L) · `7.6b` RFM cross views (M) — **2d**, after `P27`.

`/statistics/customers` is a placeholder because `MetricRegistry` has no customer-grain definition, so `GET /queries` would refuse the metric ids outright. The grain, however, is already there and the row's own evidence understates it: `reporting.fact_order` carries `is_first_order` and `customer_subject_hash`, and `agg_branch_day` carries `distinct_customers` and `new_customers` with a CHECK between them. So new-versus-returning and a repeat-purchase distribution are derivable today without any `dim_*` table.

Register the metrics — new customers, distinct customers, repeat share, basket depth, order frequency, customer value, LTV — with their formulas published through the `/reporting/metrics` endpoint `P27` wires, so the tiles carry the «?» panel that was the credibility argument against Delever's unstated LTV. Add a cohort/retention read (there is no cohort query path and no dimension a caller could group by) and a revenue cut by `is_first_order`, which is expressible from the fact and not requestable through `/queries`.

`7.6b` is a reporting-side R×F cross-tab with member counts and revenue per cell, distinct from 5.3's segment builder — a marketer can size one segment today and cannot read the grid that says which cell is worth targeting.

**Capabilities** `REPORTING_READ`.

**Tests** a `MetricRegistry` drift test naming each new customer-grain metric and its published formula; Java tests that `/reporting/metrics` returns those formulas, that a revenue cut by `is_first_order` is requestable through `/queries`, and that the cohort read refuses a range longer than its retention window. Angular specs for the «?» panel on every tile.

**Traps** the acquisition chart and the pre-order funnel are `7.6c` and are blocked on a legal input; cohort heatmap and line charts need `T09`.

### T14 · Product analytics: ABC and XYZ
**Rows** `7.7` product report (S) · `7.7a` ABC with the cumulative column (L) · `7.7b` XYZ and the ABC×XYZ matrix (L) — **2d**, after `P27`.

`7.7` ships eight of the spec's nine columns and two inert controls. Add the `Категория` column (the API returns `categoryId` and the row model drops it), the `СТОП` row-severity marker, and — the defect worth more than both — make the page react to the filter bar: `load()` runs only from the constructor, so clicking a period pill changes nothing and the table silently keeps the previous range, and the fulfilment control is ignored entirely. Also disclose that DINE_IN lines are summed into the total and fall into neither split column, so delivery plus pickup need not add up.

ABC and XYZ are the analytic half. The substrate is closer than «zero»: `readVariantSales` already returns per-product net revenue ordered descending and the page already computes a per-row share, so the cumulative column is arithmetic over data on the wire. What is genuinely absent is the **persisted run**: `reporting.classification_run` and `classification_result` have no migration, and it is the recorded parameters and thresholds — 80/15/5 and the range — that let a class-C ruling be disputed, which is the stated improvement over Delever. Add the tables, the run endpoint, the ≥28-day range guard, the XYZ coefficient of variation, and the AX/CZ matrix as a click-through filter onto the product table.

**Migration** yes — reserved numbers **`V0277`–`V0279`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.classification_run` and `classification_result`.

**Capabilities** `REPORTING_READ` for the report; a new `REPORTING_CLASSIFICATION_RUN` for starting a persisted ABC/XYZ run, because a run writes rows and its recorded thresholds are what a disputed class-C ruling is defended with.

**Tests** Java tests against the migrated schema for a persisted run recording its thresholds and range, for the ≥28-day guard refusing a month-to-date preset shorter than 28 days rather than silently accepting it, and for the XYZ coefficient of variation on a known series. Angular specs for the cumulative column, the `Категория` column, the `СТОП` marker, and — the defect — the page reloading when the filter bar changes.

**Traps** the month preset is month-to-date, so the closest available range silently violates the 28-day floor rather than refusing it. The kiosk report (`7.7c`) is a recorded decline — kiosk must be readable as a channel slice instead, which needs the filter bar's channel control from `P27`.

### T15 · Marketing reports
**Rows** `7.9a` per-customer discount history (L) · `7.9b` campaign delivery and read statistics (L) — **1d**, after `T05`.

`7.9a` answers «how much has this customer been discounted» before another goodwill code is granted — the abuse check the report exists for. It cannot come from reporting: `fact_order.customer_subject_hash` is a one-way ADR 0029 hash, so a per-account total must come from `pricing.coupon_redemptions`, which is indexed by `(tenant_id, customer_account_id)` for exactly this query and has no read path. Add the store query, a port in `pricing.api`, an operations endpoint and both consumers — the customer detail pane from `P40` and this report.

`7.9b`: `GET /campaigns/{campaignId}/recipients` returns per-recipient status, notification id and refusal reason — a real base — and there is no aggregate. Add delivery counts (the store-level `recipientCounts` rollup exists and is internal-only) and the campaign tab. Read receipts have **no data source at all**: `NotificationStatus` has no `READ` and V0043 has no `read_at`, so «how many opened it» needs a column and a provider callback before it needs a screen. Say so on the screen rather than rendering a zero.

The promo-code summary itself (`7.9`) stays deferred — ADR 0023 forbids a report reading `pricing` directly, so it needs `reporting.fact_promotion_redemption`, whose grain needs a promotions ADR.

**Migration** yes — reserved numbers **`V0280`–`V0282`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `notification_deliveries.read_at` and the `READ` status.

**Capabilities** `REPORTING_READ` for the campaign statistics; the per-customer discount total is `CUSTOMER_READ` plus `PROMOTION_READ` and lives outside reporting, since `fact_order.customer_subject_hash` is one-way by ADR 0029.

**Tests** Java tests against the migrated schema for the per-customer discount total over `pricing.coupon_redemptions` using `ix_redemptions_customer`, and for the campaign delivery aggregate matching the per-recipient list. An Angular spec that the read-receipt figure renders as «no data source» rather than a zero.

### T07 · Finance: courier money
**Rows** `8.3` cash reconciliation (L) · `8.4` delivery cost reconciliation (M) · `8/X.5` partner invoice import and matching (M) · `8.5` courier payouts (L) — **2d**, after `P29` and `T11`.

All four screens render, and on a real tenant three of them render nothing, for one reason: `CourierAccrualService.recordDelivery` has no production caller, so `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entries are never written, `cashCollectedDuringShift` returns 0 on every shift, and `openCashHandover` never opens a handover. **`T11` owns that wiring and this wave is sequenced behind it** — the first draft told both waves to fix the same call site and to «coordinate», which is not a decision. Assume `CourierAccrualService.recordDelivery` has a production caller by the time this wave starts, and if it does not, stop and say so rather than wiring it a second time.

Cash: add courier identity, shift, location and declared-at to the worklist (the view carries them all and the table is five money columns), add the status and location filters the API already accepts, and add the IA's courier daily/shift totals by payment method and the bonus-paid amount that reduces cash due.

Delivery cost: there is no акт сверки workflow — import and match are the only mutations and neither has a caller, so an operator handed Yandex's monthly file has no way to load it or resolve a `VARIANCE`/`UNMATCHED_LINE` row. Add the import form and the match action (its provider-ref→shipment map is caller-supplied by design and needs a resolution UI), a dispute/accept-variance path, the charged-versus-cost margin, and translate `matchStatus`, which prints as a raw enum beside every other status that does not.

Payouts: the salary report's columns do not exist on the backend DTO — no km, no hours, no penalty/bonus split — so adding them is a `SettlementPeriodResponse` change, not a template edit. Render `grossEarningsMinor`, `adjustmentsMinor`, `cashHeldMinor` and `statementHash`, all on the wire and none bound, and wire the statement download: the endpoint exists, the path is declared, and no method calls it. Stop hard-coding `'UZS'` on the ledger balance.

**Capabilities** `FINANCE_READ` for the four worklists; `COURIER_CASH_RECONCILE` for the handover close; `DELIVERY_INVOICE_MANAGE` for the import and the match; `COURIER_PAYOUT_MANAGE` for the statement and its download.

**Tests** Java tests that a shift with recorded deliveries produces a non-zero `cashCollectedDuringShift` and opens a handover — the case that is empty on every real tenant today; for the invoice import and the provider-ref→shipment match including an `UNMATCHED_LINE` resolution; and for `SettlementPeriodResponse` carrying km, hours and the penalty/bonus split. Angular specs for the statement download and for `matchStatus` rendering localized.

### T19 · Finance: subscription and invoices
**Rows** `8.6` subscription and billing (L) · `8/X.4` invoices the merchant can read (M) — **1d**, after `P29`.

A merchant can see the plan it is on and do nothing about it. Three things are missing and one is smaller than it looks. Statements are **already tenant-scoped**: `CommercialStatementController` serves list, draft, single and a CSV export at `ScopeType.TENANT`, held by tenant-owner and tenant-finance — the console simply has no client and no screen. Add both: `commercial-api.ts` gains list/detail/download, `finance-paths.ts` gains the three paths, and a statements page renders lines and totals. Optionally alias the reads onto the operations prefix so the app stops calling `/control-plane/**` for its own invoices.

Genuinely absent, and each needing backend: a tenant-facing purchasable-module catalogue with inline purchase (the catalogue is `CommercialModuleController`, control-plane only), tenant-visible arrears state and the restricted-feature banner (`ArrearsController`, control-plane only), and period close. The prepaid wallet and credit expiry are `8/X.3` and are blocked on ADR 0095's acceptance.

Add the missing `.spec.ts` — `subscription-page` ships without one.

**Capabilities** the statement reads are already `ScopeType.TENANT` and held by tenant-owner and tenant-finance — reuse them unchanged; the purchasable-module catalogue and the arrears read need tenant-scoped mirrors of the control-plane capabilities, named in the wave's ADR note.

**Tests** Angular specs for the statements list, detail and CSV download against the already-tenant-scoped endpoints, and the missing `subscription-page.spec.ts`. Java tests for the tenant-facing module catalogue and the arrears read refusing another tenant.

### T08 · Staff: the audit log
**Rows** `9.3` activity log (M) · `9.3a` field-level diff (L) · `9.3b` a named human actor (M) · `9.3c` a bulk action produces N records (S) · `9.2d` created-by/accepted-by attribution (M) — **2d**, after `P30`.

The most valuable screen in the section is a column of UUIDs. Three fixes, in order of leverage.

**Names**: `actor_display` is null on nearly every row because ~99 call sites pass `ActorRef.user(subject, null)`. Do not backfill 99 call sites — add a read-time resolution step in `AuditQueryService`, which already selects `actor_display` in both queries, and every audit-consuming screen benefits at once (activity log, data-privacy, product-editor history). The name source is the staff-identity ADR; Keycloak's `firstName`/`lastName` are written by `KeycloakStaffAccounts.completeSetup` and read back by nothing, so a cached resolver over the admin API is the interim.

**Diffs**: `ChangeDocuments.change(field, before, after)` produces the `{before, after}` shape the viewer already parses and has **zero production callers**; ~130 of the 132 `.changed(...)` sites write flat after-only maps. This is a write-side migration, not a screen change. Normalise `TenantControlPlaneService`'s two hand-rolled root-level `{before, after}` maps into the per-field shape while you are there.

**Bulk correlation**: the grouping data is not there, contrary to the row's premise — `GrantAuditListener:43` correlates by the grant's **own id**, so twelve revokes carry twelve different correlation ids and pasting one into the filter returns one row. Add a correlation field to `GrantChanged`, accept the request `X-Correlation-Id`, have the listener use it, and have `staff-page.ts` mint one id across its `Promise.allSettled` fan-out. Only then the «часть массового действия» chip and the click-through.

Also: cursor paging (`Page.last(events)` always yields a null cursor, so past 200 events the operator is stuck), an action-code dictionary so «Что» stops printing `iam.grants.revoke`, a person picker instead of a subject-id text box, the four backend-ready filters the UI never exposes, a deep link from a person's card, and a `.spec.ts` — this is the only staff screen without one. `9.2d` adds the actor to the order detail and an actor-count aggregate.

**Migration** yes — reserved numbers **`V0283`–`V0285`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a correlation column on the audit fact.

**Capabilities** `AUDIT_READ` at `TENANT` scope. The name resolver runs **inside** `AuditQueryService` under the caller's own capability; it must not become a directory lookup anyone can call, and it must never resolve a name for a principal the caller could not otherwise see.

**Tests** Java tests that `AuditQueryService` resolves `actor_display` at read time for a row written with a null display, that it resolves **no** name for a principal the caller could not otherwise see, that `ChangeDocuments.change` output round-trips through the viewer's `{before, after}` parser, and that a bulk fan-out sharing one `X-Correlation-Id` yields N rows under one id — the premise the current `GrantAuditListener:43` breaks. Plus cursor paging past 200 events, and the screen's first `.spec.ts`.

### T20 · Staff: contacts, POS ids, Telegram links, self-service
**Rows** `9.2b` contact persons (M) · `9.2c` operator ↔ POS operator id (L) · `9/X.1` Telegram staff links (S) · `9/X.5` my profile (M) — **2d**, after `T08`.

Four rows that mostly wait on the staff person record, with three pieces that do not.

`9/X.1` ships now: no console screen issues a link code, so the only built path to a staff Telegram link is someone reading `POST .../staff/telegram/link-codes` out of the API by hand. Add a self-service card that hands the staff member her `/link <code>` command, and render the `telegramUserId` the console already receives. Unlinking is **missing in the backend**, not merely unwired — `TelegramStaffLinkService` has no revoke and V0105's header says revocation is check-at-tap against grants — so «unlink Aziza» needs an endpoint plus a screen, or an explicit decision that the grant revoke is the unlink.

`9/X.5` splits the same way: «Мои должности» is thin wiring over `GET /api/v1/session/context`, already in the browser, and the Telegram self-link above. Everything else — name, phone, email, sign-in history, active sessions, «выйти везде», PIN and MFA state — needs the staff profile store, the Keycloak session projection and the MFA decision, all deferred. The language switcher exists but is browser-local; say so rather than calling personalization built.

`9.2b` must be rescoped before it is built: the claim that «a manager cannot record who to call about a branch» is false — `tenant.locations.contact_phone` is editable in the console today with an E.164 check constraint. What is missing is a **named contact person** (person, role, phone) for a branch, and any contact detail attached to a staff member, which is ADR 0029 PII with no owning decision.

`9.2c` is a provider-neutral contract change, not a mapping row: `PosAdapter.OrderExport` has no operator field, so wiring an iiko operator id touches the export contract and each adapter, on top of the missing mapping table and the missing staff record it would hang from.

**Capabilities** `IAM_GRANT_MANAGE` for issuing and revoking a staff Telegram link code; `TENANT_READ` for «Мои должности», which is `GET /api/v1/session/context` and needs nothing more; `LOCATION_MANAGE` for a branch contact person.

**Tests** Java tests for issuing a link code and for the unlink endpoint (new — `TelegramStaffLinkService` has no revoke), including that a revoked link cannot be re-tapped. Angular specs for the self-service card rendering the `/link <code>` command and the `telegramUserId`, and for «Мои должности» reading only `GET /api/v1/session/context`.

### T09 · Chart family and KPI tiles
**Rows** `X.19` chart family (L) · `X.20` KpiTile and WallboardTile (M) — **2d**, after `P01`.

There is not one `<svg>` or `<canvas>` in the entire operations console, and no charting dependency. Every report answers in numbers and tables, so «is today going normally, and if not where» has to be read line by line.

Build the half that has data today and stop there: line, bar, stacked bar and donut over `/reporting/queries`' per-day rows; a histogram over the six fixed SLA buckets; an hour-of-day heatmap over `/reporting/demand-history`. Each needs axis, legend, tooltip and an accessibility affordance better than a `title` attribute, plus a dataviz palette in `frontend/design-tokens` — `tokens.css` has none. The two shapes that exist today are `[style.width.%]` divs duplicated in two files; replace both.

Funnel, cohort heatmap, ABC cumulative curve and geo heatmap have **no data source** and are not in this wave: they arrive with `T13`, `T14` and the deferred geography rows.

`X.20`: extract one `q-kpi-tile` from the overview's inline markup plus its `deltaOf`, and draw the sparkline from the per-business-date rows `/queries` already returns and `report-rollup.ts` discards. A today-so-far hourly series does not exist, so the sparkline is period-over-period, not intraday. `q-wallboard-tile` and the TV scale belong to `T23`.

**Capabilities** none — the chart family renders whatever its host screen already fetched.

**Tests** a spec per chart type over a fixed dataset asserting axis, legend and tooltip, plus a table-equivalent accessible affordance that is not a `title` attribute; a spec that the two replaced `[style.width.%]` implementations render identically to the new bar; and a dataviz-palette contrast check in both themes.

### T21 · Rule authoring components
**Rows** `X.25` ConditionBuilder, RuleList with priority reorder, RuleSimulator (L) — **2d**, after `P01`.

Five rule engines share one component and the console has none: promotions, automations, auto-add, dispatch rules and courier bonus/penalty. The only rules anybody can write today are segment predicates, hand-rolled inside `segments-page.ts`.

Extract that builder into `shared/ui/` as `q-condition-builder` — a typed condition catalogue, and/or grouping, value editors bound to `P02`'s money, percent, date and day-of-week inputs — plus `q-rule-list` with drag priority reorder and an enabled/disabled state, and `q-rule-simulator` taking a candidate input and rendering which rules matched and what they did. Re-point the segments page at the extracted components so there is exactly one implementation, and give each component a spec.

Two of the five consumers are closer than the row implies and should be named as the first real users once this lands: **promotions** needs only ADR 0018's `POST /promotions` and `/validate` — the engine, the condition catalogue and the priority tie-break already exist in `PromotionEvaluator`, so it is one controller plus one screen; and **dispatch rules** has its policy document (`DeliverySourcingPolicies.SOURCING`) already resolved through ADR 0030 and needs a write endpoint, which makes the `no backend exists` annotation on its placeholder route wrong. Automations and auto-add genuinely have nothing and stay deferred.

**Capabilities** none. The simulator runs against a candidate input the caller supplies and must not read live orders; a dry run that touches production data needs the host screen's own capability and is out of scope here.

**Tests** specs for the condition builder's and/or grouping and typed value editors, for rule-list priority reorder by drag and by keyboard, and for the simulator reporting which rules matched and what they did. A regression spec that the re-pointed segments page produces the same predicate JSON it produced before extraction.

**Traps** a dry run already exists for audiences (campaign estimate, segment snapshot); the simulator must not duplicate it, only generalise it.

### T22 · Timeline, frames, rich text, steps, OTP and the type stack
**Rows** `X.26` Timeline/DiffViewer/ActorChip (M) · `X.28` PhoneFrame siblings (M) · `X.31` RichTextEditor (L) · `X.33` Steps/ProgressRail (S) · `X.38` OtpInput (S) · `X.40` dir/script-safe type stack (S) — **2d**, after `P01`.

Six component rows that share one property: each is re-implemented per screen or not at all.

`X.26`: the same `actorLabel(event) => actorDisplay ?? actorSubject ?? '—'` appears in three files. Extract `q-actor-chip` (human versus service versus job versus migration principal), `q-timeline` (the order detail's hand-rolled lanes and the audit log's event list are the same idea) and `q-diff-viewer`. The names themselves come from `T08`.

`X.33`: build `q-steps` and give it a first consumer that already needs it — the two-step connect-provider drawer in Settings → Integrations renders no step indicator at all — plus the order lifecycle rail over the existing timeline endpoint.

`X.28`: `PhoneFrame` does not exist in Angular either, so build the base and the three siblings. Note that `AggregatorCardFrame` and `KioskFrame` have nothing real to preview until ADR 0040's projection and a kiosk configuration exist; ship the frames against the storefront projection only.

`X.31`: a sanitized **block** editor, not a textarea — the parity behaviour being replaced is Delever accepting raw tenant HTML, which is the XSS surface HorecaOS closes. Its consumers (static pages, news, vacancy bodies) are deferred, so its first honest consumer is the terms document, which ships plain textareas today.

`X.38`: build `q-otp-input` — six segmented cells, paste handling, auto-advance and auto-submit, screen-reader labelling. It moved here from `W05`, where it sat beside an XL split-tender row it has nothing to do with. Build the **component only**: there is no staff second-factor endpoint today, the storefront's phone-OTP session is a different surface and a different principal, and whether the factor is enrolled in Keycloak or modelled on the platform is the staff-identity ADR's call. The component is useful either way and is what a future terminal PIN screen reuses.

`X.40`: fix the drift the lint catches. **`P02` adds the ESLint config and the rule banning raw `px` font sizes** — this wave does not add them a second time; it fixes the 249 raw `font-size` declarations that rule fails on, 38 of which use sizes absent from the closed scale. Then normalise the uz-Latn catalogue's mixed U+02BB/U+2019 apostrophes, and record what a fourth locale actually costs — two enums, three all-locales-required validators, a hard-coded reference list, a SQL fallback CASE and nine append-only CHECK constraints, one of which already disagrees with the others about `uz` versus `uz-Latn`.

**Capabilities** none. `q-otp-input` is a component only — the staff second-factor endpoint it will bind to is the staff-identity ADR's, and this wave must not invent one.

**Tests** specs for `q-actor-chip` across human, service, job and migration principals; for `q-timeline` rendering both the order lanes and the audit event list from one component; for `q-diff-viewer` on a per-field `{before, after}` document; for `q-steps` in the two-step connect-provider drawer; for `q-rich-text` **rejecting** a script tag and a javascript: href, which is the row's whole point; and for `q-otp-input` paste, auto-advance and screen-reader labelling. `X.40` adds the lint fixture and a test that the uz-Latn catalogue uses one apostrophe codepoint.

### T10 · Drafts and abandoned carts
**Rows** `1.4` drafts and abandoned carts (S) — **1d**, after `P03`.

A small screen with a misleading number. The by-channel breakdown counts every draft including ACTIVE carts under a heading that reads «Отказы по каналам», so a live in-progress basket is reported as an abandonment; and it emits raw counts with no denominator, while the reason the screen exists is a **rate** («the Telegram bot loses 40% of baskets»). Fix the classification and show the rate.

Then the filters: orders.md §6 requires period, channel, location and owner type, the backend already accepts `from`, `to` and `channelId`, and the page calls `list(scope)` with an empty query. Add them, and render the two spec'd columns already on the response — location and `expires_at`. Do not rely on backend ordering for «newest first»; sort explicitly.

Two limits to keep: converting a cart into an order is deliberately not offered — nobody agreed to that basket — and the first-line product preview needs `ordering.cart_lines` to snapshot a name, which it does not. The ADR 0044 recovery-audience hand-off belongs with automations and is deferred.

**Capabilities** `ORDER_READ` — a cart is an order in draft and the existing list already gates on it.

**Tests** Angular specs that an ACTIVE cart is excluded from «Отказы по каналам», that the rate is shown with its denominator, that `from`/`to`/`channelId` reach the query, and that ordering is explicit rather than inherited from the backend.

### W01 · Reservations, dine-in sessions and telephony
**Rows** `1.5` reservations (M) · `1.5a` seat/complete (L) · `1.6` call centre (S) · `X.37` CallBar and CallScreenPop (L) — **2d**, after `P02` and `P32`.

Reservations is a real ADR 0047 build with three holes. The day window is hard-coded 08:00–23:00 and computed in the **browser's** timezone, so a branch in another zone gets a shifted day and bookings past 23:00 fall outside the grid — bind it to the location's service schedule, which `P43` makes readable through the `GET` it adds to `ServiceScheduleController` (`P32` is this wave's merge dependency for the locations folder; `P43` is the one that supplies the read). The guest is not merely uneditable, it is invisible: `ReservationResponse` carries no name, phone or note by design, so a host cannot match a walk-in to a booking; add them to the response and to `AmendmentRequest`, which accepts them on create and not on amend. And the detail pane prints raw table UUIDs where the grid prints table codes.

`1.5a`: seating needs the dine-in session surface — `TableSessionController` exposes create, rounds, state-actions and force-closures with **zero frontend callers** — so add a «seat this booking» action and `COMPLETED` to the target-status union. No backend work. The row's other two sub-features contradict ADR 0047 and should be struck from the IA, not built.

Telephony: the screen is real and misses one join. `POST .../{orderId}/call-provenance` (write-once, `ORDER_PROVENANCE_RECORD`) has **zero callers anywhere**, so a claimed screen-pop card is never linked to the order it produced and every operator KPI loses that join — add `recordCallProvenance` and call it from the order-taking path with the claimed `callEventId`. `roster()` is dead code: the screen marks only the operator's own presence, with no team board showing who is ONLINE/PAUSED/WRAP_UP. And the pop cannot follow an operator: lift the presence and current-call poll into a service and put a call bar in the shell — the call centre is not even in the rail today, reachable only from a tab on the orders queue.

**Capabilities** `RESERVATION_MANAGE` for create, amend and seat; `DINE_IN_MANAGE` for the table session actions; `ORDER_PROVENANCE_RECORD` for the write-once call-provenance link; `VOICE_PRESENCE_MANAGE` for the roster and the operator's own presence. Guest name and phone on `ReservationResponse` are ADR 0029 PERSONAL — they arrive behind the same reveal the rest of §5 uses, not as plain fields.

**Tests** Java tests that the reservation day window comes from the location's service schedule and not the browser (pin a location in another timezone and a booking after 23:00), that `AmendmentRequest` accepts guest name, phone and note on amend as it does on create, and that `recordCallProvenance` is write-once. Angular specs for seat-this-booking through `TableSessionController`, for table codes replacing raw UUIDs, and for the shell call bar surviving navigation.

**Traps** a softphone is a recorded decline, not a gap. There is no push channel for voice, so a console-wide bar means every operator polling from every screen until ADR 0045 covers it.

### W02 · Demand forecast
**Rows** `7.8` forecast versus actual (XL) · `7.8a` by department and product (L) · `7.8b` holiday-aware modelling (L) — **2d**, after `P27`.

What ships today is honest history, not a forecast: a same-weekday, same-hour average of completed orders for the operator's own location, refusing to show an average below the minimum sample size and never using the word «forecast» in an operator-facing string. The owner decided that on 2026-09-05 and ADR 0043 keeps the seasonal-naive model, `forecast_run` and `fact_forecast` unbuilt.

This wave is the decision's other half. Build `forecast_run` and `fact_forecast`, the seasonal-naive model with a confidence interval, and the forecast-versus-actual comparison — then the three screen gaps: a branch selector (frontend-only; the endpoint already takes an arbitrary `locationId` under a tenant-scoped capability), an operating-day hour axis instead of wall-clock 00:00–23:00, and the per-department and per-product breakdown, which needs `fact_order_line` to carry a timestamp it does not have.

`7.8b`: the calendar already exists — `tenant.public_holidays` (V0203, ADR 0090) with a read API — and reporting never joins it, so Navruz enters the same-weekday average as an ordinary Saturday with nothing marking it. Add a holiday flag per sample date, keyed by the location's country, and an exclude-or-weight option on `/reporting/demand-history`. ADR 0043's own holiday factor with `reporting.holidays` and `calendar_version` is the larger, separate item.

**Migration** yes — reserved numbers **`V0286`–`V0288`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.forecast_run` and `fact_forecast`; a holiday flag per sample date.

**Capabilities** `REPORTING_READ` for the forecast and the comparison; the run itself is a scheduled system job.

**Tests** Java tests against the migrated schema for `forecast_run` and `fact_forecast`, for the seasonal-naive model's confidence interval on a known series, for the operating-day hour axis, and for a Navruz sample being flagged and excludable. An Angular spec that the branch selector changes the series and that the filter bar is either wired or hidden — not rendered and ignored.

**Traps** the filter bar renders above this tab and the page ignores it entirely — either wire it or hide it. Cook headcount (`2.6a`) is the consumer waiting on this plus a portions-per-cook-hour policy, and stays blocked on the policy.

### W04 · Geography: histograms and the week grid
**Rows** `7.10b` delivery-time and distance histograms (L) · `7.10c` day-of-week × hour cohort grid (M) — **1d**, after `W02`.

§7.10 has no route, no nav entry and no placeholder — unlike every other unbuilt reports section, it is not even honestly surfaced. Add the route and the tab, then the two sub-rows that need no map.

The cohort grid is the cheapest thing in §7: seven `demand-history` calls, one per weekday, for the selected location, coloured by average orders — no new endpoint, no new SQL, no new fact. Cell drill-down is the only backend piece and it is thin: `/reporting/orders` has no weekday or hour-of-day filter, so either add one or filter client-side on the `occurredAt` the response already returns, capped at 300 rows.

The histograms need distances and durations in reporting. `fulfillment.courier_assignment_earnings.distance_meters` holds them and nothing copies them across, so this row waits on `T11`'s delivery fact; build the component against the SLA buckets first and widen it when the fact lands. Today the only duration view in the section is the fixed six-bucket table per branch, so a long tail of far or slow deliveries is invisible.

The map-shaped rows — the density heatmap and today's orders as pins — stay deferred with `X.4`.

**Capabilities** `REPORTING_READ`.

**Tests** Angular specs for the seven-call week grid rendering and for cell drill-down capped at 300 rows; a Java test if the weekday/hour filter is added server-side rather than client-side. A spec that the histogram renders against SLA buckets today and widens when `T11`'s fact lands.

### W03 · Privacy self-service, access check and approvals
**Rows** `10.11` data and privacy (L) · `9/X.4` access check (M) · `9.4` approvals worklist (M) — **2d**, after `P30`.

`10.11`'s headline gap is wrong in the merchant's favour: the tenant-scoped DSAR erasure backend is **fully built** — raise, history, execute under its own tighter capability, and cancel, over V0178 and `CustomerErasureService` — and the only screen showing those requests is the control plane. So this is a missing screen, not a missing backend; `P40` adds the per-customer controls and this wave adds the tenant-wide worklist. Genuinely missing: a tenant read and write for its own retention periods (the only surface is `PLATFORM`-scoped), a consent-type registry (no table anywhere, which is also what breaks `5.2b`'s purpose vocabulary), and data-subject **export** and **correction**, which ADR 0029 still lists as out of scope. Fix the page's stale copy — it renders the DSAR card as not-built quoting a sentence ADR 0029 no longer says, and describes two retention rules as unenforced that `CartRetentionSweeper` and `CourierApplicantRetentionSweeper` now enforce; its spec asserts the stale text, so the test changes too.

`9/X.4` is screen 9.5 in its own spec, fully designed, scheduled and unimplemented. The engine exists as `GET /control-plane/access-debugger` and is `PLATFORM_ADMIN`-only, so add a tenant-facing `access-check` under `IAM_GRANT_MANAGE` with scope containment, fold in the entitlement branch (`ENTITLEMENT_REQUIRED` versus `INSUFFICIENT_CAPABILITY`, which `EntitlementQueryService` computes and this endpoint would not), return the reason chain — which grant, at which scope, since when — and link it from every denied state `P30` builds.

`9.4`: two of the IA's four named cases cannot appear because nothing raises them — `CustomerController.export` records the reveal and does not gate it, and there is no discretionary order-line discount producer. Add a decided-history read (only pending exists), and note the scope limitation the controller already documents: approvals are TENANT-scope only, so a brand or location manager has no worklist.

**Migration** yes — reserved numbers **`V0289`–`V0291`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the consent-type registry; tenant retention periods.

**Capabilities** `CUSTOMER_ERASURE_RAISE`/`CUSTOMER_ERASURE_EXECUTE` for the tenant-wide DSAR worklist; `TENANT_CONFIGURATION_WRITE` from `P31` for the retention periods; `IAM_GRANT_MANAGE` for the tenant-facing access check, which must enforce scope containment so a brand manager cannot probe another brand; `APPROVAL_DECIDE` for the approvals worklist.

**Tests** Java tests for the tenant-wide DSAR worklist, for tenant retention read and write, for the consent-type registry, and for the access check enforcing scope containment — a brand manager probing another brand must be refused, not answered. Update `data-privacy-page.spec.ts`, which currently asserts the stale copy this wave deletes.

### W05 · Split tender: the payment[] array
**Rows** `8/X.1` the payment[] array (XL) — **2d**, after `P29`.

Split tender is less missing than it reads. `OrderSettlementService` really models an ordered set of tenders with a sum-equals-total check and refunds that unwind in reverse sequence, and `CheckoutSettlementPlanner` is a live production caller that plans a loyalty-points leg beside the money leg — so a points-plus-cash split is already written to `payments.order_settlements` and `payments.tenders` at checkout. What is missing is entirely operator-facing: expose the settlement's tenders as the `payment[]` array on the operations order-payment read, with per-tender status and refunded amount, and render that array where `payments-page.html:60` currently prints a «split tender not built» note. Optionally add an operator path to record a redemption leg. A **deposit** tender does not exist at all and stays blocked with `5.2f` and `6.3a`.

`X.38` OtpInput moved to `T22`, the component wave: it is a design-system row that happened to be filed next to staff MFA, and a small component does not belong beside an XL settlement row.

**Capabilities** `PAYMENT_READ` for the tender array; recording a redemption leg is `LOYALTY_ADJUST`, which already carries the ADR 0027 approval.

**Tests** a Java test that the operations order-payment read returns the settlement's tenders in sequence with per-tender status and refunded amount, and that a refund unwinds in reverse sequence. An Angular spec replacing the «split tender not built» note at `payments-page.html:60`. Assert that no deposit tender enum value is introduced.

**Traps** do not model the deposit tender «for later» — ADR 0046 withdrew it outright and a speculative enum value is what produced the `UNBILLED`-style dead constants elsewhere in this map.
