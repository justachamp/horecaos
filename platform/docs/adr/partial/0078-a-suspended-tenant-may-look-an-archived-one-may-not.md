# ADR 0078: A suspended tenant may look; an archived one may not

- Decision status: Accepted
- Implementation status: Partial — the rule is enforced and the quota is
  durable. `iam.api.TenantAvailability` gives authorization three states,
  answered by `JdbcTenantSuspensionLookup` from `tenant.tenants.status` and
  cached for thirty seconds (ADR 0033, `tenant.status`) with the writer
  evicting; `Capability.isRead()` classifies the 47 read capabilities and
  `theReadCapabilitiesArePinned` holds that set against a hand-written list;
  `JdbcAuthorizationService.applicableGrants` keeps read grants under
  `READ_ONLY`, keeps nothing under `CLOSED`, and keeps `PLATFORM` grants under
  both; V0182 adds `tenant.suspended_read_log` and
  `SuspendedTenantReadQuota` rations a suspended tenant to three requests per
  endpoint per ninety days from `CapabilityEnforcementInterceptor`. Not built:
  the quota covers HTTP only, so a suspended tenant's reads through a non-web
  caller are unrationed — today that is the staff Telegram bot, whose reads are
  single-order lookups rather than enumerations, which is why this ships anyway
  and says so; no sweeper trims `suspended_read_log` beyond the window; and
  nothing yet tells a suspended tenant's user *why* an action vanished (see
  Consequences).
- Date proposed: 2026-09-08
- Date decided: 2026-09-08
- Deciders: platform owner (answered both of ADR 0077's open inputs and set the
  quota at three per three months), Claude (architecture)
- Depends on: 0003, 0025, 0027, 0029, 0031, 0033
- Supersedes / Superseded by: Supersedes 0077
- Open inputs: none

## Context

ADR 0077, accepted the same day, refused a suspended tenant everything: its
grants did not apply, full stop. It recorded two questions it deliberately did
not answer, because both were the owner's — whether `ARCHIVED` should behave the
same way, and whether a suspended tenant's own people keep any read access.

The owner answered both, and the answers do not fit inside 0077's rule:

- **Archived: no read access.** Archival ends the relationship. 0077 had left
  `ARCHIVED` unenforced rather than settle a retention question inside a grant
  filter; the answer is that an archived tenant's people reach nothing.
- **Suspended: read only, and no export.** A suspended restaurant can still look
  at its own orders and its own numbers. It cannot change them, and it cannot
  take them.

The second answer is the one with teeth. "Read only" sounds like a narrowing and
is also a concession: 0077 gave a suspended tenant nothing, and this gives it a
door. Left uncounted, that door is an export channel — a listing endpoint called
in a loop yields the same customer book a download button would, and the party
whose access is being wound down is precisely the one with a reason to empty the
drawers on the way out. The owner set the price of the door at the same time as
opening it: **three requests per list method per three months.**

## Decision

**Three states, not two.**

| State | Tenant status | What applies |
|---|---|---|
| `OPERATING` | `PROVISIONING`, `ACTIVE` | every grant |
| `READ_ONLY` | `SUSPENDED` | read capabilities only, rationed |
| `CLOSED` | `ARCHIVED` | nothing tenant-scoped |

`PLATFORM`-scoped grants and the platform-admin realm role survive all three, so
neither state is a one-way door: the party that suspended or archived a tenant
can still read it, audit it, and reverse it.

**A read is a capability whose action segment is exactly `read` or ends in
`.read`.** Everything else is a write for this purpose, including the actions
that take data out without changing it — `track.reveal` unmasks a courier's view
of a customer, `upload` moves bytes — because an export is not made harmless by
being read-shaped. The rule is a suffix test and suffix tests are how a
data-taking capability quietly becomes a look, so the complete set is pinned in
a test against a hand-written list: a new capability the rule would classify as
readable fails the build until somebody agrees it is one.

**A suspended tenant gets three requests per endpoint per ninety days.**
Counted per `(tenant, endpoint)` and never per principal — a per-person quota is
bought off by inviting a colleague. Counted durably in `tenant.suspended_read_log`
rather than in the platform's existing in-process token bucket, because that
bucket is one JVM's heap: it empties on restart and a second replica keeps its
own, so a deploy would hand out three more reads. Append-only rather than a
decrementing counter, because the question afterwards is "what did this tenant
read while suspended, and when", which an integer cannot answer.

The quota runs in the web layer, after the capability check and the scope check.
That order matters for the same reason those two already have one: ahead of
them, a caller with no grant at all could burn a real tenant's allowance by
guessing its identifier.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep 0077's rule — a suspended tenant gets nothing | The owner's answer. A restaurant suspended over an invoice still needs to see what it took last week, and cutting that off makes suspension indistinguishable from termination | The owner reverses the answer |
| Ration only endpoints explicitly marked as enumerating | Sharper — a single-order lookup would not count — but it needs every listing endpoint on the platform annotated, and the ones that get missed are the hole. A rule that covers everything and over-restricts is safer than one that covers what somebody remembered to mark | Enough of the API is annotated for coverage to be provable rather than hoped for |
| Reuse `InProcessRateLimiter` | It is a continuously refilling token bucket in one JVM's heap. Correct for "stop hammering this endpoint"; wrong for a ninety-day allowance, where a restart silently refills it and a second replica doubles it | The platform gains a shared durable rate limiter and this becomes a client of it |
| A quota per principal rather than per tenant | Bought off by inviting a colleague | Never |
| Classify read/write with an explicit field on all 160 capabilities | More precise than a suffix rule and a 160-line edit whose reviewers would rubber-stamp it. The pinned-set test gets the same review gate at a tenth the size | The action vocabulary stops being a reliable signal |
| Let `ARCHIVED` keep reads, like `SUSPENDED` | The owner's answer is explicit: archived means no read access | The owner reverses it, or ADR 0029's export obligation requires a post-archival path — which would be a platform-side export, not a tenant-side login |

## Consequences

### Positive

- Suspension is now proportionate: a tenant that owes money can still look at
  its own business, which makes suspension a lever rather than a cliff.
- The export route that concession opened is closed by a counter that survives a
  restart, a deploy, and a second replica.
- The read/write line is one method with a pinned test, rather than a judgement
  repeated at every call site.
- `viewFor` offers exactly what still works, so a frontend renders a suspended
  tenant honestly instead of a menu of failures.

### Negative

- **Three per endpoint per ninety days is severe, and it applies to single-item
  reads too.** Looking at one order four times in three months is refused. The
  alternative was annotating every listing endpoint and living with the ones
  that got missed; this errs towards the tenant being frustrated rather than the
  data walking out, and the revisit trigger is in the table above.
- A suspended tenant's user sees `INSUFFICIENT_CAPABILITY` for a vanished write
  and `RATE_LIMIT_EXCEEDED` for an exhausted read. Neither says "your tenant is
  suspended", deliberately — that state is not something to disclose to whoever
  holds the session — so support will field "my permissions disappeared" and
  will need the audit trail to answer it.
- Every capability check now asks a second question, and every read by a
  suspended tenant now writes a row. The first is cached; the second is a write
  on a read path, bounded by how little a suspended tenant may read.
- `suspended_read_log` grows without a sweeper. The rows are evidence and are
  meant to outlive the window, but nothing trims them ever, which is a retention
  gap ADR 0029 will have to name.
- The quota is HTTP-only. A non-web caller reading for a suspended tenant is
  unrationed; today that is the staff bot, and its reads are lookups rather than
  listings.

### Accepted trade-offs

- Fail-safe towards allowing: an unknown tenant, or an unrecognised status, is
  `OPERATING`. A lookup failure must not close a trading restaurant.
- The window is ninety days rather than three calendar months. A rolling window
  needs a length.
- Thirty seconds of staleness at worst on the state itself, and only if the
  evicting write is lost.
