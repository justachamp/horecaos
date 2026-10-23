# Runbooks

These are written for one specific reader: the person who built this, at 3am,
having forgotten how it works, alone, and not in the mood to reason from first
principles.

That shapes them more than any style guide would.

- **Commands are complete and copy-pasteable.** No `<fill this in>` where a real
  value could have been named, no "adjust as appropriate".
- **Every step says what to check before the next one.** A procedure you cannot
  verify halfway through is a procedure you have to run to the end before finding
  out it was wrong.
- **Every procedure says how to get back.** If there is no way back, it says so
  in those words, at the top, before the first command.
- **Explanations come after the commands, not before.** When you need this you
  need the command; the reason it works is useful the following morning.

| Runbook | When |
|---|---|
| [alerts.md](alerts.md) | **Start here when something woke you.** Every alert, its tier, and the one runbook that answers it |
| [production-setup.md](production-setup.md) | Bare OS to a running platform — first-time setup by a devops engineer with no server access for CI or the assistant (ADR 0061) |
| [platform-email.md](platform-email.md) | Turning on the platform's own email: provider, DNS records, the SMTP password in OpenBao, and why an invitation is not sending (ADR 0097) |
| [connect-assistant-provider.md](connect-assistant-provider.md) | Turning on the grounded assistant: the platform's model-provider key in OpenBao, the pilot tenant's entitlement and switch, what bounds its cost, and what to check when it hands customers to a person (ADR 0069) |
| [external-connections/README.md](external-connections/README.md) | The index of the fourteen batch-21 external-connection files, how every one of them is read, and where a fact lands once it is connected. Read this first; the per-wave files below are the records. |
| [external-connections/w1-board-shell.md](external-connections/w1-board-shell.md) | What the order board's courier ETA, source, payment, fiscal, distance and partner-number columns, the reprint key and the plan lock are built against, and what to check the day the real provider, printer or plan is connected (batch 21) |
| [deploy.md](deploy.md) | Shipping a release, bootstrapping a host, rolling back, and everything that goes wrong in between |
| [restore.md](restore.md) | The database is gone, corrupted, or a migration has to be undone |
| [postgresql-down.md](postgresql-down.md) | Night alert: the database is not answering while the host is |
| [outbox-not-draining.md](outbox-not-draining.md) | Night alert: order flow stalled — outbox age, inbox age, or consumer lag |
| [payment-callback-failing.md](payment-callback-failing.md) | Non-200s from the Payme or Click callback roots |
| [openbao-sealed.md](openbao-sealed.md) | OpenBao is sealed, which it is after every reboot |
| [provider-circuit-stuck-open.md](provider-circuit-stuck-open.md) | A payment or POS breaker has stayed open for ten minutes |
| [dead-letter-decision.md](dead-letter-decision.md) | A message reached `DEAD_LETTER` and needs a person |
| [migration-scope-fencing-writes.md](migration-scope-fencing-writes.md) | Cutover: a burst of writes refused by the ADR 0024 gate |
| [container-crash-loop.md](container-crash-loop.md) | The watchdog restarted it and the restart did not fix it |
| [disk-filling.md](disk-filling.md) | The data volume is above 85% |
| [onboarding-run-stalled.md](onboarding-run-stalled.md) | An ADR 0008 onboarding run has not moved for an hour |
| [customers-cannot-sign-in.md](customers-cannot-sign-in.md) | No SMS is arriving, or a customer cannot get past the code screen |
| [laptop-lost.md](laptop-lost.md) | Revoking your own access from the second device |
| [control-band-response.md](control-band-response.md) | A control band breached and an agent wrote an `intent.md` to triage |
| [staff-second-factor.md](staff-second-factor.md) | Authenticator-app codes for staff sign-in: the rollout order and its realm steps, the code-budget alert, resetting a lost phone, and the break-glass for the night no administrator can (ADR 0148) |
| [staff-member-backfill.md](staff-member-backfill.md) | The tenant's own staff record against Keycloak: how many active staff have no row, what the reconciler says, the drift gauge, and when the retention sweeper may enforce (ADR 0139) |
| [catalog-offering-listing-backfill.md](catalog-offering-listing-backfill.md) | A pre-existing tenant's offerings were set `AVAILABLE` before the auto-listing fix and still read as unsellable |
| [customer-funnel-gap.md](customer-funnel-gap.md) | Finding and re-capturing business days the customer funnel missed, before the carts they were counted from are swept; the two funnel digest alerts (ADR 0167) |
| [load-uzbekistan-routing-dataset.md](load-uzbekistan-routing-dataset.md) | Measuring, building, deploying, refreshing and rolling back the road-distance engine's dataset; and the two routing digest alerts (ADR 0147) |
| [mxik-reference-import.md](mxik-reference-import.md) | Loading, refreshing and rolling back the national ИКПУ/MXIK list from the tax authority's file: the dry run, the withdrawal valve, and the two reference incidents (ADR 0173) |
| [scenario-guest-did-not-get-a-message.md](scenario-guest-did-not-get-a-message.md) | A marketer asks why a guest did not get a step of a scenario, why a marketing text is refused, or why a late order was not apologised for (ADR 0112) |
| [late-order-threshold-stored-values.md](late-order-threshold-stored-values.md) | Before the ADR 0150 release: which tenants saved a «late after» number while it did nothing, what it will now do to their unpromised orders, and how to tell them |
| [connect-card-merchant-account.md](connect-card-merchant-account.md) | Connecting HorecaOS's own card merchant account (ADR 0095): the installation to fill, the adapter it needs, replacing an account, and an unanswered charge |
| [connect-einvoicing-operator.md](connect-einvoicing-operator.md) | Connecting HorecaOS's own Didox and Faktura.uz accounts (ADR 0096): the logins in OpenBao, binding the seller's identity, finance's line codes, the first draft and how to take it back |
| [external-connections/w12-money.md](external-connections/w12-money.md) | The facts the money wave waits on that live outside the repository: the Click account and sandbox, Click's written word on tokenising in the browser, bank details and the tax rule for prepaid money, the e-invoice operators, and finance's line codes (ADR 0095, ADR 0096, ADR 0185) |
| [terminals-and-kiosks.md](terminals-and-kiosks.md) | A person forgot or locked their staff PIN, who was at a tablet, a tablet went quiet during service, rotating the PIN pepper, and installing, revoking and re-pairing a kiosk (ADR 0155, ADR 0162) |
| [staff-sessions.md](staff-sessions.md) | Setting the sign-in client's id so «Мой профиль» can list a person's sessions, what to do when «Выйти на всех устройствах» is refused, and what the history keeps (ADR 0176) |
| [staff-shifts.md](staff-shifts.md) | Before switching attendance on for a company, a shift left open, reading a staff-hours divergence, why the payroll export refuses, and the partition hazard for the next fact table (ADR 0156) |
| [marking-scanner-setup.md](marking-scanner-setup.md) | A branch that sells marked goods: the imager settings that matter, the one-minute self-test, the three preconditions for turning `feature.marked_goods` on, and what to do when a real code is refused (ADR 0161) |
| [external-connections/w14-channel-setup-pos-loyalty.md](external-connections/w14-channel-setup-pos-loyalty.md) | The five facts only the owner can supply for kiosk idle media, the Telegram review routing and the till's loyalty balance: a kiosk unit under lockdown, a BotFather round trip, an iiko or R-Keeper wallet API, Clopos's answer, and counsel's consent answer (ADR 0187, ADR 0171) |
| [print-agent-and-printers.md](print-agent-and-printers.md) | Paper: enrolling a print agent on a branch machine, registering and replacing a printer, routing documents, and reading a dead print job (ADR 0154) |
| [external-connections/w10-privacy.md](external-connections/w10-privacy.md) | Before production: legal's retention periods, the lawful basis per consent purpose and the wording of the data-subject export's cover note; how to record the confirmation so the platform starts, how to prove it, and the way back (ADR 0029, ADR 0109) |
| [refunds-fiscal-evidence-and-corrections.md](refunds-fiscal-evidence-and-corrections.md) | A customer or an inspector wants a receipt, a receipt is wrong or blocked, a customer wants money back (the cabinet to use first, and the refund-without-a-source rule), an order changed after its receipt was filed, a cash sale waits on its terminal, and how long evidence is kept (ADR 0166, ADR 0048, ADR 0165) |
| [external-connections/w3-delivery-maps.md](external-connections/w3-delivery-maps.md) | Connecting the map provider (ADR 0145): the server key, the browser key, `HORECAOS_GEO_PROVIDER`, the licence question, the 200-address golden set and bake-off, restricting the old shared key, and counsel's view of the processor. One file per wave under `external-connections/` lists the facts that wave was built against a fake for |
| [stop-read-switch-decommission.md](stop-read-switch-decommission.md) | Switching stops off for good: run the materialisation, read the report (the one place a stop can vanish), accept it, have a platform administrator write `inventory.stops.read_enabled`, what `MATERIALISATION_REQUIRED` means, and how to turn reads back on (ADR 0141) |
| [external-connections/w8-acquisition-merchandising.md](external-connections/w8-acquisition-merchandising.md) | The three facts outside the platform that acquisition links, the tenant's Mini App and storefront merchandising were built against fakes for: a real bot's `/newapp` and menu button, counsel's answers (per-person attribution, the page-load count, the 400-day funnel retention), and a signed tenant with two brands (ADR 0183) |

**A runbook that has never been executed is a draft.** Each file above carries a
`Last executed` line in its header. Most read `never`; the few that have been run
say when, where, and which steps that run did and did not exercise, so read the
file's own line rather than any summary of it (a run that found nothing to do, as
`catalog-offering-listing-backfill.md`'s did, exercises less than its date
suggests). ADR 0023 requires each to be exercised once — as a game day or during a
real incident — before it counts, and the date to go in that line is the date it
was actually run rather than the date it was written.

## The two numbers to know before reading anything else

**Recovery point.** Backups run daily. Losing the database means losing up to
24 hours of orders. This is a choice, not a limit: continuous archiving would
reduce it to minutes and has not been set up.

**Recovery time.** Unmeasured. The restore path was exercised against this exact
production stack on 2026-08-23 and completed in under a minute — against a
database holding 234 empty tables. That number is worthless as a production
estimate and must be re-measured against real data volume, then written down
here and in `infra/backup/README.md`.

An unmeasured recovery time is a guess, and a guess is what turns a bad incident
into a surprising one.

## What has no runbook, and why

**Failover.** There is nothing to fail over to. One machine, one disk, one of
everything. If the host is gone, the procedure is `restore.md` onto new hardware,
and it will take hours.

**Scaling.** Manual, by editing `deploy.resources.limits` in
`compose.production.yaml` and redeploying. There is no autoscaling and there is
no second node.

**Zero-downtime deploys.** There are none. `deploy.sh` stops the application
container and starts a new one; the gap is roughly the JVM's startup time.
Caddy holds requests for up to ten seconds across the swap, which covers a clean
restart and does not cover a slow one.

These are the stated ceiling of Docker Compose on a single VM, not oversights.
The ceiling is accepted because the alternative — a Kubernetes control plane
operated by the same one person who operates six stateful dependencies — buys
rolling deploys at the price of a much larger thing to be woken up by.

**Revisit the moment any one of these becomes true**, and treat it as an ADR,
not a weekend:

1. **The quietest hour of the week still carries one order per minute.** Today
   there is a window at 03:00 Tashkent where a sixty-second gap costs nothing.
   When there is not, "deploy at night" has stopped being an answer.
2. **A second application host exists, for any reason at all** — a read replica
   that must be promoted, a staging box that has to match production, a second
   colocation. Compose does not coordinate two machines, and the first attempt
   to make it do so is where this stops being a small system.
3. **Two incidents in one quarter were caused by what this cannot do** — a
   deploy gap that dropped orders, or a failure that a restart policy plus
   autoheal could not recover from. Once is bad luck; twice in a quarter is the
   topology telling you its answer.
