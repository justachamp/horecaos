# ADR 0173: The ИКПУ/MXIK reference dataset

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the table and the readers exist and the dataset is
  not in it. `catalog.mxik_reference` (V0028) is created empty: `code` primary key,
  `parent_code` (a self foreign key that is not deferrable), `label_ru`, `label_uz` (both
  `NOT NULL`), `label_en`, `default_package_codes text[]`, `default_unit_codes integer[]`,
  `valid_from`, `valid_until` and `imported_at`. Nothing writes it: no importer, no
  endpoint, no job, no seed and no migration inserts a row, and
  `FiscalReferenceControllerTests` is the only code that ever does, by hand. What reads it:
  `FiscalReferenceController` (`GET /api/v1/control-plane/fiscal-reference/mxik` and
  `/status`, `CATALOG_READ` at PLATFORM scope); its tenant alias in `CatalogQueryController`
  (`GET .../brands/{brandId}/catalog/fiscal-reference/mxik`, `CATALOG_READ` at BRAND scope)
  that feeds `q-mxik-picker` in `frontend/operations` (product editor, row `4.2e`, and the
  fiscalization tab's backfill editor, row `10.7c`); and `CatalogValidator`, whose
  `FISCAL_MXIK_CODE_UNKNOWN` warning stays silent until the table has a row
  (`JdbcCatalogStore#mxikReferenceIsLoaded`). Three defects wait in those readers.
  `JdbcCatalogStore#knownMxikCodes` selects by code alone and ignores `valid_until`, so a
  withdrawn code would be reported as known; `searchMxikReference` honours the window, so
  the picker and the validator would disagree. The `status` read has no tenant alias, so a
  catalog author at a brand cannot tell "never imported" from "nothing matched" except from
  a sentence in the picker's help text. And `horecaos_application` holds
  `SELECT, INSERT, UPDATE, DELETE` on the table, so a bug in any tenant-facing path could
  delete the national list. `catalog.fiscal_classifications` (V0028) carries the tenant's
  assignment (`mxik_code`, `package_code`, `fiscal_unit_code`, `fiscal_name`, marking and
  age fields) with no foreign key to the reference, deliberately. `PartnerFiscalizationBridge`
  still sends one synthetic aggregate line, so no receipt reads a classification yet.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0010, ADR 0012, ADR 0016, ADR 0025, ADR 0027, ADR 0030, ADR 0031,
  ADR 0032, ADR 0038, ADR 0085, ADR 0135, ADR 0141
- Supersedes / Superseded by: — (amends ADR 0038 by closing its open input "source and
  refresh cadence of the ИКПУ/MXIK reference list" (finance) on a proposed default, and by
  stating the withdrawn-code rule the table's `valid_until` implies and nothing enforces;
  leaves ADR 0038's classification model, its "no format validation, the code's shape
  belongs to the official list" rule and the other half of that input, "who signs off a
  tenant's assignments", exactly as decided. It does not reopen the rejected row "free text
  on the product"; it keeps V0028's decision not to put a foreign key from a classification
  to the reference, and names the condition under which that could change)
- Open inputs: each is closed on its proposed default if the owner accepts the record as
  written; the ones that name a person other than the owner stay with that person and the
  work they block is marked.
  - **Whether the tax authority publishes a machine-readable feed, and on what terms**
    (finance, platform owner; ask the State Tax Committee). What could be read on
    2026-10-07 is the portal and the way people use it: the unified electronic national
    catalogue of goods and services at `tasnif.soliq.uz`, five ways to search (text,
    barcode, customs code, brand, registration number), a catalogue download in Excel in
    its "classifier categories" section, a way to propose a missing code through the
    portal's chat or the hotline with approval reported within a day, and 17-digit
    codes since 1 August 2021. No documented public API and no stated licence was found.
    Those are secondary sources and the portal itself must confirm them. Proposed
    default: the dataset enters by a person downloading the official file and uploading it
    here; the platform does not scrape the portal and does not call an undocumented
    endpoint. Blocks: only automation; the first load and every later one proceed by upload.
  - **How often it changes, and so how often to refresh** (finance). Unknown; the
    withdrawal behaviour is not described in anything read. Proposed default: a freshness
    check every thirty days, an import whenever a tenant reports a code the picker does not
    know, and a platform alert when the loaded set is older than the ADR 0030 key
    `catalog.mxik_reference_max_age_days` (PLATFORM scope, default 45). The importer is
    idempotent, so refreshing more often than the authority publishes costs nothing.
  - **The file's exact shape** (platform owner with finance, on the first file): column
    names, whether a level or "assignable" flag exists, whether labels come in both languages,
    whether package and unit lists are columns, whether a successor code or an effective date
    is given. Proposed default: nothing is assumed. The mapping from the file to the table is
    a code-owned profile (`TASNIF_XLSX_V1`) written against the first real file and kept with a
    small anonymised fixture, and an importer meeting a header it does not recognise stops and
    says so instead of guessing. Until the profile exists the importer cannot run, which is
    the point.
  - **Whether the list may be stored and served to tenants** (legal). It is a public state
    reference; a stated licence was not found. Proposed default: stored once for the platform
    and served only to authenticated callers through the existing endpoints; never published on
    an unauthenticated route and never re-offered to a third party.
  - **Whether Click, Payme or an operator of fiscal data reject a withdrawn code**
    (finance, provider discovery). Proposed default: this record blocks no sale and no
    receipt on a withdrawn code. The signal is a finding and a coverage figure; a hard stop
    follows ADR 0038's stage 3 per brand, never before.
  - **Whether package-code names should be kept** (product). The picker offers a code, which
    an operator reads as a number. Proposed default: not stored; a second table is added only if
    the file carries names and an operator asks.
  - **Who runs and checks the first load** (platform owner, finance). Proposed default: the
    platform owner runs it in pre-production first from the runbook this record asks for; finance
    checks fifty codes, chosen across the menu categories of the pilot tenant, against the portal
    before the production load.
  - **How large a withdrawal a full file may cause before a person must look**
    (platform owner). Proposed default: two percent of the currently valid rows, key
    `catalog.mxik_import_withdrawal_threshold_percent` (PLATFORM, ADR 0030). A truncated or
    wrong file otherwise empties the list in one request.

**To accept as written:** say "accept 0173". Every open input above is then closed on its
proposed default.

## Context

Row `4.2e` of `platform/docs/operations-gap-map.md` ("ИКПУ/MXIK reference lookup, typeahead
behind the classification field") is PARTIAL and BLOCKED: *"A tenant-scoped ИКПУ/MXIK typeahead
(`q-combobox`) is now built and wired against a new BRAND-scoped alias of the reference search —
but it returns empty for every query, because the official ИКПУ/MXIK dataset has never been
imported."* Its "Blocked by": *"The official ИКПУ/MXIK dataset has never been imported; its
source and refresh cadence are an unanswered finance/owner input, and the existing search
endpoint is PLATFORM-scoped so a tenant operator could not call it even if it were loaded."* The
second clause was answered by the tenant alias; the first has been the same sentence in ADR 0038's
open inputs since 2026-08-22, which said: "source and refresh cadence of the ИКПУ/MXIK reference
list and who signs off a tenant's assignments (finance)". `docs/operations-spec/catalog.md` §4.12
describes the screen waiting for it: a typeahead over the search "showing `code — label_ru`", an
amber cell `Код не найден в справочнике` sourced from the validator, and a `/mxik/status` read "to
tell the screen whether to say so".

**Why this is a launch blocker and not a convenience.** ADR 0038 calls classification "a wall":
both payment providers require an ИКПУ and a package code on every receipt line, and a wrong code
is a tax classification error on a legal document, not a data-entry slip. The product editor and
the fiscalization backfill already ask an operator to type a seventeen-digit number from memory or
from a second browser tab. `FISCAL_MXIK_CODE_UNKNOWN`, the one control that catches a transcription
slip, cannot fire while the table is empty. The cost of an unloaded list therefore falls on the
most error-prone step of onboarding, and on a document the restaurant, not the platform, is
liable for.

**What V0028 already decided, and this record must not reopen.** The code's shape belongs to the
official list, so nothing asserts a length or a digit pattern. A classification does not foreign-key
to the reference, because "an empty reference behind a foreign key would refuse every code". The
list is a tree, so a parent lets an operator browse. Default package and unit codes are "a
suggestion offered to the operator, never a default written behind their back: the package code
appears on a legal document, and a value nobody chose is a value nobody checked." A retired code is
dated, not deleted, because "a receipt issued last year under a code that has since been retired
was still correct". `docs/operations-spec/catalog.md` adds the posture on tooling: assistive search
with a human choosing is permitted; AI generation of a code is skipped, because generating it
"transfers that risk to HorecaOS invisibly".

**What V0028 left unbuilt, which this record supplies.** The way in (a source, a job, a
refresh); the meaning of "withdrawn" in the readers (the date exists, the validator ignores it);
the signal that the list is stale or absent; the safe way to replace a national dataset that every
tenant's validator reads; and the tenant-visible answer to "is the list loaded".

**What the dataset is, from what could be read.** The official portal is the State Tax Committee's
unified electronic national catalogue (`tasnif.soliq.uz`); a code is seventeen digits; the portal
binds brand and property detail to identification codes and basic codes end in a block of zeros;
the unit of measure is bound to a code on the portal (an article on changing it exists); a wrongly
classified receipt can be corrected through the taxpayer's own cabinet; a missing code can be
proposed through the portal. Whether a download is a full snapshot or a delta, how many rows it
holds, whether it has effective dates and whether it names successors for withdrawn codes were not
established, and this record states them as unknowns rather than building on a guess. The size
matters for design only at the edges: the table is a platform singleton, not per tenant, so even a
very large list is one copy.

## Decision

**Load the official dataset by an audited, two-step, idempotent import that a platform
administrator runs from a file they downloaded from the tax authority; never delete a row,
only date its withdrawal; make every reader agree on what "valid" means; surface "not loaded"
and "stale" to the people who need to know; and let a withdrawn code warn, never block.**

1. **Source: the official file, uploaded by a person.** The only supported source is the
   State Tax Committee's published catalogue as a downloaded Excel or CSV file. The platform does
   not scrape the portal and does not call an endpoint nobody documented. If the Committee
   offers a documented feed on stated terms, an adapter may fetch the same file on a schedule and
   feed the same importer; the importer is the contract, the fetch is a convenience.
2. **A dry run first, always; apply is a separate, explicit act.** An upload creates an import
   run in `VALIDATED` state with counts and findings and changes nothing in
   `catalog.mxik_reference`. `apply` is a second call by a platform administrator that names the
   run. This is `catalog.import_runs`' own pattern (V0380: "dry-run required, true is the safe
   default"), moved to the platform because the dataset is not a tenant's.
3. **A code-owned mapping profile reads the file.** `TASNIF_XLSX_V1` (and a CSV sibling) maps
   file columns to table columns, is written against the first real file, and is tested against a
   fixture. The importer refuses an unknown header, a blank or duplicated code and a parent that
   is absent, and reports a row-length distribution to the human instead of asserting a format.
   A row that has only one of the two labels takes that text in both columns rather than being
   dropped, and the run counts how many did.
4. **Never delete; date the withdrawal.** A valid code that a *full-snapshot* run does not
   contain gets `valid_until` set to that run's source date (the date the operator enters, else the
   import date). A code that returns is reinstated: `valid_until`
   cleared. A *partial* run (a delta) only inserts and updates and withdraws nothing. The
   application role loses `DELETE` on the table.
5. **One definition of valid.** A code is valid on a date `d` when `valid_from <= d` and
   (`valid_until` is null or `d < valid_until`). `searchMxikReference`, `knownMxikCodes`, the
   validator, the coverage read and any future receipt builder call one function with that
   definition; the validator's existing silence on an empty list stays.
6. **A safety valve before a wrong file does damage.** A full-snapshot run that would withdraw
   more than `catalog.mxik_import_withdrawal_threshold_percent` of the valid rows is held:
   status `HELD_FOR_REVIEW`, with the list of codes it would withdraw. It applies only when the
   administrator repeats `apply` naming the exact withdrawal count they were shown, the
   acknowledgement ADR 0141 already uses for a decommission report. No second signature is
   asked for: the platform has one operator and a fail-closed four-eyes rule it cannot meet
   would be a lock nobody can open.
7. **A withdrawn code warns and never blocks.** The validator gains
   `FISCAL_MXIK_CODE_WITHDRAWN` (WARNING) naming the node, the code and the date. The
   fiscal-coverage read counts a node with a withdrawn code as needing attention and returns the
   count separately, and the fiscalization workbench can filter to them. No sale, receipt or
   publication is blocked by this record. If the dataset carries a successor, the workbench offers
   "replace with" as a bulk action a person clicks; nothing is replaced automatically, and an
   issued receipt's retained items stay exactly as sent.
8. **Default package and unit codes stay suggestions.** The import stores the lists; the picker
   offers them as `q-mxik-picker` does today; nothing is written to a classification behind the
   operator's back. Where the dataset lists package codes for a code and the tenant typed a
   different one, a WARNING `FISCAL_PACKAGE_CODE_NOT_LISTED_FOR_MXIK` says so, because a list that
   may not be exhaustive is evidence for a human and not a rule.
9. **The list's state is visible, to the platform and to the tenant.** `status` returns whether it
   is loaded, its dataset version, when it was imported, the source's own date, the valid row
   count and its age, and gains a brand-scoped twin so the tenant console can say "not loaded"
   instead of "nothing found". A scheduled check raises an ADR 0085 incident when the list is
   absent while any tenant has classified a node, and when it is older than the configured age.
10. **The raw file is kept, and rollback is a re-import.** The uploaded file is stored in the
    object store (ADR 0010, ADR 0135) under its content hash and referenced from the run. A bad
    load is undone by importing the previous file in full-snapshot mode and acknowledging its
    withdrawals; no bespoke undo exists.
11. **The tenant's assignment is the tenant's.** Nothing in this record assigns, suggests by
    machine or changes a tenant's classification. The foreign key V0028 declined is not added now; it
    becomes possible once the first load has landed, ADR 0038 stage 3 is on for every brand and rows
    are never deleted, and is then a `NOT VALID` constraint to be validated deliberately.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave it empty; operators type codes | The status quo, and the gap row. The one control that catches a wrong code cannot fire, and the picker answers nothing | Never |
| Scrape the portal or call its internal API on a schedule | No stated licence, an undocumented interface that can change without notice, and a legal dataset that this platform would be depending on a guess to obtain | The Committee documents a feed and its terms; then an adapter feeds the same importer |
| Ship the list as a Flyway seed migration | Ties a legal dataset's refresh to a deploy, makes the migration enormous, and cannot express withdrawal on a later refresh without another migration | The Committee publishes versioned snapshots and the owner wants the dataset reproducible per release |
| A per-tenant copy of the list | Many copies of one national fact, each able to drift, and an import that must run for every tenant | Never |
| Delete withdrawn rows on refresh | Loses "was this code valid on that day", the reason V0028 dated the row, and makes an old receipt unreproducible | Never |
| A foreign key from `fiscal_classifications` to the list now | An empty or incomplete list would refuse every code, V0028's own reason | The first full load is in, stage 3 is on for every brand, and rows are never deleted |
| Block publication and receipts on a withdrawn code | Unknown whether providers reject one, and stopping a restaurant's sales over a reference-data fact is a harsher failure than the one it prevents | Provider discovery says withdrawn codes are rejected, or ADR 0038 stage 3 turns the finding into a blocker per brand |
| Automatically migrate tenant classifications to a successor | A classification is a legal choice a person made; generating it transfers risk invisibly (catalog.md rejects AI generation for the same reason) | Never without a human click per batch, as decided above |
| Pull a code by barcode or GS1 lookup | The portal supports it and it is a good assistive feature, but it needs its own source and licence and is not the dataset | Product asks for it after the reference is loaded |
| A commercial classification API | A paid dependency and a data-residency question for a list the authority gives away | The authority's file proves unusable |
| Require a second signature to apply an import | The platform is run by one person; a rule they cannot satisfy is either bypassed or a lock. The held-for-review acknowledgement carries the intent | The platform has a second operator, at which point `REQUIRE_CONFIGURED_POLICY` fits like the wallet actions |

## Consequences

### Positive

- The typeahead returns real codes, the transcription-slip warning can fire, and the coverage
  report can be trusted, on the most error-prone step of onboarding.
- A withdrawn code is a visible, counted, filterable fact instead of an invisible one, and the
  picker and the validator finally agree on what valid means.
- A wrong or truncated file cannot silently empty the list, and a bad load is a re-import.
- The tenant console can say why the picker is empty.
- The application role can no longer delete the national list.

### Negative

- A person must obtain the file and run the import, every month, and nothing but an alert
  reminds them. The platform's freshness depends on a habit.
- The mapping profile is written against one file. If the authority changes the format the importer
  stops, which is the intended failure and still an outage of the refresh until someone updates the
  profile.
- The table gains columns and two companion tables, and every reader of `catalog.mxik_reference`
  must move to the one validity function.
- Withdrawal inference works only on full snapshots. If the authority publishes only deltas,
  nothing is ever withdrawn automatically, and the stale rows are the cost.
- A raw file in object storage is an artifact with a retention question of its own.

### Accepted trade-offs

- Withdrawal warns and never blocks, so a tenant may keep selling under a withdrawn code until a
  person acts on the finding. The alternative stops sales on a fact whose consequence is unknown.
- The first load happens after the pilot's catalogue may already hold codes, so some will be flagged
  unknown or withdrawn on day one. That is the list doing its job.
- A held-for-review run is acknowledged by the same administrator who uploaded it. It is a
  deliberate pause with a number to read, not a second pair of eyes.
- The size of the list is unknown, so the search index choice is made after the first load, with a
  measured number, and may change.

## Specification

### Physical model

The reference is a national dataset, not a tenant's: it has no `tenant_id`, as
`catalog.mxik_reference` has none today, because one copy serves every tenant and a tenant column
would invite per-tenant copies. Every table that holds tenant data in this area (the classification
that points at a code) keeps `tenant_id` and its tenant-bearing keys unchanged.

```text
catalog.mxik_reference                 (V0028; additive columns)
  + dataset_version integer null       -- the run that last wrote this row's content
  + first_run_id uuid null, last_run_id uuid null, withdrawn_run_id uuid null
  + replaced_by_code varchar(32) null  -- a successor named by the file; a suggestion only
  + level smallint null                -- the file's own level, if it has one
  index (code text_pattern_ops)        -- the prefix path an operator types
  REVOKE DELETE ON catalog.mxik_reference FROM horecaos_application

catalog.mxik_reference_state           -- one row; check (id = 1)
  id smallint pk, dataset_version integer, imported_at timestamptz,
  source_sha256 char(64), source_published_on date null,
  valid_rows integer, withdrawn_rows integer, last_run_id uuid null

catalog.mxik_import_runs               -- platform-scoped
  id uuid pk, status varchar(20)       -- VALIDATED | HELD_FOR_REVIEW | APPLYING | APPLIED | FAILED | REJECTED
  mode varchar(16)                     -- FULL_SNAPSHOT | PARTIAL
  mapping_profile varchar(32), source_file_name varchar(255),
  source_sha256 char(64), source_object_key varchar(255), source_published_on date null
  rows_total, rows_new, rows_changed, rows_unchanged, rows_withdrawn, rows_reinstated,
  rows_error integer, labels_borrowed integer
  withdrawal_threshold_percent integer, acknowledged_withdrawals integer null
  created_by varchar(255), created_at timestamptz, applied_by varchar(255) null,
  applied_at timestamptz null, dataset_version integer null, failure_reason varchar(64) null

catalog.mxik_import_run_findings       -- capped at 5,000 rows per run
  run_id, ordinal, kind (ROW_ERROR | WITHDRAWN | REINSTATED | PACKAGE_DEFAULTS_CHANGED),
  code varchar(32) null, detail_code varchar(48)
```

Rows are written parents first so `fk_mxik_reference_parent`, which is not deferrable, is always
satisfied. Apply writes the reference in batches of a few thousand rows, each idempotent on `code`,
so a restart resumes, and moves the state row and the counters in one closing transaction. Failure leaves `status = FAILED` and the table as it was, because content is written only
under `APPLYING` and the previous `dataset_version` stays in the state row until the run completes.
Explicit `GRANT`s end the migration: `SELECT, INSERT, UPDATE` on the runs and state tables,
`SELECT, INSERT` on the findings. The next free migration number is taken at implementation time
from every active worktree (AGENTS.md).

### The importer

```text
MxikReferenceImporter
  parse(fileName, bytes, profile) -> Stream<RawRow>          fastexcel / commons-csv, as CatalogImportParser
  normalise(RawRow)  -> ReferenceRow | RowError              trim, blank/duplicate/parent checks, borrow a missing label
  diff(current, incoming, mode) -> counts + findings         new | changed | unchanged | withdrawn | reinstated
  apply(run) -> dataset_version                              batches; withdraw only when mode = FULL_SNAPSHOT
```

The parser reuses the repository's spreadsheet stack (`CatalogImportParser`: `fastexcel` for
`.xlsx`, `commons-csv` for CSV) and, like the catalog import, takes the file as Base64 text in a JSON
body, up to `catalog.mxik_import_max_bytes` (default 32 MiB decoded, a placeholder that the first real
file replaces); a file beyond that goes through the object store (ADR 0010) and is named by its key.
Format follows the file extension, never a byte sniff. The raw file is stored before parsing so a
parse failure still leaves the evidence. The run's content is never logged.

### APIs (ADR 0031)

```text
POST /api/v1/control-plane/fiscal-reference/mxik/imports               Idempotency-Key; always a dry run
GET  /api/v1/control-plane/fiscal-reference/mxik/imports/{runId}
GET  /api/v1/control-plane/fiscal-reference/mxik/imports/{runId}/findings      cursor-paged
POST /api/v1/control-plane/fiscal-reference/mxik/imports/{runId}/apply         Idempotency-Key, If-Match; {acknowledgedWithdrawals}
POST /api/v1/control-plane/fiscal-reference/mxik/imports/{runId}/reject
GET  /api/v1/control-plane/fiscal-reference/mxik/status                (exists; gains fields)
GET  /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/fiscal-reference/mxik/status   (new tenant alias)
```

The writes are `Capability.PLATFORM_ADMIN` at `PLATFORM` scope, the capability every platform-wide
write here already uses; no new capability is introduced. The status reads are `CATALOG_READ`, at
PLATFORM and at BRAND respectively. The OpenAPI group is the control-plane surface (ADR 0057). The
search endpoints are unchanged in shape and gain `withdrawn` and `replacedByCode` on a row only
where the caller asked for validity as of a date.

### Readers

`MxikReference.isValidOn(code, date)` is the one definition and is applied in
`JdbcCatalogStore#knownMxikCodes`, `#searchMxikReference` and the validator. The validator's date is the
publication's own; a future receipt builder passes the order's business date (ADR 0038), so a receipt for
last month's order is judged on last month's list. New findings: `FISCAL_MXIK_CODE_WITHDRAWN` and
`FISCAL_PACKAGE_CODE_NOT_LISTED_FOR_MXIK`, both WARNING, both silent while the list is not loaded. The
coverage read `CatalogQueryService#fiscalCoverage` adds `withdrawn`. A tenant status alias lets
`q-mxik-picker` replace its generic note with the real state.

### Audit, events, PII, policy, observability

- **Audit (ADR 0027).** `catalog.mxikReference.import-validated`, `.import-held`, `.import-applied`,
  `.import-rejected`, written in the transaction of the change, with the run id, the file's hash, the mode and
  the counts, and never a file body or row content.
- **Events (ADR 0032).** None. Nothing consumes an import; readers read the table. An event is added with its
  first consumer, as ADR 0032 requires the schema and catalogue entry to precede a producer.
- **PII (ADR 0029).** None: a public classification list.
- **Policy (ADR 0030), PLATFORM scope.** `catalog.mxik_reference_max_age_days` (default 45),
  `catalog.mxik_import_withdrawal_threshold_percent` (default 2), `catalog.mxik_import_max_bytes` (default 32 MiB).
- **Alerts (ADR 0085).** `ControlPlaneAlertService` raises `FISCAL_REFERENCE_NOT_LOADED` when the list is empty
  while any tenant holds a classified node, and `FISCAL_REFERENCE_STALE` past the age key, once per class and
  subject, resolved by a successful import. A daily sweeper makes the check.
- **Metrics.** `horecaos.fiscal_reference.rows{state}`, `horecaos.fiscal_reference.age_days`,
  `horecaos.fiscal_reference.import{outcome}`; no code or label in a label.
- **Providers (ADR 0026/0007).** None while the source is an uploaded file. A future fetch adapter would sit
  behind the ADR 0007 route machinery with a fake.

### Front-end contract

The control plane gains a small "Fiscal reference" page under platform administration: upload, the dry-run
summary (counts, the row-length distribution, borrowed labels, the withdrawal list and the threshold), apply
and reject, the status block. `q-mxik-picker` and the product editor show "list not loaded" from the tenant
status read, and a withdrawn code in muted ink with its date; the fiscalization workbench gains the
withdrawn filter and the "replace with" bulk action. Strings in ru, uz-Latn and en.

### Runbook

`docs/runbooks/mxik-reference-import.md`: where to get the file, the upload, how to read the dry run, when
to acknowledge a held run, rollback by re-import, the monthly freshness check, and the fifty-code sample
that finance checks after the first load.

### Testing

- `MxikReferenceImporterTests`: a fixture file through each mapping profile; an unknown header stops the run;
  blank and duplicate codes and an orphan parent are row errors; a missing label is borrowed and counted;
  parents are written before children.
- Diff and withdrawal: a full snapshot withdraws and a partial one does not; a returning code is reinstated;
  the threshold holds a run and `apply` needs the right acknowledgement; a restarted apply resumes without
  duplicates; a failed apply leaves the table and the state row as they were.
- `isValidOn` at the boundaries of `valid_from` and `valid_until`, used identically by search, `knownMxikCodes`
  and the validator (the regression for today's disagreement).
- Validator findings, silent while unloaded; coverage counts withdrawn nodes.
- Authorization: the writes need `PLATFORM_ADMIN`; the tenant alias cannot reach the runs; the application
  role cannot `DELETE` from the table (`DatabasePrivilegeTests`).
- The stale and not-loaded alerts raise once and resolve on import.
- Tenant isolation: a tenant's search and status read show the same national data and nothing else.

## Rollout and rollback

1. Revoke `DELETE` and add the columns, the state table and the run tables, with the single validity function
   in the readers and the tenant status alias, with the list still empty (no behaviour changes).
2. Obtain the official file, write the mapping profile and fixture against it, and run the dry run in
   pre-production; read the numbers; apply; finance samples fifty codes against the portal.
3. Turn on the new findings and the alerts. The same file, applied in production by the platform owner.
4. Monthly: the freshness check and, when needed, an import.

Rollback of a load is importing the previous stored file in full-snapshot mode, acknowledging what it
withdraws. Rollback of the code is harmless: the columns are additive and the old readers still work. No
step before the first apply changes any tenant's behaviour.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Obtain the official file; record its name, hash, header row and row count in the follow-up note.
- [ ] Migration: additive columns, state and run tables, `REVOKE DELETE`, explicit grants.
- [ ] `MxikReferenceImporter` and the `TASNIF_XLSX_V1` profile with a fixture; object-store write of the raw file.
- [ ] The run endpoints and the status extensions; the tenant status alias; capability declarations.
- [ ] `isValidOn` in `JdbcCatalogStore` and the validator; the two findings; the coverage count.
- [ ] Configuration keys and their mirrored registry declarations; the daily sweeper and the two alerts.
- [ ] Control-plane page; picker, editor and workbench changes; strings.
- [ ] The runbook; metrics; audit facts.
- [ ] Tests above; `DatabasePrivilegeTests` updated; ADR 0038 status line updated to point here; gap-map rows
      `4.2e` and `10.7c` re-audited.

## Exit criteria

In pre-production the dataset is loaded from the official file: the status read reports its version, source date
and row count; typing the start of a real code or a product name into `q-mxik-picker` returns rows with their
package-code suggestions; a deliberately mistyped code in a draft produces `FISCAL_MXIK_CODE_UNKNOWN`; a test
file that withdraws a code the pilot tenant uses produces `FISCAL_MXIK_CODE_WITHDRAWN`, a coverage count and a
workbench filter, and blocks nothing; a file that would withdraw more than the threshold is held and applies only
with the acknowledgement; the application role cannot delete a row; and finance has checked fifty codes against
the portal and recorded the result.

## References

- ADR 0010 and ADR 0135 (object storage), ADR 0012 (POS staging, `gov_code`), ADR 0016, ADR 0025, ADR 0027,
  ADR 0030, ADR 0031, ADR 0032, ADR 0038 (classification, "no format validation", the reference list, rollout
  stages), ADR 0085 (platform alerts), ADR 0141 (acknowledged report precedent)
- `platform/docs/operations-gap-map.md` rows `4.2e` and `10.7c`; `platform/docs/operations-spec/catalog.md` §4.12
  and its fiscal-classification rows; `platform/docs/providers/clopos-api.md` (Q15, `gov_code` is the ИКПУ)
- `FiscalReferenceController`, `CatalogQueryController` (the tenant alias), `JdbcCatalogStore#knownMxikCodes`,
  `#mxikReferenceIsLoaded`, `#searchMxikReference`, `CatalogValidator#reportUnknownCode`,
  `CatalogImportParser`, `CatalogImportRunWorker`, `PartnerFiscalizationBridge`, `ControlPlaneAlertService`;
  `V0021`, `V0028`, `V0380`
- `frontend/operations/src/app/features/catalog/mxik-picker.ts`, `core/api/catalog-paths.ts`,
  `features/settings/fiscalization/`
- Public sources read on 2026-10-07, which are secondary and must be confirmed against the portal itself:
  the portal `tasnif.soliq.uz` and the Uzbek accountancy site `buxgalter.uz` articles on finding an ИКПУ and on
  correcting a wrong one
