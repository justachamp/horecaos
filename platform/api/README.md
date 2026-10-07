# API contract releases

[OpenAPI v1](openapi/v1/horecaos-api.json) is the reviewed released contract. It
is generated from Springdoc through the real MVC surface, not maintained by
hand.

Run `make openapi-baseline` after an intentional additive API change. The
command first rejects breaking changes against the current v1 baseline, then
updates the reviewed document and its TypeScript client artifact. CI regenerates
both from the server and fails on a mismatch.

The generated [TypeScript client contract](generated/horecaos-api-v1.ts) contains
schema types plus the typed transport interface that standalone frontend
repositories implement. It deliberately does not choose a browser fetch,
Keycloak, retry, or cache library for them.

## Per-surface groups

The full document above stays exactly as described — every consumer pinned to
`horecaos-api.json` / `horecaos-api-v1.ts` sees no change. Alongside it,
Springdoc also publishes four additive, filtered views of the same running API,
one per `OpenApiSurface`
(`src/main/java/uz/horecaos/platform/configuration/OpenApiSurface.java`), each
with its own checked-in baseline and generated client so a frontend can pin
only the surface it actually calls:

| Group | Path prefixes | Baseline | Client | Consumer |
|---|---|---|---|---|
| `storefront` | `/api/v1/storefront/**` | [horecaos-api.storefront.json](openapi/v1/horecaos-api.storefront.json) | [horecaos-api-v1.storefront.ts](generated/horecaos-api-v1.storefront.ts) | `frontend/storefront`, and eventually `frontend/mobile` |
| `control-plane` | `/api/v1/control-plane/**`, `/api/v1/platform-admin/**` | [horecaos-api.control-plane.json](openapi/v1/horecaos-api.control-plane.json) | [horecaos-api-v1.control-plane.ts](generated/horecaos-api-v1.control-plane.ts) | `frontend/control-plane` |
| `operations` | `/api/v1/tenants/**`, `/api/v1/session/**`, `/api/v1/operations/**`, `/api/v1/courier/**` | [horecaos-api.operations.json](openapi/v1/horecaos-api.operations.json) | [horecaos-api-v1.operations.ts](generated/horecaos-api-v1.operations.ts) | `frontend/operations` |
| `providers` | `/providers/**`, `/api/v1/partner/**` | [horecaos-api.providers.json](openapi/v1/horecaos-api.providers.json) | [horecaos-api-v1.providers.ts](generated/horecaos-api-v1.providers.ts) | no frontend — external payment (Click, Payme) and aggregator/marketplace callers; kept versioned like every other surface |

Each group document is served at `/v3/api-docs/<group>` (Swagger UI at
`/swagger-ui.html` lists all five). Group names are stable identifiers — they
are the URL segment and the baseline/client filename suffix — so renaming one
is a breaking change to whichever frontend pinned it.

`operations` is a catch-all for every operator-facing prefix that is not
`storefront`, `control-plane`, or `providers`; `courier` (a courier's own
self-service endpoints, distinct from the operations staff managing them) has
no dedicated frontend today and folds into it by elimination rather than by a
considered decision. `platform-admin` (HorecaOS's own staff, distinct from a
tenant's operations staff) used to fold into `operations` the same way until
ADR 0066 moved it to `control-plane`, once wave 28 gave platform-admin the
dedicated frontend that had been the open question. Splitting `courier` out
is a fair question for a future ADR once a consumer exists.

**Every path in the full document belongs to exactly one group.**
`OpenApiContractTests#everyPublishedPathBelongsToExactlyOneSurfaceGroup` proves
it from the running server on every build: it fails if a path is missing from
every group (a controller under an uncategorised prefix) or present in more
than one (overlapping `pathsToMatch` patterns). A new controller must land
under one of the prefixes above, or `OpenApiSurface` needs a new constant and
this table needs a new row — the test is the thing that notices if either step
is skipped.

`make openapi-baseline` refreshes and checks in all five baselines and clients
together, from one Maven run; `make openapi-client-check` (what CI runs)
regenerates all five and fails on any undocumented drift.

## The storefront contract (ADR 0070)

The `storefront` group is not an application's private API: it is the contract a
tenant or a vendor builds a storefront against, and our own storefront is a
client of it like any other. The group document says so itself — its
`info.description` carries everything below, so a vendor reading only the
generated document has the same facts as a reader of this file.

**Which storefront is asking.** Every request names its app in
`X-Storefront-App-Id`, in addition to the customer's own session and never
instead of it. Anonymous browsing stays anonymous for the *customer* and
requires an app identity all the same, so the platform can meter, rate-limit
and revoke. An app is registered once by the platform (control plane,
*Providers → Storefront apps*) and authorised per brand by its tenant
(operations, *Settings → Storefront apps*). A refused app is told which thing
is wrong: `APP_UNREGISTERED`, `APP_SUSPENDED`, `APP_NOT_AUTHORISED`,
`APP_REVOKED`, `APP_ORIGIN_MISMATCH`, `APP_SECRET_INVALID` or
`APP_IDENTITY_REQUIRED`. Revoking or suspending takes effect on the app's next
request: the check reads the registry and the authorisation every time.

**Public and confidential clients.** A *public* client is a browser-only
storefront. It holds no secret, because a browser keeps none; the platform
checks each request's `Origin` (or, for a same-origin read that carries none,
the origin of its `Referer`) against the allowlist the app registered. That
makes it attributable and revocable. It does **not** make it authenticated, and
no response or screen says it does. A *confidential* client is server-backed:
it registers a secret, sent in `X-Storefront-App-Secret`, which the platform
mints, shows once, and keeps only as an ADR 0028 reference
(`horecaos:<env>:provider_storefront_app:platform-storefront-apps:<id>`).

**What is promised.** Each operation in the group document carries
`x-horecaos-surface`:

| Marker | Meaning |
|---|---|
| `published` | The guaranteed public surface. A published path is never removed within `v1`; change is additive only. `OpenApiContractTests` holds every one of them to that on every build. |
| `internal` | Channel-specific and not promised: the Telegram link, mini-app and sign-in routes, the dine-in QR exchange and guest session, and the cart's table binding. It stays in the document and the generated client, and the compatibility gate does not hold it. |

The decision is a list a person edits on purpose
(`configuration/StorefrontPublishedSurface.java`), not a property a controller
has by being mounted under `/api/v1/storefront/`; a path in neither list fails
`StorefrontContractDocumentTests`, so a new storefront endpoint is never
published, or promised, by accident. Only the *released* baseline's marker
counts for the gate, so an operation cannot be exempted in the same change that
reshapes it.

**Deprecation policy.** A breaking change to a published path is a new major
version served alongside the old one. The old version keeps working for at
least **12 months** from the day its replacement is announced (the figure is
`StorefrontContractPolicy.DEPRECATION_WINDOW_MONTHS`; ADR 0070 names no number,
so this one is a decision of the build and the single place to change it), and
an operation scheduled for removal is marked `deprecated` in the document from
that day.

**Rollout.** `horecaos.storefront.app-identity.required` (default `false`) is
ADR 0070's switch between stage one and stage three. While it is `false` a
request without the header still works and is counted
(`horecaos.storefront.app.requests{outcome="unattributed"}`); the identity is
made required only once that count has stayed at zero for a stated period, and
setting the property back to `false` is the whole of the rollback.
