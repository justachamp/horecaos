# Connecting what the order-message work waits on (wave `w9-notifications-settings`, ADR 0184)

**Last executed:** never — this is a draft. Everything below describes a fact that lives outside the
platform: a person's review, a lawyer's answer, a gateway's approval, a notice to couriers. The code that
waits for each is built and tested against a stand-in; what no test can prove is the real fact, and per
ADR 0023 a runbook that has never been executed is a draft.

**How to get back.** Nothing here is a point of no return. Each section is either a document or a
configuration row, and each says how to undo it.

Each section answers three questions: what exists, where the value goes, and how to prove the connection.

## Default wording

**What exists.** `PlatformDefaultTemplates` (`notifications.domain`) holds eight order-message sentences in
Russian, Uzbek (Latin) and English, version 1: confirmed, being prepared, ready for pickup, on the way,
rejected, cancelled, expired and not paid in time. A tenant that has written no template sends them; a
template a brand or the tenant activates outranks them at once. They are tested for length, character set
(Uzbek and English stay ASCII, so one SMS segment) and for existing in every language the messages tier
serves, and they have not been read by a native speaker of each language.

**Where the value goes.** The reviewer's corrections go into `PlatformDefaultTemplates`. Any change to any
sentence bumps `PlatformDefaultTemplates.VERSION` in the same commit: a notification that was sent from
version 1 keeps meaning version 1, and one still waiting at a release that dropped its version refuses to
render and fails visibly. When a sentence changes, keep the old text in the class under its version number
before the first release that carries both, or no message in flight at the release can render. The review
itself is recorded as a line in ADR 0184's open inputs: who read which language, on which date.

**How to prove it.**

1. Open Settings → Notifications → Templates in the operations console. The «Platform default wording» card
   lists the eight sentences in all three languages, with the origin chip «Platform».
2. With a tenant that has no `ORDER_CONFIRMED` template, confirm an order for a customer whose profile
   language is Uzbek. The delivery evidence for the message names `platformDefaultVersion: 1` and no
   template, and the SMS reads the Uzbek sentence.
3. Click «Customise» on one row. The editor opens on the platform's sentences; save a version and activate
   it. The next message reads the brand's words.

**To undo.** Revert the commit that changed a sentence; do not edit a published version in place.

## Order-progress consent

**What exists.** The three optional milestones (being prepared, ready for pickup, on the way) are
`TRANSACTIONAL_OPTIONAL` (ADR 0020, ADR 0045), and the platform's wording for them names the consent
purpose `ORDER_PROGRESS_UPDATES`. By ADR 0015 a missing decision is a withheld one, so until a decision
exists for a customer, the message is suppressed `CONSENT_WITHHELD`, and the delivery record says so. No
storefront screen records a decision under that purpose, and counsel has not said whether such a message
may instead be sent unless the customer opts out.

**Where the value goes.** Two separate facts.

- *Counsel's answer.* If an opt-out is lawful, the change is a new ADR that alters `NotificationClass`'s
  rule for the optional class (or adds a class), not an edit to a template. If an opt-in is required, the
  answer is the wording the customer is shown.
- *A surface that records the decision.* The storefront or the customer's profile writes a decision through
  `ConsentRecorder.recordGrant` with the purpose `ORDER_PROGRESS_UPDATES`, the channel the customer agreed
  to, a policy version, and an evidence reference to the wording shown. A support agent can record the same
  on a customer's behalf through the customer card.

**How to prove it.**

1. Pick a customer with a phone number and no decision. Advance a delivery order to «Preparing». The
   delivery evidence of the `ORDER_PREPARING` message reads `CONSENT_WITHHELD`.
2. Record a grant for that customer under `ORDER_PROGRESS_UPDATES` on the SMS channel. Advance a new order
   to «Preparing». The message is delivered and reads the platform's (or the brand's) «we have started»
   sentence; advancing to «On the way» sends a second message; a correction sends none.
3. Withdraw the decision. The next order's messages are suppressed again.

**To undo.** Withdraw the decision; nothing else changes.

## One-time-code wording

**What exists.** The sentence a customer reads with a sign-in code is `OtpWording` (`customers.api`), in
Russian (in Latin letters, so it stays one SMS segment), Uzbek and English. A tenant cannot edit it (ADR
0184, D19). Tab 1 shows it read-only, per language, with whether this tenant's SMS gateway moderates
wordings (`GATEWAY_MODERATES` or `NOT_MODERATED`).

**Where the value goes.** A gateway that moderates (the VAS gateway is marked as moderating, ADR 0091)
must have approved the three sentences, with their two placeholders, before a code reaches a handset. The
approval is the gateway's and lives in the gateway's own console or contract. The platform records nothing
about it, because there is nothing to attach it to: the sentence is not a template version. Submit the
three sentences exactly as the card shows them, with a sample code and a sample validity in place of the
placeholders, using the process the gateway's account manager names.

**How to prove it.** Request a sign-in code for a test number from the storefront, once per language, on the
tenant's production SMS binding. The message arrives and reads the sentence on the card. A refusal from the
gateway arrives as the code request failing with `SMS_*` and no message; a gateway that rejects the text
names it in its own report.

**To undo.** Nothing to undo in the platform. Withdrawing the gateway's approval makes codes fail, which is
what the approval is for.

## Courier first name

**What exists.** The on-the-way message may carry the in-house courier's first name (ADR 0045, ADR 0184
D21), through `CourierFirstNameDisclosure`: the courier's name is decrypted under the purpose
`CUSTOMER_ORDER_STATUS_MESSAGE`, cut to its first word, used for the one message, and written nowhere.
Each disclosure leaves an audit fact, `courier.first_name.disclosed_to_customer`, naming the courier and
the order and never the name. An order carried by a partner, or with no open shipment, reads a neutral
phrase («наш курьер», «kuryerimiz», «our courier»).

**Where the value goes.** A notice, to the couriers, that their first name is shown to the customer whose
order they carry, with the retention (none) and the audit. It belongs with ADR 0045's courier transparency
notice and goes to couriers the way that one does. A tenant that does not want the disclosure writes its
on-the-way template without `{{courierFirstName}}` (the platform's own default does not use it).

**How to prove it.** With a template that uses `{{courierFirstName}}` activated, advance a delivery order
carried by an in-house courier to «On the way». The message names the courier's first name. In the audit
log, filter by `courier.first_name.disclosed_to_customer`: one fact per message, with the courier's id and no
name.

**To undo.** Remove `{{courierFirstName}}` from the template and activate the new version.
