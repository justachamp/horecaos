# A provider circuit is stuck open

**Trading-hours alert.** **Last executed:** never — this is a draft.

**There is nothing to restart and nothing to reset.** The breaker half-opens
automatically every thirty seconds and closes itself the moment the provider
answers. Ten minutes open means roughly twenty probes have failed and the
provider is genuinely down. What this runbook changes is the tenant's exposure
while it stays that way, and the action is commercial rather than technical.

## 1. Which provider, and for how long?

```bash
qc exec -T platform-app wget -q -O - http://127.0.0.1:8080/actuator/prometheus \
  | grep horecaos_provider_circuit
```

**Check:** `horecaos_provider_circuit_open_duration_seconds` names the provider and
how long it has been open. `horecaos_provider_circuit_state` reads 2 for open and 1
for half open — half open means it is already probing its way back.

## 2. Is it them or is it us?

```bash
qc exec -T platform-app wget -q -O - -S http://127.0.0.1:8080/actuator/health 2>&1 | head -20
curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' https://checkout.paycom.uz/
```

**Check:** if the host does not resolve or does not connect from this box while
the provider's status page is green, it is the route out of the colocation
rather than the provider. That is a call to the facility, not to Payme.

## 3. Tell the tenant

This is the actual point of the alert.

- **A payment provider is down:** the restaurant takes cash for the affected
  channel. Tell them which provider, and tell them it will resume by itself.
- **A courier partner is down:** the other partner is unaffected — the breakers
  are per provider precisely so that Noor's bad afternoon does not stop Yandex.
  Deliveries route to whoever is up, and the runbook is to say so rather than to
  change anything.

## 4. Do not

- Do not restart `platform-app` to "clear" the breaker. It clears itself; the
  restart loses the half-open probe state and the request in flight, and buys
  nothing.
- Do not disable the breaker. Every call then waits for its full timeout, and a
  provider that is down becomes a provider that is slow, which is worse — a
  thread pool full of pending calls to a dead endpoint takes the checkout path
  with it.

## 5. When it closes

The metric returns to zero and the alert clears. Nothing to do.
