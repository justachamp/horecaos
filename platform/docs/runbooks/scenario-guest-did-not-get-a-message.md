# A scenario guest did not get a message

**Trading-hours question, not an alert.** **Last executed:** never — this is a draft.

**Nothing here needs a code change.** A scenario writes one row for every choice it
makes, and for every block a reason code and a sentence (ADR 0112). "Why did this guest
not get step 2" is a query, not an investigation. The commands below read; the only ones
that write say so in their first line.

**Way back:** reading changes nothing. Step 5 revises a scenario by making a new draft
that needs its own approval; the version it replaces keeps running until the new one is
launched, and nothing is edited in place.

```bash
export HORECAOS_HOST=...            # the console host
export TENANT=...                   # tenant id (uuid)
export BRAND=...                    # brand id (uuid)
export TOKEN=...                    # a console token holding campaign.author at this brand
export SCENARIO=...                 # the scenario's campaign id
export GUEST=...                    # the customer account id, never a phone number
BASE="https://$HORECAOS_HOST/api/v1/tenants/$TENANT/brands/$BRAND/marketing/scenarios/$SCENARIO"
```

## 1. Ask what was decided for this guest

```bash
curl -s "$BASE/decisions?accountId=$GUEST" -H "Authorization: Bearer $TOKEN" \
  | jq '.[] | {step: .stepSequence, decision, refusalReason, reasonText, decidedAt}'
```

**Check:** no rows at all means the guest never got far enough to be decided: they are in
the control group, or have not entered, or their wait has not elapsed. Go to step 2.
A `SENT` row means the platform handed the message to the delivery path; whether it
arrived is the notification's own record (`attemptId`), not this scenario's. A `BLOCKED`
row carries the answer in `refusalReason`:

| `refusalReason` | Meaning | What happened to the guest |
|---|---|---|
| `CONSENT_WITHHELD` | The guest withdrew consent for this purpose and channel | Run ended, `STOPPED_BY_CONSENT_WITHDRAWN` |
| `SUPPRESSED` | An active suppression (a bounce, a complaint, an operator block) | Run ended, `STOPPED_BY_SUPPRESSION` |
| `FREQUENCY_CAP_REACHED` | The platform's cap, or this brand's own cap (the sentence names which, and the numbers) | Held on the same step; asked again at the time in `wait_until` |
| `SCENARIO_CONFLICT` | Another live scenario gave this guest an offer in the last 24 hours | Held on the same step for 6 hours |
| `SCENARIO_PRIORITY_LOST` | A broadcast about a higher-ranked purpose was also due on this channel | Held on the same step for 15 minutes |
| `SCENARIO_STOPPED` | A stop or continuation condition, a retired offer, a channel that can no longer deliver, or the cost ceiling | Run ended (or the whole scenario halted, if the sentence says the ceiling) |
| `NO_VERIFIED_ENDPOINT` | The guest has no verified contact on this channel | Held for a day |

A hold is a deferral, never a drop: the guest is still on the step and is decided again.

## 2. Ask where the guest is

```bash
curl -s "$BASE" -H "Authorization: Bearer $TOKEN" | jq '{status: .campaign.status, participants, decisions}'
```

`participants` counts guests by where they are: `IN_PROGRESS`, `CONTROL` (withheld on
purpose, fixed when they entered, never resampled), and each outcome. A guest counted
under `CONTROL` was never going to be messaged: that is the measurement baseline, not a
fault. `campaign.status` must be `SENDING` for anybody to be decided; `HALTED_BUDGET`
means the cost ceiling or the recipient cap stopped it, and `HALTED_OPERATOR` with
"Superseded" means a newer version replaced it (step 5).

## 3. A marketing text is refused with `SMS_PURPOSE_NOT_PERMITTED`

The SMS account carries sign-in codes and order messages by default. A marketing text is
a different purpose, and the platform refuses it before any request and before any
credential is read until the owner has said in writing which account may carry it
(ADR 0146). The answer is one line on that account's installation:

```
permittedPurposes = TRANSACTIONAL,MARKETING
```

in the `non_sensitive_config` of its ADR 0026 installation. **That is the owner's
decision, not an operator's.** Until it is made, a scenario with an SMS step cannot be
launched: the refusal arrives when somebody presses launch, naming the step, not weeks
later at the third text.

## 4. A brand wants fewer messages, or a longer night

The contact policy is the brand's to tighten and never to loosen:

```bash
curl -s "https://$HORECAOS_HOST/api/v1/tenants/$TENANT/brands/$BRAND/marketing/contact-policy" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The `platform` object is the bound an override is measured against (a cap of three in a
day or a week, eight in thirty days; quiet hours no shorter than 21:00 to 10:00). An
override above a bound is refused with the platform's number in the sentence, and the
table refuses it too if anything else writes to it. Setting or removing one needs
`marketing.contact_policy.manage` and **writes**; removing needs a `reason`.

## 5. Change a scenario that is already approved

A published version is never edited. A new version is a new draft that points at the old:

```bash
curl -sX POST "$BASE/revisions" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: revise-$SCENARIO-1" | jq '{id: .campaign.campaignId, status: .campaign.status}'
```

**Writes** one new `DRAFT`. Edit its steps (`PUT .../steps`), estimate, submit, and have
someone who is not its author approve it. Launching it halts the version it replaces. The
second signature is spent again at every version: that is the cost of a version being
immutable.

## 6. Did it work

```bash
curl -s "$BASE/results?model=FIRST_TOUCH&windowDays=14" -H "Authorization: Bearer $TOKEN" | jq .
```

`lift` is null when the scenario ran without a control group: there is no baseline to
state it against, and the number is not guessed. `LAST_TOUCH` credits an order to the
most recent campaign that reached the guest before it, which another campaign's later
message can take from this scenario; `FIRST_TOUCH` credits the first.

## The late-order apology

An automation rule of kind `LATE_ORDER_APOLOGY` sends words and states no benefit. It
considers an order only once it has been closed for thirty minutes, so support has first
refusal; it is **cancelled, with the reason on its run, when an ADR 0013 remedy is already
recorded for the order**; and it fires once per order. To see why one order was or was
not apologised for:

```bash
RULE=...    # the automation rule id
curl -s "https://$HORECAOS_HOST/api/v1/tenants/$TENANT/brands/$BRAND/marketing/automations/$RULE/runs" \
  -H "Authorization: Bearer $TOKEN" | jq '.[] | {status, refusalReason, refusalDetail, cancelledReason}'
```

`CANCELLED` with a remedy in `cancelledReason` is the reconciliation working, not a
fault. `REFUSED` carries `refusalDetail`, the sentence from the contact policy.
