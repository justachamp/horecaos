# OpenBao is sealed or unreachable

**Trading-hours alert.** **Last executed:** never — this is a draft.

**This does not self-heal, by design.** OpenBao comes back sealed after every
reboot and needs the unseal material by hand. That is the gap that makes
unattended reboot recovery a claim ADR 0023 does not make.

## 1. Confirm

```bash
qc exec -T openbao bao status
```

**Check:** `Sealed  true`, or a connection error. If `Sealed false`, the
container is up and this alert was the probe failing to reach it — check
`qc ps openbao`.

## 2. Unseal

```bash
qc exec -it openbao bao operator unseal
```

Repeat with each share until `Sealed` reads `false`. The shares are the ADR 0034
escrow material and are not on this host, not in this repository, and not on the
laptop.

**Check:**

```bash
qc exec -T openbao bao status | grep Sealed
```

## 3. Then check what expired while it was sealed

This is the part that is easy to forget. ADR 0028's bounded cache hides a sealed
OpenBao for one TTL, after which **every provider call fails while HTTP still
reports healthy**. It is the failure most likely to look fine on a dashboard and
be an outage in the restaurant.

```bash
qc logs --tail 200 platform-app | grep -i 'secret\|openbao\|credential'
```

**Check:** provider calls resuming. Nothing needs restarting — secrets resolve at
call time, so the next call succeeds by itself. If you find yourself restarting
`platform-app`, you are working around a design that already handles this.

## 4. Why this waits until morning

It is a trading-hours alert, so a 3am reboot is discovered at 09:00. That is
affordable only because trading has ended by then and no customer is paying.
**A tenant that trades overnight invalidates that reasoning immediately**, and
the correct response is to move this to the night tier and remove one of the
three — not to add a fourth.
