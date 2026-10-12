# Loading the national ИКПУ/MXIK list

**Not a night alert; the runbook two morning-digest incidents link to.** **Last executed:** never — the official file has not been obtained, so this is a draft written against an invented fixture (ADR 0173).

**The way back, before the first command.** Nothing in this procedure can stop an order being taken or a receipt being built. An upload only ever creates a *dry run*; the list changes at the second, explicit act (`apply`), and a bad load is undone by importing the previous file in full-snapshot mode and acknowledging what it withdraws. The platform never deletes a row of the list: a code the authority withdraws is *dated*, and a code that returns has its date cleared. An issued receipt keeps the code it was sent under.

**Who:** the platform administrator (`platform.admin` at platform scope). Finance does the sampling in step 6.

## 0. What is open until the first real file exists

These are ADR 0173's open inputs and nothing in the code guesses them:

- The file itself: the State Tax Committee's published catalogue (`tasnif.soliq.uz`), downloaded by a person as `.xlsx` or `.csv`. The platform does not scrape the portal and calls no undocumented endpoint.
- Its **header row**. `MxikMappingProfile` (code-owned, `catalog/application/`) maps header spellings to columns, and today knows only the invented fixture's spellings. A column it does not know **stops the run** (`UNKNOWN_HEADER`, naming the column) instead of being guessed. Writing the profile is: put the real headers into its alias table, and add a trimmed copy of the file beside `src/test/resources/mxik/tasnif-fixture.csv`.
- Whether the file carries both languages, a level, package and unit lists and a successor column. Absent columns are optional; a row with one label only takes that text in both columns and the run counts how many did.
- The licence. Do not commit the real file.

## 1. Upload the file: a dry run

```bash
TOKEN=...   # a platform administrator's access token
BASE=https://api.horecaos.uz
FILE=tasnif-2026-10.xlsx

# Small file (up to about 0.7 MiB decoded; the API refuses a request body over 1 MiB): in the body.
curl -sS -X POST "$BASE/api/v1/control-plane/fiscal-reference/mxik/imports" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mxik-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"fileName\":\"$FILE\",\"mode\":\"FULL_SNAPSHOT\",\"sourcePublishedOn\":\"2026-10-01\",\"contentBase64\":\"$(base64 < "$FILE" | tr -d '\n')\"}"
```

The real list is larger than the body cap, so use a signed upload:

```bash
TICKET=$(curl -sS -X POST "$BASE/api/v1/control-plane/fiscal-reference/mxik/imports/uploads" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mxik-ticket-$(date +%s)")
echo "$TICKET" | jq .
# PUT the file to .url with every header in .requiredHeaders, then name .objectKey:
curl -sS -X PUT "$(echo "$TICKET" | jq -r .url)" $(echo "$TICKET" | jq -r '.requiredHeaders | to_entries[] | "-H \(.key):\(.value)"') --data-binary "@$FILE"
curl -sS -X POST "$BASE/api/v1/control-plane/fiscal-reference/mxik/imports" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mxik-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"fileName\":\"$FILE\",\"mode\":\"FULL_SNAPSHOT\",\"sourcePublishedOn\":\"2026-10-01\",\"objectKey\":\"$(echo "$TICKET" | jq -r .objectKey)\"}"
```

`mode` is `FULL_SNAPSHOT` (the file is the whole list: a code it no longer contains is withdrawn) or `PARTIAL` (a delta: only inserts and updates; **a partial file withdraws nothing**). `sourcePublishedOn` is the date the authority published the file; withdrawals are dated with it, else with the import date.

**Check:** the answer is `status: VALIDATED` (or `HELD_FOR_REVIEW`, step 3) and an `ETag`. The list has not changed: `GET /api/v1/control-plane/fiscal-reference/mxik/status` still says what it said before.

A `422` names why in `findingCode`: `UNKNOWN_HEADER`, `DUPLICATE_HEADER`, `REQUIRED_COLUMN_MISSING`, `FILE_EMPTY`, `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `CSV_UNREADABLE`, `XLSX_UNREADABLE`, `RAW_FILE_NOT_STORED`. The refusal records a `FAILED` run so the file's hash and the stored copy exist as evidence.

## 2. Read the dry run

`GET .../mxik/imports/{runId}` and `GET .../mxik/imports/{runId}/findings` (cursor-paged by `after`).

| Look at | Healthy | Stop and look if |
|---|---|---|
| `rowsTotal` | close to the portal's own count | far from it: a truncated or wrong-sheet file |
| `rowsError` and the `ROW_ERROR` findings | a handful, each a row number and a stable code | many `CODE_STORED_AS_NUMBER`: the spreadsheet stored a seventeen-digit code as a number and **lost its last digits**; export the code column as text and upload again |
| `codeLengthHistogram` | one dominant length | several lengths: the format is not what we assumed; the importer asserts none, so read it before applying |
| `labelsBorrowed` | small | most rows: one language column is missing or misnamed |
| `rowsWithdrawn` (full snapshot) | small | large: see step 3 |
| `PACKAGE_DEFAULTS_CHANGED` findings | few | many: the authority changed its package lists; they are suggestions only, nothing is rewritten |

Rows carrying a **row error are not loaded**; the rest are. A child whose parent is neither in the file nor in the list is dropped with `PARENT_UNKNOWN`, and so are its own children.

## 3. A held run

A full snapshot that would withdraw more than `catalog.mxik_import_withdrawal_threshold_percent` (default 2) of the valid rows comes back `HELD_FOR_REVIEW`, with the codes it would withdraw in the findings. That is nearly always a truncated or wrong file, not a purge.

- If it is the wrong file: `POST .../imports/{runId}/reject` with `If-Match`. The run stays as a record; its staged rows are cleared.
- If the authority really withdrew that many codes: apply with the exact count you were shown (step 4). No second signature is asked for: the platform has one operator, and a fail-closed four-eyes rule it cannot meet would be a lock nobody can open.

## 4. Apply

```bash
curl -sS -X POST "$BASE/api/v1/control-plane/fiscal-reference/mxik/imports/$RUN/apply" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mxik-apply-$(date +%s)" \
  -H "If-Match: $ETAG" -H 'Content-Type: application/json' \
  -d '{"acknowledgedWithdrawals": 0}'   # for a held run, the exact rowsWithdrawn count
```

`If-Match` is the run's version from the dry run. `422 RUN_IS_STALE` means the list changed after you validated, whether another run was applied or the counts you were shown no longer hold: upload again. Applies run one at a time, so a second administrator applying while yours is running waits for it and is then answered this way. `422 WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED` means the count you named is not the count you were shown.

**Check:** `status: APPLIED`, `datasetVersion` one higher than before, and the status read now says `loaded: true` with `validRows`, `withdrawnRows`, `sourcePublishedOn` and `ageDays: 0`. The two incidents `FISCAL_REFERENCE_NOT_LOADED` and `FISCAL_REFERENCE_STALE` resolve themselves.

The apply is one transaction, parents first. If it fails, the list and the state row are exactly as they were and the run is `FAILED` with `APPLY_FAILED`; nothing was half-written. Fix the cause (the log names the run) and upload the file again.

## 5. Rolling back a load

There is no bespoke undo. Import the **previous** file with `mode: FULL_SNAPSHOT`: what the bad load added is withdrawn (dated) and what it changed is restored. That run will probably be held; acknowledge its withdrawals. The raw file of every run is kept in the object store under its content hash (`mxik-imports/<sha256>.<ext>`, `sourceObjectKey` on the run).

## 6. The sample finance checks after the first load

Finance picks fifty codes at random from the tenants' real menus and from the portal itself, searches each in the console's classification picker, and records: the code, the label shown, the label on the portal, match or not. The result goes in the follow-up note to ADR 0173. This is the only step that proves the profile read the file *correctly*, not merely *completely*.

## The monthly refresh

`catalog.mxik_reference_max_age_days` (default 45). A daily check raises `FISCAL_REFERENCE_STALE` when the last apply is older than that, and `FISCAL_REFERENCE_NOT_LOADED` when the list is empty while any tenant has classified a node. Each is one incident per class (a repeat counts an occurrence), and a successful import resolves it. The procedure is steps 1 to 4 with the current file.

Tenants see the same state: the tenant status read says "not loaded" instead of "nothing found", and a withdrawn code in a tenant's menu shows as a WARNING (`FISCAL_MXIK_CODE_WITHDRAWN`) with its date and the successor the file names, in the fiscal coverage read, and never blocks a sale, a receipt or a publication.

## When it goes wrong

| Seen | Meaning | Do |
|---|---|---|
| `413` on the upload | the body is over the 1 MiB cap | use the signed upload in step 1 |
| `UNKNOWN_HEADER` | the file has a column `MxikMappingProfile` does not know | read the message for the column; update the profile; this is the first-real-file work |
| every code reported unknown by the validator | the list is empty or loaded from the wrong file | `GET .../mxik/status`; the validator is silent while `loaded` is false, so this means it *is* loaded |
| a tenant's code reported withdrawn | the authority retired it | the coverage read lists the node with the successor; a person clicks "replace with"; nothing is replaced automatically |
