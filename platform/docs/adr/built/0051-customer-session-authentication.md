# ADR 0051: A proven phone number becomes a platform-issued opaque session

- Decision status: Accepted
- Implementation status: Built — `POST /identity/sessions` redeems a verification grant,
  resolves or creates the account behind the proven number, and returns an opaque bearer;
  `CustomerSessionAuthenticationFilter` resolves that bearer to `customer.customer_sessions`
  ahead of the resource server, `CustomerSessionBearerTokenResolver` keeps it away from the
  JWT decoder, and `PrincipalCustomer` answers both principal models. Identity mode is
  enforced per request by comparing the session's stored partition against the partition the
  tenant's current policy implies. An ended session answers `SESSION_EXPIRED` rather than
  `UNAUTHENTICATED`. `DELETE /identity/sessions/current` signs out. The three pre-account
  identity endpoints are permitted in the filter chain, which they had never been.
- Date proposed: 2026-08-28
- Date decided: 2026-08-28
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0003, ADR 0015, ADR 0025, ADR 0028, ADR 0029, ADR 0031, ADR 0033,
  ADR 0047, ADR 0049
- Supersedes / Superseded by: supersedes ADR 0003's "build an in-house identity provider —
  never" for the customer principal only, and ADR 0015's assumption that a Keycloak
  authentication flow mints the customer session. Staff authentication, tenant matching and
  the organization claim are untouched / —
- Open inputs: none

## Context

A customer proves a phone number with a one-time code and receives a single-use grant.
They may then register, which yields an account id. Neither is a credential this API
accepts: every customer endpoint resolves its caller from a validated JWT through
`PrincipalCustomer`, and nothing in the platform mints one.

So the storefront's only route to a token was the staff OAuth flow against Keycloak. That
is what the owner has been looking at: a consumer asked to join the operator's identity
directory in order to buy lunch, and every guarded route bouncing to a login page built for
staff.

ADR 0049 decided how a non-staff principal is **authorized**. It did not decide how a
customer **authenticates**, and that undecided seam is why none of the pieces connect.
`CustomerVerificationService.redeem` names the missing half in a javadoc — a Keycloak
authenticator flow nobody has written — and `login-code-step.ts` states in its own javadoc
that verification "cannot succeed today". Both are honest, and both have been true for a
year, which is how long a decision made only in comments survives.

Two smaller facts turned out to be part of the same problem. The three pre-account identity
endpoints were never added to the filter chain's `permitAll` list, so a request for a code
was answered 401 before the handler that was written for an anonymous caller could run.
And no local profile can send an SMS at all, so even a corrected chain could not be
exercised on a laptop.

## Decision

**The platform mints its own customer sessions, as opaque database-backed bearer tokens,
and does not add a second JWT issuer.**

- `POST /api/v1/storefront/tenants/{t}/brands/{b}/identity/sessions` redeems a verification
  grant and returns a token. The endpoint is unauthenticated because the grant is the
  authorization and it is spent there.
- The token is 256 bits from a CSPRNG behind the fixed prefix `qcs1.`, stored only as a
  SHA-256 digest in `customer.customer_sessions`. It encodes nothing. Tenant, brand,
  account and identity partition are columns on the row the digest finds.
- Sign-in resolves the account through `customer.principal_links` on
  `(urn:horecaos:customer-identity:proven-phone, <ADR 0029 per-tenant keyed hash of the
  number>)`. The issuer is a code constant, not configuration.
- A filter placed before the resource server's bearer filter resolves the token and sets a
  `CustomerSessionAuthentication` carrying no authorities. A `BearerTokenResolver` returns
  null for a customer token so the JWT decoder is never offered one.
- `PrincipalCustomer` answers a session by comparing the session's stored identity
  partition against the partition the tenant's **current** policy implies for the brand
  being addressed. Under `TENANT_SHARED` one session reaches every brand; under
  `BRAND_ISOLATED` it reaches the brand it was minted at.
- An ended session — expired or signed out — answers 401 `SESSION_EXPIRED`. A token this
  platform never issued answers 401 `UNAUTHENTICATED`. A client can tell the two apart.
- A customer session confers no authority. Authorization remains ADR 0049 ownership.
- One phone number, configured only under a local profile, signs in with a fixed code and
  sends no message. A non-local profile that has that configuration refuses to start.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| **Keycloak mints it** — a custom authenticator SPI redeems the grant, finds or creates a realm user for the number, and issues a normal access token | The realm already exists and the resource server already trusts it, so this is the option with the fewest moving parts on paper. It costs: a realm user per consumer, in the same directory as staff, where one mis-set organization claim reads as a staff principal; a Java provider jar deployed into Keycloak, which is a second deployable with its own release cadence and upgrade risk; and Keycloak on the sign-in hot path, so an identity-provider outage stops customers ordering as well as stopping staff working. It also does not avoid phone-keyed identity — its authenticator finds the user *by the number* — so the property people fear is present either way, with the operator lever in a different database from the order history it protects. And the practical fact: nobody wrote it, for a year, which is why this seam was still open | A customer needs federation, MFA, or social login — all of which are Keycloak's actual strengths and none of which a phone-code storefront has asked for |
| **A second JWT issuer the platform runs** — sign a customer JWT with a platform key, trust two issuers on customer paths | Answers `PrincipalCustomer`'s argument by contradicting it. A JWT subject is unique only within the realm that minted it, so two trusted issuers mean a subject string from one can be minted to match a subject from the other; the mitigation is to key every lookup on `(issuer, subject)` and never on subject alone, which is exactly the discipline that is easy to state and easy to lose in one query. It also buys key management, JWKS publication and rotation (ADR 0028) to obtain statelessness the platform does not need — every request already reaches PostgreSQL | Session resolution measures as a bottleneck, which needs a load profile this platform does not yet have |
| Treat the verification grant itself as the session — long TTL, reuse it | Collapses two things with opposite requirements. A grant is single-use so that a proven number cannot be replayed; a session is multi-use by definition. Making it reusable removes the property that makes the code exchange safe | Never |
| A stateless signed cookie or HMAC token with the account in it | Cannot be revoked. "I lost my phone" then has no answer short of rotating a global key and signing out every customer of every tenant | Never |
| Keep deferring, and let the storefront use the staff flow | The status quo, and the reason the product does not work. It also puts consumers in the staff directory, which is the cost the Keycloak option was rejected for, without any of that option's benefits | Never |

## Consequences

### Positive

- A customer can sign in and stay signed in, which is the first time that has been true.
- Sign-in does not depend on Keycloak, so an identity-provider outage stops staff working
  and does not stop customers ordering.
- Revocation is a row: one session, or every session an account has, ends in one statement
  at the authority — not when a copied claim happens to expire.
- Consumers are not in the operator's identity directory, so a claim mapping mistake cannot
  make a customer read as staff.
- The `(issuer, subject)` discipline is intact and the resource server still validates
  exactly one JWT issuer.

### Negative

- Session resolution is a database read on every authenticated customer request. It is one
  probe on a unique index, and every such request already reaches PostgreSQL, but it is a
  read that a stateless token would not do.
- There are now two authentication paths through the filter chain, and a reader has to know
  that the prefix is what routes between them.
- The platform owns session lifetime, expiry and revocation, which are things it previously
  owned none of.

### Accepted trade-offs

**Identity is keyed on a proven phone number, and ADR 0015 argues against phone-keyed
identity.** That has to be stated rather than glossed. What ADR 0015 forbids is resolving an
account from a number a request *asserts* — a contact table full of unverified, imported and
stale rows, where a recycled number silently hands one person another's history. Here the
number was proved seconds earlier by a one-time code, and what it resolves through is a
durable `customer.principal_links` row an operator can set to `UNLINKED`. After that, the
same number proves the same control and reaches a **new** account; the old one keeps its
orders and becomes unreachable. That detach lever is the thing ADR 0015 wanted an identity
provider for, and it is better held here: it is in the same database, and can be in the same
transaction, as the history it protects. The Keycloak option would have had the identical
property with the lever one system further away.

The subject stored is the ADR 0029 per-tenant keyed hash, not the number. The same person at
two tenants is two unrelated subjects, and a database dump yields nothing to look anybody up
by. It is the value `customer.contact_points` already stores, so nothing new about a
customer is written down.

## Specification

### The session

`customer.customer_sessions` (V0092) holds `id`, `tenant_id`, `brand_id`,
`customer_account_id`, `identity_partition_brand_id`, `token_hash`, `issued_at`,
`expires_at`, `revoked_at`. `token_hash` is unique and constrained to 64 lower-case hex
characters. There is no phone number, no hash of one, and no reference to the verification
challenge — that row carries an encrypted number and is purged on its own retention
schedule.

Establishing a session is one transaction: the grant is spent by the conditional `UPDATE`
that makes it single-use, and the session row is written in the same unit of work. There is
no interval in which a customer's proof has been consumed and they hold nothing.

The identity partition is copied from the **account row**, not recomputed from the tenant's
policy. Those agree today and would disagree across a governed cutover, and the account's own
column is where the account actually is.

### Resolution

`CustomerSessionAuthenticationFilter` runs before `BearerTokenAuthenticationFilter` and only
looks at a bearer value carrying `qcs1.`. Everything else falls through, so staff
authentication is unchanged. `CustomerSessionBearerTokenResolver` returns null for the same
values, so the resource server never offers one to the JWT decoder — without it a customer
token would be rejected by Spring's own header pattern, which excludes `_`, a character in
the Base64url alphabet the token is drawn from.

The filter answers its own failures. A controller advice cannot: it is downstream of every
filter, and the distinction being drawn is one the generic entry point cannot make.

`JwtCurrentActor` returns an actor for any authentication whose principal is a
`NonStaffPrincipal`, carrying no global and no organization roles. Without that every
`@Idempotent` storefront mutation would refuse a signed-in customer inside an interceptor,
with a 403 describing the wrong problem. The subject is namespaced `customer:<accountId>`
and cannot collide with a Keycloak subject.

### Expiry

Default lifetime `P30D` (`horecaos.customers.session.ttl`). An expired or revoked session is
401 `SESSION_EXPIRED`; an unknown token is 401 `UNAUTHENTICATED`. A client that branched on
status alone would treat them identically, which is how a customer whose token expired
mid-basket gets shown the screen a first-time visitor sees, with no explanation that
anything happened.

`CustomerSessionSweeper` deletes sessions that ended more than `P7D` ago. It is housekeeping
and never a correctness control: every resolution tests `expires_at` and `revoked_at`
itself, so a sweeper that stopped would leave rows and would let nobody in.

### The two test numbers

They are not the same kind of thing and must not be built as though they were.

**The fixed-code number.** `horecaos.customers.verification.preset.phone` and
`.code`, set only in `application-local.yml`, which is activated by the `local` profile —
the same binding `db/local-fixtures` has. `PresetVerificationCodeSource` is `@Profile({local,
test, default})` and conditional on the phone property, and it answers exactly one
canonicalised destination; every other number on the same profile still draws a random code
and still needs a transport, so the preset cannot hide a broken SMS path. A preset code sets
`requiresDelivery = false`, which skips the transport entirely and skips the per-destination
issuance budget — that budget bounds an SMS bill and a brute-force oracle, and a code that is
never sent creates neither.

`PresetVerificationCodeGuard` refuses to start any non-local profile that has either
property set, naming both. The bean already could not be created there; the guard exists
because "silently ignored" is the state in which somebody discovers, months later, that a
deployment has been carrying a bypass switch that happened not to be wired.

The shipped default is `+998000000000`. Uzbek mobile operator codes are two digits in the 33
and 88–99 ranges, so `00` addresses no subscriber while still satisfying `PhoneNumber`'s
`+998`-and-nine-digits rule. A plausible-looking number would be a real person's handset in
a configuration file.

**The real-SMS number is not a test number.** It is an ordinary Uzbek mobile, and what it
needs is a configured SMS gateway. The route, the adapter and the approved endpoint all
exist (ADR 0007, ADR 0026, V0061); what is missing in any given deployment is an
installation, a binding and a credential. `docs/providers/sms-gateway-vas.md` lists the three
inputs, and the credential is the operator's to place in OpenBao — never in a commit, never
in configuration (ADR 0028). An unconfigured deployment says so: the transport answers
`NO_PROVIDER_BINDING` or `SMS_ROUTE_UNAVAILABLE`, the challenge is withdrawn rather than left
live with a code nobody can know, and the customer is told the code could not be sent. It
does not swallow the message.

### The filter chain

`POST .../identity/verification-challenges`, `.../attempts` and `.../sessions` are
`permitAll`, listed one path at a time. `POST .../registrations` is not: it requires a token
by design. The list is explicit rather than `/identity/**` for the reason the storefront GET
list already gives — a wildcard opens whatever is added next, silently.

## Rollout and rollback

One migration, additive, with no backfill: V0092 creates a table nothing yet reads. Ship it
with the code.

Rolling back the code leaves the table in place and unread, and returns the storefront to the
state the owner reported — no customer can sign in. Rolling back the migration after sessions
exist signs every customer out; they sign in again by proving their number, which costs one
SMS each.

There is no flag. A switch that turned customer sessions off would turn sign-in off, and an
opt-out for an authentication path is a way of shipping two authentication paths and testing
one.

## Implementation checklist

- [x] `customer.customer_sessions` with a unique token digest and an application `GRANT`.
- [x] `POST /identity/sessions` redeeming a grant into a session.
- [x] `DELETE /identity/sessions/current` signing out, and revocation for a whole account.
- [x] A filter and a bearer-token resolver that route the two principal models apart.
- [x] `PrincipalCustomer` answering a session, including the identity-mode split.
- [x] `NonStaffPrincipal` so idempotency scoping has a subject for a customer.
- [x] `SESSION_EXPIRED` distinct from `UNAUTHENTICATED`.
- [x] The three pre-account identity endpoints permitted in the filter chain.
- [x] A local-only preset code, and a guard that refuses to start a real profile with one.

## Exit criteria

A customer signs in on a local profile with the preset number and no SMS, and reaches their
own account, cart and orders with the token they were given. A non-local profile with the
preset configured fails to start. A number that is not the preset still goes to the SMS
adapter. An expired token answers `SESSION_EXPIRED` and an unknown one answers
`UNAUTHENTICATED`. Under `BRAND_ISOLATED`, a session minted at one brand resolves no account
at a sibling brand; under `TENANT_SHARED`, it resolves the same account at both. A staff
token behaves exactly as it did before.

## References

- [ADR 0003: Keycloak tenant authorization](../built/0003-keycloak-tenant-authorization.md)
- [ADR 0015: Customer accounts and cross-brand identity](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md)
- [ADR 0047: Dine-in table service and QR ordering](../partial/0047-dine-in-table-service-and-qr-ordering.md)
- [ADR 0049: Non-staff principal authorization](../built/0049-non-staff-principal-authorization.md)
- [SMS Gate API and what a tenant must supply](../../providers/sms-gateway-vas.md)
