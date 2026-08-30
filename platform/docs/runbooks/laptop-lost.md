# The laptop is lost

**Not an alert.** **Last executed:** never — this is a draft, and it is the one
runbook here that is worthless as a draft.

**You cannot run this from the laptop.** Everything below runs from the second
device, which is why the second device exists. If you do not have it in front of
you, stop reading and go and get it.

There is no second administrator to revoke the first, so revocation is arranged
in advance rather than performed by someone else. Losing the laptop must be a
revocation and not a lockout, and the difference is entirely whether the
following was set up beforehand.

## 0. Prerequisites, which are set up long before this day

- A **second WireGuard peer** and a **second SSH key** on a device kept apart
  from the laptop.
- The ADR 0034 **escrow material** for OpenBao, off both devices.
- The **backup passphrase** in the ADR 0034 sealed copy.

The laptop carries full-disk encryption and **no long-lived provider
credential** — everything resolves from OpenBao at call time under ADR 0028. So
it holds keys to the machine, not keys to the money. That is what makes the list
below finite.

## 1. Get in from the second device

```bash
wg-quick up qoida
ssh -i ~/.ssh/qoida_second_ed25519 qoida@10.8.0.1
```

**Check:** you are on the host. If this fails, the second device was never set
up and you are into the facility's out-of-band console, which is slow and is the
path this runbook exists to avoid.

## 2. Remove the lost peer from WireGuard

```bash
sudo wg show qoida
sudo wg set qoida peer <lost public key> remove
sudo wg-quick save qoida
```

**Check:** `sudo wg show qoida` no longer lists it. Do this first: it closes the
only route to the SSH port, which does not listen on the public address at all.

## 3. Remove the SSH key

```bash
sudo sed -i.bak '/<comment on the lost key>/d' /root/.ssh/authorized_keys /home/qoida/.ssh/authorized_keys
sudo grep -c '' /home/qoida/.ssh/authorized_keys
```

**Check:** the count dropped by exactly one, and your own key is still there.
Verify with a second session before closing this one — locking yourself out at
this point is the classic way to turn a lost laptop into an outage.

## 4. Rotate OpenBao and re-shard the unseal material

```bash
cd /opt/qoida/qoida-platform
qc exec -it openbao bao operator rekey -init -key-shares=5 -key-threshold=3
```

Then rotate the AppRole secret identifiers each service authenticates with, and
re-mount them as `0600` files under `QOIDA_SECRET_DIR`.

**This step is not optional and is not a separate chore.** A revocation that
leaves the old unseal shares valid has revoked nothing.

## 5. Rotate Keycloak's administrative credential

```bash
qc exec -it keycloak /opt/keycloak/bin/kcadm.sh set-password \
  --server http://localhost:8080 --realm master --user admin
```

## 6. Rotate the backup passphrase

New backups use the new passphrase. **Old backups stay readable under the
previous one**, which is in the sealed copy, so no history is re-encrypted. That
is precisely what the sealed copy buys, and it is why this step is quick.

**Check:** run one backup and one restore rehearsal from it before you consider
this finished.

## 7. If both devices are lost

ADR 0034's escrow and the facility's out-of-band console are the remaining path.
It is slow, it involves other people, and it is the reason the second device is
kept in a different place from the laptop rather than in the same bag.

## Afterwards

Update this file's `Last executed` line with today's date. That line is the only
thing that distinguishes a procedure that works from one that reads as though it
would.
