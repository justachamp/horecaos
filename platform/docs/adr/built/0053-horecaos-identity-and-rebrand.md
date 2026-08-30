# ADR 0053: The platform is named HorecaOS, and the code says so once

- Decision status: Accepted
- Implementation status: Built — packages (`uz.horecaos.platform`), Maven coordinates,
  configuration keys, the `HORECAOS_` env prefix, database roles, Keycloak realm and
  client names, the OpenAPI title and baseline, the generated TypeScript client, Make
  targets and operational docs all say HorecaOS, and `make ci` is green on the renamed
  tree (2,362 tests). Historical records (`docs/adr/`, `intent/`) and legacy artifact
  names deliberately keep the old name. The four frontend apps' own identifiers are the
  next pass and are not this record's scope.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (name, domain, and package root are theirs to confirm)
- Depends on: 0001, 0052
- Supersedes / Superseded by: —
- Open inputs: none — the owner confirmed (2026-08-30): product name **HorecaOS**,
  domain **horecaos.uz**, package root **uz.horecaos**. "Qoida" survives only in
  historical records (ADRs, intents, the founding review) and in the names of legacy
  artifacts (`qoida-dashboard`, `qoida-storefront-jizbiz`)

## Context

The imported platform carries its old identity in roughly everything: Java packages
(`uz.qoida.platform`, 1,144 files), Maven coordinates (`uz.qoida:qoida-platform`),
configuration keys (`qoida.*`), environment variables (`QOIDA_*`), the database role
(`qoida_application`, granted in 88 Flyway migrations), Keycloak client and realm names,
OpenAPI titles, the generated TypeScript client, the Makefile, and the docs.

Nothing is deployed. No migration has ever been applied outside disposable local and test
databases. A total rename today is one mechanical commit gated by `make verify`; after
the first tenant it becomes a data migration, and after the first production deploy it
becomes impossible in places (the migration files freeze). The rename is at its cheapest
right now and will never be cheaper.

## Decision

Rename the platform's code identity to HorecaOS in one mechanical commit,
before any feature work, gated by a green `make verify`:

- Java package root `uz.qoida.platform` → `uz.horecaos.platform`
- Maven coordinates → matching groupId/artifactId
- Configuration keys `qoida.*`, env prefix `QOIDA_*`, database role, Keycloak
  client/realm names, OpenAPI title, generated client filenames, Make targets and docs

Not renamed: references to the legacy `milliy` system, provider documentation
(Click/Payme facts), and historical ADR prose, which describe the past truthfully.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep Qoida identifiers under the HorecaOS banner | Every future reader asks why the code disagrees with the product; the cost compounds with every file added | "Qoida" is confirmed as a permanent internal codename |
| Rename gradually, module by module | Two identities coexist for months; config keys and DB roles cannot be half-renamed | Never — partial renames of runtime identifiers do not work |
| Rename only outward-facing names (OpenAPI, clients), keep internals | Halves the diff but keeps the confusing half (packages, config, roles) | The rename window has closed (production data exists) |

## Consequences

### Positive

- One identity everywhere before the codebase grows further.
- The rename commit is a natural first proof that `make verify` guards the monorepo.

### Negative

- A one-time all-file diff that makes `git blame` one hop longer against pre-import
  history (which lives in `../Qoida/qoida-platform`).
- The Flyway migration files change content before their HorecaOS freeze. This is the
  one deliberate exception to the append-only rule, legitimate only because no database
  outside disposable containers has ever applied them. The exception is recorded here,
  happens once, and the append-only rule binds from the commit after.

### Accepted trade-offs

Diffing HorecaOS files against their Qoida ancestors becomes noisier after the rename.
The founding review and the source repositories remain the bridge.

## Specification

Mechanical, in one branch: `git grep`-driven replacement of the identifier families
listed in the Decision, `git mv` of the package directories, regeneration of the OpenAPI
baseline and TypeScript client, and a full `make verify` plus `make lint`. The
tenant-scoped-reference allowlist, hygiene checks, and hooks are re-pointed at the new
package root in the same commit.

## Rollout and rollback

Rollout is merging the rename commit. Rollback before merge is deleting the branch;
after merge, a revert commit — both trivial while nothing is deployed.

## Implementation checklist

- [x] Owner confirms name spelling, domain, and package root
- [x] Rename branch: packages, coordinates, config keys, env vars, DB role, realm/client
      names, OpenAPI title, generated client, Make targets, docs
- [x] Re-point hygiene checks, hooks, and allowlists at the new package root
- [x] `make verify` and `make lint` green
- [x] Regenerate ADR index; update CLAUDE.md/AGENTS.md stack prose

## Exit criteria

`git grep -il qoida -- ':!docs' ':!legacy*'` over `platform/src` returns nothing, and
`make verify` exits zero on the renamed tree.

## References

- ADR 0052 — One repository for the whole platform
- [Founding review of the Qoida groundwork](../../../../docs/qoida-review.md)
