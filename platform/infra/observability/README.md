# Observability and alerting

ADR 0023 decides what this platform measures, what is allowed to make a noise,
and what recovers itself. ADR 0034 decides where the watching happens. This
directory is the on-box half of that split. The off-box half is configured in
two external services and is described at the bottom of this file, because
nothing in this repository can create it and nothing on the production VM can
substitute for it.

## The split, and why it is not negotiable

A monitor hosted on the machine it monitors cannot report that the machine is
gone. A Prometheus and an Alertmanager on this box would produce exemplary
graphs right up to the moment the disk failed, and then produce nothing — at the
one moment their output mattered.

So the alert of last resort runs the other way round. `horecaos-probe.sh` runs from
cron on the box, evaluates every alert in ADR 0023's table, delivers what is
firing, and **pings an external dead-man's switch when it finishes**. The alert
is the absence of that ping, evaluated by a service that is not on this machine
and does not depend on it.

    on the box      horecaos-probe.sh, every minute from cron
                    -> evaluates the thresholds
                    -> pushes night and trading alerts to a webhook
                    -> accumulates morning items and sends one digest at 09:00
                    -> pings the dead-man's switch

    off the box     a dead-man's switch, which fires when the ping stops
                    an HTTP uptime check against the customer probe
                    a phone that can distinguish a loud channel from a quiet one

## What is here

| File | What it is |
|---|---|
| `horecaos-probe.sh` | Every alert in ADR 0023's table, at the stated threshold and tier, with the reason for the threshold in a comment above the check |
| `alerting.env.example` | The shape of `/etc/horecaos/alerting.env`. Copy it to the host, fill it in, `chown root:root`, `chmod 0600`. It is not in this repository and must never be (ADR 0028) |
| `crontab.example` | The two cron entries, and the one that has to be removed |

The metrics the probe reads are published by the application's
`uz.horecaos.platform.observability` module and scraped from
`/actuator/prometheus`. That endpoint is not reachable from the internet — the
edge answers 404 for it — and inside the platform it is permitted only for a
request whose peer address is the application container's own loopback, which is
what `docker compose exec` produces. The probe therefore needs no bearer token,
which is deliberate: minting one needs Keycloak, and an alert path that depends
on the identity provider goes quiet in the outage it exists to report.
The series names are a contract between the two halves and are asserted in
`HealthProbeAndMetricTests`, because renaming a meter is otherwise a silent way
to disable a night alert: the match finds nothing, "no value" reads as "not
firing", and paging stops without anything failing.

## Installing it

    sudo install -m 0700 -d /var/lib/horecaos/probe
    sudo install -m 0600 infra/observability/alerting.env.example /etc/horecaos/alerting.env
    sudo "${EDITOR}" /etc/horecaos/alerting.env
    sudo crontab -e     # see crontab.example

Then prove it works before trusting it:

    sudo HORECAOS_STALL_SECONDS=0 /opt/horecaos/horecaos-platform/infra/observability/horecaos-probe.sh

That forces the order-flow alert to fire against real data and delivers it down
the real webhook. ADR 0023's exit criteria require each of the three night
alerts to have been *verified to fire*, not merely written down; every threshold
in the script is overridable by an environment variable so that this is a
one-line exercise rather than a staged outage.

## The three tiers

**Night** wakes the operator at any hour. ADR 0034 caps it at three and ADR 0023
spends all three: the platform unreachable, PostgreSQL down while the host is
up, and order flow stalled. A fourth is available only by removing one and
saying which.

**Trading hours** is loud between 09:00 and 23:30 Asia/Tashkent and falls into
the digest outside them. Payment callbacks failing, OpenBao sealed, a payment
circuit stuck open, a monetary dead letter, an ownership fence burst, and a
container the watchdog could not fix. Each has an action worth taking at noon
and pointless at 03:00.

**Morning** is a silent message at the start of the working day: the backup did
not run, the data volume is above 85%, the TLS certificate expires within seven
days, an ADR 0008 onboarding run has not moved for an hour. The last of those is
a tenant that is not live yet, so nobody is losing anything while it waits for
09:00 — and it deliberately stays quiet for a run that is only waiting for a
platform administrator to press activate.

## What the box cannot do for itself

Three things, and they are the whole off-box half. Until they exist, the box is
watched by something that stops working at exactly the moment it is needed.

### 1. A dead-man's switch

Any of healthchecks.io, Better Stack, Cronitor, or equivalent. Configure:

- **Period 5 minutes, grace 10 minutes.** The probe runs every minute, so ten
  minutes of silence is nine missed runs and not a slow one.
- **Notification: loud.** This is the "platform unreachable" night alert's other
  half — it is what fires when power, network, disk, kernel, or the Docker
  daemon has taken the whole machine, and none of those can be observed from the
  machine.
- Put its ping URL in `HORECAOS_HEARTBEAT_URL`.

It cannot be created from here: it is an account, on a service, with a payment
method, and its entire value is that it is not on this machine.

### 2. An external HTTP uptime check

Against `https://<api hostname>/actuator/health/customer`. Configure:

- **Two consecutive failures before alerting, checked every minute.** ADR 0023's
  threshold, and the reason is stated there: one failed probe is a blip on a link
  into Uzbekistan, and two consecutive is longer than any restart this platform
  performs, including the 90-second deploy window, so it can never be a deploy.
- **From at least two probe locations**, so that one transit provider's bad
  minute is not an outage.
- **Notification: loud, at any hour.** This is night alert one.

Note the path. `/actuator/health/readiness` is the reverse proxy's question and
answers 200 while the database is unreachable, which is correct for a proxy and
useless for an uptime check. `/actuator/health/customer` consults PostgreSQL and
is the one that answers whether a customer could place an order.

During cutover this check has two subjects, per ADR 0023: the customer-visible
answer is whichever system currently owns the journey, so the legacy estate needs
its own check for as long as it owns one.

### 3. Two notification channels, one loud and one silent

`HORECAOS_ALERT_WEBHOOK_LOUD` and `HORECAOS_ALERT_WEBHOOK_QUIET`. With Telegram these
are the same `sendMessage` URL, the quiet one carrying
`&disable_notification=true`. The tier has to be a property of the alert rather
than of what the phone thinks the time is, because the operator travels and the
restaurants do not.

### What is deliberately not asked for

No paging provider, no escalation policy, and no rotation. There is one person.
An escalation policy whose every tier terminates at the same phone manufactures
an impression of coverage that does not exist; ADR 0034's credential escrow is
the real answer to the operator being unavailable.

## What is not built

- **The single "is it working" dashboard.** The metrics ADR 0023 lists are
  published and scrapeable, and the alerts are evaluated without a metrics
  server, but there is no Prometheus, no Alertmanager, and no dashboard on the
  box. Everything on the "deliberately not an alert" list — latency, CPU, cache
  hit rate, a breaker opening — is therefore currently measured and unwatched.
  That is a real gap against ADR 0023's checklist and it is stated here rather
  than quietly dropped.
- **Traces.** No OpenTelemetry exporter is wired, so the sampling rates ADR 0023
  specifies have nothing to sample.
