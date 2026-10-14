# ADR 0187: Kiosk idle media, and the channel forms the console does not owe

- Decision status: Accepted — proposed by Claude (batch 21); accepted by the platform owner 2026-10-14
- Implementation status: Not started — written first; the building wave (`w14-channel-setup-pos-loyalty`) advances this line as each
  piece lands.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0010](../partial/0010-s3-media-lifecycle-and-filesystem-migration.md),
  [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md),
  [ADR 0055](../meta/0055-greenfield-launch-scope.md),
  [ADR 0058](../partial/0058-telegram-notification-channels.md),
  [ADR 0071](../built/0071-order-reviews-a-rating-the-tenant-can-see.md),
  [ADR 0082](../built/0082-a-feature-flag-is-a-boolean-configuration-key.md),
  [ADR 0119](../partial/0119-wave-p17-device-shell-auth-and-qr-pairing.md),
  [ADR 0159](../not-started/0159-service-recovery-and-feedback-settings.md),
  [ADR 0162](../partial/0162-kiosk-device-pairing.md),
  [ADR 0171](../partial/0171-who-owns-a-loyalty-balance-when-the-till-also-keeps-one.md)
- Supersedes / Superseded by: — (amends [ADR 0162](../partial/0162-kiosk-device-pairing.md) without editing its argument: its open
  input «Idle media, the 9:16 content type and its 1 MB cap» and the two rows of Decision 8 that read «Deferred»
  («Customer profile orders are booked under», «Idle media, content type») are answered here, and ADR 0162 carries a dated
  status addition pointing at this record. It also answers, for the one row it concerns, the ADR 0010 sentence that idle media
  «needs the purpose column ADR 0010 lacks»: the column is added here. It amends the information architecture (row `10.5`)
  and `operations-spec/settings.md` §10.5, which are not records.)
- Open inputs: each is closed on the value the owner chose; the ones that are external facts stay with the person named, and
  the work they block is marked.
  - **A kiosk unit, under operating-system lockdown, paired at a pilot branch, playing its idle media** (the owner, who supplies
    the device and the branch). Blocks: the runbook's «Last executed» line in `docs/runbooks/terminals-and-kiosks.md`, and
    nothing in the code.
  - **A real BotFather round trip** (the owner's BotFather identity). Blocks: the «Last executed» line of
    `docs/runbooks/connect-telegram-bot.md`, and nothing in the code. This wave does not touch the Telegram per-channel
    settings, which wave `w8-acquisition-merchandising` builds.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Gap-map row `10.5` (channel setup) stayed `PARTIAL` after batch 20 for three reasons the row names and one it does
not. The KIOSK form's idle media has «no upload path and no record that decides it»: ADR 0162 defers it («It needs the
purpose column ADR 0010 lacks and nothing reads it until a kiosk application exists»). The kiosk application does not
exist. And the mobile-app forms (`IOS`, `ANDROID`) that `settings.md` §10.5 lists are not in the gap-map text at all, so
the row could never be read as finished. Each is a decision, and each has an answer the repository's own earlier records
already imply.

**Idle media now has a reader.** ADR 0162 deferred the field because nothing would read it. Something does: the device
shell's kiosk status screen is a screen on a paired kiosk, and a screen that shows «Ready» on a blank panel in a lobby is
the thing an idle loop exists to replace. A field with a reader before any ordering application is not the «no call
site» defect, and the rest of the form's data (the channel, the branch, whether it is open) is already served to the
device by `GET …/kiosk/configuration`.

**The media pipeline already knows how to refuse a wrong picture.** ADR 0159 built a tenant-owned public image check for
a feedback tag's icon (`TenantImageCheck`: raster, public, tenant-owned, an aspect ratio with a one percent tolerance, a
byte cap on the store's own verified size). What it lacks is the purpose column ADR 0010 predicted: nothing in
`media.assets` says what an asset is for, so a product photo uploaded for a menu could be attached to a lobby screen.
Nothing about a purpose is hard; it is one nullable column and a rule that a purposed asset is a public tenant-owned image.

**A branch manager cannot upload.** The people ADR 0162 has pair kiosks hold `kiosk.device.manage` at their branch and
neither `channel.manage` (tenant-wide) nor `media.upload` (a tenant or brand grant). A KIOSK card that needs either would be
a card the people who use it cannot use. The capability for the work is the one they have.

**The kiosk ordering application is a different piece of work from the console's channel setup.** ADR 0162's first open
input says the application «waits for a pilot tenant to name physical kiosks», and that the direction to test is a
short-lived kiosk order session minted by the device on its channel, never `order.place`. It needs a payment path and a
fiscal path that no record decides. Holding the console row open until it exists means the row can never be read as
BUILT; and the field Delever's form calls «customer profile orders are booked under» only has meaning inside that
application.

**The mobile apps are on hold.** ADR 0055 records that the Flutter customer app is on hold and revisited when a pilot tenant
asks. A form with an app-store link, a version gate and push credentials has no app to read it, and the push credential
additionally needs an Apple or Google account that is the owner's to open. Building the field is the «no call site»
defect again, with an external fact on top.

## Decision

**Add kiosk idle media as images only (9:16, at most 1 MB each, at most 8 per KIOSK channel, in a chosen order) with a media
purpose column and a table of its own, managed by the branch manager under `kiosk.device.manage` and played by the device
shell; make the kiosk ordering application its own gap-map row with its own record and strike the booking-profile field
from the KIOSK form; and amend the information architecture so the mobile-app forms belong to the wave that un-holds the
app, with an honest locked state in the hub until then.**

1. **D25 — Kiosk idle media (deferred by ADR 0162): images only, 9:16 enforced at upload, at most 1 MB each, at most 8 per
   channel; no video.** JPEG, PNG and WebP. The ratio is 9:16 within the one percent tolerance `TenantImageCheck` already
   uses, measured on the dimensions the verification worker read from the file's own header; the size is the object
   store's verified size, 1 048 576 bytes. «At upload» is two places: the upload request declares a purposed asset and is
   refused beyond 1 MB, and verification rejects a purposed asset whose ratio is wrong (`REJECTED`, with its reason), so a
   wrong picture never becomes `AVAILABLE`; attaching it to a channel re-checks both, because the attach is where a
   guessed asset id would be tried. Video is out: the pipeline's video path is the content editor's (ADR 0157, behind an
   entitlement), a looping video on a kiosk is a hardware question this record does not answer, and a single image per
   slide is what a lobby screen needs.
2. **D26 — Channel setup does not owe a kiosk ordering application, and does not owe «customer profile orders are booked
   under»: no.** The kiosk ordering application becomes its own gap-map row and its own record, which will decide how a
   kiosk orders and is paid (ADR 0162 open input 1). The field is struck from the KIOSK form and from
   `operations-spec/settings.md` §10.5: it describes the application's order-booking, not the channel's setup, and the
   application's record will say whether it needs a customer at all (the guest-cart path ADR 0019 records as supported by
   the schema and refused by the controller). ADR 0162 Decision 8's row «Customer profile orders are booked under» was
   «Deferred with ordering»; it is now «Struck: belongs to the kiosk ordering application's record».
3. **D28 — Mobile-app channel forms while the app is on hold: amend the IA.** The `IOS` and `ANDROID` forms (app-store
   links, version gating, push credentials), the "dynamic button" and a per-channel «languages offered» field belong to the
   wave that un-holds the app (ADR 0055). Languages offered stays brand-level (row `10.12`); the dynamic button is
   declined with the app. Until then the hub shows an honest `LockedState` for `IOS` and `ANDROID` that names what it is
   waiting for, and adds no field that nothing reads.
4. **The KIOSK form's printer, terminal and marking block renders as a `LockedState` that names what is not built.** This
   is `settings.md` §10.5's own sentence; batch 20 recorded it as built «by decision» without drawing it. It is drawn here.
5. **Positive and negative review chat routing.** Two event classes, `REVIEW_POSITIVE` (a rating of 4 or 5) and
   `REVIEW_NEGATIVE` (1 to 3), join `TelegramEventClass` and `ck_telegram_binding_event_class` together. A trigger beside
   `ReviewsOperationsAlertTrigger` listens to the in-process `ReviewSubmitted` and fans out through `OperationsAlertPort`;
   the message carries the review's id and its rating and nothing else (ADR 0029: no customer, no comment, no tag). A
   chat hears it only if it subscribed (no default set includes the new classes; a subscription is the opt-in, unlike the
   recovery alerts, which also need a policy switch because they start clocks).
6. **Hub completeness.** For `AGGREGATOR` the hub links to Integrations (read-only here: an aggregator is an installation
   with mappings, not a storefront); for `CALL_CENTRE` it shows a read-only summary of order policy card 5; a channel whose
   installation last failed shows a banner at the top of the form for every type that has an installation; every type
   with a preview offers a *Предпросмотр* link to the existing channel preview.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep idle media deferred until the kiosk application exists (ADR 0162 as accepted) | The row cannot be BUILT while the application is undecided, and the shell's status screen is a reader today | Never for the media; the application's record may ask for more than images |
| Allow short video loops | The video pipeline is entitlement-gated and owed to the content editor; a loop on an unmanaged screen is a bandwidth and playback-hardware question with no unit to test on | A pilot tenant supplies a kiosk unit and asks |
| Any aspect ratio, cropped on the device | The device is the thing least able to crop well, and the uploader already crops to 9:16 in the manager's browser | A pilot kiosk is landscape (the ratio becomes a per-channel setting) |
| Reuse `channel.manage` and `media.upload` | A branch manager holds neither; the card would be unusable by the people ADR 0162 has pair kiosks | The branch-manager grant is redesigned (ADR 0103) |
| Put the images in the channel's presentation row (ADR 0157 content slots) | A content slot is storefront merchandising with revisions and a publish step; an idle loop is device configuration read by a device principal | The kiosk application is a storefront client and reads the content API |
| Build the kiosk ordering application inside this row | It needs a payment and a fiscal path no record decides, and a pilot tenant to name the hardware (ADR 0162) | A pilot tenant names kiosk units |
| Build the IOS/ANDROID forms now | A field with no app to read it; push credentials need an Apple or Google account that is the owner's to open | The Flutter app is revived (ADR 0055's revisit) |
| Keep the booking-profile field as a disabled input | A disabled field is a promise; the application's record may not need it | The ordering record decides a booking profile exists |
| Positive/negative thresholds as a brand policy | One more key declared twice (module and tenancy registry) for a boundary nobody has asked to move | A tenant asks to treat 3 stars as neutral |

## Consequences

### Positive

- A paired kiosk in a lobby shows the tenant's own pictures instead of «Ready», and the field has a reader and a test that
  fails without it.
- A purposed asset can never be a catalogue photo or a private document, and the database says so (`ck_media_asset_purpose_shape`).
- A branch manager can do the whole job (upload, order, remove) without a grant she should not have.
- The row stops waiting for something it never owned: the application and the app forms each have a named home.
- Positive and negative reviews reach a chat that asked for them, and the routing screen offers them without a code change
  to the screen (it is driven by the server's list).

### Negative

- Any manager of any branch where a KIOSK channel sells can change the pictures every kiosk on that channel shows. Idle
  media is channel content, not branch content; the audit fact names the actor and the branch they acted from.
- A second table and a second purpose of an asset (`media.assets.purpose`) that the media module's usage probes, deletion
  worker and reference catalogue must all know about.
- 9:16 at 1 MB means a photographic picture exported at 1080×1920 may need a lower quality; the console exports at 720×1280 and
  says so when a file is still over the cap.
- No video, no per-slot duration, no schedule: every picture shows for the same time (8 seconds) in the chosen order.
- `ck_telegram_binding_event_class` is restated again, and another wave of batch 21 may restate it too: whichever migration
  merges last must carry every class (the integrator's job, named in the report).

### Accepted trade-offs

- The ratio is checked on the dimensions in the file's header with a one percent tolerance; a picture the browser cropped to
  the pixel is rarely exactly 9:16.
- The kiosk ordering application's row is not written by a builder: this record states its text and the re-audit adds it.
- A kiosk whose channel has no pictures shows the status screen it shows today.

## Specification

### Model (V0695, V0696)

```text
media.assets      + purpose varchar(16) null          ck_media_asset_purpose        purpose IS NULL OR purpose = 'KIOSK_IDLE'
                                                      ck_media_asset_purpose_shape  purpose IS NULL OR (owner_scope = 'TENANT' AND visibility = 'PUBLIC')

tenant.kiosk_idle_media
  tenant_id, id                         PK (id); uq (tenant_id, id)
  channel_id, channel_type varchar(16)  ck channel_type = 'KIOSK'; (tenant_id, channel_id, channel_type) -> tenant.sales_channels
                                        (tenant_id, id, system_type) [uq_sales_channel_type, V0526]
  asset_id                              (tenant_id, asset_id) is checked by the service against media.assets; MediaAssetReferenceCatalogTests
                                        lists the column and the tenancy usage probe covers it
  position smallint                     ck 0..7 (at most 8 per channel); uq (tenant_id, channel_id, position) DEFERRABLE INITIALLY DEFERRED
                                        so a reorder is one statement; uq (tenant_id, channel_id, asset_id): a picture is on a channel once
  created_by, created_at
tenant.kiosk_idle_media_versions        (tenant_id, channel_id) PK, version int: the list's If-Match, bumped by every attach, reorder and remove
```

`GRANT SELECT, INSERT, UPDATE, DELETE` on `tenant.kiosk_idle_media` and `SELECT, INSERT, UPDATE` on the versions table to
`horecaos_application` (the idle list is configuration, not history: a removed picture is removed, and the audit fact is the
record). V0696 restates `ck_telegram_binding_event_class` from the live constraint (V0544's list) and adds `REVIEW_POSITIVE`,
`REVIEW_NEGATIVE` and `LOYALTY_SYNC_DISCREPANCY`.

### Endpoints (ADR 0031; OPERATIONS group; `kiosk.device.manage` at `LOCATION` scope, as every kiosk endpoint)

```text
GET    …/locations/{l}/kiosk/channels/{channelId}/idle-media               list + version (ETag) + the limits the screen shows
POST   …/idle-media/upload-requests   { contentType, sizeBytes, filename } Idempotency-Key   a purposed upload ticket (TENANT-owned, PUBLIC, KIOSK_IDLE)
POST   …/idle-media/uploads/{assetId}/finalize                              Idempotency-Key   the ordinary finalize, refused for an asset of another purpose
POST   …/idle-media                   { assetId }                           Idempotency-Key   If-Match   attach at the end
PUT    …/idle-media/order             { itemIds: [...] }                    Idempotency-Key   If-Match   a permutation of the current list
DELETE …/idle-media/{itemId}                                                Idempotency-Key   If-Match   remove
GET    …/kiosk/configuration          (existing)                            gains idleMedia: [{ id, position, url }] and idleMediaVersion
```

The channel must be an `ACTIVE` KIOSK channel with an `ACTIVE` binding at the branch in the path (the service's
`KIOSK_CHANNEL_NOT_AVAILABLE`, as for pairing). Refusals carry stable codes: `IDLE_MEDIA_WRONG_ASPECT`, `IDLE_MEDIA_TOO_LARGE`,
`IDLE_MEDIA_NOT_AN_IMAGE`, `IDLE_MEDIA_NOT_AVAILABLE` (not yet verified), `IDLE_MEDIA_LIMIT` (the ninth) and
`VALIDATION_FAILED` for an asset of another purpose. Audit (ADR 0027): `kiosk.idle_media.attached` (`ChangeDocuments.created`),
`kiosk.idle_media.reordered` and `kiosk.idle_media.removed` (`ChangeDocuments.diff` of the ordered asset ids), each with the
branch the manager acted from. A kiosk's configuration read lists the pictures by a URL on the existing anonymous storefront
media route (`/api/v1/storefront/tenants/{t}/media/{assetId}`), which serves a PUBLIC displayable asset and nothing else.

### The device shell

On the kiosk status screen the shell reads `kiosk/configuration` with the same minute poll as its own record, and plays the
list in order, 8 seconds a picture, a cross-fade; no list, or an unreachable one, leaves the status screen it shows today. The
status facts (channel, branch) stay visible over the loop in a corner. The shell caches nothing but the last good list in
memory.

### The console

The KIOSK card gains *Idle media*: the shared `q-media-uploader` with the 9:16 ratio only and a 1 MB cap, a `KioskFrame` preview
of the list, move up and down, remove. The printer, terminal and marking block is a `LockedState`. The hub renders
`AGGREGATOR`, `CALL_CENTRE`, `IOS`/`ANDROID` and `POS` as above, and a FAILED-installation banner for a channel whose installation
failed its last connection check.

### Testing (each seen failing first)

- A picture of the wrong ratio is `REJECTED` by verification and refused at attach; one over 1 MB is refused at the request and
  at attach; a catalogue photo (no purpose) is refused at attach; a ninth picture is refused; a reorder that is not a
  permutation is refused; a stale `If-Match` is `STALE_VERSION`.
- A manager at branch A cannot read or change a channel that does not sell at A; a cook (no capability) is refused; another
  tenant's channel is the same 404.
- The kiosk's configuration lists the pictures in the chosen order and its token still reads nothing else.
- `MediaAssetReferenceCatalogTests` lists the new column and `TenancyMediaUsageProbe` reports an attached asset as in use.
- Review routing: a 5-star review reaches a chat subscribed to `REVIEW_POSITIVE` only, a 2-star one `REVIEW_NEGATIVE` only, the
  variables are exactly `reviewId` and `rating`, a replay is deduplicated, and the enum and the CHECK admit the same set.

## Rollout and rollback

Ships behind `feature.kiosk_devices` (ADR 0162, Decision 9), which already hides the KIOSK card and the self-read's kiosk member.
The review classes ship subscribed by nobody. Rollback is removing the pictures (the table is inert without rows) and the flag;
V0695 and V0696 are additive and stay.

## Implementation checklist

- [ ] V0695: the purpose column, its checks, the idle-media table and its versions table, granted.
- [ ] `media`: the purposed upload and finalize port, the verification rule for ratio, the size rule at the request.
- [ ] `tenancy`: the idle-media service and the endpoints; the configuration read; the usage probe; the audit facts.
- [ ] V0696 and `TelegramEventClass`; the review trigger; the OPERATIONS_ALERT variable catalogue.
- [ ] Console: the idle-media panel, the LockedState block, the hub completeness, the IOS/ANDROID locked state, i18n in ru, uz-Latn, en.
- [ ] Device shell: play the list.
- [ ] `docs/runbooks/external-connections/w14-channel-setup-pos-loyalty.md`.
- [ ] A dated status addition on ADR 0162; IA row `10.5` and `settings.md` §10.5 amended.

## Exit criteria

A branch manager uploads three pictures at the right shape, puts them in order and removes one; a paired kiosk's status screen plays the
remaining two in that order within a minute; a picture of the wrong shape, one that is too large, a ninth, and a catalogue photo are
each refused with a stated reason; a chat subscribed to `REVIEW_NEGATIVE` is told of a two-star review with its id and rating and
nothing about the customer. The KIOSK form does not contain a booking-profile field and the hub does not offer a form for an app that is
on hold.

## References

- ADR 0010 (media), ADR 0025, ADR 0027, ADR 0031, ADR 0036, ADR 0055 (the app on hold), ADR 0058, ADR 0071, ADR 0079, ADR 0082, ADR 0119,
  ADR 0151, ADR 0157 (the tenant's media in content), ADR 0159 (the image check, the recovery trigger), ADR 0162
- `platform/docs/operations-gap-map.md` row `10.5`; `platform/docs/operations-spec/settings.md` §10.5;
  `platform/docs/frontend-information-architecture.md` row `10.5`; `platform/docs/delever-parity-matrix.md` (kiosk)
- `MediaAssetService`, `TenantImageCheck`, `MediaUsageProbe`, `KioskDeviceService`, `KioskDeviceController`, `TelegramEventClass`,
  `ReviewsOperationsAlertTrigger`; `V0526`, `V0533`, `V0544`
- `frontend/operations/src/app/features/settings/kiosk/kiosk-card.ts`, `shared/ui/media-uploader.ts`, `shared/ui/kiosk-frame.ts`,
  `device/device-shell.ts`
