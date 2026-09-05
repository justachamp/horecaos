# Observability and alerting

ADR 0023 decides what this platform measures, what is allowed to make a noise,
and what recovers itself. ADR 0034 decides where the watching happens. This
directory is the on-box half of that split, and it now has two independent
pieces that both read the same metrics and are not allowed to disagree about
what they mean:

- **`horecaos-probe.sh`**, evaluating every alert in ADR 0023's table from a
  direct scrape and paging over Telegram, exactly as before. It is the alerting
  authority — nothing below second-guesses a threshold it already evaluates.
- **`compose.observability.yaml`**, a Prometheus that scrapes the same
  endpoint, an Alertmanager that pages the subset of the table a metrics scrape
  can actually see, and the one Grafana dashboard ADR 0023 asks for. This is
  new in this wave and is the collection-and-display half the record's own
  status line has been missing.

The off-box half — a dead-man's switch and an external uptime check — is still
configured in two external services and is described near the bottom of this
file, because nothing in this repository can create it and nothing on the
production VM can substitute for it.

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
| `compose.observability.yaml` | The collection overlay: Prometheus, Alertmanager, Grafana. Self-contained — see "Collecting and displaying it" below for what it is and how to attach it |
| `prometheus/prometheus.yml`, `prometheus/rules/adr-0023-alerts.yml` | The scrape config and the subset of ADR 0023's alert table a metrics scrape can evaluate |
| `prometheus/rules/rules_test.yml` | `promtool test rules` fixtures for every rule above — run before trusting an edit |
| `alertmanager/alertmanager.yml.example` | The shape of `/etc/horecaos/alertmanager.yml`, which carries the Telegram bot token and chat id and is never committed (ADR 0028), exactly like `alerting.env.example` above |
| `alertmanager/templates/horecaos.tmpl` | The Telegram message template, one line per alert in the shape `horecaos-probe.sh` already uses |
| `grafana/` | Datasource and dashboard provisioning for the one dashboard ADR 0023 asks for |

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

## Collecting and displaying it — Prometheus, Alertmanager, Grafana

`horecaos-probe.sh` pages; it does not remember, graph, or show anyone what
led up to a page. Until this wave, nothing did — ADR 0023's own checklist said
so: "the metrics are published and scrapeable, so this is a build rather than
a design." `compose.observability.yaml` is that build.

### Why this is a separate compose file rather than an edit to a production one

Two trees currently compete to be the production deployment
(`compose.production.yaml` here and `deploy/compose.production.yml`), and
which one wins is a decision this wave does not make. Editing either risks
colliding with that decision. `compose.observability.yaml` is additive
instead: it starts three new services and reads two things both trees already
define identically — a service named `platform-app` and networks named
`public` and `core` — and changes nothing that exists today.

### Attaching it

Once a tree is chosen, merge the two compose files in one invocation:

    export HORECAOS_OBSERVABILITY_DIR=/opt/horecaos/horecaos-platform/infra/observability
    docker compose \
      -f compose.production.yaml \
      -f "$HORECAOS_OBSERVABILITY_DIR/compose.observability.yaml" \
      up -d

(or the same two `-f` flags against `deploy/compose.production.yml` — nothing
above names a tree). `HORECAOS_OBSERVABILITY_DIR` defaults to this directory's
real install path, so the `export` is a habit rather than a requirement; every
bind mount inside `compose.observability.yaml` is anchored to that variable
rather than to a plain relative path on purpose — see the comment at the top
of that file for the reason, which is a real Compose behaviour this wave hit
and fixed rather than a hypothetical one: a relative path in a file merged
with `-f` resolves against the *first* file's directory, not its own.

Before the first `up -d`, the two secrets this stack needs must exist:

    sudo install -m 0600 infra/observability/alertmanager/alertmanager.yml.example /etc/horecaos/alertmanager.yml
    sudo "${EDITOR}" /etc/horecaos/alertmanager.yml   # fill in the Telegram bot token and chat id
    echo -n '<a strong password>' | sudo tee "${HORECAOS_SECRET_DIR}/grafana-admin-password" >/dev/null
    sudo chmod 0600 "${HORECAOS_SECRET_DIR}/grafana-admin-password"

Reach Grafana the same way the record already reaches the diagnostic
`/actuator/health` surface — over WireGuard to the box, then an SSH tunnel
straight to the container's own bridge address, never a published port (ADR
0023 names Prometheus, Alertmanager, and the dashboard among what must never
have one — and `internal: true` on `core` was confirmed for this wave to
actively refuse Docker's own port-publish mechanism for a container that is
only on that network, even a loopback-only binding, so there was no simpler
option to reject in favour of this one):

    docker compose ps -q grafana | xargs docker inspect \
      --format '{{ (index .NetworkSettings.Networks "horecaos-production_core").IPAddress }}'
    ssh -L 3000:<that address>:3000 <operator>@<box>

sshd on the box sits in the host's own network namespace, the same place
Docker creates the bridge interface, so this is ordinary Linux routing rather
than anything Docker-specific — but it is the one mechanism in this wave that
rests on that reasoning rather than on a test actually run against it. This
sandbox runs Docker Desktop for Mac, which puts dockerd inside a Linux VM, so
a command issued from the sandbox's own host is not in the bridge's network
namespace the way sshd on the real, single-OS production box would be, and the
direct reachability itself could not be exercised here. Confirm it once
against the real box before relying on it operationally.

### Verifying it before trusting it

Every config below was checked with its own tool before being written here —
not assumed from documentation:

    docker run --rm -v "$(pwd)/prometheus":/etc/prometheus --entrypoint promtool \
      prom/prometheus:v3.0.1 check config /etc/prometheus/prometheus.yml
    docker run --rm -v "$(pwd)/prometheus/rules":/work --entrypoint promtool \
      prom/prometheus:v3.0.1 test rules /work/rules_test.yml
    docker run --rm --entrypoint amtool -v /etc/horecaos/alertmanager.yml:/work/alertmanager.yml:ro \
      prom/alertmanager:v0.28.1 check-config /work/alertmanager.yml

The `rules_test.yml` run is a real behavioural test, not a syntax check: it
asserts, for example, that `HorecaosMonetaryDeadLetter` fires on a net rise in
the dead-letter gauge and does not fire once the row has resolved back down —
the reason that rule uses `delta()` rather than `increase()` (a gauge, unlike
a counter, can legitimately fall, and `increase()` would badly over-count a
fall followed by a rise). The whole stack — the real config files, not a
throwaway example — was also brought up once end to end against a stand-in
metrics endpoint for this wave, confirming Prometheus's scrape health, its
Alertmanager discovery, and Grafana's datasource health and dashboard
provisioning all actually succeed rather than merely parse. See the wave's own
report for the transcript.

### How Prometheus reaches a loopback-only endpoint

`/actuator/prometheus` answers only a caller whose peer address is genuinely
`127.0.0.1` (`LocalMetricsScrapeMatcher`) — not a bearer token, not an
allowlist, the literal peer address, for the same reason `horecaos-probe.sh`
needs none: minting a token depends on Keycloak, and an alert path must not
depend on the thing it is monitoring. `horecaos-probe.sh` satisfies this with
`docker compose exec platform-app wget ...`, which runs inside that
container's own network namespace. `compose.observability.yaml` runs
Prometheus with `network_mode: "service:platform-app"` instead, which shares
that namespace for a long-running container rather than a one-shot exec — a
scrape of `127.0.0.1:8080` from inside it is, to the application, identical to
traffic that never left the container, because it didn't. **No change was
made to the application's security configuration.** This was verified
empirically for this wave (a sidecar container sharing another container's
namespace really does see `127.0.0.1` as its peer address, confirmed with a
throwaway HTTP server before being relied on here) rather than assumed from
what the Docker documentation says the flag does.

### What Prometheus and Alertmanager cover, and what they cannot

Six of ADR 0023's alerts are evaluated in `prometheus/rules/adr-0023-alerts.yml`
at the table's own thresholds — order flow stalled (the outbox/inbox legs),
payment callback failing, provider circuit stuck open, monetary dead letter,
ownership fence burst, and onboarding stalled. Every other alert in the table
stays exactly where it already was, with the reason written into the rule
file's header rather than left to be discovered:

- **Platform unreachable** is off-box by construction, in both evaluators —
  this was never a gap to close.
- **PostgreSQL down**, **secrets manager sealed**, **backup did not run**, **TLS
  certificate expiring**, and **a container autoheal could not fix** each need
  something a metrics scrape of the application cannot see: a direct
  `pg_isready`/`bao status` against another container, a stamp file on the host
  filesystem, a live TLS handshake, or Docker's own restart counters.
  `horecaos-probe.sh` already reads every one of these directly, and duplicating
  them here would mean two evaluators that can silently disagree — the risk this
  wave's brief explicitly ruled out.
- **Data volume above 85%** is *not* evaluated from the application's own
  `disk_free_bytes` gauge, on purpose: `DataVolumeMetrics`'s own documentation
  says the production container's filesystem is read-only and that figure
  describes nothing about the host. Alerting on it would page — or fail to page
  — on the wrong quantity. The Grafana panel that shows it is captioned with
  exactly this limitation instead of hiding it.
- **Order flow stalled**'s Kafka consumer-lag leg is not evaluated here either:
  no consumer-lag metric is published by the application (Spring Kafka's
  Micrometer binder is not wired up), and `horecaos-probe.sh` gets that number
  by running `kafka-consumer-groups.sh --describe` against the broker directly.
  The outbox/inbox legs of the same alert *are* evaluated here.

Nothing above is a promise to close later inside this stack specifically —
some of it is a metrics-exporter question (Kafka, containers, TLS) for a
future wave to weigh against ADR 0023's own stated minimalism, and some of it
(PostgreSQL, OpenBao) is architecturally the probe's job because it can reach
containers a Prometheus scraping only the application cannot.

### The one dashboard

`grafana/dashboards/is-it-working.json`, provisioned read-only so a rebuilt
container comes back configured rather than blank (ADR 0023: "a solo operator
will keep one dashboard honest and will not keep nine"). Panels are read
directly off the alert table: order flow age, orders by status, dead letters by
domain, provider circuit state, payment callback error rate, fenced writes, and
onboarding staleness — plus a panel that lists, in the dashboard itself, every
signal in the paragraph above that this page cannot show and where each one
actually lives. Nothing on the page pages anyone; `alertmanager.yml.example`'s
routes do that.

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

- **Traces.** No OpenTelemetry exporter is wired, so the sampling rates ADR 0023
  specifies have nothing to sample. Nothing in this wave touches this — it is
  still exactly the gap ADR 0023 names.
- **Six of the table's alerts are not evaluated by Prometheus**, and stay
  solely on `horecaos-probe.sh`: platform unreachable (correctly, off-box by
  construction in both evaluators), PostgreSQL down, secrets manager sealed,
  backup did not run, TLS certificate expiring, and a container autoheal could
  not fix. The Kafka consumer-lag leg of order flow stalled is in the same
  position. "Collecting and displaying it" above states the reason for each —
  mostly, that the fact they need is not visible from a scrape of the
  application. None of these were alerted on before this wave either; this
  wave changes how many of the table's alerts are *doubly* evaluated (six),
  not how many are evaluated at all.
- **Dead letters by `FailureCategory` on the outbox side** is unchanged from
  ADR 0023's own "What is not built yet": the outbox table has no column to
  group by, so both the probe and the Grafana dashboard show
  `failure_category="unclassified"` for every outbox row rather than a real
  category. The alert that matters — a *monetary* dead letter — does not need
  the category and is unaffected.
- **Host-accurate free disk and a real backup-age figure are not on the
  dashboard.** Both would need a new exporter (`node_exporter`'s textfile
  collector, at minimum) reading facts that live outside every container, which
  this wave did not add — ADR 0023 is explicit that nothing should be measured
  merely because it is measurable, and the in-process `disk_free_bytes` gauge
  Grafana shows instead is captioned with exactly why it is not the number the
  85% alert fires on.
