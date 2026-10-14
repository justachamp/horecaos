# ADR 0184: What a customer is told about an order: the platform's words, three more milestones, and which personal values a message may say

- Decision status: Accepted — proposed by Claude (operations batch 21, wave `w9-notifications-settings`); accepted by the platform owner 2026-10-14
- Implementation status: Partial — built and tested 2026-10-14: the platform's own wording for the five required order messages (confirmed, rejected, cancelled, expired, not paid in time) and for the three optional ones, resolved last and frozen onto the notification row; the lexicographic resolution order; `OrderPreparing`, `OrderReady` and `OrderFulfilling` through the outbox; the new merge variables with the personal values read at render and never stored; the one-time-code sentence read-only in Tab 1; Tab 1's completeness, moderation and severity view, copy to another language and Telegram test send. Not built, and the reason the record is not Built: no surface records a customer's decision under `ORDER_PROGRESS_UPDATES`, so the three optional milestones are withheld `CONSENT_WITHHELD` for every customer, and the default wording has not been read by a native speaker (the two unticked items below)
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: Ayubkhon Abbosov (platform owner), on options proposed by Claude
- Depends on: ADR 0015, ADR 0019, ADR 0020, ADR 0025, ADR 0029, ADR 0031, ADR 0032, ADR 0045, ADR 0056, ADR 0058, ADR 0068, ADR 0075, ADR 0091, ADR 0149
- Supersedes / Superseded by: — (amends ADR 0020's resolution order, its class list and its variable rule, and builds ADR 0045's customer milestones; each carries a dated status addition pointing here, and none of their Decision, Alternatives or Consequences text is rewritten)
- Open inputs: each is closed on the default this record states, and the rows that name another person or an external fact stay with them.
  - **A native speaker's review of the platform's default wording** (platform owner). The sentences in `PlatformDefaultTemplates` are written, tested for length and character set, and not yet read by a person who speaks each language. Until they are, a tenant that wants words it can vouch for writes its own, which outranks them. Runbook section «Default wording».
  - **Counsel's answer on the legal basis of the three optional milestones, and a surface where a customer gives it** (counsel; platform owner). The code reads the class as ADR 0020 defines it: `TRANSACTIONAL_OPTIONAL` requires a consent decision under the template's purpose, and absence of a decision withholds. No storefront screen records a decision under `ORDER_PROGRESS_UPDATES` yet, so until one exists, or counsel says an opt-out is lawful and a new record changes the class's rule, the three milestones are suppressed `CONSENT_WITHHELD` and say so in the delivery evidence. Runbook section «Order-progress consent».
  - **The SMS gateway's approval of the three one-time-code sentences** (platform owner, with the gateway). The platform owns the sentences and shows them; a gateway that moderates wordings must have approved them before a code reaches a handset, and nothing in this build can record that approval. Runbook section «One-time-code wording».
  - **Whether couriers have been told that their first name is shown to a customer** (platform owner, counsel). ADR 0045 decided the disclosure; the notice to the people it is about is a document. Runbook section «Courier first name».

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Gap-map row `10.9a` is the console's template editor, and three things stood between it and an order message a customer could actually read.

**The editor did not reach the platform.** Its seven path builders were built on `/api/v1/operations/tenants/...`; `NotificationTemplateController` is mapped at `/api/v1/tenants/...`, and the platform serves nothing at the first. Every call answered 404, and no suite noticed, because every spec of the screen replaces the API with a stub and so never sees a URL.

**A new tenant sent no order message at all.** Templates are created only through that controller. Nothing seeds one, so a tenant that had written nothing sent no confirmation, no rejection and no cancellation, and the suppression reason `NO_ACTIVE_TEMPLATE` was the only evidence. ADR 0020 names a platform default beneath the brand's and the tenant's wording; ADR 0068 gave the default terms a bundled shape for exactly this kind of layer; this one did not exist.

**ADR 0045 promised three customer milestones that nothing published.** «Being prepared», «ready» and «on the way, with the courier's first name» are named there as ADR 0020 messages. `OrderStatus` has `PREPARING`, `READY` and `FULFILLING`, but `OrderingEvent` had no event for them: `OrderStateService` said in a comment that they "have no external consumer", which was true until this record, and `OrderNotificationTrigger` was written to be silent about everything else on purpose.

Beneath those, three questions no Accepted record answers, and which the work cannot proceed without answering:

- the Tab 1 list lets an operator author a template of the `SECURITY` class, for the one-time code. Nothing ever sends one. The sentence a customer reads with a code is a constant in the SMS adapter and goes through no template, no moderation and no tenant;
- `JdbcTemplateStore.activeTemplate` orders candidates by a sum that counts the brand as one more dimension. A tenant-wide template pinned on both a fulfilment mode and a channel source scores 2 and a brand-wide generic one scores 1, so the brand's own override is silently ignored for exactly the orders the tenant pinned. The earlier test only pinned the tie between two single-dimension variants, "by column order, not a product rule", and said so;
- the thirteen merge variables Delever offers include a customer's name and phone and a courier's phone and surname. The current design stores a notification's variables on the row and keeps them free of personal data (ADR 0020, ADR 0029), so a personal variable is a design change and not a catalogue edit.

## Decision

### D19. The one-time-code sentence is the platform's, and Tab 1 shows it read-only

1. **A tenant cannot edit the one-time-code wording.** It stays in code (`OtpWording` in `customers.api`), is sent by the customers module without a template, and does not pass through template moderation. The reasons the adapter already gave stand: a tenant-editable body is a body somebody shortens until the code is all that is left, or lengthens past one SMS segment, and `customers` cannot depend on `notifications`.
2. **Tab 1 shows it.** Per language, as plain text with its two placeholders, with whether this tenant's SMS gateway moderates wordings (the same question ADR 0091 asks of a template, answered by `smsWordingAwaitsGateway`). It has no input and no button.
3. **A `SECURITY` template cannot be created.** `NotificationTemplateService.createTemplate` refuses the class with `VALIDATION_FAILED` and a sentence that says why; the console does not offer it; the variable catalogue has no entry for it. The enum value stays: ADR 0020 names the class, preferences and notification rows still carry it, and a later record may give it a sender.
4. **This amends ADR 0020's class list and answers IA row 10.9's «OTP/confirmation templates carrying provider moderation state».** The confirmation half is the platform default layer below; the OTP half is not a template and has no moderation state of its own to show, only the gateway's.

### D20. Scope outranks variant, and fulfilment mode outranks channel source

1. **The order is lexicographic.** Among the ACTIVE templates that match a message, the winner is chosen by, in turn: a row of the message's brand over a tenant-wide row; then the row that pins down more variant dimensions (fulfilment mode and channel source) over one that pins fewer; then, between two rows that each pin exactly one, the fulfilment-mode row over the channel-source row. Below all of those sits ADR 0020's platform default.
2. **A brand's generic wording beats the tenant's pinned wording.** A brand that wrote its own template means it for every order it serves, unless it also wrote a narrower one. The tenant can still narrow for all its brands, and a brand that wants the tenant's pin writes the same pin.
3. **The platform default answers only when nothing else exists.** Only when the tenant has no active template at any scope for the key and channel. A tenant template that exists and has no wording in any language tried is a visible gap (`NO_TEMPLATE_FOR_LOCALE`), and the platform's wording does not quietly cover it.
4. **This amends ADR 0020's order**, which named brand, tenant, platform and nothing about the variant dimensions ADR 0036 and gap-map row 10.9a added.

### D21. Two personal values may be merge variables, each in one place, resolved at render and never stored

1. **`customerName`**, for a message addressed to a customer (`TRANSACTIONAL_REQUIRED`, `TRANSACTIONAL_OPTIONAL`, `MARKETING`), and for no group message. It is the name the customer gave their profile, as entered. A customer with no name reads a neutral word in their language.
2. **`courierFirstName`**, for the on-the-way message (`ORDER_FULFILLING`) only. It is the first word of the name of the in-house courier carrying the order's open shipment, revealed under a declared purpose with an audit fact that names the courier and never the name. An order carried by an external partner, or with no open shipment, reads a neutral phrase.
3. **Resolved at the moment of rendering and written nowhere.** Not in `variables_json`, not in the variables hash, not in a log, an event or a dead-letter summary. The eligibility step removes both names from the frozen map even if a trigger supplied them. The rendered text is hashed as before, the text itself is never kept.
4. **Nothing else about a person is a merge variable.** The customer's phone, the courier's phone and the courier's surname are declined. No list offers them, and a version that declares one is refused when it is saved, naming it. A version that declares `customerName` in a group message, or `courierFirstName` outside the on-the-way message, is refused the same way. ADR 0045 is why the courier's number was never an option; ADR 0058 is why a group does not get a customer's name.
5. **This amends ADR 0020's variable rule**, which allowlisted typed variables and was silent on personal ones.

### The platform's own wording (the layer ADR 0020 names last)

Bundled in code as `PlatformDefaultTemplates`, in Russian, Uzbek (Latin) and English, versioned (`VERSION`), for eight keys: `ORDER_CONFIRMED`, `ORDER_PREPARING`, `ORDER_READY`, `ORDER_FULFILLING`, `ORDER_REJECTED`, `ORDER_CANCELLED`, `ORDER_EXPIRED` and `ORDER_PAYMENT_LAPSED`. Not stored as rows with no tenant, because that would be the one row ADR 0056's row-level security does not govern; the shape is ADR 0068's. A notification sent from it freezes `platform_default_version` in place of a template id and version. It is offered on the wired customer channels (SMS and Telegram), only to a customer-addressed row of the class the platform wrote it for, and it uses only the order number, the total and its currency, which every order has. `ORDER_COMPLETED` has no default: that message carries the rating prompt, ADR 0075 makes the prompt opt-in by activating a template, and V0545 preserved existing prompts on exactly that premise.

### The three milestones

`OrderStateService` publishes `OrderPreparing`, `OrderReady` and `OrderFulfilling` when a forward transition enters those statuses, through the outbox, with schemas, catalogue entries and documentation rows (ADR 0032). A compensating edge (ADR 0110) publishes nothing. `OrderNotificationTrigger` turns each into an intent of the class `TRANSACTIONAL_OPTIONAL` with the purpose `ORDER_PROGRESS_UPDATES`, for thirty minutes at most, to the customer only. A delivery order's `READY` is «waiting for a courier», which ADR 0045 does not message, so only a pickup order's `READY` becomes an intent.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| **D19.** Let a tenant edit the one-time-code wording through the template machinery | Needs a `customers.spi` wording port so the customers module can ask notifications for text (an XL change), makes a code's length and segment count a tenant's choice, and has no moderation answer for a text sent before any template exists | A tenant must send the code in its own words, and the owner accepts the port and the segment risk |
| **D19.** Keep `SECURITY` creatable and send it from a trigger | There is no trigger. The class would again be wording nobody can see being sent, which is the silent failure the old console invited | A security notice other than the code exists and has a sender |
| **D20.** Keep the sum of dimensions, brand included | It lets a tenant's pin silently override a brand's own wording; the earlier record of this case in the tests called the tie-break «deliberate-but-arbitrary» | Never |
| **D20.** Put the tenant's pinned variant above the brand's generic wording | Makes it impossible for a brand to override a tenant's pin without writing the same pin, and reads the tenant's narrower scope as stronger than the brand's closer one | A tenant needs to force a wording on every brand: a second flag on the template, not a different order |
| **D20.** Let the operator choose fulfilment over channel or the reverse per template | A second knob with no stated need, and an answer that would differ between two templates for the same order | An operator shows two variants whose order they must flip |
| **D21.** Keep every personal value out of messages | The IA lists a customer's name among the thirteen variables and ADR 0045 says «with the courier's first name»; a tenant would write the name in by hand into a template that cannot know it | A regulator or counsel withdraws the allowance |
| **D21.** Freeze `customerName` on the notification row like the other variables | A table a support export reads would hold a name beside an order number, which V0026's header says it must never become; erasure would have two places to clean | Never |
| **D21.** Offer the customer's phone, or the courier's phone | The message already goes to the customer's phone; a courier's number is exactly what ADR 0045 declined | ADR 0045 is superseded |
| **Platform default.** Rows with no tenant | The one row ADR 0056 does not govern, a seeding step per release, and a text a tenant could edit by accident through a tenant-scoped API | Never |
| **Platform default.** Seed a copy into each tenant when it is onboarded | A copy drifts: a later correction to a sentence reaches nobody, and a tenant cannot tell its own words from the platform's | A tenant must be able to see and edit exactly what it sends before its first order |
| **Platform default for `ORDER_COMPLETED`** | Switches the rating prompt on for every tenant that never chose it, and breaks the premise of V0545 | ADR 0075's opt-in is replaced by an opt-out |
| **Milestones.** Make the optional messages opt-out by treating a missing consent decision as consent | Contradicts ADR 0015 («absence is not consent») and `NotificationClass`, and is the legal question counsel owns | Counsel says an opt-out is lawful, in a new record that changes the class's rule |
| **Milestones.** Message a delivery order's `READY` | A customer told «ready» and then «on the way» within minutes has two messages for one fact; ADR 0045 lists only the second | A tenant asks for it per fulfilment mode: it can write a variant on the key |

## Consequences

### Positive

- The template editor can list, create, version, activate, test-send and load its catalogue, and a gate resolves every path builder of the console against the published contract so the next wrong prefix fails a build.
- A new tenant sends its first confirmation, rejection and cancellation in three languages without writing a word, and every notification says which wording it came from.
- A brand's own wording can no longer be silently overridden by a tenant's narrower pin, and the tie-break between fulfilment mode and channel source is a rule with an owner.
- A customer is told the milestones ADR 0045 promised, without being told twice and without being told falsely when an operator corrects a mistake.
- A customer's name and a courier's first name reach a message and no table; the declined values are refused while somebody writes, not discovered when a customer reads.
- The list shows what is wrong first: which languages a template lacks, whether the gateway withholds it, and what it is.

### Negative

- **The three optional milestones send nothing until a customer has agreed.** No surface records a decision under `ORDER_PROGRESS_UPDATES`, so in practice they are suppressed `CONSENT_WITHHELD` until one exists. This is deliberate and visible in the delivery evidence, and it makes the milestones a built mechanism with a dormant default.
- The platform's default wording is not yet read by a native speaker, and it is the first thing a tenant that wrote nothing sends. Mitigated by being the lowest layer and by a tenant's wording outranking it the moment it is activated.
- A tenant that had deliberately sent no confirmation (by never writing a template) now sends the platform's. The delivery evidence and the delivery-channel router are unchanged, but a tenant that wanted silence has no switch except a template with no wording. This is a change of behaviour for exactly the tenants that did not choose.
- Two lookups the old path did not make happen at send time for a wording that names the courier's first name: an assignment read and a decrypt, with an audit fact each. They are made only when the wording asks.
- A default used on Telegram means a customer routed to Telegram by ADR 0058's router who has no Telegram template of the tenant's now receives the platform's wording there, where before they received nothing.
- The platform's sentences are changed only by a release that bumps `VERSION`; a notification in flight at a release whose sentences changed refuses to render against a version the release no longer carries, and fails visibly. The cost of keeping old sentences is not paid until a sentence changes.

### Accepted trade-offs

- `customerName` is the profile's display name as entered, which may be a full name. A tenant that wants a first name only writes its greeting without the variable.
- `READY` is a pickup message only. The «ready» for delivery and dine-in is not a customer fact in this record.
- The milestone intents expire after thirty minutes at most (`OrderNotificationTrigger`'s constant), not at the six hours a confirmation is worth. It is not yet configurable.
- The SMS gateway's approval of the platform's one-time-code sentences is a fact held outside the platform; the card shows whether the gateway moderates, not whether it has approved.

## Specification

### Resolution (`NotificationTemplateService.resolve`, `JdbcTemplateStore.activeTemplate`)

```sql
ORDER BY (brand_id IS NOT NULL) DESC,
         ((fulfillment_mode IS NOT NULL)::int + (channel_source IS NOT NULL)::int) DESC,
         (fulfillment_mode IS NOT NULL) DESC,
         (channel_source IS NOT NULL) DESC
```

`resolve(..., notificationClass)` answers the platform default only when the tenant resolution is `NO_ACTIVE_TEMPLATE`, the class equals the entry's class, the channel is SMS or Telegram, and some language in the brand's chain (the customer's if served, else the brand's default, then the registry's fallback) has a sentence. `TemplateVariantResolutionTests` pins every step of the order; `PlatformDefaultResolutionTests` pins the three layers and the places the platform stays silent.

### Data (`V0677`)

- `notifications.template_versions.show_item_prices boolean NOT NULL DEFAULT false`: one value per version, written on every language row. When true it is part of the content hash, so every earlier version keeps its hash.
- `notifications.notifications.platform_default_version integer`, with `ck_notification_platform_default`: set only when `template_id` is null.
- `NotificationEligibilityService` freezes the language the wording is actually in, not the customer's, on the row. A brand that does not serve a customer's language writes to them in its own, and the dispatcher looks the version up by exactly that tag. (The previous code froze the customer's language and failed to find the row.)

### Merge variables (`NotificationVariableCatalog`)

| Class | Offered |
|---|---|
| `TRANSACTIONAL_REQUIRED` | `orderNumber`, `amount`, `currency`, `restaurantName`, `deliveryTime`, `preparationTime`, `productList`, `discountAmount`, `deliveryFee`, `reasonCode`, `customerName` |
| `TRANSACTIONAL_OPTIONAL` | the same without `reasonCode`; plus `courierFirstName` for `ORDER_FULFILLING` |
| `MARKETING` | `customerName`, `discountCode` |
| `OPERATIONS_ALERT` | unchanged |
| `SECURITY` | none |

`restaurantName` is the brand's display name; `deliveryTime` is the promised time on the branch's clock, `HH:mm`, and `preparationTime` the kitchen's promised minutes, each `—` when no promise was made; `productList` is `2x Lavash, 1x Cola` (an ASCII `x`, so an SMS stays GSM-7), with `(50000 UZS)` after each when the version's switch is on, naming the first six items and counting the rest; `discountAmount` and `deliveryFee` are numbers in the currency's whole units. All are read through `OrderDirectory.notificationFacts`, a narrow read that carries no customer, only when the wording names them.

### Endpoints (`NotificationTemplateController`, all `GET`, `notification.template.author`, `BRAND` scope)

- `GET .../notification-templates` now also carries `origin` (`BRAND` or `TENANT`), `shownVersion`, `versionLocales`, `requiredLocales` and `providerReview`.
- `GET .../notification-templates/platform-defaults`: the eight defaults, read-only.
- `GET .../notification-templates/otp-wording`: the three sentences and `gatewayApproval` (`GATEWAY_MODERATES` or `NOT_MODERATED`).
- `GET .../notification-templates/variable-catalogue?templateKey=`: adds `courierFirstName` for `ORDER_FULFILLING`.
- `POST .../versions` accepts `showItemPrices`; `GET .../versions` returns it. `POST .../test-send` accepts a Telegram template, with a bound chat's binding id as the destination (checked against the brand's active operations chats), and writes `notification.template.test_sent` without the destination.

### Events

`OrderPreparing`, `OrderReady`, `OrderFulfilling` (v1, `ordering.events`, key `orderId`): brand and location, fulfilment mode, the time, the status and the order version. No customer and no courier. Schemas under `events/ordering.events/`, baselines frozen under `events-baseline/`, rows in `docs/domains/events.md`, entries in `EventCatalog`.

### Security and privacy

No new table holds personal data. The audit fact `courier.first_name.disclosed_to_customer` (`SECURITY`) names the courier and the order and records the purpose `CUSTOMER_ORDER_STATUS_MESSAGE`. The test-send fact carries the template, version, language, channel and outcome. `OrderMilestoneNotificationTests` asserts that neither name is in any column of `notifications.notifications` or `notifications.delivery_attempts` after a send.

### Testing

`OrderMilestoneNotificationTests` (the real path from the fact to the handset, 16 cases), `PlatformDefaultResolutionTests`, `TemplateGovernanceTests`, `TemplateVariantResolutionTests`, `PlatformDefaultTemplatesTests`, `NotificationVariableCatalogTests`, `OrderMessageValuesTests`, `OtpWordingTests`, `CourierFirstNameDisclosureTests`, `NotificationTemplateControllerHttpTests` (the route, the capabilities and the console's real JSON), and in the console `settings-paths.spec.ts`, `path-builders-resolve.spec.ts`, `notifications-page.spec.ts`, `template-editor.spec.ts` and `template-list-order.spec.ts`.

## Rollout and rollback

Additive. The migration adds a defaulted column and a nullable one. The new events reach no consumer but the notification trigger and the outbox. Rollback is a code revert: a notification already frozen with `platform_default_version` would refuse to render on the old code and be retried to a visible failure, and the column can stay.

## Implementation checklist

- [x] The seven path builders on `/api/v1/tenants`, a path spec that asserts the literal paths and the published verbs, a gate that resolves every builder against the baseline, and an HTTP test that names the route
- [x] D20: the ORDER BY, and a test of every step of the lattice
- [x] The platform default layer, its notification freeze, its render, its read-only console view and «customise»
- [x] `OrderPreparing`, `OrderReady`, `OrderFulfilling`, with schemas, baselines, catalogue and documentation rows, and the trigger
- [x] The merge variables, the price switch, and D21 at render
- [x] D19: the refusal, the read-only card
- [x] Tab 1: language chips, the moderation column, severity order, the consent notice, copy to another language, a Telegram test send and its audit fact
- [x] OpenAPI baselines and generated clients for the five documents
- [x] Runbook `docs/runbooks/external-connections/w9-notifications-settings.md`
- [ ] A native speaker has read the platform's wording in each language (open input, platform owner)
- [ ] A surface records a customer's decision under `ORDER_PROGRESS_UPDATES` (open input, counsel and platform owner)

## Exit criteria

- A tenant with no template of its own, an order confirmed in each of the three languages, produces an SMS in that language and a delivery record that names `platform_default_version`.
- Activating a brand template for the same key changes the next message to the brand's words without a deployment.
- With a recorded decision under `ORDER_PROGRESS_UPDATES`, advancing a delivery order through the kitchen path produces three messages (preparing, and on the way with a courier's first name), a pickup order produces two (preparing, ready), and a correction produces none.
- No column of `notifications.notifications` contains a customer's name or a courier's first name after any of them.
- Authoring a version that names a customer's phone, a courier's phone or surname is refused with the variable named.
- Tab 1 lists a withheld required template above a complete one, shows the languages a live template lacks, and shows the one-time-code sentence read-only.

## References

- ADR 0015 customer accounts and consent; ADR 0019 order state machine; ADR 0020 notification preferences, templates and delivery; ADR 0025 capability model; ADR 0029 PII protection; ADR 0031 HTTP API conventions; ADR 0032 event contract governance; ADR 0036 fulfilment modes and promised time; ADR 0045 customer milestones and the courier's first name; ADR 0056 row-level security; ADR 0058 Telegram channels; ADR 0068 default terms; ADR 0075 the rating prompt; ADR 0091 SMS wording moderation; ADR 0110 compensating transitions; ADR 0149 languages
- `docs/operations-spec/settings.md` §10.9; `docs/operations-gap-map.md` rows `10.9a`, `10.10c`
