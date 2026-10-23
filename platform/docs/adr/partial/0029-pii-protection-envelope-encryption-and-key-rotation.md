# ADR 0029: PII protection, envelope encryption, and key rotation

- Decision status: Accepted
- Implementation status: Partial — the encryption primitives are built and widely used.
  `DataEncryptionKeyProvider` derives per-tenant, per-data-class keys by HKDF-SHA256 from
  the ADR 0028 KEK, with the derivation generation (`g1` legacy, `g2` current) part of the
  stored key identifier; `EnvelopeFieldProtection` does AES-GCM with associated-data
  binding and keyed lookup hashing; `@Classified` and `ClassificationScanner` are consumed
  by the ADR 0032 event-payload check. Twenty classes protect fields through it —
  customer profiles and recipient contacts, courier engagement, shifts, accruals and track
  reveal, ordering carts, checkout, order queries and outcomes, dine-in reservations,
  telemetry tracks, marketplace ingestion, POS order sourcing — and `SubjectPseudonym`
  keeps reporting off raw identifiers behind the `horecaos_reporting_read` role. Two of those
  arrived today and are the scheme's hardest uses: V0055 verification challenges, where
  `CodeProtection` and `VerificationChallengeIssuer` hold the phone as randomized AEAD
  beside the same keyed lookup hash `customer.contact_points` uses, and the six-digit code
  only as a keyed MAC over `challengeId + ":" + code`; and V0056's delivery destination,
  where `JdbcDeliveryOrderPort` and `JdbcCustomerAddressBook` protect the address a
  courier is sent to. There is now one masked
  projection for support — the order detail's customer phone (`PhoneMasking`,
  raw value behind a separate `customer.pii.reveal` call) — but still no
  restricted database role, and no masked projection anywhere else.
  **A data-subject erasure request now exists, and anonymisation is one of its
  transitions.** `V0178` creates `customer.erasure_requests`: a durable,
  tenant-scoped record with a `PENDING -> COMPLETED | CANCELLED` state machine,
  raised by the subject themselves (`POST
  .../storefront/tenants/{tenantId}/brands/{brandId}/me/erasure-request`,
  `@CustomerOwned`, self-service) or by an operator on their behalf (`POST
  .../tenants/{tenantId}/customers/{accountId}/erasure-requests`,
  `customer.manage`) — both idempotent under retry against
  `ux_erasure_request_pending`, a partial unique index admitting at most one
  `PENDING` request per account, and against an account already anonymised,
  which answers with the request that anonymised it rather than raising a new
  one. Raising records intent only. `CustomerErasureService.execute`, gated by
  a new capability held more tightly than `customer.manage` —
  `customer.erasure.execute`, `TENANT_OWNER`/`TENANT_ADMIN` alone, not
  `SUPPORT_AGENT` — is the one place in this codebase
  `customer.customer_accounts.status` is ever written as `ANONYMIZED`: it
  overwrites the account's display name and date of birth, every contact
  point's ciphertext and keyed lookup hash, every address's protected fields
  whether active or archived, and severs every principal link so a later
  sign-in cannot reattach to the account it just anonymised. This is the
  anonymisation this record's own Closed input names ("erasure by
  anonymisation, not per-customer crypto-shredding"), scoped to exactly the
  `customer` schema's own protected columns: `brand_profiles` (order-count and
  spend aggregates, first/last order timestamps) and `consent_decisions` (the
  append-only evidence of what someone agreed to and when) are deliberately
  untouched, which is what "financial history survives and the person does
  not" means in code rather than in decision prose. A new SPI,
  `customers.spi.CustomerErasureParticipant`, is the seam another module's own
  erasure operation is meant to be called through — nothing implements it yet,
  so `marketing`'s own already-built and already-tested
  `CustomerMetricProjectionService.erase` and `JdbcAudienceStore.eraseMembership`
  (ADR 0044) are not yet wired to it; that adapter is `marketing`'s own file to
  write, deliberately left to the wave that owns that module.
  `customer.blacklist_entries.reason_encrypted` and `lift_reason_encrypted` are
  a named, deliberate gap this wave leaves: they are not scrubbed.
  `CustomerErasureTests` covers idempotent raising and execution under retry,
  cross-tenant refusal, a second subject's contact and address data surviving
  an erasure of the first untouched byte for byte, an ADR 0027 fact naming the
  actor on every transition (requested, executed, cancelled), and the account
  row, brand profile, and consent decisions surviving execution. **Still not
  built:** the re-encryption job that moves `g1` records onto the current
  generation, so rotation has a seam but no mover, and retired-key retention is
  undefined; export, correction, retention-expiry enforcement, legal hold, and
  proof — ADR 0029's own larger privacy-operations surface, deliberately out of
  this wave's narrower scope. Retention periods and lawful basis remain
  provisional, per the Open input.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), security, legal
- Depends on: ADR 0028, ADR 0034
- Supersedes / Superseded by: —
- Open inputs: Retention periods per data class and lawful basis per purpose (legal) — provisional defaults are in use and cannot reach production unconfirmed
- Closed inputs: Per-tenant key scope with erasure by anonymisation, not per-customer crypto-shredding (2026-08-20)

## Context

ADR 0015 introduces envelope encryption for customer contact values, addresses,
and push tokens, and requires "a rotatable envelope-encryption scheme" plus
normalized hashes for lookup. That is roadmap step eleven. Protected data
appears well before it: ADR 0010 media carries classification rules from step
six, ADR 0013 stores protected payment references, and ADR 0014 handles courier
identity documents and live coordinates, which are among the most sensitive data
in the platform.

Each of those ADRs says "protected reference" without defining what protects it.
If the scheme is defined inside the customer ADR, four other modules will either
wait for it or invent their own, and Uzbekistan's data protection regime — which
was amended in March 2026 and still mandates domestic storage for sensitive
categories — makes inconsistent handling a legal exposure, not only a technical
one.

## Decision

One data protection scheme, owned by a `crypto` capability inside `iam`, applied
by every module that stores personal data.

- **Classify first.** Every persisted field carrying personal data is assigned a
  class in code, and the class determines storage, logging, export, retention,
  and residency treatment:

  ```text
  PUBLIC              no restriction
  INTERNAL            business data, no personal content
  PERSONAL            name, contact, address, device, preferences
  PERSONAL_SENSITIVE  identity documents, precise location history, biometric-adjacent
  FINANCIAL           payment references, settlement identifiers
  SECRET              credentials, handled by ADR 0028, never in business tables
  ```

- **Envelope encryption for `PERSONAL`, `PERSONAL_SENSITIVE`, and `FINANCIAL`
  values at rest.** A data encryption key encrypts the value; a key encryption
  key held in the ADR 0028 manager encrypts the data key. Ciphertext carries the
  key identifier and algorithm so rotation never requires guessing.
- **AES-256-GCM with per-record random nonces**, authenticated with associated
  data binding the ciphertext to its tenant and record identity. A ciphertext
  moved to another row or another tenant fails to decrypt, which turns a
  copy-paste mistake into an error rather than a silent leak.
- **Deterministic lookup uses a keyed hash, never the ciphertext.** Phone and
  email lookups use HMAC-SHA-256 over a normalized value with a dedicated
  lookup key. Encryption stays randomized, so equal values do not produce equal
  ciphertext, and lookup remains possible.
- **One key scope per tenant per class**, and deliberately not per customer.
  Tenant-scoped keys give per-tenant compromise containment and make tenant
  offboarding a crypto-shredding operation. Per-customer keys would give a
  stronger erasure proof and were rejected: a key per customer becomes a real
  key-management subsystem to store, rotate, back up, and audit, and a lost key
  becomes indistinguishable from a deliberate erasure.
- **Customer erasure is anonymisation, not key destruction.** Protected values
  are overwritten while order totals and settlement facts stay reconcilable, so
  financial history survives and the person does not. Erasure proof is a
  recorded, audited report rather than a destroyed key.
- **Rotation is scheduled and online.** Key encryption keys rotate on a period;
  data keys rotate on re-encryption. Records carry their key identifier, so old
  and new keys coexist and a background re-encryption job proceeds with
  checkpoints. Decryption of retired keys stays available for the legal
  retention window.
- **Personal data never enters events, logs, traces, metrics, error messages, or
  dead-letter summaries.** This rule already appears in ADRs 0005, 0015, 0019,
  and 0020; here it becomes one enforced mechanism with one test suite.
- **`PERSONAL_SENSITIVE` and `FINANCIAL` data stay in-country** per ADR 0034,
  regardless of where other workloads run.
- **Privacy operations are product capabilities, not scripts**: export,
  correction, anonymization, retention expiry, legal hold, and deletion proof
  each traverse primary tables, projections, object storage, reporting stores,
  and archives.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| PostgreSQL transparent data encryption or full-disk encryption alone | Protects against stolen disks and nothing else. A compromised application, a leaked backup restored elsewhere, or an over-broad support query still yields plaintext | Never as the only control; it remains a useful additional layer |
| `pgcrypto` with keys in the database or in application configuration | The key sits beside the ciphertext, so a database compromise or a backup copy defeats it entirely | Never |
| Application-level encryption with one static key for everything | No rotation story, no per-tenant containment, no crypto-shredding, and a single compromise exposes every tenant | Never |
| Deterministic encryption so values remain directly searchable | Equal plaintexts produce equal ciphertexts, which leaks frequency and enables confirmation attacks on small domains such as phone numbers. The keyed-hash lookup column gives searchability without that leak | Never for personal data |
| Tokenization through an external vault service | Strong for card data, and unnecessary here because Qoida deliberately stores no card data per ADR 0013. It would add a network hop to every customer read | Qoida ever needs to store an instrument identifier that cannot be provider-referenced |
| Per-customer keys with crypto-shredding erasure | The strongest possible erasure proof: destroy the key and the data is unrecoverable by construction. Rejected because it turns key management into a subsystem sized by customer count, and because a key lost to a backup gap is then indistinguishable from a deliberate erasure, which is a worse failure than the one it solves | A regulator or enterprise contract requires cryptographic erasure proof that anonymisation cannot satisfy |
| A dedicated field-level encryption product or database proxy | Adds a component in the data path with its own availability and residency profile, for a scheme that is a few hundred lines behind a port | Field-level encryption spreads to most columns and hand-rolled application code becomes the maintenance burden |
| Define the scheme inside ADR 0015 as originally planned | Four other modules need protected storage before step eleven, and would each improvise | Never |

## Consequences

### Positive

- One scheme, one key hierarchy, and one test suite protect personal data across
  every module, with per-tenant containment and a real erasure mechanism.
- Associated-data binding makes cross-tenant ciphertext reuse fail closed.
- Residency obligations are satisfied by classification rather than by
  remembering which table holds what.

### Negative

- Encrypted columns cannot be queried, sorted, or joined directly, so every
  access path must be designed around hash lookups, and support tooling and
  reporting become materially harder to build.
- Decryption cost appears on customer read paths, and a busy Operations list
  view can become expensive if it decrypts eagerly rather than on demand.
- Key management is now a permanent operational obligation with its own failure
  mode: losing a key encryption key destroys data irrecoverably.

### Accepted trade-offs

- Crypto-shredding is treated as an erasure mechanism where legally acceptable,
  which means key destruction becomes as consequential as data deletion and
  needs the same approvals.
- Re-encryption after rotation is a long-running background job that must
  coexist with live traffic, checkpoint reliably, and be observable.

## Implementation notes

Delivered: the data classification, AES-256-GCM envelope encryption with
associated-data binding, per-tenant key derivation from an ADR 0028
key-encryption key, and keyed per-tenant lookup hashing.

The associated data binds a ciphertext to its tenant, table, column, and row
identifier. Moving a ciphertext to any other context fails to decrypt, so a
mis-joined query or a copy-paste mistake becomes an error instead of a silent
cross-tenant leak. Tests cover all four movements plus tampering.

`@Classified` and `ClassificationScanner` replace the interim name-based checks
that ADR 0027 and ADR 0032 were each carrying privately. Two sources are
combined deliberately: a declaration is authoritative, survives renaming, and
catches fields whose names give nothing away, while the heuristic still catches
what nobody remembered to annotate. False positives are the intended direction —
a wrongly flagged field costs one annotation, a wrongly permitted one puts a
phone number on a Kafka topic.

Not yet delivered: rotation generations beyond the first, the background
re-encryption job, and masked projections beyond the one order-detail phone
mask. Anonymisation lands with ADR 0015, where the customer data it operates on
is defined — and now has an entry point: `V0178`'s `customer.erasure_requests`
and `CustomerErasureService`, which overwrite an account's own protected fields
on execution but do not yet implement export, correction, retention-expiry
enforcement, legal hold, or proof, the rest of the privacy-operations surface
this ADR names.

## Status addition, 2026-10-23 (batch 21, wave w10-privacy)

This is a dated addition to the record, not a change to what it decided: the Decision, Alternatives considered and
Consequences above stand as accepted. It records how five of the six privacy operations the Decision lists were built, the
defaults the build had to choose where no record fixed them, and what stays open. The status line and the checklist are
left for the documentation close-out.

**Export.** A data-subject export is a request with its own lifecycle (`customer.export_requests`, V0684) beside the
erasure request: raised by the customer on the storefront (`POST .../me/export-request`) or by staff on her behalf
(`customer.manage`), and produced by a person who holds `customer.pii.export`, under an ADR 0027 approval when the tenant has
published a policy for that action, with a purpose they state. The format and delivery are the smallest default the Decision
allows, and are written here because no record fixed them: **one JSON document per request** under a key made of the tenant
and the request id, **a section per module** (a `customers.spi.CustomerExportParticipant` declaring its tables with
`@ExportsCustomerData`, discovered by type the way the erasure participants are), values **decrypted under the declared
purpose** with the audit fact (`customer.dsar.exported`) written before the first decrypt, a **manifest of counts per table**
kept on the request and never a value, and a **link that expires in five minutes**, minted on each authorised read and itself
an audit fact (`customer.dsar.bundle_revealed`). Both codes are registered in `PiiEgressActions`, so the merchant's egress log
shows them. The file is kept for `customers.dsar_bundle_retention_days` (default 7) and then removed by `ExportBundleSweeper`,
or at once when the customer is erased. What a participant leaves out on purpose is named in the manifest (staff notes on a
service-recovery case, the reasons on a blacklist entry), and a build test (`ExportInventoryTests`) requires every table that
carries a customer key or a PERSONAL column to be exported or exempt with a reason, as the erasure inventory does.

**Correction.** `customer.profile_corrections` (V0684) keeps the value a correction replaced, protected under the envelope and
bound to its own row, with a reference to the evidence (a ticket or document number: a pattern that refuses spaces, so prose
and names are a validation error, enforced in the service and by the database). The edit itself goes through the existing
profile, contact-point, address and date-of-birth methods, so versions and normalisation are not reimplemented. The prior
value is returned only inside a data-subject export, and goes with the account on erasure.

**Legal hold, proof, retention.** A hold on a named customer (V0682) is placed and lifted under two-person approval and refuses
the erasure while it stands; an executed erasure stores per-module row counts as its proof (V0681); the abandoned-cart and
courier-applicant sweeps honour a tenant's shorter window down to a floor, and `ProvisionalRetentionGuard` refuses a production
start while a retention value is unconfirmed (the owner records legal's confirmation in the environment, with a reference).

**Still open (legal, the Open input above).** The wording of the note that opens an export and the response deadline are ADR 0030
keys with provisional defaults (`customers.dsar_cover_note_*`, `customers.dsar_response_deadline_days`); the bundle states
whether it carries platform wording (`PROVISIONAL`) or text a platform administrator or tenant set (`AUTHORED`). Whether a data
subject is entitled to the tenant's internal notes about her is withheld pending legal, not decided. Key rotation,
re-encryption and masked projections are not part of this addition.

## Status addition, 2026-10-23 (batch 21, review fixes for wave w10-privacy)

A second dated addition, not a change to what the record decided. A review of the integrated wave found four places where the
erasure described above was weaker than its text, and the code now says what the text says.

- **The POS export keeps no copy of her number.** `integration.pos_order_exports.customer_phone_hash` (V0036) holds an unsalted
  SHA-256 of the digits of the order's telephone number (`LineFingerprint.phoneHash`). A Uzbek mobile number is one of about
  10^9 candidates, so the digest is reversible in seconds. No participant declared the table, and the inventory guard did not see
  it, because the table keys to the customer through `order_id` and the column name matched none of the guard's key patterns.
  `PosErasureParticipant` (module `pos`, declared as `pos`) now overwrites the column with a fresh random value on every export of
  the account's orders, through the order's `customer_account_id` (the keyed lookup hashes an erasure is handed cannot match an
  unkeyed digest), and `ErasureInventoryTests` treats any `*phone*hash` column as a customer key. The recovery read does not
  depend on the stored column: `PosOrderExportService` builds its probe from the live order's number and `CloposAdapter` hashes
  both sides in memory, and an erased order has no number left to compare. Not done: replacing the digest of customers who were
  not erased with the keyed lookup hash.
- **A saved address keeps no pin.** `JdbcCustomerStore.eraseAddress` overwrote the label and the two ciphertexts and left
  `latitude` and `longitude` in the clear on every active and archived address, while the ordering participant already zeroes the
  cart's destination. It now clears both and sets `coordinate_source` to `LANDMARK_ONLY`, the one terminal source V0021 allows
  beside a null point that is not on the geocoding backfill's work list (`NOT_GEOCODED` is, so a tombstone would have been offered
  to a geocoder).
- **A hold and an erasure do not interleave.** `CustomerErasureService.execute` looked for a hold with a plain read and nothing
  locked the account or the hold, so a hold committed between that read and the erasure's commit was ignored, which is the
  destruction of evidence a hold exists to prevent. `execute` and `LegalHoldService.place` now take the same transaction-scoped
  advisory lock, keyed by tenant and account (`JdbcLegalHoldStore.lockHoldDecision`), before they read or write; whichever comes
  second waits for the first to commit and then sees its result. The statement above that a hold «refuses the erasure while it
  stands» is true under concurrent use only from this change.
- **Unsubscribe tokens are not kept for ever.** `notifications.unsubscribe_tokens` (V0671) gained a deletion by account in
  `NotificationsErasureParticipant` (now declared there, and no longer an exemption), an index for it (V0697), and an
  `UnsubscribeTokenSweeper` that removes tokens past their 400 days. The residual risk the exemption used to record, that no job
  purged an expired token, is closed.

## Provisional retention

Legal has not yet set retention periods or lawful basis per purpose. Rather than
block ADR 0010 media classification and ADR 0015 customer data behind a review
with no date, the values below are **provisional** and resolve through ADR 0030
configuration, so replacing them is a configuration change and not a release.

```text
PERSONAL             retain while the account is active, then 24 months
PERSONAL_SENSITIVE   retain only while operationally required, then 6 months
FINANCIAL            7 years, per the financial retention policy
INTERNAL             per the owning capability's own rules
observability        90 days, PII-free by construction
```

Two guards make the provisional status real rather than a comment:

- A startup check refuses a production profile while any retention value is
  still flagged provisional, so unconfirmed numbers cannot govern real customer
  data.
- Destructive retention jobs begin in report-only mode and require sampled proof
  before enforcement, as ADR 0023 already requires.

## Contract

```java
interface FieldProtection {
    ProtectedValue protect(TenantId tenant, DataClass dataClass, RecordRef record, String plaintext);
    String reveal(TenantId tenant, ProtectedValue value, RecordRef record, RevealPurpose purpose);
    LookupHash lookupHash(TenantId tenant, LookupDomain domain, String normalizedValue);
}
```

`reveal` requires a declared purpose, which is recorded for `PERSONAL_SENSITIVE`
and `FINANCIAL` classes as an ADR 0027 audit fact. Bulk reveal is a separate,
capability-gated, audited operation, because the difference between a support
agent viewing one customer and exporting fifty thousand is exactly the
difference this control exists to capture.

Storage layout for a protected column:

```text
{key_id, algorithm, nonce, ciphertext, aad_version}
```

## Enforcement

- A field annotated with a data class is serialized through the classification
  serializer everywhere: API responses, events, audit change documents, logs.
- A build-time check asserts that no `PERSONAL`, `PERSONAL_SENSITIVE`, or
  `FINANCIAL` field is reachable from an event payload type.
- A logging filter drops known protected field names and fails tests if a
  protected value appears in captured output.
- Database roles for reporting and support have no access to protected columns;
  they read masked projections instead.

## Privacy operations

```text
export        assemble a customer's data across modules, decrypted under audit
correct       amend with evidence, preserving prior values under retention
anonymize     replace protected values, retain non-personal commercial facts
retention     expire by class and purpose, report-only first
legal hold    suspend expiry and erasure for named subjects with approval
proof         produce evidence of what was erased, when, and by whom
```

Order snapshots are anonymized rather than deleted, so financial history stays
reconcilable while the person becomes unidentifiable.

## Testing

- Ciphertext moved between rows or tenants fails to decrypt.
- Equal plaintexts produce different ciphertexts and equal lookup hashes.
- Rotation leaves old records readable and re-encryption is resumable.
- No protected field appears in any event payload type, asserted structurally.
- Logs, traces, metrics, and dead-letter summaries contain no protected values.
- Anonymization leaves order totals and settlement facts intact.
- A reporting role cannot read a protected column.

## Rollout and rollback

Introduce classification and the protection port with encryption enabled for new
writes in ADR 0010 media metadata and ADR 0015 customer data as those ship. No
legacy plaintext is migrated until the key hierarchy has a tested backup and
restore. Rollback disables new protected features; it never decrypts stored data
back into plaintext columns.

## Implementation checklist

- [ ] Confirm the provisional retention periods and lawful basis with legal before production.
- [x] Derive data keys from an ADR 0028 key-encryption key (`DataEncryptionKeyProvider`). Production provisioning, backup, and separation of duties follow ADR 0034.
- [x] Implement envelope encryption, associated-data binding, and keyed lookup hashing (`EnvelopeFieldProtection`).
- [x] Implement the `@Classified` annotation and `ClassificationScanner`, consumed by the ADR 0032 event-payload check.
- [ ] Implement rotation, re-encryption checkpoints, and retired-key retention.
- [~] Implement export, correction, anonymization, retention, legal hold, and proof.
      Anonymization has an entry point now: `V0178`'s `customer.erasure_requests`
      request/execute/cancel lifecycle and `CustomerErasureService.execute`,
      which overwrite the account's own protected fields and hand off to other
      modules through `customers.spi.CustomerErasureParticipant` — no
      implementation registered yet. Export, correction, retention-expiry
      enforcement, legal hold, and proof remain unbuilt.
- [ ] Add masked projections and restricted database roles for support and reporting.
- [x] Add cross-tenant, cross-record, cross-column, tampering, and lookup-scoping tests. Anonymisation arrives with ADR 0015.

## Exit criteria

Every personal, sensitive, and financial field is stored encrypted with a
tenant-scoped key and a bound associated-data context; lookups work without
deterministic encryption; key rotation completes online with old records
readable; no protected value can reach an event, log, trace, or metric, proven
by structural and runtime tests; and a customer export, anonymization, and
erasure proof can be produced end to end.

## References

- [ADR 0015: Customer accounts, cross-brand identity, and consent](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md)
- [ADR 0034: Hosting environments, topology, and data residency](../partial/0034-hosting-environments-topology-and-data-residency.md)
