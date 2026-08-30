---
name: secrets-and-pii
description: Use whenever handling a credential, API key, token, provider secret, or personal data (name, phone, address, payment detail) in HorecaOS Platform — including logging, events, traces, metrics, error messages, dead letters, test fixtures, and migrations. Encodes ADR 0028 and ADR 0029.
---

# Secrets and personal data

Two separate rules that fail the same way: something sensitive ends up somewhere durable
and readable.

## Secrets (ADR 0028)

- Secrets live **only** in the secrets manager. PostgreSQL stores a **reference**.
- No module keeps a private provider credential table. Provider accounts are installations
  and bindings (ADR 0026); `provider_binding_id` always means that row.
- Camel routes and service-to-service calls use dedicated Keycloak service accounts with
  least-privilege scopes. Credentials come from the manager at runtime.
- Frontend and mobile clients are **public** — Authorization Code with PKCE, no client
  secret in a bundle, ever.
- Tokens are held in memory where the platform permits. Never in local storage.
- Never copy a credential, token, or fixed OTP out of the legacy system. Identity is
  linked explicitly and a new Keycloak session is required after cutover.

## Personal data (ADR 0029)

Classified and envelope-encrypted, with key rotation.

**Never appears in:** an event payload, a log line, a trace attribute, a metric label, an
error message, a dead-letter summary, a test fixture committed to git, or a URL.

Carry an identifier instead and resolve it through an authorized call. Dead-letter
summaries deserve special care — operators read them, and their authorization to see the
underlying record is not implied by their authorization to work the queue.

## Never log

Access tokens, refresh tokens, payment credentials, OTPs, raw sensitive provider payloads.
Structured logs carry tenant, correlation, aggregate, and request IDs — those are the
things worth having at 3am anyway.

## Before saying it is done

- [ ] No secret value in the database, a config file, a fixture, or a diff
- [ ] `python3 tools/checks/repo_hygiene.py` passes (it fails on tracked credential files)
- [ ] No PII in any event, log, trace, metric, or dead-letter summary
- [ ] Personal data encrypted per ADR 0029, with the classification recorded
- [ ] Audit record written in the same transaction as the change (ADR 0027)
- [ ] Least-privilege IAM and secrets access, verified rather than assumed

## Reject

Any request to commit a credential "temporarily", to log a payload "just for debugging",
or to put personal data in an event "because the consumer needs it". The consumer resolves
it through an authorized call.
