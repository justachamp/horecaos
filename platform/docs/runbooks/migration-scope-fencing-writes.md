# A migration scope is fencing writes

**Trading-hours alert**, cutover only. **Last executed:** never — this is a
draft.

One fenced write is the ADR 0024 gate working correctly. A burst — more than ten
in five minutes for one capability — means routing and ownership disagree:
writes are arriving at a platform that believes legacy still owns the
capability, and every one of them is a customer action that did not happen.

## 1. Who owns it right now?

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c \
  "SELECT capability, state, write_mode, read_mode, state_entered_at, tenant_id, brand_id, location_id
     FROM migration.scopes ORDER BY capability, state_entered_at DESC"
```

**Check:** the capability the alert named. `write_mode` is the answer to "may
this platform write", and `state` is why.

- `PAUSED` or `BLOCKED_RECONCILIATION` — deliberate. Someone stopped this, or a
  reconciliation gate refused to clear. Section 3.
- `LEGACY_ONLY` while traffic is arriving here — routing is wrong. Section 2.
- No row at all for a capability that should have one — the gate fails closed on
  everything it cannot resolve, which is correct and is why you are seeing this.

## 2. Routing and ownership disagree

The fix is at the edge, not in the table. Customers are being sent to a platform
that is not the writer.

**Do not transition the scope to make the writes succeed.** That is the one
action this runbook forbids outright. The gate is the only proof that exactly
one writer exists, and opening it to stop an alert creates the second writer the
whole programme exists to prevent. Send the traffic back to the owner instead.

## 3. `BLOCKED_RECONCILIATION`

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c \
  "SELECT id, scope_id, status, severity, detected_at, summary
     FROM migration.reconciliation_results
     WHERE status <> 'PASSED' ORDER BY detected_at DESC LIMIT 20"
```

**Check:** what failed. Resolve the reconciliation through the migration API —
it is what recorded the block and it is what can clear it. The scope transitions
only after the evidence exists.

## 4. What is not happening while this is open

Writes are **refused, not queued**. There is no backlog that drains when you fix
it, and no replay. Every request in the burst was answered with a failure the
caller saw. Whatever the customers were trying to do, they have to do again —
which is why this is a trading-hours alert and not a morning one.
