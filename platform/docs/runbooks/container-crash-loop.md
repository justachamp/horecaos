# A container the watchdog could not fix

**Trading-hours alert.** **Last executed:** never — this is a draft.

The watchdog restarts a container that fails its health check, and ADR 0034 is
explicit that a page whose resolution is "restart it" is a bug in the automation
rather than an entry in a runbook. **So this alert is not "a container is
unhealthy".** It is "the restart is not fixing it": three or more restarts in ten
minutes, which is the state restarting cannot leave.

## 1. Which one, and how many times?

```bash
qc ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'
docker inspect --format '{{.Name}} restarts={{.RestartCount}}' $(qc ps -q)
```

## 2. Why it is dying

```bash
qc logs --tail 200 <service>
```

**Check:** the last lines before each restart, not the first lines after. The
three shapes worth recognising:

- **`OutOfMemoryError` or exit code 137** — the memory limit in
  `compose.production.yaml`. A JVM that has genuinely outgrown 2 GB is a capacity
  decision; a JVM that OOMs on a specific request is a leak, and the heap dump is
  in the container's `/tmp` tmpfs and dies with it. Capture it before restarting
  again.
- **Fails during startup, every time** — a configuration or dependency error, not
  a crash. Common ones: a missing environment variable, an unsealed OpenBao
  ([openbao-sealed.md](openbao-sealed.md)), or an issuer the container cannot
  resolve. The message is on the last line before exit.
- **Healthy, then unhealthy, repeatedly** — the health check is failing on a
  running process. If it is `platform-app`, that is the readiness probe, and it
  consults no dependency, so a failing one means the process itself is wedged
  rather than something it talks to.

## 3. Stop the loop while you work

```bash
qc stop <service>
```

A container restarting every thirty seconds produces log noise faster than you
can read it and, for `platform-app`, takes the deploy window's worth of JVM
startup each time. Stopping it is not making the outage worse — it was not
serving anything between restarts either.

## 4. Roll back if it started at a deploy

```bash
HORECAOS_IMAGE_TAG=<previous tag> qc up -d <service>
```

**Check:** `docker inspect --format '{{.RestartCount}}'` stops rising. ADR 0034's
rollback row is only true because every migration is backward compatible with the
running image, so the previous tag starts against the current schema.

The full procedure, including what to do when the migration was not backward
compatible, is [deploy.md](deploy.md).
