# ADR 0065: Tenants manage their own integrations — secrets enter once, through a write-only door

- Decision status: Accepted
- Implementation status: Not started — this record only. The APIs it fronts
  largely exist (provider installations, merchant bindings, the wave-13
  rotate-reference endpoint); no tenant-facing screen manages any of them, and
  no API accepts a secret value at all.
- Date proposed: 2026-09-02
- Date decided: 2026-09-02
- Deciders: platform owner (directed tenant self-service for integrations and
  secret management, 2026-09-02), Claude (the write-only-door architecture and
  the control-plane placement recommendation)
- Depends on: 0025, 0026, 0027, 0028, 0029, 0031, 0033, 0035
- Supersedes / Superseded by: — (amends one clause of ADR 0028's practice the
  way ADR 0062 amended ADR 0035's sign-in mechanics: 0028's rule that a secret
  value "never passes through this API" was written for an operator who can
  reach the secret store directly; a tenant cannot, so this record opens
  exactly one ingress — everything else 0028 mandates stands untouched)
- Open inputs: none for the decision; per-provider form fields beyond
  Click/Payme/Telegram arrive with their adapters.

## Context

Connecting a payment provider today takes two actors: someone with
infrastructure access writes the secret value into OpenBao, and someone with a
control-plane token creates the binding carrying the reference. That was right
for the founding team operating its own pilot; it cannot be the product. A
multi-tenant SaaS hands every tenant a screen: see your integrations, connect
Click with the credentials Click gave you, rotate them when Click rotates,
disconnect what you stopped using — with the platform's own guarantee that the
credential can be written but never read back.

The owner directed the screens on 2026-09-02. The architectural question they
force: ADR 0028 says values never pass through the platform API. A tenant has
no other path — so either tenants never self-serve (rejected by the directive)
or the platform accepts the value once, in a door built for nothing else.

## Decision

- **The screens live in the control-plane app** — integrations are tenant
  administration (the owner/admin connecting a merchant account), the same
  surface that already owns the binding and installation APIs, not the
  floor-staff operations app. An "Integrations" section: installed providers
  and merchant bindings with status, connect flows per provider, rotation, and
  archive — every action capability-gated (the existing
  `INTEGRATION_INSTALLATION_MANAGE` / `PAYMENT_MERCHANT_BINDING_MANAGE`
  weights) and ADR 0027-audited.
- **One write-only ingress for secret values.** A dedicated endpoint accepts a
  secret value, writes it into the secret store under a PLATFORM-GENERATED
  reference in the enforced format (`horecaos:{environment}:{namespace}:
  {owner}:{id}` — the tenant never chooses paths), and returns only the
  reference for the binding/installation call that follows (or performs both in
  one transaction — the connect flow's shape). Properties that make it a door
  and not a leak: the value exists only in the request body and the store write
  — never in logs, traces, audit facts (the fact records the reference name and
  actor), error messages, or any response; there is no read-back endpoint of
  any kind — the UI shows a masked placeholder and "last rotated" only;
  rotation is writing a new value (the wave-13 verify-then-swap pattern extends:
  where a provider offers a harmless authenticated call, the new credential is
  verified before the reference flips); rate-limited per ADR 0033; the endpoint
  lives on its own path so ADR 0031's conventions and the capability tests can
  hold it to this contract explicitly.
- **ADR 0028's everything-else stands**: references in every row and config,
  the check constraints, resolution through `SecretResolver`, values absent
  from git, chat, and dumps. What changes is solely who may put a value into
  the store and how — the tenant, through this door, instead of a human at the
  vault.
- **Provider connect flows are declarative per adapter**: each provider
  declares the fields its connection needs (Click: merchant/service ids +
  secret key; Payme: cashbox id + key; Telegram: bot token) so the screen
  renders from the adapter's declaration and a new provider means no new
  screen work — the same neutrality discipline ADR 0064 just recorded for
  voice.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Tenants get scoped vault access instead | Hands every tenant a second product's auth model and UI; the vault becomes tenant-facing infrastructure | Never |
| Keep operator-mediated setup | Rejected by the directive; does not scale past the pilot | — |
| Client-side encryption to the vault (platform never sees plaintext) | Real key-distribution complexity for a threat the TLS+no-persistence door already covers at this scale; the platform operator is trusted with far more already | A compliance regime demands it |
| Screens in the operations app | Integrations are tenant administration, not floor work; the surface model (ADR 0031) puts tenant admin in control-plane | The owner overrules the placement |

## Implementation checklist

- [ ] Write-only secret ingress endpoint: platform-generated references, store write, no read-back, no logging, rate limits, capability + audit; capability-test coverage of the no-echo contract
- [ ] Connect flows: per-adapter field declarations; Click/Payme/Telegram first; verify-before-flip where the provider allows
- [ ] Control-plane app "Integrations" section: list, connect, rotate, archive; masked display; i18n
- [ ] Rotation generalized from the wave-13 endpoint's pattern to value-rotation through the door
- [ ] Sandbox runbook (`connect-click-payme-sandbox.md`) rewritten to use the screens once they exist

## Exit criteria

A tenant admin with no infrastructure access connects a Click sandbox merchant
account entirely from the control-plane app, a real payment round-trips
against it, the credential appears nowhere in logs or responses, rotating it
from the screen keeps payments working, and the audit trail names who
connected and rotated what — by reference, never by value.
