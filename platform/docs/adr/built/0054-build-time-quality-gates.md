# ADR 0054: The build measures what the review policy assumes

- Decision status: Accepted
- Implementation status: Built — JaCoCo, Spotless, Error Prone/NullAway, and enforcer duplicate/convergence rules run inside `./mvnw verify`; the `security-audit` profile and its weekly `.github/workflows/security-audit.yml` run OWASP dependency-check outside the per-commit path.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner (approved working the founding review's queue), Claude
- Depends on: 0001, 0053
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The founding review found the build has no Maven-level quality gates at all: no
coverage measurement over ~152k main lines and ~78k test lines, no formatter, no
static bug detection, no dependency scanning. Every existing gate lives in Python
scripts and CI diffs — excellent for repository policy, blind to Java-level defects
(a dead store, a misused format string, a nullness bug) and to what the tests
actually reach. REVIEW.md's correctness pass silently assumes signals the build
does not produce.

The moment is deliberate: the day after ADR 0053, while the tree has one author,
no deployment, and a one-hop blame history — a whole-tree formatting commit and a
new compile-time analyzer will never be cheaper.

## Decision

Four gates enter the Maven build itself, each with an explicit staging state:

1. **JaCoCo** — coverage measured on every `verify`; a bundle-level LINE floor
   enforced by `jacoco:check`, recorded as the property `horecaos.coverage.floor`,
   set just below the measured baseline at adoption. **The floor may only rise.**
2. **Spotless** — one whole-tree `spotless:apply` commit, then `spotless:check`
   bound to `verify`, absolute (no ratchet). Formatter preference order:
   palantir-java-format, then google-java-format, then Spotless's mechanical steps
   only (import order, unused-import removal, trailing whitespace, final newline)
   — take the first that parses this tree's Java 25 without altering semantics.
3. **Error Prone + NullAway** — on for every compile, **all findings demoted to
   warnings** (`-XepAllErrorsAsWarnings`), NullAway scoped to
   `uz.horecaos.platform`. Promotion to build-failing errors is a follow-up
   change per check family, made when its warning count is zero.
4. **Dependency scanning** — enforcer gains `banDuplicatePomDependencyVersions`
   on every build (plus convergence rules only if they pass out of the box);
   OWASP dependency-check lives in a `security-audit` Maven profile and a weekly
   scheduled CI workflow, not in the per-commit path.

`make lint` stays JVM-free; the new gates ride inside `verify`. A `make format`
target runs `spotless:apply`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Everything strict on day one | Error Prone at default severities on 152k unaudited lines blocks all work behind a fix-marathon of unknown size | Never — staging costs one follow-up commit |
| Spotless with `ratchetFrom` instead of whole-tree format | Work happens on `main`, so the merge-base ratchet degenerates to "uncommitted changes only" and committed unformatted code is never caught | A protected-branch PR workflow arrives |
| Checkstyle for layout | A formatter makes violations impossible rather than reported; the stray `checkstyle:` suppressions in the tree are vestigial, not a config | A rule Spotless cannot express is actually wanted |
| Coverage floor as a hard target (e.g. 80%) | An aspirational number either blocks the build today or is set low and lies; a measured floor that may only rise is honest | Never |
| OWASP scan on every build | NVD data pulls add many minutes per build for information that changes daily, not per commit | The scan drops under ~1 minute |

## Consequences

### Positive

- Coverage becomes a number with a one-way floor instead of an unknown.
- Formatting stops being reviewable at all.
- A class of Java defect is now caught at compile time, before review.

### Negative

- Compile time roughly doubles under Error Prone; `verify` gets slower.
- One whole-tree formatting commit adds a blame hop.
- Warning-level findings can be ignored indefinitely; promotion discipline is a
  human obligation this ADR creates but cannot enforce.

### Accepted trade-offs

NullAway over an unannotated codebase reports incompletely until `@Nullable`
annotations accumulate; its warnings are directional, not exhaustive.

## Specification

`horecaos.coverage.floor` lives in `pom.xml` with a comment stating the only-rises
rule. JaCoCo excludes generated sources (none today). Error Prone and NullAway
attach via `annotationProcessorPaths` with the JDK exports the tools document for
this JDK. The weekly workflow is `.github/workflows/security-audit.yml`, path
`platform/`, schedule plus manual dispatch.

## Rollout and rollback

Rollout is one commit (config, the formatting diff, the recorded floor). Rollback
is reverting it; no schema, data, or API surface is touched.

## Implementation checklist

- [x] JaCoCo report + check on `verify`; floor property recorded from measurement
- [x] Whole-tree `spotless:apply` commit; `spotless:check` in `verify`; `make format`
- [x] Error Prone + NullAway compiling warn-only; warning counts recorded below
- [x] Enforcer duplicate-dependency ban; convergence rules if clean
- [x] `security-audit` profile + weekly CI workflow
- [x] CLAUDE.md commands and docs/development.md updated
- [x] `make ci` green end to end

## Exit criteria

`make ci` fails on: a line-coverage drop below the recorded floor, an unformatted
Java file, or a duplicate-managed dependency — and passes on the tree as
committed. Error Prone emits its findings in every compile log. The warning
inventory at adoption is recorded in this ADR so promotion work is sized, not
guessed.

## Adoption inventory (2026-08-30)

Measured on the fully-formatted tree, `./mvnw verify` with Error Prone and
NullAway wired in (main + test compile combined): **200 warnings**, all
non-blocking (`-XepAllErrorsAsWarnings`). NullAway dominates, as expected for
an unannotated codebase's first pass — its findings are directional, not
exhaustive (see Accepted trade-offs).

| Check | Count | Source |
|---|---:|---|
| NullAway | 157 | NullAway (nullness) |
| MissingSummary | 17 | Error Prone (Javadoc) |
| StringCaseLocaleUsage | 10 | Error Prone (locale-sensitive case conversion) |
| UnusedVariable | 9 | Error Prone (dead code) |
| NotJavadoc | 3 | Error Prone (comment misparsed as Javadoc) |
| ExposedPrivateType | 2 | Error Prone (API leakage) |
| StreamResourceLeak | 1 | Error Prone (unclosed stream) |
| CheckReturnValue | 1 | Error Prone (ignored return value) |

Eight distinct checks fired — fewer than the top-20 this section budgets for.
Promotion candidates in ascending order of remaining work:
`StreamResourceLeak` and `CheckReturnValue` (1 each) are each a single
inspection away from promotion to build-failing; `ExposedPrivateType` (2),
`NotJavadoc` (3), and `UnusedVariable` (9) are next; `StringCaseLocaleUsage`
(10) and `MissingSummary` (17) are larger but still bounded; `NullAway` (157)
needs `@Nullable`/`@NonNull` annotation coverage to build up before its
warning count is a promotion signal rather than noise.

## References

- [Founding review](../../../../docs/qoida-review.md) — queue item 2
- ADR 0001 — platform foundation (build baseline)
