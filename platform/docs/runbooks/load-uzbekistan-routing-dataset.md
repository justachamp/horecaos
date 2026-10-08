# Loading the Uzbekistan routing dataset

**Not an alert; also the runbook two morning-digest alerts link to.** **Last executed:** never — this is a draft.

**The way back, before the first command.** Road distance for `ROAD` delivery tariffs
comes from the platform's own OSRM engine (ADR 0147). Everything below is reversible
without a deploy of code and without a failed checkout: set
`HORECAOS_ROUTING_OSRM_ENABLED=false` (or suspend the tenant's routing installation),
and every `ROAD` fee prices from the straight line times the tariff's detour factor and
records `RADIUS_FALLBACK`. Nothing in this procedure can stop an order being taken. The
one thing it cannot undo is a fee that was measured by the engine and already issued: that
quote keeps the fee it was issued with, by design.

**Who:** a devops engineer with a laptop or a CI runner that has Docker, and with SSH
access to the host for steps 4 onward. **Never on the host:** building the dataset. ADR
0061's rule is that nothing is built on the server.

## 0. Is the engine even allowed on this machine?

ADR 0147's first open input, and its answer is a measurement, not an opinion. ADR 0073
sizes the pilot VM at 16 GB with limits that already total about 13 GB; the planning
ceiling of 2 GB for a country-sized dataset is an assumption.

Do steps 1 and 2 on a workstation, then:

```bash
docker run -d --name osrm-measure -m 4g -p 5000:5000 \
  -v "$PWD/routing-data:/data:ro" \
  ghcr.io/project-osrm/osrm-backend:v6.0.0@sha256:729461bcc9ae9e6aafa92c0f93db9b060a32e85d5e72092c01ae4a4a9f1eb564 \
  osrm-routed --algorithm mld --ip 0.0.0.0 --port 5000 /data/uzbekistan-latest.osrm
sleep 60
# Send real traffic at it first: a freshly started engine has not touched most of its pages.
python3 platform/tools/routing/accuracy_gate.py sample.csv --engine http://localhost:5000 || true
docker stats --no-stream osrm-measure
docker rm -f osrm-measure
```

**Check:** the `MEM USAGE` column is the resident size under load. Write the figure and
the date into `HORECAOS_OSRM_MEMORY_LIMIT` (`deploy/env.template`), with headroom, and
compare it with what the host can spare:

```bash
free -m && docker stats --no-stream
```

**If it does not fit** beside PostgreSQL's page cache, stop here. ADR 0147's second
choice, a hosted routing API, applies and the rest of that record is unchanged; nothing
below is set, and `ROAD` tariffs keep pricing from the straight line.

## 1. Build the dataset

On a workstation or a CI runner, not the host:

```bash
cd /path/to/HorecaOS
deploy/routing/build-dataset.sh --tag 2026-10-01
```

It downloads Geofabrik's `uzbekistan-latest.osm.pbf`, checks its published checksum,
runs `osrm-extract` (car profile), `osrm-partition` and `osrm-customize` (MLD) from the
**same pinned engine image the host runs**, and builds
`$HORECAOS_REGISTRY/horecaos-routing-dataset:2026-10-01` from the files and a
`BUILD-INFO.txt`. Without `--tag` the tag is the extract's own date.

**Check:** the last line reads `done: …/horecaos-routing-dataset:2026-10-01`, and
`docker run --rm --entrypoint cat <that image> /dataset/BUILD-INFO.txt` names the extract,
the engine digest, `mld` and `car`.

**The tag is the dataset version.** It is recorded on every fee the engine measures and
shown on the tariff screen, so name it for the map and not for the build: a dataset built
today from last week's extract is last week's map.

## 2. Does the map hold? (the accuracy gate)

ADR 0147's second open input, closed on its proposed default: against a reference of 100
trips between 1 and 10 km, the engine's median deviation is at most 10% and its 95th
percentile at most 25%. The reference is route distance from a second source for the same
endpoints, or the GPS track of delivered orders (the track is evidence for this gate and
never pays a courier, ADR 0042). The sample is coordinates only: no address, no order id.

```bash
deploy/routing/build-dataset.sh --tag 2026-10-01 --gate sample.csv
```

The script builds the dataset, starts the pinned engine on the new files, runs
`platform/tools/routing/accuracy_gate.py` against it, and stops there if the gate fails:
nothing is pushed. (To run the gate by hand against any engine:
`python3 platform/tools/routing/accuracy_gate.py sample.csv --engine http://localhost:5000`.)

**Check:** exit status 0 and `gate                 HOLDS` in the report. Exit 1 is a map that is not good enough for
fees: do not publish this dataset to production, and either improve the extract or
take ADR 0147's route to a hosted API. Exit 2 is an unusable sample or an engine that did
not answer, which is not the same as failing it.

**The same run measures the detour factor.** The report's `detour factor` is the median of
road distance over straight line for Tashkent, and ADR 0147 replaces ADR 0037's unmeasured
1.30 with it as the region's default for the fallback path. The report prints it as
`road_factor_basis_points` (13625 for 1.3625). That figure goes into the next draft of
each `ROAD` tariff's road factor; record it, the sample's date and its size beside the
dataset tag.

## 3. Publish the image

```bash
deploy/routing/build-dataset.sh --tag 2026-10-01 --push
```

(Or the monthly workflow `.github/workflows/routing-dataset.yml`, which does the same
and is the normal route once the first run has been done by hand.)

**Check:** `docker manifest inspect $HORECAOS_REGISTRY/horecaos-routing-dataset:2026-10-01`
answers.

## 4. Deploy it

On the host, in the deployment directory, with the env file the deploy procedure uses:

```bash
# deploy/env.template, "Routing": the tag, the profile, and the engine still OFF.
sed -i 's/^HORECAOS_ROUTING_DATASET_TAG=.*/HORECAOS_ROUTING_DATASET_TAG=2026-10-01/' /etc/horecaos/production.env
sed -i 's/^COMPOSE_PROFILES=.*/COMPOSE_PROFILES=routing/' /etc/horecaos/production.env
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env pull osrm-dataset osrm
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d osrm-dataset osrm
```

**Check:**

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps osrm-dataset osrm
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env logs osrm-dataset | tail -3
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env exec platform-app \
  wget -q -O - 'http://osrm:5000/route/v1/driving/69.2405,41.3110;69.2641,41.3309?overview=false&steps=false'
```

`osrm-dataset` shows `Exited (0)` and its last line says `routing dataset 2026-10-01
loaded`; `osrm` is `healthy`; the `wget` returns `"code":"Ok"` with a `distance` of a few
kilometres. **The `osrm` health check was written from the image's description and has not
been run against it from this repository** — if it reports `unhealthy` while the `wget`
above answers, the check is wrong and not the engine, and the first thing to fix is
`healthcheck.test` in `deploy/compose.production.yml`.

The engine is deployed and **still not used**: `HORECAOS_ROUTING_OSRM_ENABLED` is
`false`, so no fee has changed.

## 5. Switch it on for one tenant

```bash
sed -i 's/^HORECAOS_ROUTING_OSRM_ENABLED=.*/HORECAOS_ROUTING_OSRM_ENABLED=true/' /etc/horecaos/production.env
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d platform-app
```

The flag is read by the application, so it is on for every tenant whose tariff names a
routing installation, and **only** for those: a tenant with no `ROAD` tariff is unaffected,
and a `ROAD` tariff is measured by the engine only once its draft chose "use platform
routing" (or named an installation) and was activated. So the rollout is a pilot tenant
drawing one `ROAD` tariff in the console (Delivery tariffs → draft a version → distance
mode **By road** → **Use platform routing**), activating it, and binding it to one branch.

**Check, within a minute of the first order:**

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env exec platform-app \
  wget -q -O - http://127.0.0.1:8080/actuator/prometheus | grep -E 'horecaos_routing|horecaos_delivery_distance'
```

`horecaos_routing_calls_total{outcome="ok"}` is rising, `outcome="timeout"` and `"error"`
are not, `horecaos_routing_dataset_age_days` is a small number, and on the tariff screen
the tariff's **Distance measured by** line says *By road* with *Dataset 2026-10-01*, read
from the fees. For a week, compare that tenant's fees with their `RADIUS_FALLBACK` shadow:

```sql
-- Run through the DB socket as the ops role; the dataset is on every row.
SELECT date_trunc('day', created_at) AS day, distance_source, routing_dataset_version,
       count(*) AS fees, round(avg(distance_meters)) AS avg_metres, round(avg(final_fee_minor)) AS avg_fee_minor
  FROM fulfillment.delivery_fee_resolutions
 WHERE tenant_id = '<tenant id>' AND distance_mode = 'ROAD' AND created_at > now() - interval '7 days'
 GROUP BY 1, 2, 3 ORDER BY 1, 2;
```

## 6. The monthly refresh

Run steps 1 to 3 with the new extract's date, then move the engine first and the
application second, back to back. The order is the point: the application is what stamps
the dataset tag on a fee and keys its route cache by that tag, so the tag must not move
before the engine holds the map it names.

```bash
sed -i 's/^HORECAOS_ROUTING_DATASET_TAG=.*/HORECAOS_ROUTING_DATASET_TAG=2026-11-01/' /etc/horecaos/production.env
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env pull osrm-dataset
# 1. The engine. osrm-dataset replaces the volume's files; osrm is recreated after it
#    because its configuration names the tag, and so loads the new map from scratch.
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d osrm-dataset osrm
# 2. Wait until osrm says healthy (the engine takes a while to load the graph):
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps osrm
# 3. Prove the engine is the new one, not the old one left running:
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' \
  "$(docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps -q osrm)" \
  | grep '^HORECAOS_ROUTING_DATASET_TAG='
# 4. Only then the application, which starts stamping the new tag:
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d platform-app
```

**Check:** step 3 prints `HORECAOS_ROUTING_DATASET_TAG=2026-11-01`. If it prints the old
tag, `osrm` was not recreated: stop here, do not run step 4, and run
`docker compose … up -d --force-recreate osrm`. Never run `up -d platform-app` for a new
tag against an engine that has not been recreated: the fees would say the new map measured
them and the metres would come from the old one.

**What changes and what does not.** The dataset tag on new fees is `2026-11-01`, the cache
misses every entry (the tag is part of its key, and the cache lives in the application's
memory, so recreating the application empties it), and a tenant comparing two weeks of fee
reports will see small shifts that nobody edited a tariff for: the dataset version on the
fee's evidence is what explains them. A quote **issued before** the refresh is accepted at
the fee it was issued with, and the next quote measures against the new map.

**The windows, and which way they lean.** While `osrm` reloads (step 1 to healthy) the
engine does not answer, so `ROAD` quotes fall back to the straight line and say
`RADIUS_FALLBACK`; expect a burst of `outcome="error"` and, if it lasts, `breaker_open`.
That is the designed behaviour and it clears when the engine is healthy. Between the engine
turning healthy and the application restarting (the seconds between steps 2 and 4) a fee is
measured on the new map and stamped with the old tag, and its cached route dies with the
old application process; no fee is ever stamped with a tag whose map has not been loaded.
Keep steps 2 to 4 together to keep that window short.

## 7. Roll back

In order of how little they undo:

1. **One tenant:** suspend its routing installation (Integrations, the *Platform routing*
   row, or `UPDATE integration.installations SET status = 'SUSPENDED' WHERE id = …`). Takes
   effect on the next quote, cached routes included.
2. **Everyone:** `HORECAOS_ROUTING_OSRM_ENABLED=false` and `up -d platform-app`.
3. **The previous map:** set `HORECAOS_ROUTING_DATASET_TAG` back and run the step 6
   commands in the same order, engine first and application last. The old tag's image is
   still in the registry, and the tag is in the engine's configuration, so the engine is
   recreated on the old map before the application stamps the old tag.
4. **The engine itself:** remove `routing` from `COMPOSE_PROFILES` and
   `docker compose … rm -sf osrm osrm-dataset`. The `osrm-data` volume holds only files
   the dataset image can recreate.

Each of these leaves every `ROAD` fee on `RADIUS_FALLBACK` with a working checkout, which
the tariff screen says in words.

## 8. When it goes wrong

| What you see | What it is | What to do |
|---|---|---|
| `HorecaosRoadRoutingFallingBack` in the morning digest | More than 5% of `ROAD` quotes over fifteen minutes fell back | `horecaos_routing_calls_total` by `outcome`: `timeout`/`error` is the engine (step 4's `ps` and `logs osrm`); `unavailable` is the flag, the dataset tag or a suspended installation; `breaker_open` means it was already failing and has stopped being asked for thirty seconds at a time |
| `HorecaosRoutingDatasetStale` | The dataset tag's date is more than 60 days old | The monthly refresh has stopped: run step 6, then find out why the workflow did not |
| `osrm` restarts and the log says the dataset was built for another algorithm | The image's files and `--algorithm` disagree | Rebuild with `deploy/routing/build-dataset.sh`, which uses MLD; do not edit the command |
| Every `ROAD` fee says `RADIUS_FALLBACK` and `outcome="unavailable"` | Engine flag off, dataset tag empty, or no active routing installation | `HORECAOS_ROUTING_OSRM_ENABLED`, `HORECAOS_ROUTING_DATASET_TAG`, and the tariff's installation status |
| Fees jumped by a band for some addresses after a refresh | OpenStreetMap changed under them (a one-way added, a courtyard dropped) | Compare the two tags in the SQL above; if the new map is worse, step 7.3 and re-run the gate on the new extract before trying again |
| `outcome="no_route"` for a particular street | The engine says the two points are not connected, or one is more than the snap radius from any road | Not a fault: the fee falls back for that address. Many in a row for one branch means the branch's pin is in a courtyard the map does not route into |

## What this does not do

It does not make a promise time out of the engine's `seconds`: they are free-flow, an
empty road, and say nothing about rush hour (ADR 0147, decision 2). Wiring them into
`promise_travel_minutes` is the remaining ADR 0037 item. It does not run the real
engine in the platform's own test suite: that needs the engine image and a preprocessed
extract, and what the suite proves is everything the adapter decides around it.
