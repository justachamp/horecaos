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

**What exists.** `ProvisionalRetention` lists seven numbers the platform acts on that a lawyer, not an engineer, owns:

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

**What exists.** Nothing yet. A data-subject export is the next step of this wave's item list and is not built in the step this
record describes; the cover note belongs to the export bundle (ADR 0029). This section is here so the fact has a place to land
and so nobody ships an export without it.

**Where the value goes.** When the export is built: the bundle's cover section reads its wording from configuration, one text per
language, and the response deadline is an ADR 0030 integer (days). Neither has a key today.

**How to prove the connection.** Not applicable until the export exists. The proof then is: request an export for a test customer,
open the bundle, and read the cover note in the customer's language with the legal deadline in it.

## What the wave does not touch

- The platform-wide retention of the audit trail (ADR 0027) and of financial records: those are legal obligations the platform
  keeps regardless, and `class.financial` is on the list only so that the number is confirmed, not because a job reads it.
- A decision on what happens to a customer's remaining loyalty balance when she is erased. The erasure suspends her points
  account and moves no points; closing it would forfeit them and paying them out is refused by the not-money rules (ADR 0046).
  That is the tenant's decision, to be taken with legal.
