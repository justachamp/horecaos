# ADR 0076: New rows get time-ordered identifiers, and old rows keep theirs

- Decision status: Proposed
- Implementation status: Not started
- Date proposed: 2026-09-07
- Date decided: —
- Deciders: Ayubkhon Abbosov (platform owner, raised the question); Claude (architecture)
- Depends on: ADR 0031 (cursor pagination), ADR 0029 (PII), ADR 0056 (tenant
  isolation), ADR 0054 (build-time quality gates)
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a creation timestamp embedded in a `customer.customer_accounts` id
    is acceptable disclosure — owner (Ayubkhon Abbosov) with legal. A v7 id
    tells anyone holding it when the row was made. For an order that is a fact
    the holder already knows; for a customer account it is a signup date
    readable by any tenant staff member who can see the id. Proposed below as
    **v7 everywhere except where the record's own Specification excludes it**,
    but this one is the owner's to confirm.
  - Whether the generator is a dependency or ~20 lines of this platform's own
    code — owner. Proposed as **our own**, for the reason in Alternatives.

## Context

Every identifier this platform mints is a UUIDv4. There are 315 call sites of
`UUID.randomUUID()` in `src/main/java` and one schema default,
`DEFAULT gen_random_uuid()` (V0077), which is also v4.

v4 is 122 bits of randomness with no structure. As a primary key that means every
insert lands at a random point in the index. On a B-tree that is the pathological
case: the right-hand edge is never hot, pages split across the whole index rather
than at one end, and the working set that must stay in shared buffers is the
*entire* index rather than its most recent pages. The cost is invisible while a
table is small and compounds as it grows, which is exactly the shape of a defect
that ships fine and hurts a year later.

Two facts settle how this could be done, and both were checked rather than
assumed:

- **PostgreSQL 18.6 provides `uuidv7()` natively** — verified against this
  project's own database, no extension required. `SELECT uuidv7()` returns a
  well-formed v7 today.
- **JDK 25 provides no v7 generator.** `java.util.UUID` has no such method in
  25.0.4, and there is no UUID library in `platform/pom.xml`.

So the database could mint them for free and the application cannot, which
matters because **this codebase mints ids in the application on purpose**: a
service needs the id before the row exists, to reference it from a sibling
insert, to return it to a caller, and to put it in an ADR 0032 event published in
the same transaction. Moving generation into the database would mean
`INSERT … RETURNING id` at every one of those sites and a redesign of every
method that currently takes an id as a parameter. That is a far larger change
than the one this record is about.

The codebase also does not use UUIDs for one thing. It uses them for three, and
they have different requirements:

1. **Row identity.** The 315 `randomUUID()` sites, most of them a primary key.
2. **Deterministic derived ids.** Nine production sites use
   `UUID.nameUUIDFromBytes` over a stable string — the digest scheduler, delivery
   assignment and quote ids, the role registry, voice event and operator presence
   ids. These are v3, and their whole purpose is that the same input yields the
   same id, which is how they deduplicate.
3. **Internal lease and fencing tokens.** `claim_token`, `processingToken`,
   `leaseToken` in the outbox, inbox, onboarding steps and notification store.
   These are compared inside a `WHERE` clause by the worker that minted them and
   are never presented by an external caller.

A change that treats all three as one thing breaks the second and misjudges the
third.

## Decision

**New rows get v7. Existing rows keep the ids they have. Nothing is backfilled.**

Concretely:

- Row identity is minted by one platform generator returning RFC 9562 v7.
- Deterministic derived ids stay exactly as they are. They are not identity in
  the sense this record is about; they are a hash with a UUID's shape.
- Lease and fencing tokens stay v4. They gain nothing from ordering and there is
  no reason to touch working code.
- **No migration rewrites an existing id.** Every primary key in this schema is
  referenced by foreign keys, snapshotted into immutable publication documents,
  embedded in ADR 0032 event payloads that consumers have already read, and
  written into audit facts that exist to be unalterable. Rewriting them is not a
  migration, it is a data loss event with extra steps.

The consequence to hold on to: **a column will contain both versions, and only
the v7 half is time-ordered.** That is acceptable, and it is also the single
thing most likely to cause a bug — see Consequences.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave everything v4 | The cost is real and grows with the tables that grow fastest. It is also cheap to stop paying on new rows, and the alternative to acting is acting later against more data | — |
| Backfill existing ids to v7 | Every primary key is referenced by foreign keys, copied into immutable publication documents, embedded in already-consumed event payloads, and written into audit facts whose point is immutability. A backfill is a rewrite of the entire database with no way to reconcile what external consumers already hold | Never |
| Generate in the database with `DEFAULT uuidv7()` | Free, native in PG 18.6, and wrong for this codebase: services need the id before the insert — to reference from a sibling insert, to return, and to put in an event published in the same transaction. It would mean `INSERT … RETURNING` at hundreds of sites and a redesign of every method taking an id parameter | Generation moves out of the application for other reasons, or for a table whose id is genuinely never needed before the row exists |
| Add a UUID library (`uuid-creator` or similar) | v7's layout is 48 bits of millisecond timestamp, 4 bits version, 12 bits `rand_a`, 2 bits variant, 62 bits `rand_b`. That is a short, testable piece of code against a published RFC. A dependency for it buys little and costs supply-chain surface — and this platform's own scheduled audit currently fails on eleven transitive artifacts, so the appetite for one more is low | The hand-rolled generator proves subtly wrong under concurrency in a way a maintained library has already solved |
| ULIDs, or a `bigint` sequence | Both order better than v4. A `bigint` also leaks row counts and makes ids guessable across tenants, which ADR 0056 exists to prevent; a ULID is not a `uuid` column type and would mean changing every column, every foreign key and every serializer. v7 is the one option that is a drop-in for the type already in the schema | — |
| Change all 315 sites in one commit | It would conflict with every branch in flight and be unreviewable. Worse, it would apply v7 to the deterministic and token uses that must not have it | — |

## Consequences

### Positive

- Inserts on the high-volume tables land at the right-hand edge of the index
  rather than scattering, so page splits concentrate and the hot working set is
  the recent pages rather than the whole index.
- `ORDER BY id` becomes approximately chronological for new rows, which makes
  ADR 0031's keyset pagination cheaper: a cursor on the primary key alone
  approximates `(created_at, id)` without the second column or the second index.
- The generator is one place, so the next question about identifiers has one
  place to be answered.

### Negative

- **A column will hold both versions, and the ordering property is a half-truth
  until every v4 row has aged out.** Any code that treats id order as time order
  is correct for new rows and silently wrong for the back catalogue. This is the
  trap, and it is worse than having no ordering at all, because it will pass
  every test written against fresh fixtures. Keyset pagination must therefore
  keep ordering on `(created_at, id)` and not switch to `id` alone — the cheaper
  cursor is a benefit for *future* data, not a change to make now.
- **A v7 id tells its holder when the row was created**, to the millisecond. An
  order id in a URL discloses a fact the customer already knows. A customer
  account id discloses a signup date. That is an ADR 0029 question and it is the
  first open input.
- 315 call sites is a large mechanical change that will conflict with anything
  in flight, which is why the rollout below is per-module rather than one commit.
- A generator that does not handle two ids minted in the same millisecond loses
  the ordering it exists for, in exactly the burst conditions where it matters.

### Accepted trade-offs

- **The benefit is invisible today and unmeasurable on the current data volume.**
  No table here is large enough for index locality to show in a query plan. This
  is a decision taken on the shape of the growth rather than on a measurement,
  and that is stated plainly rather than dressed up with a benchmark that would
  prove nothing at this size.
- **Two id versions in one column is permanent**, short of the backfill this
  record refuses. A reader of the schema years from now will find both and must
  be able to learn why from here.

## Specification

### The generator

One class, `uz.horecaos.platform.configuration.Ids` (or the module the owner
prefers), with `Ids.newId()` returning an RFC 9562 v7:

- bits 0–47: milliseconds since the Unix epoch, big-endian;
- bits 48–51: version, `0b0111`;
- bits 52–63: `rand_a`, used as a monotonic counter within a millisecond;
- bits 64–65: variant, `0b10`;
- bits 66–127: `rand_b`, from a `SecureRandom`.

Within one millisecond the generator increments `rand_a` rather than re-randomising
it, so a burst stays ordered; on `rand_a` overflow it waits for the next
millisecond rather than emitting an out-of-order id. A clock that moves backwards
must not emit an id ahead of one already issued — hold the last emitted timestamp
and never go below it.

### Which uses change

| Use | Version | Why |
|---|---|---|
| Row identity (primary keys) | **v7** | The subject of this record |
| `UUID.nameUUIDFromBytes` derived ids | **unchanged (v3)** | Determinism is the feature; the same input must yield the same id |
| Lease and fencing tokens (`claim_token`, `processingToken`, `leaseToken`) | **unchanged (v4)** | Never ordered, never external, and working |
| Anything an untrusted caller presents as proof | **not a UUID version question** | If a value must be unguessable, its security cannot rest on which UUID version it is. Audit each such value on its own terms; do not assume v7 is safe there because it has 74 random bits |

The one `DEFAULT gen_random_uuid()` in V0077 becomes `DEFAULT uuidv7()` in a
forward migration — PostgreSQL 18.6 has it natively, so this costs nothing and
needs no extension.

### Testing

- The generator emits version 7 and variant `0b10` for every id.
- Two ids minted in the same millisecond compare in mint order.
- Ids minted across a millisecond boundary compare in mint order.
- A backwards clock step does not produce an id that sorts before one already
  issued.
- Under concurrent minting no two ids are equal, and the count is exact.
- A `Set` of a large batch has no duplicates — the cheap test that catches a
  broken `rand_b`.

An architecture test asserts that new production code calls `Ids.newId()` rather
than `UUID.randomUUID()` for row identity, with the deterministic and token uses
named as the exceptions, so the next contributor does not have to remember this
record. That test is what makes the decision durable; without it the convention
decays to whatever the last person copied.

## Rollout and rollback

Module by module, smallest first, each its own change with its own gate. Never
one sweeping commit: it would conflict with everything in flight and would apply
v7 to the two uses that must not have it.

Order by where inserts are heaviest — `ordering`, `audit`, `integration` (outbox
and inbox), `notifications`, `loyalty`, `telemetry` — then the rest as they are
touched for other reasons. There is no deadline on the tail; a low-volume
configuration table gains nothing measurable and can stay v4 indefinitely.

Rollback is to stop calling the generator. Nothing needs undoing, because
nothing existing was changed — which is the main argument for doing it this way.

## Implementation checklist

- [ ] `Ids.newId()` with the layout and monotonicity rules above, and its tests
- [ ] The architecture test that keeps new code on it
- [ ] Forward migration changing V0077's default to `uuidv7()`
- [ ] `ordering` call sites
- [ ] `audit` and `integration` (outbox, inbox) call sites
- [ ] `notifications`, `loyalty`, `telemetry` call sites
- [ ] Remaining modules, opportunistically
- [ ] A note in `platform/CLAUDE.md`, since "which UUID do I use" is exactly the
      kind of question that file exists to answer

## Exit criteria

A row inserted today has a v7 id; a row inserted last month still has its v4 id
and every foreign key to it still resolves; the nine deterministic ids still
derive the same value from the same input; and a contributor who writes
`UUID.randomUUID()` for a new primary key is told by the build, not by a reviewer.

## References

- RFC 9562 §5.7 (UUID version 7)
- PostgreSQL 18 `uuidv7()` — verified present in this project's own 18.6 database
- `JdbcAudienceStore.includedMembersAfter` — the keyset comment this record's
  pagination note refers to
