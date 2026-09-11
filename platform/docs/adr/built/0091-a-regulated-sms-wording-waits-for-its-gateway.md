# ADR 0091: A regulated SMS wording waits for its gateway

- Decision status: Accepted
- Implementation status: Built — V0204's `provider_review` on `notifications.template_versions`, `TemplateProviderReviewService`, `TemplateProviderReviewController`, the withholding in `NotificationEligibilityService` with its two suppression reasons, `NotificationProviderRegistryController`, tested in `TemplateProviderReviewServiceTests` and `PaymentAndQuietHoursNotificationTests`; the control-plane notification providers screen. V0208's `moderates_wordings` on the approved endpoints, set for VAS, and new SMS versions starting `PENDING` through `JdbcTemplateStore.smsWordingAwaitsGateway`, tested in `SmsWordingModerationTests`
- Date proposed: 2026-09-11
- Date decided: 2026-09-11
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; accepted by Ayubkhon Abbosov (platform owner) on 2026-09-11, who answered its open inputs the same day
- Depends on: ADR 0020, ADR 0026, ADR 0028, ADR 0029
- Supersedes / Superseded by: —
- Open inputs: closed 2026-09-11: the VAS gateway is treated as moderating, so every new SMS wording for it waits for its approval. A new gateway is marked as moderating or not when its endpoint is approved

## Context

Some SMS gateways refuse any text their operator has not approved. Until now a
wording could be activated and sent at once, and a gateway that had not
approved it answered every message with an error. Nothing recorded which
wordings a gateway had approved, and the control plane (IA 8.4) had no list of
the gateways in use or of the name each tenant sends as. The gateway the
platform uses today documents no moderation, so the rule has to work without
assuming every gateway needs it.

## Decision

1. An SMS template version carries where it stands with its gateway: not
   needing approval (every existing and new version), waiting, approved or
   refused.
2. An approved gateway endpoint records whether it moderates texts. A new SMS
   version starts waiting, marked by the platform rather than a person, when
   the tenant sends SMS through a moderating endpoint, or has no SMS gateway
   bound yet while any approved endpoint moderates. HorecaOS staff can mark any
   version as waiting too, and record the answer: an approval names the
   gateway's reference, a refusal says what the gateway objected to. Each step
   is audited. The VAS gateway is marked as moderating, by the platform
   owner's decision of 2026-09-11.
3. A version waiting or refused is not sent. The message is suppressed with a
   reason saying which, instead of failing at the gateway once per customer.
   An approved version, or one that never needed approval, sends as before.
4. The gateway registry is read from what exists: the approved notification
   endpoints and each tenant's notification installation with the sender name
   it is configured with. Credentials stay references in the secrets manager.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Require approval for every SMS version | The gateway in use documents no moderation; every new wording would stop sending until someone noticed | A gateway that moderates everything is the only one in use |
| Decide per gateway automatically | Nothing records which gateways moderate, and a wrong guess stops a tenant's messages | Operations records it per gateway |
| Submit to the gateway's moderation API | No gateway in use offers one | A gateway publishes a moderation API |

## Consequences

### Positive

- A wording the gateway would refuse is held with a reason, and the approval is on record.
- Operators see which gateway and sender name every tenant uses.

### Negative

- Marking a wording is a person's job; a wording nobody marks sends and may be refused at the gateway.
- A suppressed message is not retried when the approval arrives; later messages send.

### Accepted trade-offs

- The wording's text is shown to staff reviewing it; it is the tenant's template, not a customer's data.

## Specification

- `GET /control-plane/template-reviews?state=` (`notification.read`, platform scope).
- `POST /control-plane/tenants/{tenantId}/template-versions/{versionId}/provider-review`
  (`notification.template.activate`, platform scope) with `{ state, reference, providerNote, reason }`.
- `GET /control-plane/notification-providers` (`integration.installation.manage`).
- Suppression reasons `TEMPLATE_AWAITING_PROVIDER`, `TEMPLATE_REFUSED_BY_PROVIDER`.

## Rollout and rollback

Additive; every version starts as not needing approval, so nothing changes until a wording is marked.

## Implementation checklist

- [x] Columns, service, controller, withholding, registry, tests
- [x] Notification providers screen

## Exit criteria

A payment-failure SMS whose wording waits on its gateway is suppressed with
that reason, and the next one sends once the approval is recorded.

## References

- `docs/frontend-information-architecture.md` §8.4
