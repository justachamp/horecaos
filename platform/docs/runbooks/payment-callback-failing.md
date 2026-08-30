# A payment callback is failing

**Trading-hours alert.** **Last executed:** never — this is a draft.

More than three non-200 responses in five minutes from `/providers/payme/*` or
`/providers/click/*/{prepare,complete}`.

**Why this is not an availability statistic.** Payme and Click both read any
non-200 as a transport failure and retry until the payment reaches their manual
investigation queue. A 5xx here is a customer's money in a state Qoida cannot
see, which is a different thing from a 5xx on the storefront.

## 1. What is failing, and with what?

```bash
qc exec -T platform-app wget -q -O - http://127.0.0.1:8080/actuator/prometheus \
  | grep 'http_server_requests.*providers' | grep -v 'status="2'
```

```bash
qc logs --tail 300 platform-app | grep -i providers
```

**Check:** the status code decides where you go.

- `502` or `504` at the edge — the application is not answering. That is
  [deploy.md](deploy.md), "It did not come up".
- `500` from the application — a genuine fault in the handler. The log line has
  the correlation id; the payload does not appear in it and must not (ADR 0029).
- `403` or a connection refused at the edge — the Payme source allowlist. Section 3.
- Payme returning `200` with a JSON-RPC error code is **not** this alert and is
  correct behaviour: that is how Payme is told about a business error.

## 2. Is the edge passing them through untouched?

```bash
grep -n -A 10 'providers' infra/production/caddy/Caddyfile
```

**Check:** no content filtering, no request rewriting, no WAF rule on these two
roots. ADR 0023 forbids all three. A callback rejected by a content filter
becomes a retried payment and then a manual investigation at the provider, which
is worse than whatever the filter was protecting against.

## 3. The Payme source allowlist

Payme publishes fifteen source addresses, `185.234.113.1–15`, allowlisted at the
proxy as defence in depth and **never as the only check** — the per-cashbox Basic
credential verified inside the controller is the authentication. If Payme adds an
address and the allowlist has not been updated, every callback is refused at the
edge and the application never sees one, which presents as silence in the
application log and a burst of 403s in Caddy's.

```bash
qc logs --tail 300 edge | grep providers
```

Click gets no allowlist: it publishes no equivalent list, so its MD5 signature is
the whole of its authentication, which is why that signature is verified before
any database is touched.

## 4. What you must not do

- Do not "fix" this by answering 200 to everything. The 200 is a contract: for
  Payme it carries the JSON-RPC error envelope, and for Click it carries the
  result code. A blanket 200 tells the provider a payment succeeded.
- Do not treat the binding segment in the path as a secret. It is guessable by
  design; the credential is the authentication, and rotating the path fixes
  nothing.

## 5. Afterwards

Every callback that got a non-200 will be retried by the provider, so a
successful fix normally needs no replay. What does need checking is whether any
payment reached the provider's manual investigation queue while it was broken —
which is a conversation with the provider, not a query here.
