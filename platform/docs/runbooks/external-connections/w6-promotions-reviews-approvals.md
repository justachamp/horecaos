# External facts for the promotions, reviews and approvals wave

**Last executed:** never. Each section below is one fact that exists outside this repository. The code that
needs it is built against a fake, a flag that is off, or an unactivated template, so the platform runs without
it and does exactly nothing with it until it arrives. A section says **what exists** now, **where the value
goes** when it is known, and **how to prove the connection** once it has been put there. Nothing here is
guessed into a default: where an answer is unknown the feature stays dark and the section names the setting
that will carry it.

Decisions this wave took are in [ADR 0181](../../adr/built/0181-compensation-from-a-case-promotion-targeting-requote-and-discretionary-discounts.md);
the batch-21 gap-map rows are `5.4`, `6.1`, `6.3` and `9.4`.

| # | Fact | Owner | Blocks |
|---|---|---|---|
| 1 | How a service line is taxed and carried on the fiscal receipt | Finance | Switching `pricing.order_markup.enabled` on for anyone |
| 2 | Whether a charge that depends on the payment method is lawful | Counsel | A service charge conditioned on `PAYMENT_METHOD`, and a surcharge for paying by card |
| 3 | The class of the loyalty-expiry message, and expiring a promotional balance | Counsel | A tenant activating `LOYALTY_POINTS_EXPIRING` for real customers |
| 4 | The SMS gateway's approval of each tenant's wording | The gateway, per tenant | The SMS half of the same message (Telegram is not moderated) |
| 5 | Finance's confirmation of the approval limits | Finance | Nothing: tenants set their own; the defaults stay marked provisional |

## 1. Fiscal and tax treatment of a service line

**What exists.** An order-level markup is a stage of the pricing engine (`PricingEngine`, stage 5): a
`SERVICE_CHARGE` line inside `fees` (`fee_minor`), computed after the order's discounts, with no tax share (the
tax is a placeholder, `0`) and with `ck_order_total_reconciles` holding. The promotion editor offers the action
and the validator accepts it only when `pricing.order_markup.enabled` is on. The flag is checked three times:
at authoring (the validator refuses), at input resolution (the resolver strips an ORDER markup when it is
off), and at evaluation (a stored definition applies nothing). It is `false` at the platform and, as declared,
settable at `PLATFORM` and `TENANT` only; it is not shown on the tenant's own Settings screen.

**Known gaps, stated so nobody finds them in production.**

- The fiscal receipt has no line or tax classifier for a service charge. The charge would travel inside the
  delivery-fee figure the receipt builder reads.
- Fee reports count the charge in `fee_minor`, so a delivery-fee figure would include it.
- The dine-in session-rate feed that would supply a rate for a table is not built.
- The storefronts have no «service charge» wording for the order-level markup (they say «surcharge»).

**Where the value goes.** Finance supplies: the classifier code (ИКПУ/MXIK) and VAT treatment of a service
line, and whether the line is separate on the fiscal receipt or inside the item lines. When answered, the
change is code (the receipt line builder and the classifier), not configuration; this section's job is to say
that switching the flag first would put a charge on a customer's total that the fiscal document cannot show.

**How to prove the connection.** With the flag off, authoring an ORDER markup answers
`ORDER_MARKUP_NOT_AVAILABLE` from the validator and `OrderMarkupFlagTests` pins that no total moves. To try the
charge on a sandbox tenant only, a platform operator sets the flag at that tenant:

```sh
curl -sS -X POST "$API/api/v1/control-plane/configuration/keys/pricing.order_markup.enabled/values" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"scopeType":"TENANT","tenantId":"'"$TENANT"'","booleanValue":true,"explicitNull":false,"reason":"sandbox walk of the service charge"}'
```

Check: a quote of a delivery cart carries a `SERVICE_CHARGE` line in `adjustments`, the total reconciles, and the
fiscal receipt preview for that order is read before anyone calls it done. To go back, set `booleanValue` to
`false` (or `explicitNull` to inherit the platform's `false`); a stored definition applies nothing again at once.

## 2. A charge that depends on the payment method

**What exists.** A promotion condition `PAYMENT_METHOD` already exists for discounts. Combined with an ORDER
markup it would be a surcharge for paying a particular way. Nothing in the validator tells these apart: the only
guard is the flag in section 1.

**Where the value goes.** Counsel's answer is a rule, not a number. If surcharges for card payments are not
lawful, the rule is a validator refusal (an ORDER markup may not carry a `PAYMENT_METHOD` condition) added next
to `TITLE_LOCALE_INVALID` in `PromotionValidator`; if they are, nothing changes.

**How to prove the connection.** Until counsel answers, do not switch the flag on for a tenant that wants a
method-dependent charge. After the answer, `PromotionValidatorTests` carries the case either way.

## 3. The class of the loyalty-expiry message, and expiring a promotional balance

**What exists.** `LoyaltyMaintenanceService.warnExpiringLots` groups a customer's lots inside their warning
window into one call to `LoyaltyExpiryWarningPort`; `LoyaltyExpiryWarningNotifier` (module `notifications`)
hands it to `CustomerAccountAlertPort` as the semantic template key `LOYALTY_POINTS_EXPIRING` with three typed
variables, `loyaltyPoints`, `loyaltyExpiresOn` and `loyaltyDaysRemaining`. A customer is left alone for 24
hours after a warning, so the message is at most one a day (ADR 0046's default). The class is
`TRANSACTIONAL_REQUIRED`, the precedent ADR 0159's rating prompt takes: the customer's preference cannot switch
it off. It is a constant, `CustomerAccountAlertFanoutService.NOTIFICATION_CLASS`. **A template nobody has
authored and activated sends nothing**: the intent is suppressed `NO_ACTIVE_TEMPLATE` and the lot counts as
warned. Activate the wording before the first lot reaches its window, or accept that warnings in the window
before activation are not repeated.

**Where the value goes.** Counsel decides two things. (1) Whether the message may be `TRANSACTIONAL_REQUIRED`.
If not, change the constant to `TRANSACTIONAL_OPTIONAL`, give the template a consent purpose, and expect a
customer without that consent to receive nothing; the variable catalogue already offers the three variables
under both classes. (2) Whether a promotional balance may expire at all under Uzbek consumer-protection rules;
if not, the answer is the accrual rule's `lotLifetimeDays` (Marketing, Loyalty), not code.

**How to prove the connection.** A tenant owner authors and activates the wording in Settings, Notifications
(the editor previews with `12 000`, `28.10.2026` and `14`), then the owner reads the result of the next sweep:

```sh
psql "$DATABASE_URL" -c "select status, suppression_reason, count(*) from notifications.notifications
  where template_key = 'LOYALTY_POINTS_EXPIRING' group by 1,2 order by 3 desc;"
```

`READY`/`SENT` rows are messages; `SUPPRESSED` with `NO_ACTIVE_TEMPLATE` means the wording was not active;
`TEMPLATE_AWAITING_PROVIDER` is section 4. `LoyaltyExpiryWarningNotificationTests` proves each outcome against a
real database. To stop the message, deactivate the template: the sweep still marks lots and nothing is sent.

## 4. The SMS gateway's approval of each tenant's wording

**What exists.** A new SMS wording starts with provider review `PENDING` where the gateway moderates (ADR 0091),
and eligibility withholds it as `TEMPLATE_AWAITING_PROVIDER`. Telegram delivery is not subject to the gateway's
moderation. The channel is chosen by `CustomerTelegramChannelRouter`: Telegram when the customer has a linked
chat and the tenant is entitled, otherwise the SMS default (`horecaos.notifications.order-channel`).

**Where the value goes.** The gateway's decision is recorded per template version by whoever holds the
moderation desk's capability (`provider_review` on `notifications.template_versions`), in Settings,
Notifications. Nothing in this wave stores a gateway value.

**How to prove the connection.** Submit the wording to the gateway, record `APPROVED`, then re-run the check in
section 3: the same message now reads `READY`. `PaymentAndQuietHoursNotificationTests` and
`LoyaltyExpiryWarningNotificationTests` hold the withheld and the released cases.

## 5. Finance's confirmation of the approval limits

**What exists.** Settings, Approvals edits the three promotion limits (`pricing.promotion.approval.percentage_over_bp`,
`...amount_over_minor`, `...always_for_markup`) at the tenant or a brand, and the customer export row limit
(`customers.pii_export_approval_threshold_rows`) at the tenant. A `FREE_ITEM` gift's highest unit price is
weighed against the amount limit. The platform defaults are provisional and the screen says so. The refund limit
is not a tenant setting: it stays the one platform-wide number ADR 0048 keeps
(`horecaos.payments.remedy-approval-threshold-minor`).

**Where the value goes.** Finance's numbers go in the platform defaults (the declared defaults of the four
keys, a code change reviewed with finance) and, per tenant, on the Settings, Approvals screen. Who signs each
action is a separate act: the account owner publishes it under "Who signs" on the same screen, over
`/api/v1/operations/tenants/{tenantId}/approval-policies`.

**How to prove the connection.** Read the resolution of each key (`GET /api/v1/operations/tenants/{tenantId}/configuration/keys/{code}/resolution`)
and confirm `cameFromDefault` is `false` where a tenant has set a value. The four producers of the
approval queue are proved against the real policy route by the endpoint tests named in the gap map's `9.4` row.
