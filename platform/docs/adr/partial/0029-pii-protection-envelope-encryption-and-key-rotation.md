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
  keeps reporting off raw identifiers behind the `qoida_reporting_read` role. Two of those
  arrived today and are the scheme's hardest uses: V0055 verification challenges, where
  `CodeProtection` and `VerificationChallengeIssuer` hold the phone as randomized AEAD
  beside the same keyed lookup hash `customer.contact_points` uses, and the six-digit code
  only as a keyed MAC over `challengeId + ":" + code`; and V0056's delivery destination,
  where `JdbcDeliveryOrderPort` and `JdbcCustomerAddressBook` protect the address a
  courier is sent to. Not built: the
  re-encryption job that moves `g1` records onto the current generation, so rotation has a
  seam but no mover, and retired-key retention is undefined; no data-subject export,
  correction, anonymisation, retention, legal-hold or proof operation exists anywhere —
  there is no privacy endpoint, service or table; and there is now one masked
  projection for support — the order detail's customer phone (`PhoneMasking`,
  raw value behind a separate `customer.pii.reveal` call) — but still no
  restricted database role, and no masked projection anywhere else. Retention periods and lawful basis remain
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
re-encryption job, masked projections, and the privacy operations. Anonymisation
lands with ADR 0015, where the customer data it operates on is defined.

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
- [ ] Implement export, correction, anonymization, retention, legal hold, and proof.
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
