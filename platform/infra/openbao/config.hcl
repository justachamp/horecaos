# OpenBao server configuration for the colocated production host (ADR 0028).
#
# The single most important difference from `compose.yaml` is that this is not
# dev mode. Dev mode holds everything in memory behind a fixed root token that is
# printed to the log; production starts sealed, and the unseal key shares are the
# only thing standing between someone with the disk and every credential Qoida
# owns. Those shares are never on this machine.

# Raft rather than the file backend, for one operational reason: raft supports
# `bao operator raft snapshot save`, which produces a single consistent file that
# the backup job can encrypt and ship off-site. The file backend has no
# equivalent, and copying its directory out from under a running server is how a
# backup ends up half-written.
storage "raft" {
  path    = "/openbao/file"
  node_id = "qoida-colo-1"
}

# TLS is terminated at the edge and this listener is reachable only from the
# compose network, which has no published port. Enabling TLS here would mean
# issuing and rotating an internal certificate for a socket that never leaves the
# host — cost with no attacker removed. If OpenBao ever moves to its own machine
# this line becomes wrong immediately and must change with it.
listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

api_addr     = "http://openbao:8200"
cluster_addr = "http://openbao:8201"

# There is deliberately no `disable_mlock` line. OpenBao 2.x removed mlock
# support entirely and refuses to start if the option is present at all — the
# upstream position being that mlock gave a weaker guarantee than it appeared to.
#
# The protection it used to provide has to come from the host instead, and this
# is the one host-level requirement this deployment has:
#
#     swapoff -a && sed -i '/ swap /d' /etc/fstab
#
# Without that, an unsealed master key can be written to the swap partition by
# the kernel and survives on the disk after a power cut. On a colocated machine
# whose drives will one day be handed back to somebody, that matters.

# The UI is off. It is one more authenticated surface on the internet-facing box,
# and every operation this deployment needs is in the runbooks as a CLI command.
ui = false

log_level  = "warn"
log_format = "json"
