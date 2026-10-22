# Privacy: what legal has to confirm, and how to connect it

**Last executed:** never. Every step below is written against the code on branch `wave21-w10-privacy` and has been run only
against test data.

This is the external-connections record for wave `w10-privacy` (row 10.11, Data & privacy). The wave built everything it could
without a lawyer: the erasure, its proof, the legal hold, per-tenant retention windows, the register that keeps unconfirmed
retention numbers out of production, and a report-only check on consent purposes. What it cannot build is the answer to three
questions only legal can give. Each section below says what exists, where the value goes, and how to prove the connection.

**Nothing here has a way back to "unconfirmed" except removing the two environment values.** Confirming is not irreversible:
delete `HORECAOS_PRIVACY_RETENTION_CONFIRMED` and the next start refuses again.

## 1. Legal's retention periods per data class and per job

**What exists.** `ProvisionalRetention` lists nine values the platform acts on that a lawyer, not an engineer, owns:

| Code | What it is | Provisional value |
|---|---|---|
| `class.personal` | PERSONAL data, after the account ends | 24 months (ADR 0029) |
| `class.personal-sensitive` | PERSONAL_SENSITIVE data | 6 months (ADR 0029) |
| `class.financial` | FINANCIAL records | 7 years (ADR 0029) |
| `class.observability` | observability data | 90 days (ADR 0029) |
| `ordering.cart_retention_days` | an abandoned cart, after it was last touched | 90 days; a tenant may set its own |
| `telemetry.track_retention_days` | a courier's track | 30 days; a tenant may set its own, never below the settlement period plus the dispute window |
| `courier.applicant_retention_months` | a courier applicant nobody verified | 12 months; a tenant may set its own |
| `iam.staff_sign_in_retention_days` | a staff sign-in record | 180 days; a tenant may set its own, 30 to 730 |
| `customers.dsar_cover_note` | the wording that opens a data-subject export, and the days allowed to answer it (section 3) | platform wording, marked `PROVISIONAL` in the file; 30 days |

`ProvisionalRetentionGuard` refuses a production start while any of them is unconfirmed. Local, test and default profiles log the
list and continue.

**Where the value goes.** The deployment's environment file, `/etc/horecaos/production.env` on the pre-production host:

```bash
HORECAOS_PRIVACY_RETENTION_CONFIRMED=all
HORECAOS_PRIVACY_RETENTION_CONFIRMATION_REFERENCE=<the identifier of the signed document, for example LEGAL-2026-117>
```

`all` confirms every value. To confirm some, list their codes, comma separated; the start then refuses on exactly what is left.
A confirmation without a reference does not count: "legal said yes" with nothing to point at is what the check exists to stop.
Changing a *number* is a different act: a tenant-settable one is an ADR 0030 value set in Settings -> Data & privacy (or by the
platform administrator at platform scope); a class default is a code change and a release, because no job reads the class
defaults from configuration yet. Confirming does not change any number.

**How to prove the connection.**

1. Before confirming, start the application with the production profile and read the refusal. It must name every code in the
   table above. If it names fewer, a value was confirmed already or the register has lost an entry.
2. Set the two variables, restart, and confirm the application starts. The log has no "Retention values are provisional" line.
3. Open Settings -> Data & privacy as a tenant owner. The retention rows no longer show «Provisional — awaiting legal
   confirmation».
4. To go back: delete `HORECAOS_PRIVACY_RETENTION_CONFIRMED`, restart. The start refuses again (or, with
   `HORECAOS_PRIVACY_RETENTION_CHECK=REPORT_ONLY`, logs a warning at every start).

`HORECAOS_PRIVACY_RETENTION_CHECK` is `AUTO` (refuse in production profiles, report elsewhere), `REPORT_ONLY` (never refuse; use
for one deployment while the document is obtained, and never leave it) or `ENFORCE` (refuse in every profile).

## 2. Legal's lawful basis per consent purpose

**What exists.** The registry of purposes (`customer.consent_types`, worded per language) and a check that can say a consent
decision was recorded under a purpose the tenant has not registered. `ConsentService.record` still accepts any purpose; it
cannot be made to refuse until legal says which basis each purpose rests on and which spellings existing writers use should be
migrated rather than refused (the SendPulse import writes `MARKETING`, singular).

**Where the value goes.** Two places, in this order.

1. The purposes legal approves are the rows of Settings -> Data & privacy -> Consent. Today a tenant cannot author a purpose
   there; the three defaults are seeded on first read. Adding one is a data change until an authoring screen exists.
2. The check is switched on with `HORECAOS_CUSTOMERS_CONSENT_PURPOSE_CHECK=REPORT_ONLY` (default `OFF`). It counts, per source,
   in the metric `horecaos.customers.consent.unregistered_purpose` (exported as `horecaos_customers_consent_unregistered_purpose_total`, tagged `source`) and logs the source and the tenant, never the purpose
   someone typed. It never refuses.

**How to prove the connection.** With the flag on, record a consent decision for a purpose that is not in the registry (the
operator's consent form takes any) and watch the counter move; record one for `MARKETING_PROMOTIONS` and watch it not. To see
which purposes are unregistered for a tenant, which is the list legal needs, run on the pre-production database (read-only):

```sql
SELECT d.purpose, d.source, count(*) AS decisions
  FROM customer.consent_decisions d
 WHERE d.tenant_id = '<tenant id>'
   AND NOT EXISTS (SELECT 1 FROM customer.consent_types t
                    WHERE t.tenant_id = d.tenant_id AND t.code = d.purpose AND t.active)
 GROUP BY d.purpose, d.source
 ORDER BY decisions DESC;
```

The `purpose` column is free text; treat the result as staff-only.

## 3. The wording and response deadline of the cover note on a data-subject export

**What exists.** A customer can ask for a copy of her data (storefront: Profile -> My data; console: the customer's *Copy &
corrections* tab) and a person who holds `customer.pii.export` produces it, with a purpose and, where the tenant published an
approval policy for `customer.pii.export`, a second person's approval (ADR 0029, status addition of 2026-10-23). The file is one
JSON document; its first section, `cover`, carries three things legal owns:

| ADR 0030 key | What it holds | Default |
|---|---|---|
| `customers.dsar_cover_note_ru`, `customers.dsar_cover_note_uz_latn`, `customers.dsar_cover_note_en` | The note that opens the file, one text per language, plain text up to 2,000 characters | blank: the platform's provisional wording is used and the file marks it `PROVISIONAL` |
| `customers.dsar_response_deadline_days` | Days from the day the request is raised by which the customer is owed her data (1 to 90). Fixed on each request when it is raised; changing it later does not move a promise already made | 30 |

Neither wording nor deadline is legal's answer yet; both are built so that giving the answer is a configuration change and not
a release. `customers.dsar_bundle_retention_days` (1 to 30, default 7) is an engineering choice about how long the file waits
in object storage and is not part of what legal confirms.

**Where the value goes.** Set the three note keys and the deadline at platform scope (control plane, configuration) so every
tenant carries the same text, or per tenant where a tenant's legal entity needs its own. Then record the confirmation in the
environment as for the retention numbers in section 1: `HORECAOS_PRIVACY_RETENTION_CONFIRMED` naming `customers.dsar_cover_note`
(or `all`) together with `HORECAOS_PRIVACY_RETENTION_CONFIRMATION_REFERENCE`. Until then a production start is refused.

**How to prove the connection.**

1. In a non-production deployment, raise a request for a test customer on the storefront (Profile -> My data) and see the due
   date: it must be the deadline you set, counted from today.
2. In the console, open that customer's *Copy & corrections* tab, produce the file with a purpose and open the link.
3. In the file, `cover.noteStatus` must read `AUTHORED` for each of `ru`, `uz-latn` and `en` and `cover.note` must carry legal's
   text; `cover.responseDeadlineDays` and `cover.responseDueAt` must match step 1. A language still reading `PROVISIONAL` has no
   text set.
4. In Settings -> Data & privacy, the request is in the worklist with its due date, and the egress log lists the production and
   the link as personal data leaving.
5. Delete the platform-scope note and produce a second file: the status returns to `PROVISIONAL`.

A browser or a chat client is not a place to keep the file: the link works for five minutes, the file is removed after
`customers.dsar_bundle_retention_days`, and an erasure of the customer removes it at once.

## What the wave does not touch

- The platform-wide retention of the audit trail (ADR 0027) and of financial records: those are legal obligations the platform
  keeps regardless, and `class.financial` is on the list only so that the number is confirmed, not because a job reads it.
- Whether a data subject is entitled to a tenant's internal notes about her (the reasons on a blacklist entry, staff notes on a
  service-recovery case). The export withholds them and says so in its manifest; including them is a legal answer, not a build.
- A decision on what happens to a customer's remaining loyalty balance when she is erased. The erasure suspends her points
  account and moves no points; closing it would forfeit them and paying them out is refused by the not-money rules (ADR 0046).
  That is the tenant's decision, to be taken with legal.
