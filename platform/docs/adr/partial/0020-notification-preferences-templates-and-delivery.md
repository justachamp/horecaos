# ADR 0020: Notification preferences, templates, and delivery

- Decision status: Accepted
- Implementation status: Partial — V0026's seven tables, the `notifications`
  module and the SMS route under `integration.camel.notification` carry order
  confirmation and rejection on one channel end to end: `OrderNotificationTrigger`
  writes the intent in the deciding transaction, `NotificationWorker` claims it
  under a lease, `NotificationEligibilityService` gates on ADR 0015 consent
  through `ConsentDirectory`/`RecipientContactDirectory`, `TemplateRenderer`
  renders a frozen template version, and `SmsGatewayAdapter` sends through the
  ADR 0007 route — proven against `FakeSmsGateway` including the accepted-then-lost
  reply. Not built: any real provider. The first vendor contract has now been
  read and written down (`docs/providers/sms-gateway-vas.md`, with its
  twenty-eight status codes mapped onto ADR 0007's outcome model, its missing
  idempotency key named, and `/search` identified as the uncertainty resolver),
  but the adapter in the committed tree is still the generic JSON-over-HTTP
  gateway. Also absent: every channel but SMS
  (`NotificationChannel.isWired()` is false for the rest), multi-channel fallback,
  quiet hours (columns nothing reads), marketing, the template approval workflow,
  webhook status ingestion, the platform-default template layer, the eight ADR
  0032 events, and any metric, audit fact or alert — the module registers none.
  Only order events trigger it; payment, fulfillment and recovery do not.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), legal
- Depends on: ADR 0005, ADR 0006, ADR 0007, ADR 0015, ADR 0019, ADR 0026
- Supersedes / Superseded by: —
- Open inputs: Consent legal basis, quiet hours, and message retention (legal, product)

## Context

Ordering, restaurant approval, payment, delivery, onboarding, and service
recovery all need reliable communication. Sending SMS, push, email, or messaging
requests directly from business transactions would couple domains to providers,
create duplicates on retry, and hide uncertain delivery. Marketing messages also
have different consent rules from transactional messages.

## Decision

The notifications module owns communication intent, template resolution,
recipient preferences, provider routing, delivery attempts, and status. Business
modules publish semantic events or issue a notification command; they never call
a provider directly and never render provider-specific payloads.

Camel adapters implement provider transport. Kafka transports commands/status,
ADR 0005 deduplicates consumption, ADR 0006 governs retries/dead letters, and a
PostgreSQL notification record is the source of delivery truth.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Send messages directly from business transactions | Duplicates on every retry, couples domains to gateways, and bypasses consent entirely | Never |
| Provider-managed templates only | No versioning or approval inside our audit trail, and no way to test rendering before send. Still used where a regulator or gateway requires preapproved templates, in which case the local record references the approved external template | Retained for those channels only |
| A general-purpose template engine with object access | Template injection and unintended data exposure, since a template could walk an object graph into PII. Allowlisted typed variables from a versioned schema instead | Never |
| Keep a second encrypted copy of phone numbers and emails in `notifications` | Doubles the PII blast radius and creates two things to rotate, expire, and erase. The endpoint row now holds a reference and a lookup hash only, with ADR 0015 remaining the contact authority | Never |
| Aggressive multi-channel fallback by default | An uncertain provider outcome plus eager fallback equals the customer receiving the same payment failure twice through two channels | Never as a default; allowed per class under explicit policy |
| Treat a provider `accepted` response as delivered | Overstates a guarantee the provider did not give, which then appears in support conversations and disputes | Never |
| Let unsubscribe delete delivery history | Destroys the evidence needed to prove what was sent and why | Never; unsubscribe changes future eligibility only |

## Notification classes and consent

```text
TRANSACTIONAL_REQUIRED
TRANSACTIONAL_OPTIONAL
MARKETING
SECURITY
OPERATIONS_ALERT
```

- Required transactional and security messages follow legal/product policy but
  still respect channel feasibility and quiet-hour exceptions.
- Optional and marketing messages require the current ADR 0015 consent at the
  applicable tenant/brand/purpose/channel scope.
- Unsubscribe changes future eligibility; it does not erase immutable delivery
  evidence.
- Operations alerts target authorized groups/on-call routes, not customer
  preferences.

The exact legal basis, retention, and country-specific rules require counsel
approval before activation.

## Physical model

### Preferences and endpoints

```text
notifications.preferences
  id, tenant_id, customer_account_id, brand_id null
  notification_class, channel, enabled, quiet_hours, timezone
  source_consent_decision_id null, version, timestamps

notifications.recipient_endpoints
  id, tenant_id, customer_account_id null, endpoint_type
  contact_point_id null, operations_endpoint_reference null
  normalized_hash, verification_status
  status, last_verified_at, version, timestamps
```

Endpoint rows hold a reference and a lookup hash. They deliberately do not hold
a second ciphertext copy of a phone number, email address, or push token: ADR
0015 owns customer contact values, and duplicating the ciphertext would double
the blast radius of a key compromise and create a second thing to rotate,
expire, and erase. The send path resolves the plaintext value from ADR 0015
through a `ResolveRecipientValue` port immediately before rendering, and never
persists it.

`operations_endpoint_reference` covers alert destinations that are not customer
contact points, such as an on-call route or a shared operations channel; those
are configuration references, not personal data.

Endpoint rows are reconciled from ADR 0015 rather than independently edited.

### Templates

```text
notifications.templates
  id, tenant_id null, brand_id null, template_key
  notification_class, channel, status, version, timestamps

notifications.template_versions
  id, template_id, version_number, locale
  subject_template null, body_template, variables_schema
  content_hash, status, approved_by, activated_at null, timestamps
```

Resolution order is brand override, tenant override, platform default, using
requested locale, brand default, then tenant default. Only allowlisted typed
variables from a versioned schema can render. Templates cannot execute code,
fetch URLs, or read arbitrary object properties.

### Intents and attempts

```text
notifications.notifications
  id, tenant_id, brand_id null, location_id null
  notification_key, notification_class, template_key/version
  subject_type, subject_id, recipient_reference
  locale, status, scheduled_at, expires_at null
  idempotency_key, rendered_content_hash, timestamps

notifications.delivery_attempts
  id, tenant_id, notification_id, channel
  provider_binding_id, attempt_number, provider_idempotency_key
  status, external_message_id null, failure_code null
  uncertain_outcome, requested_at, acknowledged_at null, timestamps

notifications.delivery_status_events
  id, tenant_id, attempt_id, provider_event_id
  normalized_status, provider_status, occurred_at, recorded_at
```

A unique `(tenant_id, idempotency_key)` protects the logical notification. A
provider attempt is separately idempotent because fallback may deliberately use
a new channel/provider.

## Lifecycle

```text
CREATED -> ELIGIBILITY_CHECKED -> SCHEDULED -> READY -> SENDING
        -> DELIVERED | FAILED_TERMINAL | EXPIRED
SENDING -> RETRY_PENDING -> READY
SENDING -> UNCERTAIN -> RECONCILING -> DELIVERED | RETRY_PENDING | MANUAL_REVIEW
```

`DELIVERED` means the strongest verified provider status available and records
whether that is accepted, dispatched, delivered-to-device, or read. Qoida must
not promise a stronger guarantee than the provider supplies.

## Command and rendering flow

1. Consume a semantic event/command through the inbox.
2. Resolve tenant/brand, class, consent/preference, locale, endpoint, template,
   and routing policy using a fixed clock.
3. Persist the intent and selected template version; skip with a reason if
   ineligible or already present.
4. Render and validate size/encoding without placing raw content on Kafka.
5. Create an attempt and route it through the selected Camel capability.
6. Normalize synchronous response/webhook/poll results through the inbox.
7. Reconcile uncertainty before fallback; publish terminal status via outbox.

Rendering can occur just before send, but its exact template version, variables
hash, and content hash are frozen. Sensitive rendered content is encrypted or
stored in a protected object with a short retention period.

## Provider capabilities

```text
SendSms
SendPush
SendEmail
SendMessagingAppMessage
QueryMessageStatus
VerifyDeliveryWebhook
```

Adapters declare supported locale/encoding, maximum size, template preapproval,
status strength, idempotency behavior, throughput, quiet-hour, and webhook
capabilities. Provider bindings are tenant/brand scoped secret references.

## Routing and fallback

Routing policy is versioned by notification class and scope. It filters for
consent, verified endpoint, provider health, message expiry, cost limit, and
capability, then selects deterministic priority. Fallback is allowed only when:

- the product policy permits another channel;
- the first provider outcome is known not delivered or reconciled safe;
- the same user-visible message would not become harmful duplication; and
- the message has not expired.

Payment, rejection, and cancellation messages favor correctness over aggressive
multi-channel fallback. Duplicate suppression keys include business event,
recipient, semantic template key, and intended occurrence.

## APIs

```text
GET /api/v1/customer/me/notification-preferences
PUT /api/v1/customer/me/notification-preferences/{class}/{channel}

GET  /api/v1/control-plane/brands/{brandId}/notification-templates
POST /api/v1/control-plane/notification-templates/{templateId}/versions
POST /api/v1/control-plane/template-versions/{versionId}/activate

GET  /api/v1/operations/notifications/{notificationId}
POST /api/v1/operations/notifications/{notificationId}/retry
POST /api/v1/operations/notifications/{notificationId}/reconcile
```

Manual retry/reconcile requires authorization, reason, and audit and cannot
override consent or send an expired marketing message.

## Events

```text
NotificationRequested
NotificationSuppressed
NotificationScheduled
NotificationDeliveryAttempted
NotificationDelivered
NotificationDeliveryFailed
NotificationManualReviewRequired
NotificationPreferenceChanged
```

Kafka records contain endpoint references and hashes, not phone numbers, email
addresses, push tokens, or rendered bodies.

## Testing

- The same command delivered repeatedly creates one logical notification.
- Consent, tenant/brand preference, quiet hours, expiry, and locale fallback are
  deterministic with a fixed clock.
- Template injection and unknown variables fail before provider send.
- Provider timeout enters uncertainty and reconciles before fallback.
- Duplicate/out-of-order webhooks do not regress terminal status.
- Rate-limit and retry-after contract tests exercise the common adapter suite.
- PII never appears in Kafka, logs, traces, metrics, or dead-letter summaries.
- Cross-tenant template, endpoint, preference, and Operations access fails.

## Rollout and rollback

Start with a fake provider and shadow notification records, then send internal
Operations alerts, then one low-risk customer template/channel, and finally
order-critical flows. Compare counts with legacy sends while deduplicating at a
shared ownership gate. Rollback switches new intents to the legacy sender;
already-started attempts finish/reconcile under one owner and evidence remains.

## Consequences

### Positive

- Every message exists once as a durable intent with a reason for being sent or
  suppressed.
- Consent and preference are enforced at send time from the authoritative
  source, not copied per campaign.
- Provider uncertainty cannot produce uncontrolled duplicate customer messages.

### Negative

- The notification path is long: event, inbox, eligibility, template resolution,
  render, attempt, provider, webhook, reconciliation. Debugging a missing SMS
  crosses several tables.
- Template versioning and approval make a copy change a governed action.
- Rendered content retention creates another protected data class to expire.

### Accepted trade-offs

- Conservative fallback means a failed channel sometimes results in no message
  rather than a duplicate through another channel, which support will notice.
- The notifications module holds only endpoint references, so any recipient
  lookup requires a call into `customer` rather than a local join.

## Implementation checklist

- [ ] Approve notification classes, consent/legal basis, quiet hours, and retention.
- [ ] Approve channel/provider routing, fallback, and delivery-status semantics.
- [x] Add preference, endpoint, template, intent, attempt, and status tables. V0026: `notification_preferences`, `recipient_endpoints`, `templates`, `template_versions`, `notifications`, `delivery_attempts`, `delivery_status_events`.
- [x] Implement the `ResolveRecipientValue` port against ADR 0015 with no local persistence of contact values. Split into `customers.api.RecipientContactDirectory` (reference for eligibility, value for the send) and `ConsentDirectory`; no `notifications` table holds a contact value or a rendered body.
- [x] Implement typed template validation, locale resolution, and safe rendering. `NotificationTemplateService`, `MessageLocale`, `TemplateRenderer` and `MoneyText`, covered by `TemplateRenderingTests`; resolution is brand override then tenant default, with no platform layer.
- [ ] Implement eligibility, deduplication, routing, scheduling, and uncertainty flows. Eligibility, subject-derived deduplication, the lease/backoff schedule and the uncertain-provider path are built (`NotificationEligibilityService`, `NotificationWorker`, `NotificationDispatchService`); channel routing and fallback are not — SMS is the only wired channel.
- [ ] Implement controlled fake and first real Camel provider adapters. `FakeSmsGateway` and the generic `SmsGatewayAdapter` on the ADR 0007 route exist. The first vendor contract now does too — `docs/providers/sms-gateway-vas.md` transcribes the VAS gateway's `/send`, `/send_msgs`, `/search` and delivery callback and maps all twenty-eight status codes onto ADR 0007's Rejected / Retryable / Uncertain model — but no adapter in the committed tree implements it, so the generic gateway is still what a deployment gets.
- [ ] Connect order/payment/fulfillment/recovery semantic events. `OrderNotificationTrigger` connects `OrderConfirmed` and `OrderRejected` only; payments, fulfillment and service recovery raise nothing this module listens to.
- [x] Build customer preference, control-plane template, and Operations APIs. `CustomerNotificationPreferenceController`, `NotificationTemplateController` and `OperationsNotificationController` — all three staff-scoped, so the "customer" preference API is one an operator uses on a customer's behalf.
- [ ] Add audit, metrics, provider dashboards, alerting, and reconciliation runbooks. The module references neither `AuditRecorder` nor `MeterRegistry`; there is no notification dashboard, alert or runbook.
- [ ] Add duplicate, PII, consent, fallback, provider-contract, and isolation tests. `NotificationDeliveryTests` and `TemplateRenderingTests` cover duplicates, consent suppression and the provider-contract path against the fake; fallback, PII and cross-tenant isolation tests are not written.

## Exit criteria

Every business notification is produced once as a durable, explainable intent;
consent and preferences are enforced at send time; templates are versioned and
safe; provider uncertainty cannot cause uncontrolled duplicates; and Operations
can reconcile delivery without exposing recipients across tenants.

## Implementation notes

Written after the build, against what is actually in the tree. The decision above
is unchanged; this section records where the first slice stops and why.

### What was built

Migration **V0026** creates seven tables in the `notifications` schema:
`notification_preferences`, `recipient_endpoints`, `templates`,
`template_versions`, `notifications`, `delivery_attempts`, and
`delivery_status_events`. `docs/minimum-viable-cutover.md` scopes this ADR to
"confirmation and rejection on one channel, consent gate", and the tables are the
ones that subset writes.

The path runs end to end: an `OrderConfirmed` or `OrderRejected` fact creates a
durable intent in the same transaction that made the decision; a worker claims it,
runs the eligibility gate, freezes the template version, the locale, the endpoint
and the variables onto the row, renders, opens an attempt, and sends through an
ADR 0007 Camel route to an SMS gateway resolved from an ADR 0026 binding. It is
exercised against a controlled fake gateway, including the timeout that accepts a
message and loses the reply.

### Where the built code departs from the text above

**The notification row is the durable send record, and nothing is published to
Kafka.** The ADR has Kafka transporting commands and status. `integration.outbox_events`
is a Kafka relay keyed by topic and partition — it is not a work queue a Camel
route can be driven from — so the notification row carries the claim, lease,
attempt counter and backoff instead, modelled on the outbox columns. The ADR 0004
property that matters is preserved: no provider is called inside the business
transaction, and the intent commits with the order. The eight events the ADR
names are **not** published, because nothing consumes them yet and ADR 0032
requires a catalogue entry, a JSON schema, and a documentation row before a
producer ships. They arrive with the first consumer.

**The trigger is an in-process transactional listener, not an inbox consumer.**
The ADR has step 1 consume through the ADR 0005 inbox. `TenancyEventListener` fans
every record out to every registered consumer name and dead-letters a consumer that
has no handler for an event type, so registering a notification consumer today
would dead-letter every tenancy event. `OrderNotificationTrigger` listens for
`OrderingEvent` at `BEFORE_COMMIT` instead, which is strictly stronger for the
property that matters — the intent and the confirmation commit together — and is
the same phase `OrderingOutboxEventListener` uses. Deduplication does not depend on
which transport is used: the idempotency key is derived from the subject, so a
replay under a fresh event id still collapses.

**Templates are tenant-owned only.** The ADR's resolution order is brand override,
tenant override, platform default. The platform layer is absent: it would be a
NULL-tenant row in a tenant-scoped table, and the first slice has one tenant.
Resolution is brand override, then tenant default.

**`notification_preferences.source_consent_decision_id` is absent.** It exists in
the ADR for preferences derived by reconciling an ADR 0015 decision. Nothing
reconciles, so the column would never be written.

**Two ports were added to `customers.api`.** `ConsentDirectory` is the read this
module uses — it returns the decision with its policy version and date, never a
bare boolean, because a refused message has to be able to say *why*.
`RecipientContactDirectory` is the ADR's `ResolveRecipientValue`, split into a
reference lookup for eligibility and a value lookup for the send. One port was
added to `ordering.api`: `OrderDirectory`, because `OrderConfirmed` correctly
carries no customer account and no order number, and notifications needs both.

**Three capabilities were added to the ADR 0025 registry.** `NOTIFICATION_READ`,
`NOTIFICATION_RETRY`, and `NOTIFICATION_PREFERENCE_MANAGE`. The two template
capabilities the registry already had are used unchanged.

### Deliberately not built

Multi-channel fallback, marketing, quiet-hour enforcement, the template approval
workflow, webhook status ingestion, push, email, and messaging-app channels, and
the platform-default template layer.

The channels are declared on `NotificationChannel` with `isWired()` false, so a
tenant authoring an email template gets a `CHANNEL_NOT_AVAILABLE` suppression with
a reason rather than a message that is created, resolved, rendered, and then
silently fails. Quiet hours are columns on `notification_preferences` that nothing
reads: the window a tenant may not text inside is a legal decision, and this build
must not invent one.

`delivery_status_events` is written only from synchronous provider answers today.
Its shape and its uniqueness on the provider's own event id are what a webhook
ingest will need, and it is written now because the reconcile path already
produces statuses that have to be recorded verbatim.

### Open inputs this build did not close

The ADR's open inputs — consent legal basis, quiet hours, and message retention —
remain open and are legal decisions. What the build fixes is the *shape*: the
purpose is explicit per template, consent resolves per purpose, and every default
is a stated configuration value rather than an assumption buried in code.

- `qoida.notifications.order-expiry` (default `PT6H`) — how long an unsent
  confirmation is still worth sending. A product decision, not a considered answer.
- `qoida.notifications.max-attempts` (default 8) and
  `qoida.notifications.retry-backoff` (default `PT30S`).
- `qoida.notifications.order-channel` (default `SMS`) — the one wired channel.
- Message retention: nothing expires a notification row today. The row holds no
  contact value and no rendered body, which is what makes that survivable until a
  retention decision lands.
