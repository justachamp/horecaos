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
| `control-plane` | `/api/v1/control-plane/**` | [horecaos-api.control-plane.json](openapi/v1/horecaos-api.control-plane.json) | [horecaos-api-v1.control-plane.ts](generated/horecaos-api-v1.control-plane.ts) | `frontend/control-plane` |
| `operations` | `/api/v1/tenants/**`, `/api/v1/session/**`, `/api/v1/operations/**`, `/api/v1/courier/**`, `/api/v1/platform-admin/**` | [horecaos-api.operations.json](openapi/v1/horecaos-api.operations.json) | [horecaos-api-v1.operations.ts](generated/horecaos-api-v1.operations.ts) | `frontend/operations` |
| `providers` | `/providers/**`, `/api/v1/partner/**` | [horecaos-api.providers.json](openapi/v1/horecaos-api.providers.json) | [horecaos-api-v1.providers.ts](generated/horecaos-api-v1.providers.ts) | no frontend — external payment (Click, Payme) and aggregator/marketplace callers; kept versioned like every other surface |

Each group document is served at `/v3/api-docs/<group>` (Swagger UI at
`/swagger-ui.html` lists all five). Group names are stable identifiers — they
are the URL segment and the baseline/client filename suffix — so renaming one
is a breaking change to whichever frontend pinned it.

`operations` is a catch-all for every operator-facing prefix that is not
`storefront`, `control-plane`, or `providers`; `platform-admin` (HorecaOS's own
staff, distinct from a tenant's operations staff) and `courier` (a courier's
own self-service endpoints, distinct from the operations staff managing them)
have no dedicated frontend today and fold into it by elimination rather than by
a considered decision. Splitting either out is a fair question for a future ADR
once a consumer exists.

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
