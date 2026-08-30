# OpenBao agent — the only thing on this host that holds a live OpenBao token.
#
# The problem it solves is narrow and specific. `OpenBaoSecretResolver` reads
# `horecaos.secrets.openbao.token` once, when its bean is built. A token with a
# fixed lifetime would therefore work until it expired and then fail in the
# middle of an ordinary week, at whatever hour the TTL ran out — a scheduled
# outage nobody scheduled.
#
# The agent authenticates once with an AppRole, writes the resulting token to a
# tmpfs sink, and then keeps renewing *that same token*. The application read it
# at startup and never has to read it again, because the token it holds stays
# valid. If the agent dies, the token stops being renewed and secret lookups
# start failing within the renewal window — which is exactly when the agent's
# health check has already gone red.

pid_file = "/run/bao/agent.pid"

vault {
  address = "http://openbao:8200"

  # OpenBao being briefly unavailable — a restart, an unseal that took a minute —
  # must not turn into a failed deploy. The agent retries rather than exiting.
  retry {
    num_retries = 20
  }
}

auto_auth {
  method "approle" {
    mount_path = "auth/approle"

    config = {
      role_id_file_path   = "/run/secrets/openbao-role-id"
      secret_id_file_path = "/run/secrets/openbao-secret-id"

      # Kept on disk rather than consumed, which is the opposite of the usual
      # advice, and the reason is the operator rather than the threat model. If
      # the agent could not re-authenticate by itself, an agent crash at 3am
      # would need a human to mint a new secret-id before the platform could come
      # back — and the whole point of this deployment is that recovery does not
      # wait for a person who is asleep.
      #
      # What makes that acceptable is where the file lives. The compose file
      # mounts it from a host tmpfs: RAM, root-only, gone on reboot. The
      # deploy script mints a fresh secret-id on every deploy, so its useful life
      # is one release cycle, and the policy behind it grants read-only access to
      # production secrets and nothing else. It cannot write, cannot list other
      # environments, and cannot unseal.
      remove_secret_id_file_after_reading = false
    }
  }

  sink "file" {
    config = {
      path = "/run/bao/token"
      mode = 0640
    }
  }
}

# The one secret the application cannot resolve through `SecretResolver`, because
# Spring needs it before any bean exists: the datasource password. Everything
# else in this platform travels as an ADR 0028 reference and is resolved at call
# time, which is why this file has exactly one template in it and should stay
# that way. A second template here is a sign that something took a value where a
# reference belonged.
template {
  destination          = "/run/bao/horecaos.env"
  perms                = "0640"
  error_on_missing_key = true

  contents = <<-EOT
  HORECAOS_DB_PASSWORD={{ with secret "horecaos/data/production/database/platform/app-password" }}{{ .Data.data.value }}{{ end }}
  EOT
}

# The agent must keep running after the first render. Exiting once the token has
# been written would leave nothing to renew it, which is the failure this file
# exists to prevent.
exit_after_auth = false
