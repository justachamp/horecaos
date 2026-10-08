# Terminals, staff PINs and kiosks

- Last executed: never
- Reader: the person on call, or the restaurant's operations lead with the owner's console
- Records: [ADR 0155](../adr/partial/0155-terminals-and-staff-pins.md) (terminals and staff PINs), [ADR 0162](../adr/partial/0162-kiosk-device-pairing.md) (kiosk pairing), [ADR 0079](../adr/partial/0079-kitchen-display-device-principal-and-enrolment.md) (device principals)

A PIN names the person at a shared screen. It is attribution, never authentication: it signs nobody in to the
console, and a stolen PIN opens exactly what the device and the person both already could. Everything below is done
from the console unless it says otherwise.

## A person forgot their PIN, or it is locked

Nobody can read a PIN, including you, and there is no screen that sets one for somebody.

1. **Staff → People →** the person **→ Security → Terminal PIN**. The status reads *Not set*, *Set* or *Locked*.
2. **Reset the PIN**, with a reason. This clears the credential and ends the person's sessions on every tablet.
3. **Issue an activation code**, with a reason. It is eight digits, single use, valid four hours, shown **once**.
   Read it to the person; do not write it in a chat.
4. The person taps their name on the tablet, **I have an activation code**, types the code and chooses a PIN of the
   length the screen asks for.

**Check:** the card reads *Set since* today, and *Мой профиль → Мой PIN* shows the unlock under their name.

A person may also ask for their own code in **Мой профиль → Мой PIN** (valid 15 minutes). The manager route is the
weaker of the two, because the manager could type the code first; that is why the person's profile lists every
unlock under their name.

**Getting back:** a code that was issued by mistake expires by itself, and a newer code supersedes it. A reset
cannot be undone; the person sets a new PIN.

**A credential locks** after 12 wrong PINs in a rolling 24 hours (across tablets), and a person is locked out of one
tablet for 15 minutes after 5 wrong PINs in a row. The 15-minute lock lifts by itself. The 24-hour lock needs the
reset above. The chat subscribed to *TERMINAL_CREDENTIAL_LOCKED* is told the staff reference (`S-0142`) and the tablet,
never a name.

## Who was at this tablet?

**Staff → Терминалы →** the tablet **→ Who was unlocked**. Each row is a person's label, when they unlocked and how it
ended (locked, replaced by someone else, tablet revoked, PIN reset, employment ended, idle, longest session). The
unlock list is the platform's; the activity log carries the actions taken under it, naming both the person and the
tablet in the *Кто* column.

**Someone says an unlock at a tablet they were not at appears under their name:** reset their PIN (above), then
look at the tablet's history for the same time. A code a manager issued shows the issuer's name on the person's card
and on their own profile.

## A tablet went quiet during service

The chat subscribed to *TERMINAL_DEVICE_UNSEEN* is told which tablet and when it was last heard from. A tablet counts
as quiet after five minutes without any request, and only where it is set to *A person must unlock* and the branch is
in service. The Терминалы list shows the same in red.

1. Look at the tablet. A screen asleep or offline is the usual cause.
2. If it is gone or broken: **Staff → Терминалы → Revoke**, with a reason, then pair the replacement from
   *Kitchen → Devices*. The tablet's sessions end with the revoke.

**Check:** the list shows *Just now* against the tablet once it is back.

## Changing how a tablet attributes actions

**Staff → Терминалы → Edit.** *The device acts; nobody unlocks* (the default, and what every tablet did before PINs),
*A person may unlock* and *A person must unlock*. **Setting a tablet back to the default ends its sessions at once**:
that is the rollback, and it needs no deploy. The Policy card above the list holds the company's PIN length, idle lock,
longest session, how the roster labels people, and the default for new tablets; it needs the owner or an administrator.

## Rotating the PIN pepper

The pepper (`HORECAOS_TERMINAL_PIN_PEPPER_REF`) keys the digest of every PIN. Rotate it if it may have leaked.
**There is no way back to the old digests without the old secret, so keep it until the last PIN has moved.**

1. Write the new secret next to the old one:

   ```bash
   bao kv put horecaos/production/data_encryption/platform/terminal-pin-pepper-2 \
     value="$(head -c 32 /dev/urandom | base64)"
   ```

2. In `/etc/horecaos/production.env`:

   ```text
   HORECAOS_TERMINAL_PIN_PEPPER_REF=horecaos:production:data_encryption:platform:terminal-pin-pepper-2
   HORECAOS_TERMINAL_PIN_PEPPER_VERSION=2
   HORECAOS_TERMINAL_PIN_PEPPER_PREVIOUS_REF=horecaos:production:data_encryption:platform:terminal-pin-pepper
   ```

3. Deploy. **Check:** the application starts; a person unlocks as before.
4. Each PIN is re-keyed on its owner's next successful unlock. Count what is left on the old version:

   ```bash
   docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env \
     exec -T platform-db psql -U postgres -d horecaos -c \
     "SELECT pepper_version, count(*) FROM iam.staff_pins WHERE status = 'ACTIVE' GROUP BY 1;"
   ```

5. When no row remains on version 1, remove `HORECAOS_TERMINAL_PIN_PEPPER_PREVIOUS_REF` and deploy again.

A PIN not used before step 5 stops verifying; its owner is shown **Set a PIN** and needs a new code.

## Kiosks

### Install one

1. Put the kiosk app on the unit under the operating system's own lockdown (single-app mode, no browser chrome, no
   way to open another page). The shell has no settings screen and no PIN of its own, so the lockdown is the control.
2. Open `/device`, choose **This is a self-service kiosk**. It shows a QR and a code, and renews them by itself every
   ten minutes.
3. On your phone, scan the QR with the camera. The console opens **Settings → Channel setup → Pair a kiosk** with the
   code filled in. **Nothing is paired yet.**
4. Choose the branch, the KIOSK channel it sells through and a name; tick that **the code on the screen in front of
   you matches the one shown**; press **Pair the kiosk**.

**Check:** within a minute the kiosk shows *Sales channel* and *Branch*, and the list in Channel setup shows *Just now*.
If the code on the page is not the code on the screen, do not pair: a QR on a counterfeit screen reads the same.

### Revoke one (theft, or it leaves service)

**Settings → Channel setup →** a KIOSK channel **→ Revoke**, with a reason. The next request from the unit is refused
and it cannot mint a token again; it returns to the pairing screen. **Check:** the row moves to *Revoked kiosks*.

### Replace or re-pair

There is no code to issue from the console: the unit displays its own. Revoke the old unit, then install and pair the new
one. To move a working kiosk to another KIOSK channel at the same branch, use **Edit**; a kiosk with no channel (its
channel was archived or withdrawn) is flagged and cannot sell until it is moved.

### Switching the feature on

Kiosk pairing is dark behind `feature.kiosk_devices`. A tenant's console shows *not switched on* until the flag is set
for that tenant.
