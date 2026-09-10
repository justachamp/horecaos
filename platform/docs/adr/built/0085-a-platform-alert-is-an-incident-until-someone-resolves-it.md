# ADR 0085: A platform alert is an incident until someone resolves it

- Decision status: Proposed
- Implementation status: Built — V0199's `notifications.control_plane_alerts`, `JdbcControlPlaneAlertStore`, `ControlPlaneAlertService` keeping every raised alert, `ControlPlaneIncidentController` (list, acknowledge, resolve, each audited), tested against the migrated schema; the control-plane alerts and incidents screen. Paging to a phone stays with the monitoring stack
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0023, ADR 0025, ADR 0027, ADR 0058
- Supersedes / Superseded by: —
- Open inputs: none

## Context

ADR 0058 gave the platform a way to raise an alert to its own operators: the
control-band watcher and the stuck-onboarding sweeper both call
`ControlPlaneAlertService`, which wrote a log line and bumped a counter. Nothing
kept the alert. The console's alerts screen (IA 1.2) had nothing to list, and
two operators could both start on the same problem without either knowing.

## Decision

Every control-plane alert is kept as an incident until a person resolves it.

1. Raising an alert inserts a row, or, while an incident for the same class
   and subject is still open, counts one more occurrence and moves its last
   raised time. One unresolved incident per class and subject is enforced by
   a partial unique index, so a noisy sweeper cannot flood the list.
2. An incident is `OPEN`, then `ACKNOWLEDGED` when someone takes it, then
   `RESOLVED` with a note of what was done. Resolving needs the note;
   acknowledging may carry one. Both are audited as security facts and name
   who did them, and repeating either changes nothing.
3. A resolved incident is never reopened. The same problem coming back opens
   a new incident, so the first one's record stays as it was.
4. Keeping the alert must never stop it being raised: the log line and counter
   still happen if the insert fails.
5. Reading needs `control-plane-alert.read`; acknowledging and resolving need
   `control-plane-alert.manage`. Platform support holds both.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Read alerts back from Alertmanager | A dependency on the monitoring stack being reachable from the app, and Alertmanager forgets a resolved alert | The monitoring stack becomes the one place operators work |
| One row per raise | A sweeper that runs every minute would bury everything else | Never needed |
| Reopen a resolved incident when it recurs | Rewrites what was recorded as resolved and by whom | An incident's history needs to be one row |

## Consequences

### Positive

- An operator sees what is wrong now, how often it has happened, and who has it.
- A resolution note is recorded with the audit trail instead of in a chat.

### Negative

- One more table the alert path writes to; an insert failure is logged, not surfaced.
- Nothing resolves an incident by itself when its cause goes away.

### Accepted trade-offs

- The screen shows the variables the raiser attached as they are; the raisers
  are responsible for keeping them free of personal data, as ADR 0058 already
  requires.

## Specification

- `GET /control-plane/incidents?includeResolved&limit` (`control-plane-alert.read`).
- `POST /control-plane/incidents/{id}/acknowledgement` and `/resolution`
  with `{ note }` (`control-plane-alert.manage`), answering `204` with no
  body: the note is free text, and a stored idempotent response at platform
  scope has no tenant key to encrypt it under. A step the incident has
  already passed changes nothing.
- V0199: `notifications.control_plane_alerts`, `ux_control_plane_alert_live`
  on `(event_class, subject_type, subject_id) WHERE status <> 'RESOLVED'`.

## Rollout and rollback

Additive. Dropping the table returns the alert path to log and counter only.

## Implementation checklist

- [x] Table, store, service write, controller, capabilities, tests
- [x] Alerts and incidents screen

## Exit criteria

An alert raised three times while open shows once with three occurrences, and
after it is resolved the next raise opens a new incident.

## References

- `docs/frontend-information-architecture.md` §1.2
