# ADR 0094: Support finds a customer by phone, and sees credentials due

- Decision status: Accepted
- Implementation status: Built — `PlatformCustomerLookupController` over each tenant's keyed phone lookup, audited, and `CredentialRotationController` over installations and merchant accounts, tested in `PlatformCustomerLookupControllerTests` and `CredentialRotationControllerTests`; the global lookup's phone search and the tenant issue queue's credentials section
- Date proposed: 2026-09-11
- Date decided: 2026-09-11
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; accepted by Ayubkhon Abbosov (platform owner) on 2026-09-11, who answered its open inputs the same day
- Depends on: ADR 0015, ADR 0026, ADR 0028, ADR 0029, ADR 0083
- Supersedes / Superseded by: —
- Open inputs: closed 2026-09-11: 180 days, the default, is the rotation interval

## Context

Two notes remained on working screens. The global lookup (IA 10.1) could not
find a customer by phone: support answering "I am a customer of one of your
restaurants" had to ask which one. The tenant issue queue (10.2) could not
list expiring credentials, because providers do not say when theirs expire.

## Decision

1. **Phone lookup.** A platform user who may reveal customer data can ask
   which tenants know a phone number as a customer. The number is hashed with
   each tenant's own key and matched exactly as that tenant's operators match
   it; the answer is the tenant and the account identifier, never a name or a
   contact value. The number travels in the request body, never a URL, and
   every lookup is audited with its reason, found or not, without the number.
2. **Credentials due.** A credential is due for rotation when it has not been
   rotated through the platform within the rotation interval, counted from its
   last rotation or, if it never was, from when it was set up. Installations
   and payment merchant accounts both count. The tenant's issue queue lists
   them; values stay in the secrets manager.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A platform-wide phone hash | Every tenant's customers would share one key, and a leak of one table would match numbers across all of them | Never |
| Show the customer's name with the match | A name is personal data the tenant's own screens already guard | Never here |
| Ask each provider when a credential expires | None of the providers in use answers | A provider publishes expiry |

## Consequences

### Positive

- Support finds which restaurant a caller belongs to without asking them to guess.
- A credential nobody has rotated in half a year shows up where support already looks.

### Negative

- A phone lookup reads every tenant, one keyed query each; fine for the pilot, slower with many tenants.
- The rotation rule is age, not expiry, so a credential can be due while still working.

### Accepted trade-offs

- The interval is one setting for every provider.

## Specification

- `POST /control-plane/customer-lookups` with `{ phone, reason }` (`customer.pii.reveal`, platform scope).
- `GET /control-plane/tenants/{tenantId}/credentials-due` (`integration.installation.manage`).
- `horecaos.integration.credential-rotation-interval` (default `P180D`).

## Rollout and rollback

Additive; read-only apart from the audit record.

## Implementation checklist

- [x] Lookup and rotation reads, audit, tests
- [x] Global lookup phone search; issue queue credentials section

## Exit criteria

A phone number known to one tenant is found there and nowhere else, with one
audit record naming the reason; a credential set up a year ago and never
rotated appears in its tenant's issue queue.

## References

- `docs/frontend-information-architecture.md` §10.1, §10.2
