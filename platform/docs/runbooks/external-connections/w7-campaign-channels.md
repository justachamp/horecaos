# Connecting the campaign channels

**Last executed:** never. Everything below was built against a fake and is dark in every environment
until the owner completes the step.

Wave `w7-campaign-channels` (ADR 0182) built four things whose last mile is not code: campaign email,
campaign push, the courier broadcast, and the delivery statistics. Each external fact is one section: what exists in
the repository, where the value goes, and how to prove the connection. Nothing here is a secret value;
a secret is an ADR 0028 reference stored with `bao kv put` and never pasted into a chat, a ticket or a file.

| Fact | Who | What is built meanwhile | Section |
|---|---|---|---|
| A mail provider and the sending domain's records | platform owner | `SmtpEmailChannelAdapter` against a recording relay and Mailpit | 1 |
| Counsel's answer on customer email addresses leaving the country | counsel | the channel, left unconfigured in production | 2 |
| The daily cap per tenant | platform owner | `horecaos.notifications.email.daily-cap-per-tenant`, default 1000 | 3 |
| The provider's bounce and complaint webhook | platform owner | a relay's refusal of an address, at send time, is a hard bounce | 4 |
| The owner's yes: a courier's own number may be used for dispatch SMS | platform owner, counsel | `IdentityCourierContactSource`, dark | 5 |
| A gateway account cleared for `COURIER` traffic | platform owner, gateway | the `COURIER` purpose gate, refusing | 6 |
| A gateway account cleared for `MARKETING` traffic | platform owner, gateway | the `MARKETING` purpose gate, refusing | 6 |
| One real VAS delivery-receipt callback | integration, provider | the receipt endpoint, disabled; statistics say «not reported» | 7 |
| A provider that reports reads or opens | platform owner | `reportsRead`, false for every wired channel | 8 |
| Web Push (a VAPID key pair, the opt-in wording) | platform owner; counsel | `WebPushChannelAdapter` against a fake push service; the storefront opt-in, dark | 9 |

## 1. Email: a provider and the sending domain

**What exists.** The `mail` module (ADR 0097) is the only SMTP connection. A campaign email is an
ordinary ADR 0020 `MARKETING` intent that `SmtpEmailChannelAdapter` (provider type `PLATFORM_SMTP`,
channel `EMAIL`) hands to `PlatformMailer`. There is no per-tenant installation or binding: the account is
the deployment's, identical for every tenant. The sender is the one address in `HORECAOS_MAIL_FROM` with
the brand's display name on it. Until `HORECAOS_MAIL_SMTP_HOST` is set the console says
`EMAIL_NOT_CONFIGURED` for the channel, per brand, and a campaign on it cannot be launched.

**Where the value goes.** The same settings `platform-email.md` describes; nothing new is needed for
campaigns. Follow that runbook's sections 1 to 4 (choose a provider, verify the domain, store the
password, point the platform at it). Two points are specific to bulk mail:

- Use a **sending subdomain** for campaign mail (for example `mail.horecaos.uz`) rather than the
  domain staff invitations come from, so a tenant's complaints cannot reach the invitation path's
  reputation. `HORECAOS_MAIL_FROM` is one address for the whole deployment, so this is a choice for the
  address that both use; if the owner wants them separate it is a second relay and a new decision.
- Publish SPF, DKIM and **DMARC** and wait until the provider shows the domain verified. Mailbox
  providers that see `List-Unsubscribe-Post: List-Unsubscribe=One-Click` from an authenticated domain show
  their own «unsubscribe» button; from an unauthenticated one they show «report spam».

**How to prove the connection.**

1. In the console, Marketing → Campaigns → new campaign: `EMAIL` is selectable with no sentence under it.
   (`GET /api/v1/tenants/{t}/brands/{b}/marketing/channels` answers `EMAIL` with `isWired: true`.)
2. Record an `EMAIL` marketing consent for a customer whose address is a mailbox you can read and
   which is **verified** (campaign eligibility refuses an unverified address with `NO_VERIFIED_ENDPOINT`).
3. Author an `EMAIL` template of class `MARKETING` with a subject in every language, activate it, and
   launch a one-person campaign.
4. The message arrives from «Brand name <HORECAOS_MAIL_FROM address>», with an «unsubscribe» link in the
   footer and the mail client's own unsubscribe control. In the message's headers: `List-Unsubscribe` and
   `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
5. Press the mail client's unsubscribe control. The customer's `EMAIL` consent for that purpose and brand
   shows as withdrawn (Customers → the customer → Consent), and a second campaign refuses them with
   `CONSENT_WITHHELD`.
6. Reports → Marketing → Campaigns shows the message as «handed to the operator», and delivered and read
   as «not reported by this channel».

**What a customer's unsubscribe page is.** `GET /providers/mail/unsubscribe/{token}` on the API origin
(`HORECAOS_API_ORIGIN`) renders a confirmation page and changes nothing; `POST` unsubscribes. The token is
in the path and never logged. The page is platform-hosted, in the message's language, with no storefront
styling. The origin must be reachable by the customer's mail client and by a browser; the production edge
already proxies it (the API origin's catch-all).

## 2. Email: counsel's answer on ZRU-547

**The fact.** Whether a customer's email address may be processed by a sending provider outside
Uzbekistan under the amended ZRU-547. ADR 0097 asked the same question about staff addresses; a
customer's address is a wider class of data.

**What exists.** The channel is built and unconfigured in production. There is no switch for the answer:
the safeguard is that no mail host is set until counsel has answered.

**Where the answer goes.** Record it in ADR 0182's open inputs (the decision record's note) and, if it is
no, either choose a provider with a processing location counsel accepts or do not set
`HORECAOS_MAIL_SMTP_HOST`.

**How to prove it.** The question is closed when the owner has the answer in writing; there is no test.

## 3. Email: the daily cap per tenant

`HORECAOS_NOTIFICATIONS_EMAIL_DAILY_CAP_PER_TENANT` (default 1000). The most campaign emails one tenant
may send per UTC day on the shared sender; the excess is **held** until 00:00 UTC, not dropped (the
message's own expiry, one day after it became eligible, may still end it). Set it against the provider's
daily limit divided by the number of tenants that may send at once. There is no per-tenant override in this
release: every tenant has the same cap.

**How to prove it.** Set it to 2, launch a campaign to three consenting customers: two arrive, the third
shows `CREATED` with `next_attempt_at` at 00:00 UTC of the next day, and arrives after it.

## 4. Email: bounce and complaint feedback

**The fact.** An asynchronous bounce or a spam complaint is reported by the provider through a webhook or a
feedback loop. No provider is named, so no webhook is built.

**What exists.** A relay's **synchronous** refusal of an address (SMTP 5xx at submission for the recipient)
is a hard bounce: the notification fails and ADR 0044 writes a `HARD_BOUNCE` suppression for that customer
on `EMAIL`. A refusal of the message itself, an unreachable relay and a lost answer suppress nobody.

**Where the value goes.** When a provider is chosen, the new route descriptor and adapter go under
`integration/camel/notification/`, and a `COMPLAINT` suppression is written through
`DeliverabilityFeedbackPort`. Until then the console shows no bounce counts beyond a refused address.

**How to prove it.** Send to an address the relay refuses at `RCPT TO` (a nonexistent mailbox on a domain
whose server verifies): the recipient row shows `REJECTED` with `ADDRESS_REJECTED`, and Marketing →
Suppressions lists a `HARD_BOUNCE` for the customer.

## 5. Courier numbers: the owner's yes

**The fact.** Whether a courier's own login number may be used to send them a dispatcher's SMS. The
platform already holds the number as the courier's identity-provider username and `phone` attribute
(ADR 0116), so the question is purpose, not custody. ADR 0042 treats couriers as self-employed.

**What exists.** `IdentityCourierContactSource` reads the number from the identity provider for one message
and keeps it nowhere. It is dark: `isWired()` is `horecaos.couriers.broadcast.contact-use-cleared`
(default `false`), and while it is false a courier broadcast is refused with `COURIER_CONTACTS_NOT_WIRED`
and nothing is read from the identity provider.

**Where the value goes.** `HORECAOS_COURIERS_BROADCAST_CONTACT_USE_CLEARED=true` in
`/etc/horecaos/production.env`, after the courier terms or consent text says so and the owner has the
answer in writing. Record the answer in ADR 0182's open inputs.

**How to prove the connection.**

1. Marketing → Courier broadcasts no longer shows «the platform may not yet use a courier's own number».
2. A broadcast to one courier whose identity-provider account has a phone: the courier's result row shows
   `HANDED_TO_OPERATOR` (or `DELIVERED` once receipts exist), and the SMS arrives on their login number.
3. A courier whose account has no usable number shows `NO_CONTACT` and nothing is sent to them.
4. The audit log (Staff → Activity) shows `MARKETING_COURIER_CONTACTS_CHECKED` once per send and
   `MARKETING_COURIER_CONTACT_REVEALED` once per message, naming the courier and never the number.

## 6. SMS: the gateway account's permitted purposes

**The fact.** The owner's written answer, per gateway account, that it may carry **courier** traffic
(`COURIER`) and **marketing** traffic (`MARKETING`) as well as sign-in codes and order messages. The
default for VAS is verification and transactional only (ADR 0146 open input 1).

**What exists.** `SmsPurposes` and the `permittedPurposes` installation configuration key. A purpose the
account is not cleared for is refused at readiness and again at send time with `SMS_PURPOSE_NOT_PERMITTED`.
Courier messages are sent with the purpose `COURIER`, marketing with `MARKETING`.

**Where the value goes.** The installation's non-secret configuration, set by the control plane:
`permittedPurposes` = a comma-separated list, for example `TRANSACTIONAL,MARKETING,COURIER`. The
installation's sender must be registered with the gateway for the wording and the purpose.

**How to prove the connection.** Marketing → Courier broadcasts and the campaign form stop showing the
`SMS_PURPOSE_NOT_PERMITTED` sentence for the brand; a one-courier broadcast and a one-customer SMS campaign
each reach a handset. A courier wording is an `SMS` template of class `COURIER_DISPATCH` (Settings →
Notifications → New template) in every language the brand serves, and VAS must approve it (ADR 0091):
until it does the console lists it as waiting and a send is refused with `TEMPLATE_AWAITING_PROVIDER`.

## 7. SMS: one captured VAS receipt callback

**The fact and what exists.** Unchanged from ADR 0146 open input 2 and `docs/providers/sms-gateway-vas.md`.
The receipt endpoint ships disabled. Until it is enabled, `reportsDelivery` is false for a brand bound to
VAS and the statistics print «not reported by this channel» for **delivered**; an accepted message is
«handed to the operator», and failures and rejections the send itself reported are counted as always.

**Where the value goes.** `horecaos.sms.receipts.enabled-provider-types=SMSGW_VAS` and
`HORECAOS_SMS_RECEIPT_ALLOWED_IPS` (the provider's published addresses), after the callback has been
captured against a controlled account and its headers and body are in `docs/providers/sms-gateway-vas.md`.

**How to prove the connection.** Reports → Marketing → Campaigns shows a number, not «not reported by this
channel», for **delivered** on an SMS campaign sent through that gateway, and `GET .../channels` answers
`reportsDelivery: true` for `SMS` for the brand.

## 8. A provider that reports reads or opens

**The fact.** None of the wired channels can: VAS and the Telegram Bot API have no read receipt, and an
SMTP relay reports neither delivery nor open.

**What exists.** `READ` is carried through the evidence: a `READ` status event on a delivered attempt makes
the recipient `READ` and the campaign's `read` count; the receipt ladder lets a read follow a delivery; and
`CampaignChannelDelivery.reportsRead()` / `providersReportDelivery()` are the per-channel declarations the
report reads. A new provider changes a flag and an adapter, not the report.

**Where the value goes.** A new adapter under `integration/camel/notification/` that implements `normalise`
for the provider's callback, and the delivery service's two flags.

**How to prove it.** The first campaign on that channel shows a number, and no longer «not reported», for
**read**.

## 9. Web Push: a VAPID key pair, the opt-in wording, and one line in each storefront

**The fact.** Web Push needs the platform to sign its requests to the browser vendors' push services
(VAPID, RFC 8292) with a key pair of its own. Nothing is bought and no third-party account exists: the
vendors' push services are free to every site. The owner generates the pair once and stores the private half.
Counsel confirms the wording the customer sees when asked (section "Opt-in wording" below).

**What exists.**

- `WebPushChannelAdapter` (provider type `WEB_PUSH`, channel `PUSH`) is the platform's own sender: one
  identity for every tenant, no ADR 0026 installation or binding. It encrypts each payload to the customer's
  browser (RFC 8291, checked against the RFC's worked example) and signs the request. It carries campaign
  push (`MARKETING`) and nothing else.
- `StorefrontPushSubscriptionController` (`/api/v1/storefront/tenants/{t}/brands/{b}/push`) takes the
  browser's own `PushSubscription` after the customer taps a prompt, and records the customer's
  `MARKETING_PROMOTIONS` consent on the `PUSH` channel in the same step. The push service address and the
  browser's keys are one ADR 0029 ciphertext and are in no log, event or URL. A subscription may name only a
  browser vendor's push service (`PushEndpointPolicy`: https, port 443, FCM, Mozilla, Apple, Windows), so a
  customer's browser cannot make the platform call an address of its choosing.
- `public/push-sw.js` and `PushSubscribeComponent` in `frontend/storefront` and `frontend/storefront-milliy`.
  The worker shows the message and reports the customer's tap to `POST /providers/push/clicks/{token}` on
  the API origin (`HORECAOS_API_ORIGIN`); that tap is the only read signal Web Push offers, so a read is
  reported as «tapped». A push service reports no delivery, so `reportsDelivery` is false and an accepted
  push is «accepted by the push service».
- Dark until configured: all three settings below empty means the console says `PUSH_NOT_CONFIGURED` for
  every brand, `GET .../push` answers `available: false`, and the storefront renders nothing.

**Generate the key pair.** On a machine you trust, with OpenSSL 3:

```
openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
# the private scalar: 32 bytes, base64url without padding (43 characters)
openssl ec -in vapid.pem -outform DER 2>/dev/null | tail -c +8 | head -c 32 | base64 | tr '+/' '-_' | tr -d '=\n'
# the public key: the 65-byte uncompressed point, base64url without padding (87 characters)
openssl ec -in vapid.pem -pubout -outform DER 2>/dev/null | tail -c 65 | base64 | tr '+/' '-_' | tr -d '=\n'
```

Then delete `vapid.pem`; nothing keeps it.

**Where the value goes.**

1. The private scalar: `bao kv put horecaos/production/provider_notification/platform/vapid value=<the 43 characters>`.
   Never paste it into a chat, a ticket or a file.
2. `/etc/horecaos/production.env` (see `deploy/env.template`):
   `HORECAOS_PUSH_VAPID_PUBLIC_KEY=<the 87 characters>`,
   `HORECAOS_PUSH_VAPID_PRIVATE_KEY_REF=horecaos:production:provider_notification:platform:vapid`,
   `HORECAOS_PUSH_VAPID_SUBJECT=mailto:<an address that is read>` (RFC 8292 wants a `mailto:` or `https:`
   contact the push services may write to if the platform misbehaves).
3. Restart `platform-app`.
4. **One line in each storefront**, which this wave did not add because the profile page is another wave's
   file: mount `<app-push-subscribe />` (and add `PushSubscribeComponent` to the page's `imports`) where a
   signed-in customer should find it, for example under `<app-profile-menu>` in
   `frontend/storefront/src/app/pages/profile/profile.component.html` and in the profile page of
   `frontend/storefront-milliy`. On a home-screen banner pass `[dismissible]="true"`. Until it is mounted no
   customer can opt in, so no campaign has a push recipient.

**How to prove the connection.**

1. Marketing → Campaigns → new campaign: `PUSH` is selectable with no sentence under it
   (`GET /api/v1/tenants/{t}/brands/{b}/marketing/channels` answers `PUSH` with `isWired: true`).
2. In Chrome on a phone or laptop, signed in to the storefront, tap «Turn on notifications» and allow the
   browser's question. Customers → the customer → Consent shows `MARKETING_PROMOTIONS` on `PUSH` granted.
3. Settings → Notifications → New template: channel `PUSH`, class `MARKETING` (fixed), consent purpose
   `MARKETING_PROMOTIONS`, a title and text in every language, optionally a 3:1 cover and a path such as
   `/menu`. Activate it, and launch a one-person campaign on that customer.
4. The notification appears on the device within seconds with the title, text and cover. Tapping it opens the
   storefront at the path.
5. Reports → Marketing → Campaigns shows the message as «accepted by the push service», and after the tap
   **tapped: 1** (delivered counts the tap too, because a tap proves arrival). Delivery is stated as not
   reported by this channel while nothing has been tapped.
6. Turn push off in the storefront: the next campaign refuses that customer with `CONSENT_WITHHELD`.

**What to know when it misbehaves.**

- `PUSH_VAPID_REJECTED` on a recipient: the push service did not accept the platform's key for that
  subscription. After a key **rotation** every earlier subscription answers this until its customer turns
  push on again, because a subscription is bound to the key it was made under. Rotate only if the private key
  leaked. A mismatched pair (public key from one generation, private from another) fails every send the same
  way; generate once and store both together.
- `PUSH_SUBSCRIPTION_GONE`: the browser dropped the subscription (the customer cleared site data or the
  browser retired it). The subscription is ended, the customer is skipped with `NO_RECIPIENT_ENDPOINT` from
  then on, and turning push on in the storefront again restores them. Nothing is suppressed: a dead device
  is not a withdrawn consent.
- One live subscription per customer per brand: the customer's newest browser receives the push, and an
  older one stops (ADR 0182 records this as an accepted trade-off).
- Browsers: iOS Safari delivers Web Push only to a storefront added to the home screen; a Telegram mini app's
  webview and many in-app browsers have no Web Push, and the storefront renders nothing there.
- The worker does not re-subscribe when a browser rotates a subscription (`pushsubscriptionchange`): it has
  no session. The customer is offered «turn on» again on their next visit.

**Opt-in wording (counsel).** The prompt says what the customer gets and what turning it on means
(`push.title`, `push.text` and `push.consentNote` in the storefront's three catalogues). Counsel confirms the
wording of `push.consentNote` before the first campaign; the consent record carries the policy version
`PUSH_OPT_IN_V1`, so a change of wording is a new version, not an edit.
