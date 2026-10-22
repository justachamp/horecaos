# What the order board's new columns, the plan lock and the reprint key wait on

**Last executed:** never — this is a draft, and says so (ADR 0023). Everything
below is read from the code that ships it and proven against the platform's own
fakes and Testcontainers database; none of it was run against a real provider,
printer or plan. Run the check in each section once in the pilot, write the date
here, and then it is a runbook.

**How to get back.** Nothing here is a point of no return. Every item is a read
or a display: no migration, no stored fact, no outbound call. A column nobody has
switched on is never asked for. Turning a feature flag back off, or removing a
column from the picker, restores the previous board exactly.

This wave (batch 21, `w1-board-shell`) built the order board's seven new columns,
the printing key, the plan lock and the branch open/closed control **against the
platform's own records**. The records are only as true as what the outside world
put in them. Each section below names the outside fact, what the code was built
against, and what to do the day the real thing is connected.

## Courier ETA («Курьер: ETA»)

- **Outside fact.** A delivery provider's quote names an ETA for the courier it
  would send.
- **Built against.** `fulfillment.api.CourierEtaPort#etaByOrders`, read once per
  board page: the ETA captured with the order's live delivery plan (ADR 0014).
  It is absent — and the cell is empty, never estimated — for an order with no live
  plan, for a plan an in-house courier carries, and for a plan whose winning quote
  named no ETA.
- **Connect later.** Nothing to connect: the column fills as providers return an
  ETA in their quotes. **Check:** in the pilot, switch the column on, open a branch
  with one provider-delivered order, and confirm the cell shows the same time as
  that order's delivery panel. An all-empty column on a branch that delivers through
  a provider means the provider's quote adapter is not storing the ETA — that is a
  defect of the adapter, not of the board.

## Source icon («Источник»)

- **Outside fact.** Which marketplace or channel an order came from.
- **Built against.** The order's `origin` and the tenant's own sales-channel
  record (`SalesChannelLookup#byCodes`), resolved on the server into one of the nine
  system types of ADR 0036 and drawn from the console's closed icon set. The
  aggregator's own logo is not fetched or shown: there is no licence to use it.
- **Connect later.** If a partner supplies logos on terms that allow it, they go
  into `q-glyph`'s set as a named icon; the server already sends the system type and
  the channel's display name. **Check:** one order per channel in the pilot; the icon
  and its tooltip name the channel the order came in on.

## Payment method («Оплата»)

- **Outside fact.** How the guest actually paid (cash, card, a provider's wallet).
- **Built against.** The order's live payment intent, read for a whole page in one
  statement (`PaymentIntentPort#paymentMethodsByOrders`): the platform's own
  `PaymentMethod` (ADR 0013), not a provider's label. An order with no intent is
  drawn without an icon.
- **Connect later.** When Click, Payme or a card acquirer is connected for real
  (`connect-click-payme-sandbox.md`, `connect-card-merchant-account.md`), the same
  field carries their method with no console change. **Check:** a sandbox payment
  shows its method on the board within one poll.

## Fiscal chip («Ф»)

- **Outside fact.** The tax authority's fiscal receipt service accepted the sale.
- **Built against.** `fiscal.fiscal_documents.status`, one statement per page
  (`OrderFiscalStatusPort`): the status word of each order's sale document, or of its
  newest document when it has none. A fiscal sign, a receipt link and a marking code
  are protected evidence (ADR 0029) and never travel in a board row.
- **Connect later.** The chip is only as live as the fiscal adapter that moves a
  document out of `PENDING`. Until a real fiscal operator is connected on the
  fiscalization screen (settings.md §10.7), expect `PENDING` and `NOT_APPLICABLE`. **Check:** after one real sale in the pilot, the chip goes from
  pending to accepted without anyone opening the order.

## Distance («Расстояние»)

- **Outside fact.** The routed distance a delivery provider quoted.
- **Built against.** `DeliveryDistancePort#distanceMetersByOrders`: the distance
  stored with the winning quote of the order's live plan. Empty when the quote named
  none; never computed in the browser from two coordinates.
- **Connect later.** Same as courier ETA. The Uzbekistan routing dataset
  (`load-uzbekistan-routing-dataset.md`) is not what this column reads.

## External number («№ у партнёра»)

- **Outside fact.** The marketplace's own id for the order.
- **Built against.** The external reference stored at intake (ADR 0040), read for
  the page in one statement. It is the partner's string, shown as sent and never
  parsed.
- **Connect later.** Nothing: it appears as aggregator orders arrive. **Check:** a
  test order from a sandbox aggregator shows the partner's number in this column and
  in the order detail.

## Print again («p»)

- **Outside fact.** A receipt printer, reached through the print agent.
- **Built against.** The server emits the `REPRINT` action only when all three hold:
  the caller has `print.reprint` at the branch, the tenant has `feature.printing`
  switched on (it is dark by default, ADR 0154) and the branch has an active
  printer (`OrderReprintPort`). The key presses the row's own button, so a row
  without the action has no key.
- **Connect later.** Follow `print-agent-and-printers.md` to install the agent and
  register a printer, then switch on `feature.printing` for the tenant. **Check:**
  `p` on a board row prints the same document as the order detail's print panel; with
  the flag off, neither the button nor the key exists.

## Locked by plan («Закрыто тарифом»)

- **Outside fact.** What the tenant's plan includes.
- **Built against.** `ConfigurationKey#entitledBy` and the platform's
  `EntitlementGate`, which is **enforcement-aware**: under the default meter-only
  mode (ADR 0021) nothing is locked and no key shows the state. Today one family is
  annotated: `assistant.enabled` and the three `assistant.disclosure_text_*` wordings,
  all against `assistant.answering.enabled`.
- **Connect later.** The lock appears when the platform's commercial enforcement is
  raised above meter-only for a tenant and that tenant's plan lacks the feature
  (`connect-assistant-provider.md` covers the assistant's own entitlement). The
  «Подробнее» link goes to the console's own Finance → Subscription screen; the
  platform's upgrade path is an API path and is never shown. **Check:** with the
  pilot tenant on a plan without the assistant and enforcement on, the Assistant
  screen's four fields are greyed with the lock, and a write to any of the four
  returns `ENTITLEMENT_REQUIRED` and stores nothing.

## Branch open/closed control («c»)

- **Outside fact.** None; it reads and writes the platform's own service state
  (ADR 0036, `location.service-state.change`). It is recorded here because the
  closed-branch strip is the console's only defence against a branch left shut
  without an expiry.
- **Check.** Close a pilot branch «до отмены» from the header (`c`), confirm the
  strip appears at the top of the live board and the branch list and cannot be
  dismissed, then reopen it from the strip.
