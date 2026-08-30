# ADR 0052: One repository for the whole platform

- Decision status: Accepted
- Implementation status: Partial — the HorecaOS monorepo exists and holds the imported
  platform tree, the three Angular applications, the Flutter application, and a canonical
  design-token file; root CI, the frontend workspace toolchain, and per-application
  history preservation do not exist yet.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (proposed the monorepo), Claude (assessment and layout)
- Depends on: 0001, 0035
- Supersedes / Superseded by: supersedes the repository topology of 0035 (four sibling
  repositories wired as git submodules); every other decision in 0035 stands
- Open inputs: none

## Context

The Qoida workspace reached 2026-08-30 as five sibling repositories plus two archives on
one development machine. Three of the four frontend applications had no git remote — they
existed only on that machine. The design-token file was vendored into four places with
four different checksums, guarded only by a drift script that skips silently when the
sibling checkout is absent. The generated OpenAPI clients ADR 0035 requires were never
built, so every frontend hand-writes its API layer against a backend that regenerates its
TypeScript baseline in CI — in a different repository, where no frontend build would ever
see the diff. The platform README's hand-written status prose drifted 38 migrations
behind the code it described.

None of these are tooling accidents. They are the costs of splitting one product built by
one person and their agents across repositories that can only reference each other by
absolute path on one laptop.

## Decision

HorecaOS is one git repository. It holds the Java platform (`platform/`), the Angular
applications (`frontend/`), the Flutter application (`mobile/`), the single canonical
design-token file (`frontend/design-tokens/`), and the root build and CI that tie them
together. A change that touches an API and its consumers is one commit and one review.
Each project keeps its own build; the root Makefile and path-filtered CI compose them.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Multi-repo with submodules (ADR 0035's layout) | The remotes were never created, so the layout degenerated into unversioned directories; token and client drift already happened exactly as a monorepo prevents | A second independent team owns a surface end to end |
| Polyrepo with published packages (npm registry, Maven repo) | Publishing overhead with no consumer other than ourselves; a team of one plus agents pays the full release-engineering tax for zero isolation benefit | External consumers need versioned artifacts |
| Monorepo build system (Nx, Bazel, Pants) | Premature; four projects with independent builds need path filters, not a build graph | Full CI wall-clock exceeds ~20 minutes or cross-project rebuild correctness becomes a real defect source |

## Consequences

### Positive

- The frontends are versioned at all, which they were not.
- One design-token file, one OpenAPI baseline, one place where a backend contract change
  and its frontend consumers move atomically.
- One CI, one review policy, one governance system (hooks, skills, evals) over everything.

### Negative

- Access control is repository-coarse; a future contractor sees everything.
- CI must path-filter or every commit pays every build.
- Repository size requires discipline: no `node_modules`, no CocoaPods `Pods/`, no build
  outputs, no legacy archives (693 MB of `legacy-archive/` stays outside git, referenced
  by path).

### Accepted trade-offs

Per-application release cadence is now a tagging convention rather than a repository
boundary. That is acceptable while every surface releases together.

## Specification

```text
HorecaOS/
├── platform/                 # Java modular monolith — imported qoida-platform, its docs,
│                             # ADRs, tools, evals, hooks; `make verify` runs here
├── frontend/
│   ├── design-tokens/        # THE tokens.css; every app consumes this file
│   ├── control-plane/        # Angular 22 — platform administration
│   ├── operations/           # Angular 22 — brand/location operations
│   └── storefront/           # Angular 21 — customer storefront
├── mobile/                   # Flutter customer application
├── docs/                     # monorepo-level docs (founding review, workspace plans)
├── Makefile                  # composes per-project targets
└── .github/workflows/        # path-filtered CI
```

## Rollout and rollback

Rollout is the import itself. Rollback is deleting the repository; the source
repositories under `../Qoida` are untouched and remain the archives of record for
pre-import history.

## Implementation checklist

- [x] Import `qoida-platform` main tree into `platform/`
- [x] Import the three Angular applications and the Flutter application
- [x] Establish `frontend/design-tokens/tokens.css` as the single canonical copy
- [ ] Root CI with path filters (platform lint/verify; per-app frontend jobs)
- [ ] Point the four apps' token references/drift checks at the canonical file
- [ ] Frontend workspace toolchain decision (pnpm workspace vs per-app npm)
- [ ] Archive-of-record note in each source repository under `../Qoida`

## Exit criteria

A contract change in `platform/` and its consuming change in `frontend/` merge as one
commit through one green CI run, and no file named `tokens.css` exists in the repository
other than `frontend/design-tokens/tokens.css` and generated copies traceable to it.

## References

- [Founding review of the Qoida groundwork](../../../../docs/qoida-review.md)
- ADR 0035 — Angular frontend platform and design system
