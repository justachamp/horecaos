# External connections: staff access (wave `w13-staff-access`, batch 21)

**Last executed:** never. Everything below was built against a fake or a null provider, and this file is
what the owner completes to connect each one. One section per fact: what exists, where the value goes,
how to prove the connection. Rows: `9.1b` (nothing external; section 4 is a deployment check), `9.2c`
(sections 1 and 2), `X.5` (sections 3 and 5).

## 1. A POS vendor whose documented create-order API carries the operator (row 9.2c)

**What exists.** A colleague is paired with the till's own operator id from the console («Настройки» →
«Интеграции» → an installation → «Соответствия» → «Операторы»); the platform resolves the pairing off the
person who accepted an order onto `OrderExport#operatorExternalId`. The new vendor capability
`ORDER_OPERATOR_ATTRIBUTION` is the sender's half: an adapter declares it only if its vendor documents the
field, and the export path hands an operator id **only** to an adapter that declares it. `PosAdapterContractTests`
holds a declaring adapter to putting the operator in the create body and a non-declaring one to never doing
so. The controlled fake declares it (and V0694 records fake-pos `SUPPORTED`); **Clopos does not (V0694
records `UNSUPPORTED`)**, because its documented `CreateOrderRequest` names `sale_type_id`, `venue_id`,
`customer` and `products` and nothing else (`docs/providers/clopos-api.md` section 6.5). Until a vendor
declares it, the pane says «this till does not receive an operator yet» and the pairing reaches no order.

**What the owner supplies.** Either (a) Clopos's answer to the question below, or (b) a second POS adapter
whose API docs carry an operator (iiko or R-Keeper).

> **Q23 to Clopos** (add it to section 12 of `docs/providers/clopos-api.md` with the date and the answer):
> does `POST /orders` accept the user, waiter or cashier who took the order (a field on the request, or a
> header), and if so what is it called, what does it take (the id from `GET /users`?) and what does it do
> to an order created with the integration's Staff user as its author?

**Where the value goes.** When a vendor documents the field: (1) add the field to the body map in that
adapter's create-order method from `order.operatorExternalId()`, (2) add `PosCapability.ORDER_OPERATOR_ATTRIBUTION`
to its `declaredCapabilities()`, (3) a **new** migration that updates the vendor's ceiling row in
`integration.pos_provider_capabilities` from `UNSUPPORTED` to `SUPPORTED` with the evidence and date (never
edit V0694), (4) quote the field in the provider note. `PosAdapterContractTests` already parameterises over
the adapter list, so adding the adapter to `tills()` is the test.

**How to prove the connection.** With a live credential and a pairing written in the console: have the paired
colleague accept an order on the console, export it, and read the order at the till: the operator is the
paired one. Then `./mvnw test -Dtest=PosAdapterContractTests` is green with the adapter in the list.

### 1a. Clopos `GET /users`: the response shape is not verified

`CloposAdapter#discoverOperators` lists the brand's users for the pairing. It reads the same envelope every
Clopos list uses (a `data` array) and, per user, `id` plus a name from `name`, else `full_name`, else
`first_name` + `last_name`, else «Clopos user #id». It never reads a login, email or phone. **The page
`users/get-users` was read on 2026-08-23 but its schema was not quoted in the notes, and on 2026-10-14
`developer.clopos.com` answered every path (including `/api-reference/v2/openapi.json`) with its single-page
app shell, so the schema could not be re-read.** The tests (`CloposAdapterTests`) pin the assumption, not
the vendor.

**How to prove it.** With a live credential: open the operators tab of the installation's mapping pane (the
right-hand list is `discoverOperators`) or, from a shell, authenticate as the adapter does and `GET /users`
with the `x-token` and `x-venue` headers, and compare: ids and names must appear. If the names live under other keys, change `operatorName` in `CloposAdapter` and
the fixtures in `CloposAdapterTests`; if the list is paged differently, change `discoverOperators`' walk.
Record the result in `docs/providers/clopos-api.md` section 12 and delete this subsection.

## 2. The ceiling is data, not code

V0694 is the only migration that decides which vendor receives the operator. Reading the answer to
Q23 (section 1) is a product decision; nothing else in the build changes when it arrives.

## 3. Mail delivery for the password reset (row X.5)

**What exists.** «Мой профиль» → «Безопасность» → «Пароль» hands the person to ADR 0098's reset page
with their own sign-in name already typed (in the router's state, never the address), and says that a
reset ends every sign-in, this console's included. `SmtpPlatformMailer` is real and tested against Mailpit
(`SmtpPlatformMailerTests`); the reset relay queues and waits. A new self-service read,
`GET /api/v1/operations/tenants/{tenantId}/staff/me/password`, answers `emailConfigured`; while it is
`false` the card says that this installation cannot send email yet, and the button is off, because a link
that cannot arrive is a promise nobody can keep.

**What the owner supplies.** A sending provider with SMTP submission; the SPF, DKIM and DMARC records on
`horecaos.uz`; and counsel's confirmation that a hosted provider abroad may process staff email addresses
under ZRU-547 (ADR 0097's open input).

**Where the values go.** `/etc/horecaos/production.env` on pre-production and production
(`HORECAOS_MAIL_FROM`, `HORECAOS_MAIL_SMTP_HOST`, `HORECAOS_MAIL_SMTP_PORT`, `HORECAOS_MAIL_SMTP_SECURITY`,
`HORECAOS_MAIL_SMTP_USERNAME`, `HORECAOS_MAIL_SMTP_PASSWORD_REF`). The password itself goes into OpenBao,
never into the env file: follow `docs/runbooks/platform-email.md`, which already walks the whole procedure
and has «Last executed: never».

**How to prove the connection.** Restart the platform; «Мой профиль» → «Пароль» no longer shows the
«cannot send email yet» sentence; press «Сменить пароль», open the link that arrives, set a new password,
and see the console return to the sign-in page (every sign-in ended). Update the «Last executed» line of
`platform-email.md` with the date and host.

## 4. Deployment check for the roles backstop (row 9.1b, V0693)

Not an external fact; a precondition. V0693 turns on row-level security for `iam.roles` and
`iam.role_capabilities`, and `RoleRegistrySynchronizer` now writes the platform's jobs under
`horecaos_platform_bypass` (`SET LOCAL ROLE`, one transaction). `horecaos_app` must be allowed to assume
that role, which `infra/production/postgres-init/10-application-role.sh` grants `WITH INHERIT FALSE`.
Inventory's expiry sweep has needed the same membership since V0161, so a database that runs it has it.
Before deploying, on pre-production:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -c \
  "SELECT pg_has_role('horecaos_app', 'horecaos_platform_bypass', 'MEMBER')"
```

Expect `t`. If it answers `f`, the platform fails to start (the synchronizer cannot write) rather than
quietly serving without its jobs; grant the membership with the script's statement and restart. Rollback
of the backstop alone is `ALTER TABLE iam.roles DISABLE ROW LEVEL SECURITY` and the same for
`iam.role_capabilities` in a forward migration; no data changes.

## 5. First real-realm serving of the session list (row X.5, ADR 0176)

**What exists.** Everything: the ledger, the Keycloak adapter, the three routes and the «Сеансы» card,
proven against a stateful fake and a throwaway Keycloak 26.7 (`infra/keycloak/spikes/staff-sessions-probe.py`).
No real realm has served the list; `docs/runbooks/staff-sessions.md` says «Last executed: never».

**What the owner does (it needs the owner's go for a deploy).** On pre-production: run
`infra/keycloak/create-staff-login-client.sh` (idempotent), put the last line it prints in
`/etc/horecaos/production.env` as `HORECAOS_KEYCLOAK_STAFF_LOGIN_CLIENT_UUID=<the client's internal id>`,
and restart the platform. With the variable unset the card still renders and lists only the platform's own
record.

**How to prove the connection.** Sign in on two devices; «Мой профиль» → «Сеансы» lists both with their
browser, system and form and «Keycloak: available», «Это устройство» on the right one; «Выйти на всех
устройствах» ends the other. Update the «Last executed» line of `staff-sessions.md` with the date, the host
and what it found.
