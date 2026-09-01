# ADR 0063: Telegram-native customer identity — Gateway OTP and share-contact sign-in

- Decision status: Accepted
- Implementation status: Partial — both features are built and
  exit-criteria-tested end to end against real PostgreSQL with
  `FakeTelegramBotApi`/`FakeTelegramGateway` (wave 20): the Gateway client with
  its delivery-policy seam in the verification send path (Gateway first when
  configured, SMS fallback, cost recorded on the challenge row — V0117), and
  share-contact sign-in (AUTH-kind pending codes — V0118, own-contact and
  phone-pattern checks, account convergence with the OTP path through one
  phone-hash resolution, ADR 0051 session claimed by a single conditional
  update, the wave-7 binding created in the same stroke, storefront "Continue
  with Telegram" polling the code). Not Built because the record's own open
  inputs stand: no real Gateway token exists (ships configured-off — SMS-only
  in practice), and the default phone pattern awaits the owner's review.
- Date proposed: 2026-09-02
- Date decided: 2026-09-02
- Deciders: platform owner (directed both features and the phone-regex gate),
  Claude (architecture; Telegram mechanics research)
- Depends on: 0013, 0015, 0020, 0026, 0028, 0029, 0033, 0051, 0058
- Supersedes / Superseded by: —
- Open inputs: the Telegram Gateway account and its API token (a separate,
  separately-billed Telegram product with its own credentials — owner obtains);
  the allowed-phone pattern's final value (default `^\+?998\d{9}$`).

## Context

Two owner directives of 2026-09-02, one theme: in a Telegram-first market, the
phone number should arrive through Telegram rather than an SMS bill.

1. **OTP delivery**: the ADR 0015 verification challenge sends a code by SMS.
   Telegram Gateway delivers verification codes to any Telegram account by phone
   number at a fraction of SMS cost — ADR 0058 already named it a
   feasibility-check item; the owner now directs the integration.
2. **Sign-in via Telegram**: the storefront offers "Continue with Telegram" — the
   user is sent to the brand bot, shares their phone with one tap, and is signed
   in if the phone matches the allowed pattern. Telegram's `request_contact`
   button returns the **account owner's own verified number** — Telegram itself
   attests the binding, which is a stronger attestation than an SMS round trip.

## Decision

- **Gateway OTP is a delivery provider behind the existing challenge, not a new
  identity flow.** The ADR 0015 verification-challenge machinery gains a
  `TELEGRAM_GATEWAY` delivery option beside SMS: `sendVerificationMessage`
  against the Gateway API (its own base URL, its own token via ADR 0028
  reference, its own `FakeTelegramGateway` test double in the ADR 0007 genre).
  Delivery selection is a policy: try Gateway when configured, fall back to SMS
  on Gateway refusal (number has no Telegram account, Gateway error) — the
  challenge, attempts, rate limits (ADR 0033) and single-use grants are the
  existing ones, untouched. Cost per delivery is recorded on the attempt row the
  way SMS attempts are recorded today.
- **Share-contact sign-in rides the wave-7 code machinery.** The storefront mints
  a short-lived, single-use auth code bound to nothing (no session exists yet —
  unlike wave 7's linking codes, which required one) and deep-links to the brand
  bot (`/start` payload, AUTH kind in the pending-link table). The bot answers
  with a one-button `request_contact` keyboard. On the contact message:
  - the contact must be the **sender's own** (`contact.user_id == from.id`) — a
    forwarded stranger's contact is refused;
  - the phone must match the configured allowed pattern (default
    `^\+?998\d{9}$`), the owner's explicit gate — a non-matching phone gets a
    polite refusal naming nothing;
  - on success the platform resolves-or-creates the customer account by phone
    through ADR 0015's identity path (the phone arrives Telegram-attested;
    recorded as a verified contact point with source `TELEGRAM_CONTACT`), issues
    an ADR 0051 session against the auth code, and the storefront — polling the
    code's status exactly as the wave-14 linking screen polls — receives the
    session and signs the user in. The 1:1 chat binding that wave 7 builds is
    created in the same stroke (the user is standing in the chat already), so
    sign-in via Telegram also links notifications.
- **Keyboard hygiene**: the contact keyboard is removed after use; codes expire
  and are single-use; the whole exchange is rate-limited per chat and per code.
- **Consent posture**: sharing a contact to sign in is identity, not marketing
  consent — ADR 0020 preferences are untouched except the notification-linking
  side effect wave 7 already defines.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Telegram Login Widget on the storefront | Returns identity but no phone; the phone is this platform's identity key (ADR 0015) | A Telegram-ID-keyed identity ever becomes acceptable |
| Mini App initData as the sign-in | Already built (wave 7) but only works inside Telegram's webview; the owner's flow targets the ordinary browser storefront | — (both coexist) |
| Replace SMS entirely | The Gateway cannot reach a number with no Telegram account; SMS stays the fallback per ADR 0015 | Never fully |

## Implementation checklist

- [ ] Gateway client + ADR 0028 secret reference + `FakeTelegramGateway`; delivery-policy seam in the challenge send path with SMS fallback; attempt-row cost recording
- [ ] AUTH-kind pending codes (single-use, expiring); bot `request_contact` exchange with own-contact and pattern checks; account resolve-or-create with `TELEGRAM_CONTACT`-sourced verified phone; ADR 0051 session issuance against the code
- [ ] Storefront "Continue with Telegram" on the sign-in screen, deep link + status polling, error states (expired, refused, pattern mismatch)
- [ ] Entitlement/config: allowed-phone pattern configurable; Gateway usable platform-wide once its token exists
- [ ] Tests: fake-Gateway delivery + SMS fallback; the whole share-contact story against `FakeTelegramBotApi` including forwarded-contact refusal, pattern refusal, expiry, single-use, and the session landing in the poll

## Exit criteria

A customer with no account taps "Continue with Telegram" in a browser, shares
their contact in the bot, and is signed into the storefront with a verified
phone and a linked 1:1 chat; a verification challenge delivers its code through
the Gateway when configured and falls back to SMS when the Gateway cannot reach
the number; no code, token, or phone appears in any log.
