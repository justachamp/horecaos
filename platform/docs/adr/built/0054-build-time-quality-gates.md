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

## Adoption inventory (2026-08-30, corrected 2026-08-31)

Measured on the fully-formatted tree, `./mvnw verify` with Error Prone and
NullAway wired in (main + test compile combined): the figure originally
recorded here — **200 warnings** — was wrong. javac's default `-Xmaxwarns` is
100; past that count it silently drops the remaining diagnostics for a
compilation-unit set, with no error and no summary line distinguishable from
"no more warnings." 100 main + 100 test is exactly 200, which is what should
have been the tell. The pom's compiler args did not set `-Xmaxwarns`, so the
count above was two truncated buckets, not a true total.

With `-Xmaxwarns 100000` added to `maven-compiler-plugin`'s `compilerArgs`,
the same tree produces **4,374 warnings**:

| Check | Count | Source |
|---|---:|---|
| NullAway | 3,779 | NullAway (nullness) |
| MissingSummary | 281 | Error Prone (Javadoc) |
| UnusedVariable | 153 | Error Prone (dead code) |
| StringCaseLocaleUsage | 33 | Error Prone (locale-sensitive case conversion) |
| DefaultCharset | 31 | Error Prone (platform-default charset) |
| NotJavadoc | 14 | Error Prone (comment misparsed as Javadoc) |
| ReturnValueIgnored | 13 | Error Prone (ignored return value) |
| SameNameButDifferent | 8 | Error Prone (shadowed identifier) |
| StringSplitter | 6 | Error Prone (`String.split` footgun) |
| UnnecessaryParentheses | 5 | Error Prone |
| ImmutableEnumChecker | 5 | Error Prone |
| AddressSelection | 5 | Error Prone (`InetAddress` resolution) |
| FutureReturnValueIgnored | 4 | Error Prone |
| InvalidParam | 3 | Error Prone (Javadoc `@param`) |
| EscapedEntity | 3 | Error Prone (Javadoc) |
| ArrayRecordComponent | 3 | Error Prone (mutable record component) |
| ReferenceEquality | 2 | Error Prone |
| InvalidLink | 2 | Error Prone (Javadoc `{@link}`) |
| FormatString | 2 | Error Prone |
| CanonicalDuration | 2 | Error Prone |
| BoxingComparator | 2 | Error Prone |
| AvoidCommonTypeNames | 2 | Error Prone |
| AssignmentExpression | 2 | Error Prone |
| UnusedMethod | 1 | Error Prone (dead code) |
| UnsynchronizedOverridesSynchronized | 1 | Error Prone |
| UnnecessaryStringBuilder | 1 | Error Prone |
| StreamResourceLeak | 1 | Error Prone (unclosed stream) |
| ReachabilityFenceUsage | 1 | Error Prone |
| OrphanedFormatString | 1 | Error Prone |
| LoopOverCharArray | 1 | Error Prone |
| LongDoubleConversion | 1 | Error Prone |
| JavaPeriodGetDays | 1 | Error Prone |
| InputStreamSlowMultibyteRead | 1 | Error Prone |
| ExposedPrivateType | 1 | Error Prone (API leakage) |
| DuplicateBranches | 1 | Error Prone |
| CheckReturnValue | 1 | Error Prone (ignored return value) |
| ByteBufferBackingArray | 1 | Error Prone |

Thirty-six distinct checks fired, not eight — the earlier table only ever saw
the first 100 warnings in each of the main and test compilation-unit sets, so
every check outside NullAway/MissingSummary/StringCaseLocaleUsage/
UnusedVariable/NotJavadoc/ExposedPrivateType/StreamResourceLeak/
CheckReturnValue was invisible from the start, and even those eight were
undercounted (NullAway alone was 3,779, not 157).

## Promotion (2026-08-31)

The inventory above is now **zero across every check**. `-XepAllErrorsAsWarnings`
is removed from the compiler plugin's args; Error Prone's own default-ERROR
checks and NullAway now fail the build directly. NullAway is pinned to
`-Xep:NullAway:ERROR` explicitly (equivalent to its default once the blanket
downgrade is gone, spelled out for clarity).

29 `@SuppressWarnings("NullAway")` sites remain, each with a one-line (or
short) justification comment immediately above it — none blanket, none at
class or file scope. They fall into four patterns:

- **Cross-module documented-but-unannotated contracts** (7 sites, `tenancy`
  module): a field the target module's own Javadoc already documents as
  optional (e.g. `ExtractionSpec.filter`, `ImportResult.targetVersion`,
  `TransformationOutcome.quarantine`'s `evidenceReference`), but that module
  was out of this change's scope and its own field is not yet annotated
  `@Nullable`. Removable once that module gets its own annotation pass.
- **A static field a test-runner hook initializes before any `@Test` runs**
  (16 sites): fifteen via `@DynamicPropertySource`, one via an enclosing
  class's `@BeforeEach` — NullAway does not recognize either as a field
  initializer the way it does a class's own `@BeforeAll`/`@BeforeEach`. A
  recurring, structural pattern in this test suite, not a one-off.
- **Deliberately-null test fixtures proving a real invariant** (4 sites): a
  fixture that constructs a record or dependency with a `null` in a slot the
  production types don't yet mark `@Nullable`, on purpose — either because
  the test is exercising unrelated fields (`RemedyApprovalHashTests`, 2
  sites), proving a production defensive null-check does its job without
  standing up a real dependency (`MigrationControlPlaneFixture`), or because
  the constructor dependencies genuinely aren't reachable from the test and
  are left null on purpose (`TenancyLegalEntityResolverTests`).
- **NullAway's local-variable dataflow needs the suppression to span a whole
  method** (2 sites, `MigrationExtractionAndTransformationTests`,
  `LegacyFilterTests`): the value's nullability is tracked from declaration
  through to where it is finally used several statements later, so a
  single-statement suppression does not reach far enough.

Since the true final NullAway count is zero and every remaining suppression
carries a justification, NullAway is promoted to `ERROR` alongside the rest
of Error Prone rather than held at `WARN`.

Promotion was verified to actually bite: a scratch violation
(`return value.length()` on a `@Nullable String` with no guard) was added to
a throwaway file, `./mvnw compile` failed with
`[NullAway] dereferenced expression 'value' is @Nullable` as a hard
`COMPILATION ERROR`, and the scratch file was then deleted.

## References

- [Founding review](../../../../docs/qoida-review.md) — queue item 2
- ADR 0001 — platform foundation (build baseline)
