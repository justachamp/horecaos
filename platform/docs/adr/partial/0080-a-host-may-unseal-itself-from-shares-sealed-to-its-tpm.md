# ADR 0080: A host may unseal itself, from shares sealed to its TPM

- Decision status: Accepted
- Implementation status: Partial — `deploy/unattended-boot.sh` (enrol, status, revoke), `deploy/session-start.sh --unattended`, `deploy/systemd/horecaos-boot.service.in`, `deploy/systemd/run-horecaos-secrets.mount` and the `horecaos-boot` policy exist. Every OpenBao behaviour they rely on was checked against a real OpenBao 2.4.1 before they were written: unsealing with the share on stdin, the AppRole login with the secret-id on stdin, the policy reading the four startup secrets and refusing a fifth, and the 127.0.0.1 binding refusing both the credential and its token from another container. The TPM round trip with the enrolment's exact flags and `systemd-analyze verify` of both units were run on the pre-production VM. No host is enrolled yet, so no boot has yet been restored by it
- Date proposed: 2026-09-10
- Date decided: 2026-09-10
- Deciders: Ayubkhon Abbosov (platform owner, and the person a sealed OpenBao used to wait for), who asked for pre-production to come back by itself after its scheduled stops; the mechanism is this record's
- Depends on: ADR 0023, ADR 0028, ADR 0034, ADR 0061, ADR 0073
- Supersedes / Superseded by: Supersedes ADR 0023's "Unsealing stays manual" for a host that enrols, and only for it. A host that does not enrol is exactly as ADR 0023 describes, and nothing else in ADR 0023 changes
- Open inputs: whether the production pilot host enrols — owner Ayubkhon Abbosov, decided when ADR 0073's host is chosen; possible only if that host has a TPM 2.0

## Context

OpenBao seals itself whenever its process stops. ADR 0023 accepted that and
kept unsealing manual: after a reboot someone runs `deploy/session-start.sh`,
types three of the five Shamir shares, and types a root or `horecaos-deploy`
token so the script can read the four startup secrets the stack needs before
the OpenBao agent can run. Until that happens the platform is down, whatever
the reason for the reboot.

Pre-production made the cost concrete. It runs on a GCP VM that stops itself
two hours after every start — a cost control its owner chose to keep — so the
stack went down every time it was started again, and the fix was always the
same person typing the same three shares.

Two facts shaped the answer. The VM is a Shielded VM with a vTPM, and its
systemd (257) can seal a secret to that TPM so that only that machine can open
it. And GCP's own answer, a KMS-backed auto-unseal, needed four changes to the
host to work there: a wider service-account scope (only possible while the VM
is stopped), a newly enabled API, OpenBao moved from the internal `core`
network onto one with a route to the internet, and a seal migration. It would
also have been GCP's alone, while production is headed for a rented machine in
Uzbekistan (ADR 0073).

## Decision

A host with a TPM 2.0 may be enrolled for unattended restart. Enrolling
(`deploy/unattended-boot.sh enrol`, run once by a person) stores five things
under `/etc/credstore.encrypted`, each encrypted by `systemd-creds` with the
host's credential key **and** its TPM:

- three unseal shares, typed at a hidden prompt;
- the role-id and one secret-id of a new AppRole, `horecaos-boot`.

At every boot `horecaos-boot.service` receives them from systemd — decrypted by
PID 1 into a directory only that unit can read — and runs
`session-start.sh --unattended`: the same restoration a person runs, with the
shares and the credential coming from systemd instead of a keyboard. The shares
and the secret-id reach OpenBao over stdin, never as an argument.

`horecaos-boot` is deliberately narrower than `horecaos-deploy`, which a person
holds and which reads every production secret. Its policy reads the four
startup secrets by exact path and issues the platform agent's secret-id, and
nothing else. The role binds both its secret-id and its tokens to
`127.0.0.1/32`, which is where every call the restart makes comes from — each
runs inside the openbao container — so the credential and any token it yields
are refused from anywhere else.

A host that has not enrolled, or has revoked (`unattended-boot.sh revoke`),
behaves exactly as ADR 0023 describes. On an enrolled host,
`sudo session-start.sh` by hand still works and is the fallback when the unit
fails.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep unsealing manual (ADR 0023) | Every scheduled stop on pre-production became an outage until one particular person was at a keyboard, for a restoration that has no judgement in it | A host has no TPM — it keeps this, because enrolment refuses without one |
| Cloud KMS auto-unseal (`seal "gcpckms"`) | Four host changes before it could work — service-account scope, a new API, OpenBao given a route to the internet, a seal migration — and specific to GCP while production goes elsewhere. OpenBao on an egress network is a wider exposure than the one this removes | Production lands on a cloud with a managed KMS, and OpenBao has to reach it anyway |
| Transit auto-unseal from a second OpenBao | A second secrets manager to run, back up and unseal — and that one's unsealing is the same problem again | There is already a second, always-on OpenBao, for example a central one for several hosts |
| Shares in a root-only file on disk | A copy of the disk — a snapshot, a backup, a detached volume — would unseal OpenBao anywhere. Sealing to the TPM is what makes the stored shares worth storing | Never, for real data |
| Shares sealed to the TPM **and** to PCR 7 (Secure Boot state) | Binds the shares to the boot chain as well as the machine, but a firmware or `dbx` update changes PCR 7, and then the unattended restart after an unattended update fails — the one restart it exists for | A host with measured boot under change control, where PCR changes are planned and re-enrolment is part of the update |
| Store the root token, or a `horecaos-deploy` token, instead of a new role | The thing on disk would read every production secret. Tokens also expire, which moves the outage to the day one does | — |

## Consequences

### Positive

- A boot restores the stack with nobody present, in the order and with the
  health waits `session-start.sh` already uses, because it is the same script.
- A copy of the disk opens nothing. Every stored credential needs the host key
  and this TPM together; the GCP vTPM belongs to the instance and does not
  travel with a snapshot.
- The credential on the machine is least privilege: four exact secrets and one
  secret-id, from loopback only. A container that steals it cannot use it.
- Rotation is re-enrolling: the role is deleted and recreated, so every earlier
  secret-id dies with it. Revoking deletes the files, the units and the role.
- Portable. Nothing here is GCP's: any Linux host with systemd 250+ and a TPM
  2.0 can enrol, including a rented machine in Uzbekistan if it has one.

### Negative

- **The host can unseal its own OpenBao.** Three shares are the threshold, so
  the off-site escrow (ADR 0034) is no longer the only way in: a running, booted
  copy of this exact machine is one too. Anyone who can start the VM and log in
  as root can reach every secret — as, in fairness, they already could once a
  person had unsealed it, which is the state it spent most of its time in.
- Anyone with root on the running host can decrypt the stored shares with
  `systemd-creds decrypt`. Root could already read the startup secrets on the
  tmpfs and `docker exec` into OpenBao; now it can also re-unseal after a
  restart without asking anybody.
- Losing the TPM loses the enrolment — deleting and recreating the instance, or
  clearing its vTPM. The unit then fails, the stack waits for a person as
  before, and the host has to be enrolled again. The shares themselves are
  unaffected: they were copies.
- Three of the five shares have now been typed into a machine that keeps them.
  Rekeying OpenBao (new shares) requires enrolling again with new ones.

### Accepted trade-offs

Pre-production holds synthetic data only, and its owner asked for it to recover
by itself. The mechanism is chosen so that the same trade is available to the
production host without making it the default there: enrolment is a deliberate
act per host, it refuses without a TPM, and ADR 0023's manual path is untouched
for any host that does not take it.

## Specification

**At rest.** `/etc/credstore.encrypted/{horecaos-unseal-1,2,3,
horecaos-boot-role-id, horecaos-boot-secret-id}`, mode 0600 in a 0700
directory. `systemd-creds encrypt --with-key=host+tpm2 --tpm2-pcrs=`: the key
is derived from `/var/lib/systemd/credential.secret` and a TPM-sealed secret,
bound to no PCRs. Each credential's name is sealed inside it, and systemd
refuses to hand a credential to a unit under another name.

**At boot.** `run-horecaos-secrets.mount` mounts the 1 MiB, 0700, noexec tmpfs
at `/run/horecaos/secrets` in the host's mount namespace — a unit of its own
because a mount made from inside a service's private namespace would be
invisible to the Docker daemon. `horecaos-boot.service` (oneshot,
`After=docker.service run-horecaos-secrets.mount`, no mount-namespace
hardening for the same reason) runs `session-start.sh --unattended`, which:

1. refuses without `$CREDENTIALS_DIRECTORY` and all five credentials;
2. stops the services that crash-loop without their secrets;
3. unseals with `bao write sys/unseal key=-`, one stored share per call, and
   stops at the first refusal;
4. logs in with `bao write auth/approle/login role_id=… secret_id=-`;
5. writes the four startup secrets and a fresh platform secret-id to the tmpfs,
   revokes its token, and starts dependencies then services, waiting for each
   to report healthy.

**The role.** `auth/approle/role/horecaos-boot`: `token_policies=horecaos-boot`,
`token_ttl=15m`, `token_max_ttl=30m`, `secret_id_ttl=0`,
`secret_id_num_uses=0`, `secret_id_bound_cidrs` and `token_bound_cidrs`
`127.0.0.1/32`. The policy is `deploy/infra/openbao/policies/horecaos-boot.hcl`.

**Observability.** The unit's outcome is `systemctl show -p Result
horecaos-boot` and its journal; `unattended-boot.sh status` shows both and
which credentials are present, without decrypting any. ADR 0023's seal alert
still fires if a boot leaves OpenBao sealed — which now means the unit failed,
not that nobody has come yet.

## Rollout and rollback

Rollout is per host: `session-start.sh` by hand once, then
`unattended-boot.sh enrol`, which offers to prove itself by sealing OpenBao and
letting the unit restore the stack while the operator watches. Rollback is
`unattended-boot.sh revoke`, which deletes the units, the stored credentials
and the `horecaos-boot` role.

## Implementation checklist

- [x] `horecaos-boot` policy: four exact paths and the platform secret-id
- [x] `session-start.sh --unattended`, the manual path unchanged
- [x] `unattended-boot.sh` enrol / status / revoke
- [x] `horecaos-boot.service.in` and `run-horecaos-secrets.mount`
- [x] OpenBao behaviours checked against 2.4.1; TPM round trip and unit verify on the pre-production VM
- [ ] Pre-production enrolled, and a boot restored by the unit
- [ ] Production pilot: enrol or not, recorded here (open input)

## Exit criteria

On an enrolled host, a stop and start of the machine brings the platform back
to healthy with nobody logged in, and `unattended-boot.sh status` reports the
unit's last run as `success`.

## References

- ADR 0023, "Security" and "Recovery": the manual unseal this replaces for an enrolled host
- ADR 0034: the unseal escrow, still the only way in on a host that has not enrolled
- `systemd-creds(1)`, `systemd.exec(5)` (`LoadCredentialEncrypted=`)
- `docs/runbooks/production-setup.md`, "Unattended restart"
