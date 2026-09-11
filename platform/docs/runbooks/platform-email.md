# Turning on the platform's email

**Last executed:** never — pre-prod runs without mail until a provider is chosen.

The platform sends its own email over SMTP submission (ADR 0097). Two messages
ride these settings, and there is nothing to configure for the second one:

- a tenant owner's invitation to set up their account (ADR 0097);
- a staff member's password reset, asked for from either console's sign-in
  page (ADR 0098).

One host, one sender address, one password — the reset relay uses the same
`PlatformMailer` the invitation relay does, so the moment mail works for one it
works for both.

Without mail configured nothing breaks — invitations wait, and the control
plane's onboarding screen says "Email is not configured on this deployment
yet". The moment the settings below are in place, every waiting invitation goes
out on the relay's next pass (every 30 seconds).

Password resets wait too, and this is the one place the two differ in a way
that matters to an operator: a reset says nothing on any screen. The request
page answers "if the account exists, an email is on its way" whether or not
mail is configured, because it answers that to everyone (ADR 0098 refuses to
let the page become a directory of who has an account). So a deployment with no
mail settings looks, to the staff member, exactly like a deployment where they
mistyped their user name. A queued reset also expires after 60 minutes rather
than an invitation's 72 hours, so resets asked for before mail is turned on are
generally dead by the time it is; the staff member asks again.

## What only the owner can do

- Choose the sending provider and hold its account.
- Publish the DNS records for `horecaos.uz`.
- Put the SMTP password into OpenBao. It is never pasted into chat, a ticket,
  an environment file or this repository (ADR 0028).

## 1. Choose a provider

Anything that offers SMTP submission on port 587 (STARTTLS) or 465 (TLS) works;
changing provider later is a change of these settings, nothing else. Common
choices: Amazon SES, Brevo, Postmark, Mailgun, or SendPulse's SMTP service
(the legacy bots already run on a SendPulse account). The address the relay
sends to is the owner's email, so a provider outside Uzbekistan processes staff
addresses: counsel should confirm this under the amended ZRU-547 before
production (ADR 0097's open input).

Google Cloud blocks outbound port 25 on the pre-prod VM; 587 and 465 are open.

## 2. Verify the sending domain

In the provider's console, add the domain you send from (for example
`horecaos.uz`, or a subdomain such as `mail.horecaos.uz` to keep reputation
separate), then publish the records it gives you:

| Record | Type | What it does |
|---|---|---|
| SPF | TXT on the sending domain, e.g. `v=spf1 include:<provider> -all` | Says the provider may send for the domain |
| DKIM | CNAME or TXT the provider names | Signs each message so receivers can verify it |
| DMARC | TXT on `_dmarc.horecaos.uz`, start with `v=DMARC1; p=none; rua=mailto:<a mailbox you read>` | Tells receivers what to do with mail that fails the two above; tighten to `quarantine` once reports are clean |

If `horecaos.uz` is on Cloudflare, these are DNS-only records (grey cloud).
Wait until the provider shows the domain as verified.

## 3. Store the password

On the host, with OpenBao unsealed and your own token:

```bash
bao kv put horecaos/production/provider_notification/platform/smtp value='<the SMTP password>'
```

Type it at the prompt of a shell whose history you then clear, or use
`value=-` and paste it on stdin. The platform reads it by reference and
caches it for five minutes, so a rotation needs no restart.

## 4. Point the platform at it

In `/etc/horecaos/production.env`:

```bash
HORECAOS_MAIL_FROM="HorecaOS <no-reply@horecaos.uz>"
HORECAOS_MAIL_SMTP_HOST=<the provider's SMTP host>
HORECAOS_MAIL_SMTP_PORT=587
HORECAOS_MAIL_SMTP_SECURITY=STARTTLS
HORECAOS_MAIL_SMTP_USERNAME=<the SMTP user name>
HORECAOS_MAIL_SMTP_PASSWORD_REF=horecaos:production:provider_notification:platform:smtp
```

Both console origins must also be set, because each emailed link points back at
the console it was asked from — a control-plane operator sent to the operations
console would land somewhere they may have no access to:

```bash
HORECAOS_OPERATIONS_ORIGIN=https://ops.horecaos.uz
HORECAOS_CONTROL_PLANE_ORIGIN=https://console.horecaos.uz
```

The sender address must be on the verified domain. Then recreate the
application so it reads the new environment:

```bash
sudo HORECAOS_SECRET_DIR=/run/horecaos/secrets docker compose -f /opt/horecaos/compose.production.yml --env-file /etc/horecaos/production.env up -d --no-deps platform-app
```

## 5. Check it

In the control plane, open a tenant's onboarding screen. A waiting invitation
turns to **Sent** within a minute. To test end to end, start onboarding for a
test tenant with an address you read, open the email, set a password, and you
land signed in on the operations console.

For the reset, use **Forgot password?** on either console's sign-in page with
an account whose address you read. The link works for 60 minutes and once.
Setting a password there ends every other session that account holds, so a
second browser you left signed in is signed out within a request or two —
which is the check worth doing, because it is the half of the feature a
screenshot cannot show.

## When it does not send

The onboarding screen names the reason:

| Shown as | Means | Do |
|---|---|---|
| Email is not configured | No host or sender address set | Step 4 |
| The mail server refused the platform's credentials | Wrong user name or password | Check step 3 and step 4's user name |
| The SMTP password is not in the secrets manager | The reference points at nothing | Step 3, at exactly the path in the reference |
| The mail server could not be reached | Network or provider outage | Retries on its own with backoff; after eight real attempts it stops and waits for **Send again** |
| The owner's email address was refused | The provider or the receiving server rejected the address | Correct the address in Keycloak, then **Send again** |

A link works for 72 hours and once. **Send again** makes a new link and the
old one stops working at once.

A password reset has no such screen and no **Send again**: nothing in either
console reports whether one was sent, deliberately, because a screen that did
would answer the question the endpoint refuses to. When a staff member says a
reset never arrived, the reasons are the table above — read the application log
for `Password reset relay:` lines, which carry counts and an error code and
never an address — plus two of their own: the account has no email address in
Keycloak at all, and the login they typed matched no account (or matched more
than one, which the platform treats as no match rather than guessing whose
password to reset). Both are answered by looking the account up in Keycloak.
A reset asked for again replaces the one before it, so there is never more than
one live link per account and telling somebody to "just ask again" is safe —
with one wrinkle worth knowing: a link that was emailed within the last five
minutes and is still live is *not* replaced. That cooldown exists because the
request endpoint takes anyone's word for a login, and without it a stranger
could keep replacing the link a staff member is holding. Somebody who asks
twice in the same minute therefore gets one email, not two, and is told nothing
about it — if they are sure the first never arrived, they ask again five
minutes later.

Two audit action codes are worth knowing when a reset is in question, in
`audit.audit_events`: `iam.password_reset.request_suppressed` is a request the
cooldown turned into a no-op, and a burst of them for one account is somebody
hammering that address rather than the account holder being unlucky;
`iam.password_reset.sessions_not_ended` means a password *was* reset but the
account's other sessions could not be ended — the new password works, and until
somebody acts on it a device that was signed in before the reset may still be.
End that account's sessions from Keycloak's admin console, and check the
application log for the ERROR line beside it.
