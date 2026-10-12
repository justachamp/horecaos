# Paper: enrol a print agent, replace a printer, read a dead job

**Morning item**, except that a kitchen ticket waiting on a printer that has not
answered for two minutes raises a Telegram alert to the chat bound to
`PRINTER_OFFLINE` (ADR 0058), and that one is a lunch-rush problem.
**Last executed:** never. This is a draft: the server side is built and tested
against a fake printer and a fake agent, and no real printer or branch machine has
been connected to it (ADR 0154).

Read this first: **the platform never dials a printer.** A small program on a
machine inside the branch (the print agent) dials out to the platform over HTTPS,
asks for work, draws it on the printer next to it, and says what happened. So
"the printer is offline" always means one of three things: the printer is off or
out of paper, the agent cannot reach the printer, or the agent cannot reach the
platform. Section 1 tells them apart in one query.

Everything below needs `print.manage` at the branch (ADR 0025): the owner, the
tenant administrator and the branch manager have it. A cashier can reprint but
cannot approve an agent.

```bash
read -rsp 'access token: ' TOKEN; echo
T=<tenant id>; B=<brand id>; L=<location id>
API=https://api.horecaos.uz/api/v1/tenants/$T/brands/$B/locations/$L
```

`feature.printing` is off until it is switched on for the tenant (ADR 0082). While
it is off the lists answer what exists and every write is refused `422
PRINTING_OFF`. Nothing prints by default: a new branch has **no routes**, and a
document with no route is printed nowhere (that is a choice, not a fault).

## 1. Is paper coming out? Where is it stuck?

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" "$API/print/printers" | jq -r \
  '.[] | [.id, .displayName, .tapeWidthMm, .connectionKind, .status, .lastStatus, .lastStatusAt] | @tsv'

curl -fsS -H "Authorization: Bearer ${TOKEN}" "$API/print/agents" | jq -r \
  '.[] | [.device.deviceId, .device.displayName, .device.status, (.advertised[] | [.key, .status, .agentBuild, .seenAt] | join(" "))] | @tsv'

for STATE in QUEUED CLAIMED DEAD; do
  curl -fsS -H "Authorization: Bearer ${TOKEN}" "$API/print/jobs?status=${STATE}" | jq -r \
    '.items[] | [.id, .documentType, .printerId, .status, .attempt, .failureCode, .queuedAt] | @tsv'
done
```

**Check:** read the three answers together.

| You see | It means | Go to |
|---|---|---|
| The agent's advertised `seenAt` is minutes old | The agent or its machine is down, or has lost the internet | section 2 |
| The agent is current, the printer's `lastStatus` is `OFFLINE`, `OUT_OF_PAPER` or `COVER_OPEN` | The printer, not the platform. Fix the paper, the lid or the cable; the next claim finds it `ONLINE` and the queue drains in order | nothing else to do |
| Jobs are `QUEUED`, the printer is `ONLINE`, the agent is current | The printer has no route to it, or the job is waiting out a retry delay (see the delays in section 5) | section 3 |
| Jobs are `DEAD` | Six attempts failed. The reason is `failureCode` | section 5 |

A job that sits `QUEUED` past its expiry (30 minutes for a kitchen ticket by
default, 24 hours for the other documents) becomes `EXPIRED` and is never printed
late; the kitchen already cooked from the screen. Section 5 says how to print it
on purpose.

## 2. Enrol a print agent (a new branch machine, or a replacement one)

The agent is a device principal of class `PRINT_AGENT` (ADR 0079): it holds no
password of a person, it can do exactly two things (claim jobs, report results),
and it can be revoked without touching anyone's account.

1. On the branch machine, start the agent. It begins an enrolment and shows an
   eight-character code on its screen (the code comes from
   `POST /api/v1/control-plane/device-enrolments` with
   `{"deviceClass":"PRINT_AGENT","label":"..."}`).
2. As a manager, approve it from the Printing screen, or here:

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"displayName":"Front counter machine"}' \
  "$API/print/agents/enrolments/<USERCODE>/approve" | jq .
```

**Check:** the reply has a `deviceId`, and `GET $API/print/agents` now lists it
as `ACTIVE`.
The pending request is readable with `GET $API/print/agents/enrolments/<USERCODE>`
first, to check the label is the machine you are standing at. A code is single-use
and short-lived. If it expired, the agent begins again and
shows a new one; there is nothing to clean up.

3. The agent's first claim says which printers it can reach, by an alias it
   chose locally (`counter`, `kitchen-1`). Until that first claim the platform
   does not know the alias exists, so **register printers after the agent has
   claimed once.**

**Revoking an agent** (a stolen machine, a replaced PC):

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"reason":"machine replaced"}' \
  "$API/print/agents/<DEVICE_ID>/revoke"
```

**Check:** `GET $API/print/agents` shows it `REVOKED` with your reason, its next
claim is refused, and any job it had claimed returns to the queue when the
60-second lease lapses. **To get back:** enrol again; a
revoked agent is not reinstated, a new one is approved.

## 3. Register a printer, route a document to it

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"displayName":"Counter 80","tapeWidthMm":80,"agentDeviceId":"<DEVICE_ID>","agentPrinterKey":"counter"}' \
  "$API/print/printers" | jq .
```

`tapeWidthMm` is 58 or 80 and it is the paper in the machine: a 58 mm roll is 384
dots and 32 columns, an 80 mm roll is 576 dots and 48. **Check:** send a test
page, which prints the roll's width as a ruler, every glyph the receipt can use,
a QR code and the printer's name:

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" "$API/print/printers/<PRINTER_ID>/test-page" | jq .
```

If the ruler is cut off at the right, the roll is wider than the printer was
registered as. If a letter is a box, tell the engineer which one: the font has
no glyph for it (the known gap is the Uzbek modifier-letter apostrophe, which is
drawn as an ordinary apostrophe).

Then route documents to it. A route is `(document type, optional station) -> printer`
with copies (1 to 3) and `autoPrint`:

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"documentType":"KITCHEN_TICKET","stationId":"<STATION_ID>","printerId":"<PRINTER_ID>","copies":1,"autoPrint":true}' \
  "$API/print/routes" | jq .
```

A kitchen ticket prints at its station's route; a station with no route of its
own falls back to the route with no station. `ORDER_SLIP` and `FISCAL_RECEIPT`
routes take no station.

## 4. Replace a printer

The same alias on a new machine is the easy case: the agent keeps claiming, the
alias now reaches the new printer, nothing changes in the platform. A different
paper width is the case that needs care, because a queued job is drawn **at the
width of the printer it is bound to when it is claimed**:

1. Register the new printer (section 3) with its real `tapeWidthMm`.
2. Move each route to it (`PUT $API/print/routes/<ROUTE_ID>` with the whole route
   and the new `printerId`; a route carries a `version`, send it as `If-Match`).
3. Retire the old one: `PUT $API/print/printers/<ID>` with the whole printer and
   `"status":"RETIRED"` (again `If-Match` the printer's `version`). A retired
   printer's routes stop printing and its jobs stay in the history.

**Check:** a test page from the new printer, then a real order. Jobs already
queued to the old printer stay with it and expire on their own.
**To get back:** `PUT` the old printer with `"status":"ACTIVE"` and move the routes
back; nothing is deleted.

## 5. Read a dead job, and print it anyway

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" "$API/print/jobs/<JOB_ID>" | jq .
```

| `failureCode` | What happened | What to do |
|---|---|---|
| `PRINTER_OFFLINE` | The agent could not reach the printer six times | Section 1 |
| `OUT_OF_PAPER`, `COVER_OPEN` | The printer said so each time | Paper or lid, then reprint |
| `TRANSPORT` | The agent could not draw to the device (cable, driver, permissions on the branch machine) | The agent's own log; reprint once it is fixed |
| `RENDER` | The platform could not draw the document at all | Send the job id to the engineer; this is a defect, not a printer |
| `LEASE_LAPSED` | The agent took the job and went silent for 60 seconds, six times | Section 2: the agent or its machine is the problem |

The attempts are at +5 s, +15 s, +45 s, +2 min and +5 min, so a printer that is
back within about eight minutes loses nothing. The document of a finished job is
erased seven days after it ended (thirty after a `DEAD` one); the row, its
digest and its outcome stay for ever, which is how a dead job a month later
still says what it was.

To print one on purpose, from the order (this makes a **new** job, regenerated
from the facts as they are now, marked as a copy, linked to the first and
recorded with who asked and why; it never resurrects the old one). Leave
`printerId` out and it goes where the document's route says; a document with no
route is refused `NO_ROUTE`:

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"documentType":"ORDER_SLIP","printerId":"<PRINTER_ID>","reasonCode":"CUSTOMER_REQUEST"}' \
  "$API/orders/<ORDER_ID>/print-jobs" | jq .
```

A `FISCAL_RECEIPT` reprint is answered `422` when the order's fiscal document is
not issued yet (`SUBMITTED`, `FAILED`) or is a refund: a receipt is only ever
drawn from the evidence the fiscal provider returned, never from a guess. That
is `NO_FISCAL_DOCUMENT` or `FISCAL_DOCUMENT_NOT_PRINTABLE`; the order's fiscal
status says why (ADR 0038).

To stop a queued job instead: `POST $API/print/jobs/<JOB_ID>/cancel` with an
`Idempotency-Key`. Only a queued job cancels; one an agent has claimed is already
on its way to paper and is answered `422`.

## 6. Run the reference agent at the branch (HorecaOS support, with the manager)

The agent in `platform/tools/print-agent/print-agent.mjs` is the whole of what
runs at the branch: Node 20 or newer, no packages to install, and it only ever
dials **out** (HTTPS to the platform and to the identity server, and the printers
on the branch network). Nothing is opened inbound.

1. Copy `print-agent.mjs` to the branch machine and write its own config. The
   printers are listed **here and nowhere else**; the alias on the left is what
   the console later binds a printer to, and the platform is never told an
   address:

```json
{
  "apiBase": "https://api.horecaos.uz",
  "printers": {
    "counter": { "kind": "tcp", "host": "192.168.1.50", "port": 9100 },
    "grill": { "kind": "tcp", "host": "192.168.1.51" },
    "usb-bar": { "kind": "file", "path": "/dev/usb/lp0" },
    "office": { "kind": "command", "command": ["lp", "-d", "Receipt", "-o", "raw"] }
  }
}
```

   `tcp` is a LAN printer on raw port 9100 (the usual case; status is read with
   `DLE EOT`). `file` is a device file and `command` is a spool command that is
   handed the bytes on its standard input; for those two the agent cannot ask the
   printer how it is, and reports `UNKNOWN`, which the platform treats as
   claimable.

2. See that the agent can reach each printer, before involving the platform:

```bash
node print-agent.mjs status --config agent.json
# counter    ONLINE
# grill      OFFLINE      <- off, unplugged, or the wrong address
```

3. Enrol it (section 2). `node print-agent.mjs enrol --config agent.json --label
   "Pilot branch mini PC"` prints a code; the manager types it into **Settings >
   Money and tax > Printing and receipts > Print agents**, checks the label, and
   approves. The credential is written once to
   `~/.horecaos-print-agent/credential.json`, readable by its owner only; it is a
   secret (ADR 0028) and is never printed or logged.

4. Run it. `node print-agent.mjs run --config agent.json` claims, draws, reports
   and claims again until it is stopped (the first `SIGTERM` or `Ctrl-C` finishes
   the job in hand). On the branch machine run it as a service so that it comes
   back after a power cut:

```ini
# /etc/systemd/system/horecaos-print-agent.service
[Unit]
Description=HorecaOS print agent
After=network-online.target
Wants=network-online.target

[Service]
User=horecaos
ExecStart=/usr/bin/node /opt/horecaos-print-agent/print-agent.mjs run --config /etc/horecaos-print-agent/agent.json
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**Check:** on the console, **Print agents** lists it `Active` with the aliases it
reports, then register the printers (section 3) and send a test page from each.
The agent exits with `3` when the platform refuses its credential (revoked or
removed: enrol again, do not retry), `2` for a bad config, and `1` for anything
else; the service restarts it on `1` and a person has to act on `2` and `3`.

**Rehearsing without hardware** (a developer, or support before the visit):
`node print-agent.mjs fake-printer --port 9100 --out ./pages` (or
`docker compose --profile printing up -d fake-printer` in `platform/`) listens
where a thermal printer listens, answers the status questions "online, paper
present", draws every page it receives as text in its log and saves it as a
`.pbm` picture. Point an `agent.json` printer at `127.0.0.1:9100`.
`make print-agent-test` proves the agent against a fake platform and that printer.

## 7. The wire contract (what the agent calls)

For an engineer writing another agent, or reading a trace. Every call is made by
the agent's own device token, at its own branch only; `{base}` is
`/api/v1/tenants/{t}/brands/{b}/locations/{l}/print`.

| Step | Call | Notes |
|---|---|---|
| Enrol | `POST /api/v1/control-plane/device-enrolments` `{deviceClass:"PRINT_AGENT", label}` then `POST .../{deviceCode}/poll` | Unauthenticated by design (ADR 0079). The poll returns the credential once, on approval |
| Token | client-credentials grant at the `tokenEndpoint` in the credential | Short-lived; renew once on a 401 |
| Where am I | `GET /api/v1/devices/me` | Learns the tenant, brand and branch it was approved at |
| Claim | `POST {base}/claims` with an `Idempotency-Key`; body `{printers:[{key,status}], maxJobs, waitSeconds, agentBuild}` | Long-polls up to 20 s. Reply `{jobs:[{jobId, printerKey, documentType, leaseToken, leaseExpiresAt}]}`. It first repeats this agent's own unexpired leases, so a retry after a lost reply loses nothing; it never holds the paper |
| Bytes | `GET {base}/jobs/{jobId}/artifact?lease={leaseToken}` | `application/octet-stream`, header `X-Print-Protocol`. `409` if the lease is stale or not yours; `422 RENDER_FAILED` if the document cannot be drawn (the job is already dead) |
| Report | `POST {base}/jobs/{jobId}/results` with an `Idempotency-Key`; body `{leaseToken, outcome:"PRINTED"\|"FAILED", failureCode}` | `409` for a stale or foreign lease, changing nothing; the same `PRINTED` twice answers `200 REPLAYED` |

The rules the contract rests on:

* **A lease is 60 seconds.** If the agent goes silent the job returns to the queue
  with the attempt counted and prints again (at least once, never at most once).
  Report with the **same** `Idempotency-Key` on a retry of the same report.
* **One job at a time per printer, in the order queued**, so a station's tickets
  keep their order. A printer that reports anything but `ONLINE` or `UNKNOWN`
  is not claimed from; its jobs wait (and expire, for a kitchen ticket).
* **`status`** is one of `ONLINE`, `OFFLINE`, `OUT_OF_PAPER`, `COVER_OPEN`,
  `ERROR`, `UNKNOWN`. **`failureCode`** is one of `PRINTER_OFFLINE`,
  `OUT_OF_PAPER`, `COVER_OPEN`, `TRANSPORT`, `RENDER`. Six failures end a job
  `DEAD`.
* **The bytes** are `ESC @`, raster bands (`GS v 0`) at the printer's dot width
  (384 for 58 mm, 576 for 80 mm), `ESC d` feed and a cut. The agent does not
  parse, change or add to them, and holds no template, language or business rule.
* **What the agent logs** is identifiers and outcomes: a job id, an alias, a
  document type, a byte count. Never the document, a note or a name.

## What to watch

| Signal | Meaning |
|---|---|
| `horecaos_printing_jobs_total{state="DEAD"}` rising | Section 5; one printer, or one agent |
| `horecaos_printing_enqueue_failures_total` rising | A confirmed order could not be turned into a job. The reconciler (every minute, two-hour window) makes the missing jobs, so this is a defect to report and not a lost ticket |
| `horecaos_printing_claim_latency_seconds` | How long an agent's claim takes on the database; seconds mean the queue table needs a look |
| Telegram `PRINTER_OFFLINE`, `PRINT_JOB_DEAD` | A kitchen ticket waited two minutes on a printer that did not answer; a job ended `DEAD` |

## Why it is built this way

* **Jobs are durable and leased, and delivery is at least once.** An agent that
  crashes between drawing and reporting leaves a lease that lapses in 60 seconds
  and the job prints again. A duplicate ticket is the right failure here; a
  missing one is not. Every second print of a document carries a «Копия» line.
* **Jobs are created after the business transaction commits**, never inside it:
  a defect in printing must not stop a restaurant confirming orders.
* **The platform draws the paper**, as a one-bit picture on the 12-dot cell
  grid, so the agent is a dumb byte pipe and what you see in the preview is
  what the printer gets. There is no template designer and no native printer
  text.
* **No customer name, phone or address is ever on a document.** A kitchen ticket
  carries the line notes (they are for the cook); nothing carries the person.
