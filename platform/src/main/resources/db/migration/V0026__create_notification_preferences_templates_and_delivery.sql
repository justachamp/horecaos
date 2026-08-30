-- ADR 0020: notification preferences, templates, and delivery — the blocking
-- subset named in docs/minimum-viable-cutover.md ("confirmation and rejection on
-- one channel, consent gate").
--
-- Multi-channel fallback, marketing, the template approval workflow, provider
-- routing policy, and quiet-hour enforcement are deliberately absent. Their
-- tables arrive with the decisions and the code that own them, because a table
-- nobody writes reads as a capability that exists and is schema everybody has to
-- reason about.
--
-- Three ideas run through everything below.
--
-- First: this module holds references, never contact values. A notifications
-- table is the single most tempting place to denormalise a phone number and a
-- customer's name, and it is the table most likely to end up in a support
-- export. ADR 0015 stays the contact authority; the endpoint row here holds a
-- contact-point id and a keyed lookup hash, and the send path resolves the
-- plaintext immediately before rendering and never writes it down.
--
-- Second: a message that is not sent is a fact, not an absence. Consent is a
-- gate rather than a filter, so an ineligible message becomes a SUPPRESSED row
-- carrying the reason. Without that row a tenant cannot answer "why did the
-- customer not get their confirmation?", which is the question support actually
-- receives.
--
-- Third: the notification row is itself the durable send record, in the ADR 0004
-- sense. Its claim/lease/backoff columns are modelled on
-- integration.outbox_events on purpose: nothing calls a provider inside the
-- business transaction that caused the message, the intent commits with the
-- order, and a worker carries it out afterwards with its own retries. What it
-- does not do is publish to Kafka — integration.outbox_events is a Kafka relay
-- keyed by topic and partition, and there is no consumer for a notification fact
-- yet. ADR 0032 requires a catalogue entry, a schema, and a documentation row
-- before a producer ships; those arrive with the first consumer.

-- --------------------------------------------------------------- preferences

-- What a customer has asked for, per class and channel.
--
-- Distinct from consent and never a substitute for it. Consent answers whether
-- the law permits a message; a preference answers whether the customer wants
-- one they could lawfully be sent. Collapsing the two would let a preference
-- toggle silently create a legal basis, which is exactly the mistake ADR 0015
-- exists to prevent.
--
-- A required transactional message ignores this table. An order confirmation the
-- customer switched off is still the receipt for money they spent.
CREATE TABLE notifications.notification_preferences (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    -- Null for a tenant-wide preference, set when the customer wants different
    -- treatment from one brand. Resolution prefers the brand row.
    brand_id uuid,

    notification_class varchar(32) NOT NULL,
    channel varchar(24) NOT NULL,
    enabled boolean NOT NULL,

    -- Local wall-clock times in `timezone`, not instants: "do not text me before
    -- 09:00" means nine in the morning wherever the customer is, and storing an
    -- instant would move the window twice a year.
    quiet_hours_start time,
    quiet_hours_end time,
    timezone varchar(64),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_preference_class CHECK (
        notification_class IN ('TRANSACTIONAL_REQUIRED', 'TRANSACTIONAL_OPTIONAL',
                               'MARKETING', 'SECURITY', 'OPERATIONS_ALERT')
    ),
    CONSTRAINT ck_preference_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    -- Pair completeness stated as an equality. Written as
    -- `(both null) OR (both set)` it would be satisfied by a row with one of them
    -- null, because a comparison against NULL is unknown and a CHECK admits
    -- unknown. That exact hole shipped here once.
    CONSTRAINT ck_preference_quiet_hours_pair CHECK (
        (quiet_hours_start IS NULL) = (quiet_hours_end IS NULL)
    ),
    -- A quiet window with no zone is not a window. It would be evaluated against
    -- whatever zone the server happened to run in, which is how a customer gets
    -- woken at 04:00 by a rule that says 09:00.
    CONSTRAINT ck_preference_quiet_hours_zone CHECK (
        quiet_hours_start IS NULL OR timezone IS NOT NULL
    ),
    CONSTRAINT ck_preference_version CHECK (version >= 1),
    CONSTRAINT fk_preference_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- Two partial indexes rather than one constraint over a nullable column: a NULL
-- brand does not compare equal to itself, so a single UNIQUE would let the same
-- tenant-wide preference be inserted twice and leave resolution depending on
-- which row the planner returned first.
CREATE UNIQUE INDEX ux_preference_for_brand
    ON notifications.notification_preferences
       (tenant_id, customer_account_id, brand_id, notification_class, channel)
    WHERE brand_id IS NOT NULL;

CREATE UNIQUE INDEX ux_preference_tenant_wide
    ON notifications.notification_preferences
       (tenant_id, customer_account_id, notification_class, channel)
    WHERE brand_id IS NULL;

COMMENT ON COLUMN notifications.notification_preferences.enabled IS
    'ADR 0020. Whether the customer wants this class on this channel. Never a legal basis: consent lives in customer.consent_decisions and is read, not mirrored here.';

-- ADR 0020 also names `source_consent_decision_id` on this table, for
-- preferences derived by reconciling an ADR 0015 decision. Nothing reconciles
-- yet — every row here is written by the customer's own preference API — so the
-- column is absent rather than present and never written.

-- ---------------------------------------------------------------- endpoints

-- Where a message can be sent, as a reference.
--
-- ADR 0020 rejected keeping a second encrypted copy of phone numbers and email
-- addresses here: it doubles the blast radius of a key compromise and creates a
-- second thing to rotate, expire, and erase. So this row carries the ADR 0015
-- contact-point id and the same keyed lookup hash that table uses, and nothing
-- else about the person.
--
-- Rows are reconciled from ADR 0015 rather than edited independently. An
-- endpoint that disagrees with the contact point it names is a bug with a
-- customer-visible symptom: messages sent to a number the customer replaced.
CREATE TABLE notifications.recipient_endpoints (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Null for an operations destination. A shared on-call route belongs to the
    -- platform, not to a customer.
    customer_account_id uuid,

    endpoint_type varchar(24) NOT NULL,

    -- The ADR 0015 contact point this endpoint stands for. The only way to reach
    -- an actual phone number is to resolve this id through the customers module
    -- at send time.
    contact_point_id uuid,

    -- An alert destination that is not a customer contact point: an on-call
    -- route, a shared operations channel. Configuration, not personal data.
    operations_endpoint_reference varchar(255),

    -- ADR 0029: keyed, per-tenant, deterministic — the same value ADR 0015
    -- stores. Present so an operator can ask "was anything sent to this number?"
    -- without the number ever being stored here.
    normalized_hash varchar(64),

    verification_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED',
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    last_verified_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_endpoint_type CHECK (
        endpoint_type IN ('PHONE', 'EMAIL', 'PUSH_TOKEN', 'OPERATIONS_ROUTE')
    ),
    CONSTRAINT ck_endpoint_verification CHECK (
        verification_status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'FAILED')
    ),
    CONSTRAINT ck_endpoint_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    -- Exactly one destination. An endpoint that is both a customer contact and an
    -- operations route is two endpoints wearing one row, and the send path would
    -- have to guess which one it is holding.
    CONSTRAINT ck_endpoint_destination CHECK (
        (contact_point_id IS NOT NULL) <> (operations_endpoint_reference IS NOT NULL)
    ),
    -- Stated as an equality for the reason given on the preferences table: the
    -- disjunctive form admits a row where only one side is null.
    CONSTRAINT ck_endpoint_owner CHECK (
        (customer_account_id IS NULL) = (operations_endpoint_reference IS NOT NULL)
    ),
    CONSTRAINT ck_endpoint_hash_pair CHECK (
        (contact_point_id IS NULL) = (normalized_hash IS NULL)
    ),
    CONSTRAINT ck_endpoint_verified_at CHECK (
        (verification_status <> 'VERIFIED') OR (last_verified_at IS NOT NULL)
    ),
    CONSTRAINT ck_endpoint_version CHECK (version >= 1),
    CONSTRAINT fk_endpoint_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_endpoint_contact_point FOREIGN KEY (contact_point_id)
        REFERENCES customer.contact_points (id),
    -- The key notifications.notifications points at, so an endpoint cannot be
    -- attached to another tenant's message.
    CONSTRAINT uq_endpoint_identity UNIQUE (id, tenant_id)
);

-- One endpoint per contact point. Two would send the same customer the same
-- message twice, and the duplicate would look like a provider fault.
CREATE UNIQUE INDEX ux_endpoint_contact_point
    ON notifications.recipient_endpoints (tenant_id, contact_point_id)
    WHERE contact_point_id IS NOT NULL;

CREATE INDEX ix_endpoints_account
    ON notifications.recipient_endpoints (tenant_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;

-- ---------------------------------------------------------------- templates

-- A semantic message a tenant can send, per channel.
--
-- Tenant-owned only. ADR 0020's resolution order also names a platform default
-- underneath the tenant override; that layer is not built here, because a
-- platform default is a NULL-tenant row in a tenant-scoped table and the first
-- slice does not need one. Resolution is therefore brand override, then tenant
-- default, and the missing third step is a gap in coverage rather than a
-- half-built one.
CREATE TABLE notifications.templates (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Null for the tenant's default wording, set for a brand that words it
    -- differently. Resolution prefers the brand row.
    brand_id uuid,

    -- The semantic name a business event maps to: ORDER_CONFIRMED, not
    -- "sms-confirm-v3". A trigger names what happened; the template decides how
    -- it is said.
    template_key varchar(64) NOT NULL,

    notification_class varchar(32) NOT NULL,
    channel varchar(24) NOT NULL,

    -- The ADR 0015 consent purpose this template needs before it may be sent.
    -- Null for a required transactional message, which is not marketing and does
    -- not need marketing consent — the distinction is legal, not tonal, and
    -- modelling it as one nullable column is what stops a receipt being gated on
    -- a promotional opt-in.
    consent_purpose varchar(64),

    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    -- The version number currently sent. Null until a version is activated, which
    -- is also what makes a template unresolvable rather than silently sending a
    -- draft.
    active_version integer,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_template_class CHECK (
        notification_class IN ('TRANSACTIONAL_REQUIRED', 'TRANSACTIONAL_OPTIONAL',
                               'MARKETING', 'SECURITY', 'OPERATIONS_ALERT')
    ),
    CONSTRAINT ck_template_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    -- An ACTIVE template with no active version resolves to nothing at send time,
    -- which would present as a silently missing confirmation.
    CONSTRAINT ck_template_active_version CHECK (
        (status <> 'ACTIVE') OR (active_version IS NOT NULL)
    ),
    -- A marketing or optional template that names no purpose has nothing to check
    -- consent against, and "no purpose" must not read as "no consent needed".
    CONSTRAINT ck_template_consent_purpose CHECK (
        notification_class NOT IN ('TRANSACTIONAL_OPTIONAL', 'MARKETING')
        OR consent_purpose IS NOT NULL
    ),
    CONSTRAINT ck_template_version CHECK (version >= 1),
    CONSTRAINT uq_template_identity UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ux_template_for_brand
    ON notifications.templates (tenant_id, brand_id, template_key, channel)
    WHERE brand_id IS NOT NULL;

CREATE UNIQUE INDEX ux_template_tenant_wide
    ON notifications.templates (tenant_id, template_key, channel)
    WHERE brand_id IS NULL;

-- One locale of one version of one template.
--
-- The locale set is ru, uz-Latn, and en per ADR 0035. A version is only
-- activatable when all three exist: a missing translation must fail while
-- somebody is authoring copy, not at 22:00 when a customer in Tashkent gets an
-- English confirmation or none at all. The rule is enforced in
-- TemplateService.activate rather than by a constraint, because it is a
-- statement about a set of rows and a CHECK cannot see one.
CREATE TABLE notifications.template_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    version_number integer NOT NULL,
    locale varchar(16) NOT NULL,

    -- Null on channels that have no subject. SMS is one; keeping the column
    -- nullable rather than blank means "this channel has no subject" and "the
    -- author left it empty" stay distinguishable.
    subject_template text,
    body_template text NOT NULL,

    -- The allowlist. A template may name these variables and nothing else, and
    -- the renderer resolves nothing but a name from this map — no property
    -- access, no expressions, no URL fetches. ADR 0020 rejected a general
    -- template engine precisely because an object graph is a path from a
    -- template to a customer's address.
    variables_schema jsonb NOT NULL,

    content_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    -- Who signed this wording off. ADR 0020's full approval workflow is deferred;
    -- what is kept is the attribution, because a copy change that reached
    -- customers with nobody's name on it cannot be reviewed after the fact.
    approved_by varchar(255),
    activated_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_template_version_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_template_version_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')
    ),
    CONSTRAINT ck_template_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_template_version_body CHECK (length(body_template) > 0),
    CONSTRAINT ck_template_version_schema CHECK (jsonb_typeof(variables_schema) = 'object'),
    -- Approval and activation happen together or not at all. Stated as an
    -- equality: the disjunctive form would admit an activated version nobody
    -- approved, which is the audit hole this pair exists to close.
    CONSTRAINT ck_template_version_approval_pair CHECK (
        (approved_by IS NULL) = (activated_at IS NULL)
    ),
    CONSTRAINT ck_template_version_active CHECK (
        (status <> 'ACTIVE') OR (activated_at IS NOT NULL)
    ),
    -- Matched on (id, tenant_id) so a template belonging to another tenant cannot
    -- be given a version here. Matching on template_id alone would insert cleanly.
    CONSTRAINT fk_template_version_template FOREIGN KEY (template_id, tenant_id)
        REFERENCES notifications.templates (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_template_version_locale UNIQUE (template_id, version_number, locale),
    CONSTRAINT uq_template_version_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_template_versions_active
    ON notifications.template_versions (template_id, version_number)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN notifications.template_versions.content_hash IS
    'ADR 0020. SHA-256 over locale, subject, and body. Frozen onto every notification rendered from this version, so what a customer was sent can be proved without keeping the rendered text.';

-- ------------------------------------------------------------ notifications

-- One logical message: the intent, its outcome, and the reason for it.
--
-- What is deliberately not here: the recipient's phone number, their name, and
-- the rendered body. Evidence of what was sent is reconstructible from the frozen
-- template version, the frozen variables, and the two hashes — which is enough to
-- prove the wording without this table becoming the place a support export leaks
-- a customer's number.
CREATE TABLE notifications.notifications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid,
    location_id uuid,

    notification_class varchar(32) NOT NULL,
    channel varchar(24) NOT NULL,
    template_key varchar(64) NOT NULL,

    -- Frozen at eligibility, not read again at send time. A tenant activating new
    -- wording between the intent and the attempt must not change what this
    -- message says: the version that was chosen is the version that is sent, and
    -- is the version an auditor is shown.
    template_id uuid,
    template_version integer,
    locale varchar(16),

    -- What the message is about. 'Order' plus an order id today; the pair exists
    -- so a payment or a recovery case can be the subject without a second table.
    subject_type varchar(32) NOT NULL,
    subject_id uuid NOT NULL,

    -- Resolved at eligibility. Null while the message is still CREATED, and null
    -- forever on a message suppressed for having no reachable endpoint.
    recipient_endpoint_id uuid,

    -- An identifier, not personal data: it is what the customers module resolves
    -- a contact value from, and it is already carried on the order.
    recipient_account_id uuid,

    -- The ADR 0032 event that caused this message. Recorded for the trail rather
    -- than for deduplication — see idempotency_key, which is keyed on the subject
    -- so a replay under a fresh event id still collapses.
    trigger_event_id uuid,

    -- The logical message identity. One confirmation per order per channel, for
    -- as long as this row exists.
    idempotency_key varchar(255) NOT NULL,

    status varchar(24) NOT NULL DEFAULT 'CREATED',

    -- Why this message will never be sent. A refused message is a fact the tenant
    -- has to be able to read back; dropping it silently is what makes "why did the
    -- customer not get their confirmation?" unanswerable.
    suppression_reason varchar(48),

    -- The allowlisted variables this message renders with, frozen at eligibility.
    -- Everything here came from an ADR 0032 event payload, which the event
    -- classification test already forbids protected data in; no other source may
    -- write this column.
    variables jsonb NOT NULL DEFAULT '{}'::jsonb,
    variables_hash varchar(64),
    rendered_content_hash varchar(64),

    scheduled_at timestamptz NOT NULL DEFAULT now(),

    -- After this, sending is worse than not sending: a confirmation that arrives
    -- an hour after the customer collected their food is noise, and a rejection
    -- that arrives the next morning is an incident.
    expires_at timestamptz,

    -- The dispatch lease. Modelled on integration.outbox_events because it is the
    -- same problem: durable work claimed by one of several nodes, retried with
    -- backoff, and never carried out inside the transaction that created it.
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_at timestamptz,
    terminal_at timestamptz,
    last_error varchar(2000),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_notification_class CHECK (
        notification_class IN ('TRANSACTIONAL_REQUIRED', 'TRANSACTIONAL_OPTIONAL',
                               'MARKETING', 'SECURITY', 'OPERATIONS_ALERT')
    ),
    CONSTRAINT ck_notification_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_notification_locale CHECK (
        locale IS NULL OR locale IN ('ru', 'uz-Latn', 'en')
    ),
    CONSTRAINT ck_notification_status CHECK (
        status IN ('CREATED', 'READY', 'SENDING', 'RETRY_PENDING', 'UNCERTAIN',
                   'RECONCILING', 'DELIVERED', 'FAILED_TERMINAL', 'SUPPRESSED',
                   'EXPIRED', 'MANUAL_REVIEW')
    ),
    -- A suppression without a reason, or a reason without a suppression, are both
    -- meaningless. The equality form is what actually says "exactly together";
    -- see the preferences table for why the disjunctive form does not.
    CONSTRAINT ck_notification_suppression_pair CHECK (
        (status = 'SUPPRESSED') = (suppression_reason IS NOT NULL)
    ),
    -- A template id without its version cannot be resolved back to wording, and a
    -- version without its template names nothing.
    CONSTRAINT ck_notification_template_pair CHECK (
        (template_id IS NULL) = (template_version IS NULL)
    ),
    -- A location belongs to a brand. Without this a row could name a location and
    -- no brand, and the composite foreign key below would not be checked at all.
    CONSTRAINT ck_notification_location_brand CHECK (
        (location_id IS NULL) OR (brand_id IS NOT NULL)
    ),
    CONSTRAINT ck_notification_variables CHECK (jsonb_typeof(variables) = 'object'),
    CONSTRAINT ck_notification_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_version CHECK (version >= 1),
    -- The lease is held or it is not. A claim token without a claim time cannot be
    -- expired by the sweeper, so the row would be stuck forever.
    CONSTRAINT ck_notification_claim_pair CHECK (
        (claim_token IS NULL) = (claimed_at IS NULL)
    ),
    CONSTRAINT fk_notification_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_notification_template FOREIGN KEY (template_id, tenant_id)
        REFERENCES notifications.templates (id, tenant_id),
    CONSTRAINT fk_notification_endpoint FOREIGN KEY (recipient_endpoint_id, tenant_id)
        REFERENCES notifications.recipient_endpoints (id, tenant_id),
    CONSTRAINT fk_notification_account FOREIGN KEY (recipient_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    -- The whole defence against sending one customer two confirmations for one
    -- order. The outbox and the inbox both deliver at least once, so the second
    -- arrival is expected rather than exceptional, and it lands on this index.
    CONSTRAINT uq_notification_idempotency UNIQUE (tenant_id, idempotency_key),
    -- The key delivery_attempts points at.
    CONSTRAINT uq_notification_identity UNIQUE (id, tenant_id)
);

-- The dispatch worker's claim query. Partial on the non-terminal statuses,
-- because the overwhelming majority of rows reach a terminal state within
-- seconds and scanning them forever would make the sweep slower every day the
-- platform runs.
--
-- SENDING is in the set on purpose. A node that dies mid-send leaves a row
-- holding a lease that nothing else would ever pick up; including it here means
-- the row becomes claimable again once next_attempt_at passes, and the claimer
-- finds the open attempt and reconciles it rather than sending a second message.
CREATE INDEX ix_notifications_due
    ON notifications.notifications (next_attempt_at)
    WHERE status IN ('CREATED', 'READY', 'SENDING', 'RETRY_PENDING',
                     'UNCERTAIN', 'RECONCILING');

CREATE INDEX ix_notifications_subject
    ON notifications.notifications (tenant_id, subject_type, subject_id);

COMMENT ON COLUMN notifications.notifications.rendered_content_hash IS
    'ADR 0020, ADR 0029. SHA-256 of the rendered message. The hash rather than the text: the rendered body of a confirmation contains the customer''s order and, on other templates, would contain their name, and this table must stay safe to export.';

COMMENT ON COLUMN notifications.notifications.expires_at IS
    'ADR 0020. Past this, sending is worse than not sending. Configured per template key with a stated default; the exact windows are a product decision this build does not invent.';

-- --------------------------------------------------------------- attempts

-- One request to one provider through one binding.
--
-- Separate from the notification because a provider attempt is separately
-- idempotent: a retry reuses its key so the provider deduplicates, while a
-- deliberate second attempt on another channel is a new key on purpose.
CREATE TABLE notifications.delivery_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    notification_id uuid NOT NULL,
    channel varchar(24) NOT NULL,

    -- The ADR 0026 binding this went through. Null only when the attempt failed
    -- before a provider was resolved, which is itself worth recording.
    provider_binding_id uuid,
    provider_type varchar(64),

    attempt_number integer NOT NULL,

    -- Sent to the provider. Stable for the life of this attempt: a retry that
    -- generated a fresh key would defeat the provider-side deduplication the
    -- retry depends on, and the customer would receive two messages.
    provider_idempotency_key varchar(255) NOT NULL,

    status varchar(24) NOT NULL,
    external_message_id varchar(255),
    failure_code varchar(64),

    -- The outcome that matters. Set when the provider may or may not have acted;
    -- the route reconciles before anything else happens, because retrying blindly
    -- is how one confirmation becomes two.
    uncertain_outcome boolean NOT NULL DEFAULT false,

    requested_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_attempt_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_attempt_status CHECK (
        status IN ('REQUESTED', 'ACCEPTED', 'DELIVERED', 'REJECTED',
                   'RETRYABLE_FAILURE', 'UNCERTAIN', 'RECONCILED_NOT_SENT')
    ),
    CONSTRAINT ck_attempt_number CHECK (attempt_number >= 1),
    -- The flag and the status cannot disagree. They did in an earlier draft, and a
    -- reconciliation sweep keyed on the flag silently skipped rows whose status
    -- said otherwise.
    CONSTRAINT ck_attempt_uncertain_pair CHECK (
        uncertain_outcome = (status = 'UNCERTAIN')
    ),
    -- Qoida must not claim a stronger guarantee than the provider gave. A
    -- delivered attempt has an acknowledgement time; an accepted one may not.
    CONSTRAINT ck_attempt_delivered_ack CHECK (
        (status <> 'DELIVERED') OR (acknowledged_at IS NOT NULL)
    ),
    CONSTRAINT ck_attempt_binding_pair CHECK (
        (provider_binding_id IS NULL) = (provider_type IS NULL)
    ),
    CONSTRAINT fk_attempt_notification FOREIGN KEY (notification_id, tenant_id)
        REFERENCES notifications.notifications (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_attempt_number UNIQUE (notification_id, attempt_number),
    -- One provider request per key within a tenant. This is what makes the retry
    -- path safe to run twice.
    CONSTRAINT uq_attempt_provider_key UNIQUE (tenant_id, provider_idempotency_key),
    CONSTRAINT uq_attempt_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_attempts_notification
    ON notifications.delivery_attempts (notification_id, attempt_number);

-- Every status the provider told us about, in the order we heard it.
--
-- Append-only, and unique on the provider's own event id, so a webhook delivered
-- twice or out of order is recorded once and cannot regress a terminal status.
-- ADR 0020 is explicit that unsubscribe changes future eligibility and never
-- erases delivery evidence, so the grants below withhold UPDATE and DELETE.
CREATE TABLE notifications.delivery_status_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    attempt_id uuid NOT NULL,

    -- The provider's id for this status change. Where a provider supplies none,
    -- the adapter synthesises a stable one from the attempt and the status, so
    -- the uniqueness below still deduplicates rather than silently allowing
    -- repeats.
    provider_event_id varchar(255) NOT NULL,

    normalized_status varchar(24) NOT NULL,

    -- The provider's own word for it, kept verbatim. Normalising destroys the
    -- distinction between "accepted" and "delivered to handset", and support
    -- conversations turn on exactly that distinction.
    provider_status varchar(64),

    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_status_event_normalized CHECK (
        normalized_status IN ('ACCEPTED', 'DISPATCHED', 'DELIVERED', 'READ',
                              'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT fk_status_event_attempt FOREIGN KEY (attempt_id, tenant_id)
        REFERENCES notifications.delivery_attempts (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_status_event_provider_id UNIQUE (tenant_id, attempt_id, provider_event_id)
);

CREATE INDEX ix_status_events_attempt
    ON notifications.delivery_status_events (attempt_id, occurred_at);

COMMENT ON TABLE notifications.notifications IS
    'ADR 0020 durable notification intent and outcome. Holds references and hashes; never a contact value or a rendered body.';
COMMENT ON TABLE notifications.recipient_endpoints IS
    'ADR 0020 endpoint references. ADR 0015 remains the contact authority; nothing here decrypts to a phone number.';
COMMENT ON TABLE notifications.delivery_attempts IS
    'ADR 0020 one provider request, separately idempotent from the logical notification.';
COMMENT ON TABLE notifications.delivery_status_events IS
    'ADR 0020 immutable provider status evidence. Insert and select only.';

GRANT USAGE ON SCHEMA notifications TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.notification_preferences TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.recipient_endpoints TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.templates TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON notifications.template_versions TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON notifications.notifications TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON notifications.delivery_attempts TO qoida_application;
-- Insert and read only. Delivery evidence that the application can rewrite is not
-- evidence, and ADR 0020 forbids unsubscribe destroying the record of what was
-- already sent.
GRANT SELECT, INSERT ON notifications.delivery_status_events TO qoida_application;
