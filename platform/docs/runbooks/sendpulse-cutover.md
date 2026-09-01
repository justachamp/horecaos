# Cutting one bot over from SendPulse

**Last executed:** never — this is a draft.

**Rollback before BotFather token rotation (step 8): repoint the webhook back
to SendPulse (step 6, run in reverse).** SendPulse keeps working the whole
time up to that point — nothing about this procedure closes the SendPulse
account or revokes its access. **After step 8 there is no rollback**: the old
token is dead the instant BotFather issues the new one, so SendPulse can no
longer authenticate to Telegram on this bot's behalf, whatever webhook URL it
still thinks it owns.

Run every step below **once per bot** (ADR 0058's bot-per-brand topology: one
BotFather bot, one `integration.installations` row, one brand). A tenant with
three brand bots runs this whole procedure three times, not once with three
inputs.

## What only the owner can do

Nobody but the business owner holds the SendPulse account or the BotFather
identity behind each bot. Every step below that says **(owner)** cannot be
delegated to an engineer running this runbook — the owner performs the action
personally, or hands the engineer a credential for that one step and rotates
it afterward.

- Exporting the contact list from SendPulse (step 1).
- Every BotFather interaction (steps 6's `setWebhook` needs the bot token,
  which only BotFather or the current `integration.installations.secret_reference`
  holds; step 8's token rotation is a BotFather conversation, full stop).
- The decision to close the SendPulse account once every bot on it has cut
  over (out of scope for this runbook — a commercial/contract action, not a
  technical one).

## Precondition

The tenant's `integration.installations` row for this bot already exists,
`ACTIVE`, with `brand_id` set (ADR 0058 stage 1's own onboarding step) and a
`webhook_secret_reference` (a shared secret this platform chose, distinct from
the bot token itself — ADR 0058, "the webhook `secret_token` mechanism...
`setWebhook` registers it, and the adapter verifies the
`X-Telegram-Bot-Api-Secret-Token` header"). If neither exists yet, this is a
new bot, not a cutover — provision it the way any other Telegram installation
is provisioned first.

```bash
read -rsp 'access token: ' TOKEN; echo
API="https://api.horecaos.uz/api/v1/control-plane/tenants/<tenantId>"
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT id, brand_id, status, webhook_secret_reference IS NOT NULL AS has_webhook_secret
  FROM integration.installations WHERE id = '<installationId>'"
```

**Check:** one row, `status = ACTIVE`, `brand_id` not null, `has_webhook_secret = t`.

## 1. Export contacts from SendPulse **(owner)**

SendPulse's bot-audience export (Automation360 → Chatbots → the bot →
Subscribers → Export, or the equivalent REST endpoint) as CSV or JSON. Column
names vary by account — whatever custom subscriber-form fields this tenant
configured — and that is fine: step 2's parser is deliberately tolerant of
that (`SendPulseContactFileParser`'s own javadoc states the exact column
aliases it recognises for chat id, phone, subscription status, and
subscription date).

**Check:** the file's row count against SendPulse's own subscriber count for
this bot, shown on the same export screen. A mismatch here is caught before
step 2 ever runs, not after.

**Rollback:** nothing was written anywhere. Delete the file if it should not
persist.

## 2. Dry-run import

```bash
CONTENT="$(cat contacts-export.csv | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))')"
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  --data "{\"installationId\":\"<installationId>\",\"format\":\"CSV\",\"fileName\":\"contacts-export.csv\",\"content\":${CONTENT}}" \
  "${API}/sendpulse-imports" | tee dry-run-report.json | jq '.counts'
```

`dryRun` defaults to `true` — this call writes nothing (no customer, no
binding, no consent decision; `SendPulseContactImportService`'s own class
javadoc states the guarantee). Requires `CUSTOMER_IMPORT` (ADR 0025), held by
`tenant-owner` alone — see that capability's own comment for why it is not
also on `tenant-admin`: a bulk write of customer accounts and consent from an
external, about-to-be-retired vendor is the same weight class as
`AUDIENCE_EXPORT`.

**Check:** `jq '.counts'` against the export's own row count —
`total` should equal the file's row count, and
`createdCustomer + matchedCustomer + skippedAlreadyLinked + rejected` should
equal `total`. `jq '.rows[] | select(.outcome=="REJECTED") | .rejectReason'`
lists every rejected row's reason (`MISSING_CHAT_ID`,
`UNRECOGNIZED_SUBSCRIPTION_STATUS`, `MALFORMED_PHONE`,
`AMBIGUOUS_PHONE_MATCH`, or `ACCOUNT_ALREADY_LINKED_TO_ANOTHER_CHAT` — see
`SendPulseImportRejectReason`'s own doc comments for what each means and how
to fix the row).

**Rollback:** nothing was written. Re-run after fixing the export if the
reject list is not empty for a reason worth fixing (a genuinely unrecoverable
row — no chat id at all — is not).

## 3. Review the report **(owner, or whoever the owner delegates the read to)**

Read `dry-run-report.json` in full, not just the counts. In particular:

- `rejected` rows: is the missing/unrecognised data actually unrecoverable, or
  does the SendPulse export need a re-run with a different column selected?
- `createdCustomer` count: this many **new** `customer.customer_accounts` rows
  are about to be created. If this number is surprising (much larger or
  smaller than SendPulse's own subscriber count), stop and find out why before
  step 4 — a wrong `installationId` pointed at the wrong bot's brand is the
  likely cause, and it is much cheaper to catch here than to unwind after a
  real run.

**Check:** the owner (or delegate) explicitly signs off before step 4 runs.
This runbook does not gate that signoff technically — there is no approval
workflow in front of the real-run call — so it is a procedural checkpoint,
not an enforced one.

**Rollback:** none needed; nothing was written.

## 4. Real import

Identical call, `dryRun=false`, a **fresh** `Idempotency-Key`:

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  --data "{\"installationId\":\"<installationId>\",\"format\":\"CSV\",\"fileName\":\"contacts-export.csv\",\"content\":${CONTENT}}" \
  "${API}/sendpulse-imports?dryRun=false" | tee real-run-report.json | jq '.counts, .runId'
```

This writes: a `customer.customer_accounts` row per new contact (matched by
phone where the export carried one; a phone-less contact gets an account with
no Keycloak principal at all, reachable only through its Telegram binding —
ADR 0059's own "Contacts are customers" decision, deliberately, not a
parallel contact store); an `integration.telegram_bindings` CUSTOMER-audience
row plus its `notifications.recipient_endpoints` row, the exact shapes the
live `/start` handshake creates; a `customer.consent_decisions` row per
contact, `source = IMPORT`, `purpose = MARKETING`, `channel = TELEGRAM`,
`decision = GRANTED` for a subscribed contact and `WITHDRAWN` for an
unsubscribed or blocked one — **never a default**, a row either way; and, for
an unsubscribed contact, the TELEGRAM preference written explicitly disabled
for every notification class that respects one.

**Idempotent, provably:** re-running this exact call (even with a fresh
idempotency key, on the same or a re-exported file) finds every already-linked
chat via `TelegramBindingStore#customerAccountFor` and writes nothing further
for it — `skippedAlreadyLinked` accounts for it in the counts instead of
`createdCustomer`/`matchedCustomer`. Safe to re-run after fixing a rejected
row without redoing the whole file by hand.

**Check:** `jq '.counts'` matches the dry run's counts from step 2 exactly
(same file, same starting state — `createdCustomer`/`matchedCustomer` should
be identical; if this real run is not the first against this bot,
`skippedAlreadyLinked` will legitimately be higher). Then:

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" "${API}/sendpulse-imports/<runId>" | jq .
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT count(*) FROM integration.telegram_bindings
  WHERE tenant_id = '<tenantId>' AND retired_at IS NULL"
```

**Rollback:** there is no unimport. A customer account or binding created
here is created for real, the same as one created by a live `/start` — the
honest fix for a bad import is not a rollback but a correction (retire a
wrongly-created binding through the normal retirement path, or reconcile
consent through the normal consent endpoint), because by the time step 6
repoints the webhook these records are what makes the platform recognise the
tenant's existing subscribers at all.

## 5. Verify counts against SendPulse

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT
    count(*) FILTER (WHERE tb.retired_at IS NULL) AS bound,
    count(*) FILTER (WHERE cd.decision = 'GRANTED') AS consented_in
  FROM integration.telegram_bindings tb
  JOIN notifications.recipient_endpoints re
    ON re.provider_binding_id = tb.binding_id AND re.tenant_id = tb.tenant_id
  LEFT JOIN customer.consent_decisions cd
    ON cd.customer_account_id = re.customer_account_id AND cd.source = 'IMPORT'
  WHERE tb.tenant_id = '<tenantId>' AND tb.audience = 'CUSTOMER'"
```

**Check:** `bound` equals SendPulse's own subscriber count for this bot minus
whatever step 3 knowingly accepted as rejected. This is the number that
matters most — every bound chat is a subscriber who will keep hearing from
the business the instant step 6 repoints the webhook; anyone short of that
count goes silent.

**Rollback:** none — this is a read.

## 6. Repoint the webhook to the platform

```bash
BOT_TOKEN='<the current bot token, from BotFather or the current secret reference>'
SECRET_TOKEN='<this installation's webhook_secret_reference value, from the secrets manager>'
curl -fsS "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=https://api.horecaos.uz/providers/telegram/<installationId>/webhook" \
  --data-urlencode "secret_token=${SECRET_TOKEN}"
```

This is the one call that actually moves traffic: Telegram itself, not this
platform, decides where the next update for this bot goes, and it decides
based on whichever `setWebhook` call it received most recently — SendPulse's
or this one. Nothing about this call touches SendPulse's own webhook
registration; it is simply overwritten.

**Check:**

```bash
curl -fsS "https://api.telegram.org/bot${BOT_TOKEN}/getWebhookInfo" | jq .
```

`url` is this platform's endpoint, `last_error_date` is absent or older than
this call, `pending_update_count` starts draining as step 7 proves live
traffic actually arrives.

**Rollback — this is the one that matters:**

```bash
curl -fsS "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=<SendPulse's own webhook URL, recorded before this step>" \
  --data-urlencode "secret_token=<SendPulse's own secret token, if it used one>"
```

Record SendPulse's current `getWebhookInfo` output **before** running the
repoint call, specifically so this rollback command has real values to use.
This works because the bot token has not rotated yet (step 8) — SendPulse's
own webhook registration authenticates with the same token it always did, and
`setWebhook` is genuinely idempotent and reversible up to that point, exactly
as ADR 0059's own words say: "atomic per bot and reversible by the same
call".

## 7. Verify live traffic

Ask a real subscriber (or the owner, from their own account) to send `/start`
or any message to the bot.

**Check, in order:**

```bash
# 1. The webhook was received and deduplicated (ADR 0032 at-least-once dedup,
#    the platform-wide gap wave-8 closed): a row exists, keyed by this
#    installation and Telegram's own update_id.
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT installation_id, update_id, processed_at FROM integration.telegram_processed_updates
  WHERE installation_id = '<installationId>' ORDER BY processed_at DESC LIMIT 5"

# 2. A conversation exists for the chat that just messaged (ADR 0059 stage 1's
#    flow engine, or stage 2's inbox, depending on what this bot has active).
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT id, channel, channel_chat_id, state, created_at FROM conversations.conversations
  WHERE tenant_id = '<tenantId>' ORDER BY created_at DESC LIMIT 5"

# 3. The flow actually advanced past the first block — proof a reply was
#    attempted, not proof Telegram delivered it: ADR 0059 stage 1's own
#    stated trade-off is that a flow send "is a log line, not a retried
#    attempt row" (it goes through the Bot API client directly, never the
#    notifications dispatch pipeline notifications.notifications tracks).
#    The only way to confirm actual delivery of a flow message is asking the
#    subscriber, or the application's own log line for the send.
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "SELECT conversation_id, current_state_id, status, updated_at FROM conversations.flow_runs
  WHERE tenant_id = '<tenantId>' ORDER BY updated_at DESC LIMIT 5"
```

**Check:** all three produce a fresh row for the test message, in that order
(a dedup row with nothing behind it — no conversation, no flow run — means
the inbound path worked but something downstream did not; that is a real
defect to chase, not a webhook problem). For belt-and-braces confirmation
that the welcome message was actually delivered rather than merely attempted,
ask the test subscriber directly — the one proof this runbook cannot get from
a query, by ADR 0059's own accepted trade-off above.

**Rollback:** still step 6's repoint-back command, if this step reveals a
problem worth stopping for. The bot token has still not rotated.

## 8. BotFather token rotation **(owner)**

Talk to `@BotFather`: `/mybots` → the bot → **API Token** → **Revoke current
token**. BotFather issues a new token immediately and the old one stops
authenticating to Telegram's Bot API **at that instant** — every standing
capability the old token carried, including SendPulse's own ability to call
`setWebhook` again and re-point traffic to itself, ends here. This is the
point of no return this runbook's own header names.

**Check:** the old token, tried against any Bot API method
(`getMe` is the cheapest), returns `401 Unauthorized`.

```bash
curl -s "https://api.telegram.org/bot${BOT_TOKEN}/getMe"
# {"ok":false,"error_code":401,"description":"Unauthorized"}
```

**Rollback: none.** The old token is dead. If anything about the cutover
still needs fixing, it is fixed forward from here — reconciling a wrong
binding, correcting a consent decision, or (in the worst case) provisioning
this brand's bot again from scratch under a new BotFather identity — never by
reviving the SendPulse-era token.

## 9. ADR 0028 secret-reference update

The new token has to reach this installation's `secret_reference` (ADR 0028:
the database stores a reference, never the value). Write the new token to the
secrets manager under the same reference this installation already has on
file — `SELECT secret_reference FROM integration.installations WHERE id =
'<installationId>'` — so nothing about the reference string changes and no
other row needs to be touched:

```bash
qc exec -it openbao sh
export BAO_TOKEN=<a token with write access>
# The reference horecaos:{environment}:{category}:{owner}:{id} maps onto this
# KV path one-to-one (OpenBaoSecretResolver#pathFor) — e.g. a reference of
# horecaos:production:provider_notification:tenant-<id>:telegram-bot writes to:
bao kv put horecaos/production/provider_notification/tenant-<id>/telegram-bot \
  value="<the new BotFather token>"
```

**Known gap, named rather than hidden:** there is no HTTP endpoint that
rotates an existing installation's `secret_reference` in place —
`ProviderInstallationController`'s only write is the initial `POST` at
creation. The secret reference string itself does not need to change (only
the value behind it does, which is the entire point of ADR 0028's
reference/value split), so this step is a pure secrets-manager write with no
database write at all — **unless** the reference string is being changed too
(e.g. a fresh installation row for a re-provisioned bot), in which case:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "UPDATE integration.installations
  SET secret_reference = '<new reference string>', updated_at = now(), version = version + 1
  WHERE id = '<installationId>' AND tenant_id = '<tenantId>'"
```

**Never with SQL for anything but the reference string itself** — the same
rule `dead-letter-decision.md` states for its own domain: a hand-edited row
has no audit trail, and this one at least should be rare (most rotations
reuse the existing reference and touch only the secrets manager, above).

**Check:**

```bash
curl -fsS "https://api.telegram.org/bot<the new token>/getMe"
```

`ok: true`, and the application's own next outbound send to this bot
(step 10) is proof the resolver picked up the new value — `OpenBaoSecretResolver`
caches for a TTL, so a send attempted immediately after this step may still
be using the cached old value; wait out the cache TTL or restart the
application process if step 10 needs to run right away.

**Rollback:** none possible past step 8 — see that step's own note. If the
new token was mistyped here, the fix is BotFather issuing another new token
(another rotation), not recovering the one just revoked.

## 10. Final verification

Repeat step 7's three checks — dedup row, conversation, outbound send — with
a **fresh** test message, now that the token has rotated. Then confirm with
the owner that SendPulse's own dashboard shows no further inbound activity
for this bot (SendPulse cannot receive webhooks for it anymore, but its own
UI may still show stale state until the owner closes that bot's automation
there — a SendPulse-side cleanup, not a platform one).

**Check, the exit criterion:** a real subscriber's `/start` reaches the
platform's flow engine and gets the welcome series, exactly as ADR 0059's own
exit criterion states — with the one accepted exception the same record
names: a customer who was mid-flow in SendPulse at the moment of cutover
starts fresh at their next `/start` rather than resuming where SendPulse left
them.

## The mid-flow-state caveat

**Flow state is not exportable, and this runbook does not attempt to migrate
it.** A SendPulse subscriber who is partway through a multi-step SendPulse
flow at the instant step 6 repoints the webhook does not resume that flow on
this platform — the next message they send starts this platform's own
welcome series from the beginning, the same as any subscriber's first
`/start`. ADR 0059's own words: "a customer mid-flow at cutover is treated as
idle and simply starts fresh at their next `/start` (blast radius is small
for a three-message welcome series, and the runbook says so instead of
promising nobody notices anything)."

Two things make this blast radius small rather than a real cost: the
observed SendPulse flow this platform's own welcome series reproduces is
three messages and one captured input (ADR 0059's Context section), so a
customer who was mid-flow rejoins a short flow rather than losing a long one;
and scheduling the cutover (step 6 specifically) for a quiet hour reduces how
many subscribers are ever mid-flow at that exact instant. **This runbook does
not schedule around traffic** — that is a per-tenant decision the owner
makes, informed by knowing when their own subscribers are typically talking
to the bot.
