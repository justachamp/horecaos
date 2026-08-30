# ADR 0026: Provider installations, bindings, and secret references

- Decision status: Accepted
- Implementation status: Built — V0013 creates `integration.provider_environments`,
  `installations`, `bindings`, `binding_capabilities` and `provider_entity_mappings`, with
  `secret_reference` stored as a reference and never a value. `BindingRef`,
  `ProviderInstallationLookup`, `ProviderEntityMappingLookup` and `ProviderCategory` are
  the shared types ADRs 0013, 0014 and 0020 reference, and the four Camel gateways
  (`PaymentGateway`, `NotificationGateway`, `PosGateway`, `DeliveryGateway`) resolve
  credentials through `SecretReference`/`SecretResolver`, each calling `resolveFresh` once
  after an authentication failure so a rotation needs no identifier change.
  `RotationAwareSecrets`, which adds the one-fresh-read-per-cooldown claim, is a payments
  construct only — `PaymeCredentials` and `ClickCallbackProcessor` use it on the inbound
  callback path and nothing else does. `ProviderInstallationController` exposes
  installation and binding creation, activation and suspension under ADR 0025
  capabilities. `ProviderCapabilityReconciliationService` now gives payment,
  delivery and notification installations their shared safe preflight:
  `ProviderCapabilityCatalog` verifies the exact capability declaration of the
  wired adapter, ADR 0028 resolves the referenced credential without returning
  or persisting its value, and V0083 appends the evidence in
  `integration.provider_capability_probes` before refreshing the installation
  snapshot and each binding's verification metadata. The control plane exposes
  `POST /{installationId}/capability-reconciliation` and refuses activation
  until every enabled binding capability is supported by that snapshot. POS
  keeps its stricter category-specific live discovery in `PosCapabilityService`
  and `integration.pos_capability_probes`, reached through
  `POST /pos-sync-runs/capability-reconciliation`.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0007, ADR 0028, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: none

## Context

ADR 0011 introduced `integration.installations`, `integration.bindings`, and
`integration.provider_entity_mappings` under the title "POS installations". The
model is not POS-specific and the rest of the roadmap already assumes it is
shared: ADR 0013 stores a `merchant_account_id` and a fiscal
`provider_binding_id`, ADR 0014 attaches quotes and assignment attempts to a
`provider_binding_id`, and ADR 0020 routes notification attempts through a
`provider_binding_id`. None of those three ADRs defines the entity they
reference.

An undefined shared entity referenced by three ADRs is how three subtly
different implementations get built. Payments would grow its own merchant
account table, notifications its own gateway configuration, and delivery its own
partner registry, each with a different answer to credential rotation, scope
resolution, connection checking, and audit.

ADR 0016 also proposed a second mapping table, `catalog.external_mappings`,
duplicating `integration.provider_entity_mappings` with no defined winner when
the two disagree.

## Decision

Extract one provider integration model into `integration`, owned by this ADR and
referenced by every provider-facing capability.

- **An `IntegrationInstallation` is a tenant-owned configured relationship with
  one external provider account.** It carries provider type, provider category,
  display name, status, a secret reference, non-sensitive configuration, a
  capability snapshot, adapter version, and connection-check evidence. It never
  stores a credential value.
- **Provider category is an explicit enum**: `POS`, `PAYMENT`, `DELIVERY`,
  `NOTIFICATION`, `GEOCODING`, `OTHER`. Category determines which capability
  ports may be declared, so a payment installation cannot be bound as a courier
  partner.
- **A `Binding` scopes an installation to a brand or a location** with status,
  priority, effective dates, and configuration overrides. `provider_binding_id`
  throughout the roadmap means this row. A location binding must carry matching
  brand and tenant ancestry.
- **A merchant account is an installation of category `PAYMENT`** with its
  binding scope, not a payments-local table. Fiscalization is a *capability* of
  a `PAYMENT` installation rather than a category of its own, because Qoida's
  partners (Click, Payme) fiscalize as part of accepting payment; see ADR 0013.
  A standalone `FISCAL` category is added only if a direct fiscal-operator
  integration is ever required.
- **`integration.provider_entity_mappings` is the single store of external
  identifier mappings.** Catalog, inventory, ordering, and fulfillment read it
  through a `ProviderEntityMappingLookup` port. No module keeps a local mapping
  table.
- **Exactly one effective primary binding exists per scope and capability**
  unless an explicit failover policy is configured, enforced by a partial unique
  index rather than by application convention.
- **Credentials resolve at call time** from the ADR 0028 secrets manager using
  the stored reference. Rotation changes the secret behind a stable reference
  and never changes the installation ID.
- **Endpoints come from an approved provider environment catalogue**, never from
  tenant-supplied configuration, which closes the server-side request forgery
  path at the model level.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave the model inside ADR 0011 and let each capability reference it informally | Already failing: three ADRs reference `provider_binding_id` without a definition, which is exactly how divergent implementations begin | Never |
| A separate installation model per capability (payments, delivery, notifications, POS) | Four implementations of credential rotation, scope resolution, connection checking, capability discovery, and audit. Provider concerns are genuinely the same across categories; only the capability sets differ | A category's lifecycle diverges so far that sharing costs more than it saves. Category-specific tables would then extend, not replace, the installation |
| One installation per binding, with no separate binding table | A tenant with forty locations on one POS account would hold forty credential references, making rotation a forty-row operation with forty chances to half-fail | Never |
| Store credentials encrypted in `integration.installations` | Application-owned secret storage with weaker rotation, weaker access separation, and worse audit than a purpose-built manager. ADR 0028 owns this | Only as the explicitly approved fallback defined in ADR 0028 |
| Model bindings as configuration rows under ADR 0030 | Bindings have their own lifecycle, activation, suspension, connection evidence, and audit. Configuration resolution answers "what value applies here", not "which external account is live for this location" | Never; the two compose, and binding overrides use ADR 0030 resolution |
| Keep `catalog.external_mappings` as well | Two mapping stores with no defined winner. Provider identifiers are integration evidence with a provider lifecycle, not catalog authoring state | Never |

## Consequences

### Positive

- One place answers credential rotation, scope resolution, connection checking,
  capability discovery, and audit for every provider Qoida integrates.
- `provider_binding_id` becomes a defined foreign key rather than a convention,
  so ADRs 0013, 0014, and 0020 lose an undefined dependency.
- A new provider category costs a capability set and an adapter, not a new
  configuration subsystem.

### Negative

- A shared model across POS, payments, delivery, and notifications will
  accumulate optional fields that matter to one category and not others, and
  policing that is ongoing review work.
- Every provider-facing module now depends on `integration`, which makes
  `integration` a high-traffic module whose changes ripple widely.
- Capability snapshots go stale when providers change behavior silently, so
  reconciliation must be scheduled rather than assumed.

### Accepted trade-offs

- Category is a coarse enum, so an unusual provider spanning two categories
  needs two installations against the same external account. That duplication is
  accepted to keep capability validation strict.
- Refusing tenant-supplied endpoints prevents self-service integration with an
  unlisted provider instance. Adding a provider environment is a platform
  change, deliberately.

## Implementation notes

Delivered: the schema, the environment catalogue, and both lookup ports.
`provider_binding_id` is now a real foreign key with a `BindingRef` type behind
it, so ADRs 0013, 0014, and 0020 stop referencing something undefined.

Two things the database enforces rather than the application. **Ancestry**:
composite keys make a binding that reaches another tenant's location impossible,
not merely discouraged. **One primary per scope and capability**: which provider
handles a capability must never depend on row order, so it is a partial unique
index. That required denormalising the binding's narrowest scope onto
`binding_capabilities`, kept honest by a trigger — without the trigger the
uniqueness would be enforcing a fiction.

Resolution runs narrowest-scope-first, the same direction ADR 0030 resolves
configuration, rather than introducing a second precedence rule.

There is deliberately no `FISCAL` category: Click and Payme fiscalise as part of
accepting payment, so it is a capability of a `PAYMENT` installation and a
separate category would model a relationship Qoida does not have.

The shared reconciliation path deliberately does not invent a generic provider
ping. Several installed providers have no harmless authenticated “who am I”
operation: their available calls create a charge, courier booking, or message.
For payment, delivery and notification, it therefore stores exactly what it can
prove without an external effect — a resolvable secret reference and a wired
adapter declaration — and labels that evidence accordingly. POS is the category
that does expose safe live capability probes and continues to record those
provider responses separately. A future adapter with a safe authenticated probe
adds it as category-specific evidence; it does not broaden this common path into
unsafe traffic.

## Physical model

```text
integration.installations
  id, tenant_id, provider_category, provider_type, display_name
  environment_code, status, secret_reference
  non_sensitive_config jsonb, capability_snapshot jsonb, adapter_version
  last_connection_check_at, last_connection_status, last_connection_evidence
  version, created_at, updated_at
  unique(tenant_id, provider_type, environment_code, external_account_reference)
    where external_account_reference is not null and non-sensitive

integration.bindings
  id, tenant_id, installation_id
  brand_id null, location_id null
  status, priority, effective_from, effective_until null
  configuration_override jsonb
  version, created_at, updated_at
  check (brand_id is not null or location_id is not null)
  check (location_id is null or brand_id is not null)
  foreign key (tenant_id, brand_id, location_id)
    references tenant.locations (tenant_id, brand_id, id)

integration.binding_capabilities
  binding_id, tenant_id, capability_code, enabled, is_primary
  verified_at, capability_version
  unique(tenant_id, coalesce(location_id, brand_id), capability_code)
    where is_primary and status = 'ACTIVE'

integration.provider_entity_mappings
  id, tenant_id, installation_id, binding_id
  entity_type, qoida_entity_id, external_entity_id, external_parent_id null
  status, mapping_source, last_seen_at
  version, created_at, updated_at
  unique(binding_id, entity_type, external_entity_id)
  unique(binding_id, entity_type, qoida_entity_id)

integration.provider_environments
  code, provider_type, base_url, is_production
  egress_allowlist, tls_policy, notes
```

`provider_environments` is platform-owned reference data, not tenant-writable.

## Ports

```java
interface ProviderInstallationLookup {
    Optional<BindingRef> primaryBinding(TenantId tenant, ResourceScope scope, CapabilityCode capability);
    List<BindingRef> candidateBindings(TenantId tenant, ResourceScope scope, CapabilityCode capability);
    InstallationSnapshot installation(BindingId bindingId);
}

interface ProviderEntityMappingLookup {
    Optional<ExternalId> externalIdFor(BindingId binding, EntityType type, UUID qoidaEntityId);
    Optional<UUID> qoidaIdFor(BindingId binding, EntityType type, String externalId);
}
```

Domain modules receive a `BindingRef` and a capability. They never see a
credential, a base URL, or a provider DTO.

## Capability declaration

Each adapter declares the capabilities it implements for its category. The
capability codes themselves are owned by the capability ADRs — POS in ADR 0011,
payments in ADR 0013, delivery in ADR 0014, notifications in ADR 0020 — while
this ADR owns declaration, storage, verification, and primary selection.

Activating a binding for a capability requires a successful connection check and
a verified capability snapshot. A capability that the adapter does not implement
cannot be marked primary, and the control plane must not present it as the sole
business path.

`ProviderCapabilityReconciliationService` performs this before activation for
payment, delivery and notification. Its connection result means the credential
reference resolved and the configured adapter was found; `last_connection_evidence`
and the append-only probe row say so explicitly. It is not a fabricated claim
that a provider accepted a live charge, booking, or message. Each snapshot entry
is `SUPPORTED` only when the resolved preflight and the adapter declaration both
agree; otherwise activation is refused. POS retains ADR 0011's stronger live
probe, whose evidence is stored in its existing category-specific table.

## Security and audit

- Secret values are write-only through the API and ideally submitted directly to
  the secrets manager workflow. Responses return reference metadata only.
- Installation creation, binding activation and suspension, capability changes,
  secret-reference changes, and connection checks are ADR 0027 audit facts.
- Authentication headers and sensitive payload fields are redacted from logs and
  traces by the shared Camel policy in ADR 0007.
- Managing installations requires `integration.installation.manage` and binding
  activation requires `integration.binding.activate` from ADR 0025.

## Testing

- A location binding referencing a brand outside its tenant fails at the
  database, not only in application code.
- Two primary bindings for one scope and capability cannot both be active.
- Rotating a secret leaves the installation ID, bindings, and mappings intact.
- A capability absent from the adapter cannot be activated or marked primary.
- Reconciliation stores only a redacted evidence description, never the resolved
  credential, and appends `CONNECTION` plus one result per capability in V0083.
- Mapping conflicts surface as conflicts rather than last-write-wins.
- A secret value never appears in an API response, log, trace, or database
  column, asserted by a test that scans captured output.
- A tenant cannot read or bind another tenant's installation.

## Rollout and rollback

Ship the model with one fake provider category and no live credentials. Add POS
under ADR 0011, then payments under ADR 0013, then notifications and delivery.
Each category's first binding stays suspended until connection and capability
checks pass. Rollback suspends bindings; installations, mappings, and evidence
are retained for reconciliation.

## Implementation checklist

- [x] Add installation, binding, binding-capability, mapping, and environment tables (`V0013`).
- [x] Add the provider environment catalogue as platform-owned reference data. Egress enforcement lands with ADR 0007.
- [x] Implement `ProviderInstallationLookup` and `ProviderEntityMappingLookup`.
- [x] Implement safe connection preflight, capability verification, append-only evidence storage, and activation gating (`ProviderCapabilityReconciliationService`, `ProviderCapabilityCatalog`, `V0083`).
- [x] Implement control-plane installation and binding APIs with ADR 0025 capabilities.
- [x] Integrate ADR 0028 secret references and rotation without ID change.
- [x] Define `provider_binding_id` as `BindingRef`, so ADRs 0013, 0014, and 0020 reference a real type.
- [x] Add ancestry, primary-uniqueness, mapping-conflict, and secret-reference tests.

## Exit criteria

Every provider-facing capability resolves its external account through one
binding lookup, `provider_binding_id` is a real foreign key everywhere it
appears, no module holds a second mapping or credential store, and rotating a
credential never changes an installation or binding identifier.

## References

- [ADR 0011: POS installations, bindings, and capability adapters](../partial/0011-pos-installations-bindings-and-capability-adapters.md)
- [ADR 0028: Secrets management and credential lifecycle](../partial/0028-secrets-management-and-credential-lifecycle.md)
