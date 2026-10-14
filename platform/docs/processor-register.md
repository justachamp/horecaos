# Processor register

Every party outside the platform's own hosts that receives data from it, and the
[ADR 0029](adr/partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) data
classes each may see. It is a privacy and access-control artifact, not a residency one
([ADR 0034](adr/partial/0034-hosting-environments-topology-and-data-residency.md): no
in-country storage obligation currently binds the platform, and counsel confirms or changes
that). ADR 0034 said this register would exist and it did not; ADR 0145 added a processor
that needed one. This is its first version, seeded from ADR 0034's "What leaves" table and
extended with the geocoder.

**Last reviewed:** 2026-10-14 (batch 21). **Holder:** the platform owner (Ayubkhon Abbosov).
**Counsel has not reviewed this register**: every "foreign" row below is a statement of fact
about where a request goes, not a legal opinion that it may.

## How to read it

The data classes are the ones in `DataClass` and ADR 0029:

| Class | Means | Encrypted at rest |
|---|---|---|
| `PUBLIC` | Published to anyone: the menu, its pictures, storefront assets | no |
| `INTERNAL` | Operational facts naming no person: service and alert names, status codes | no |
| `PERSONAL` | Name, contact, **address**, device, preferences | yes (ADR 0029) |
| `PERSONAL_SENSITIVE` | Identity documents, precise location history, biometric-adjacent data (a courier's track) | yes |
| `FINANCIAL` | Payment references and settlement identifiers | yes |
| `SECRET` | Credentials; never in a business table (ADR 0028) | yes |

A row says which classes the processor **may** see, which is the ceiling the platform
enforces by what it sends, and not a promise about what the processor does with them. A class
not listed is a class the platform does not send; if code starts sending one, this register
changes in the same commit or the change is wrong.

## By data class (the key)

| Class | Leaves the platform's hosts, in the clear, to | Rule |
|---|---|---|
| `PUBLIC` | the CDN edge (when one is adopted), the external uptime probe | No restriction beyond not mislabelling a private asset as public (ADR 0010). |
| `INTERNAL` | alert delivery (Telegram, SMS), managed error tracking (when adopted) | Alert bodies carry a service name and an alert name and never an order, customer or amount. Error tracking is a log sink and inherits ADR 0029's rule: nothing is sent that a log may not carry. |
| `PERSONAL` | the SMS and messaging providers (a recipient and a text), the POS and payment providers (as each integration requires), **the geocoder (a typed address and a customer-confirmed pin)** | Only what the task needs. The geocoder receives an address *string* and, for a reverse lookup, one point; it is never sent a customer's name, phone or order, and nothing it answers is stored as a coordinate source of its own (`GEOCODER` is never written). |
| `PERSONAL_SENSITIVE` | **nobody** | A courier's track, an identity document and the compliance file never leave. The live map draws positions in the browser from the platform's own endpoint; no position is sent to a map vendor. |
| `FINANCIAL` | the payment providers (Click, Payme and the card merchant account), the fiscal operators | Payment references only, never a card number (ADR 0095). |
| `SECRET` | **nobody**, except to the one provider a secret is for, at call time, from OpenBao | A secret is a reference everywhere else (ADR 0028). |

Ciphertext is not a class here: an off-site backup carries every class as AES-256
ciphertext whose key does not travel with it (ADR 0034), which is why that row lists none.

## The register

| # | Processor | Role | Where it runs | Classes it may see | What it carries | Controls | Status |
|---|---|---|---|---|---|---|---|
| 1 | Off-site backup destination | Holds the encrypted backup | A domestic bucket preferred; foreign only if no domestic one exists | none in the clear | AES-256 ciphertext only | Encrypted before upload; the key is not in the destination's account; the script refuses an off-site endpoint equal to the primary (`infra/backup`) | Configured by the owner (`HORECAOS_BACKUP_OFFSITE_*`); not yet proved against a real remote bucket (ADR 0034) |
| 2 | External uptime check | Probes the platform | Foreign or domestic, the owner's choice | `PUBLIC` | A URL, a status code, a response time | The probe hits an unauthenticated health endpoint that answers status and nothing else | Configured outside this repository; not verified |
| 3 | Alert delivery (Telegram, SMS) | Reaches the operator | Telegram: foreign. SMS falls back to a domestic provider so the last-resort path stays in-country | `INTERNAL` | Service name and alert name | No order, customer or amount ever appears in an alert body (`docs/runbooks/alerts.md`) | Configured by the owner |
| 4 | Managed error tracking | Collects stack traces | Foreign | `INTERNAL` | Stack traces and request context, scrubbed | Adopted only with personal data scrubbed before send: no request bodies, query strings or headers | **Not adopted** |
| 5 | CDN edge | Serves public media and storefront assets | Foreign or domestic | `PUBLIC` | Public menu media, storefront assets | No personal data cached; authenticated traffic is not terminated at the edge. An edge that would terminate TLS for authenticated traffic becomes a processor and is assessed as one | **Not adopted** |
| 6 | POS, payment and SMS providers: Clopos, Click, Payme, Eskiz | Execute the integration they are for | Domestic | `PERSONAL`, `FINANCIAL` | Order and contact data as each integration requires | ADR 0026 installations and bindings; secrets by reference (ADR 0028); contract tests per adapter; no provider DTO in the core (`docs/providers/`) | In use by the pilot where configured; a second POS vendor later does not change this row |
| 7 | **Geocoder (server side): Yandex Geocoder and Suggest** (ADR 0145; first adapter, chosen provisionally until the bake-off) | Turns a typed address into places and a pin into an address | **Foreign** (exact infrastructure to be confirmed) | `PERSONAL` only | The address text a person typed (customer or operator), a customer-confirmed or operator-placed pin for a reverse lookup, the region's bounding box as a bias, the locale | One platform licence and one server key, an ADR 0028 reference (`horecaos.geo.yandex.secret-reference`); every call goes through the platform (`GeocodingService`): region applied and checked, rate limited per person and per tenant, answered from a thirty-day cache, metered (`geocode.requests`); the query string is the provider's and is never logged (`ProviderHttpClient`; `SensitiveQueryTurboFilter` backstop); nothing the provider returns is persisted, and `GEOCODER` is never written as a coordinate source | **Built, not connected**: no environment holds a key, so no request has ever left. Counsel's view on a foreign processor is outside the build |
| 8 | **Map tiles and script (browser side): the geocoder vendor's map API** (ADR 0145) | Draws the map the viewer is looking at | **Foreign** | `PERSONAL` (the viewer's device: address, user agent, referrer, the area on screen) | The vendor's script and tile requests made by the viewer's own browser, carrying the browser key | The key is public and referrer-restricted at the vendor; the script is fetched from the vendor's origin only when a map opens and is never bundled; **pins, couriers, orders, branches and zone outlines are drawn in the browser and are not sent to the vendor**; with no browser key there is no script and no request | **Built, not connected** (no browser key anywhere). The previously committed shared key is still live in the owner's vendor account until it is restricted or rotated (`docs/runbooks/external-connections/w3-delivery-maps.md`, section 6) |

Rows 7 and 8 are two processors in one vendor on purpose: one receives text the platform
chose to send and the other receives what a browser asks for, and the controls differ.

## Integrations in the repository that are not entered above

These exist (see `docs/providers/` and `docs/runbooks/`) and have not been assessed against the
classes yet. Their holders enter a row, or write why none is needed, before the integration is
enabled for a customer: the e-invoicing operators (Didox, Faktura.uz), the platform email
provider (`platform-email.md`), the Telegram bot as a customer channel
(`connect-telegram-bot.md`), the grounded assistant's model provider
(`connect-assistant-provider.md`), the marketing SMS route, and the fiscal operators behind the
payment providers (`fiscalization-via-payment-providers.md`). Self-hosted components
(PostgreSQL, Kafka, Keycloak, OpenBao, the object store, the OSRM engine) are not processors.

## Changing this register

A new processor, a new class sent to an existing one, or a new place a processor runs is a row
or a cell changed here **in the same commit as the code that sends it**, and is recorded in the
ADR that introduces it. Removing a row needs the integration gone from the code, not merely
switched off.
