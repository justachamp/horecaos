# ADR 0157: Content and merchandising slots

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — nothing authors, stores or serves
  brand-written content today, and every fact below was read from the tree on
  2026-10-07. *No tables:* there is no `content` module, schema or table; the only
  trace of a slot is the sketch of `marketing.slot_items` and
  `marketing.slot_item_locales` inside ADR 0044's "Merchandising slots" section,
  which was never migrated, and the `marketing` module's own descriptor says it
  holds "Audiences, campaigns, suppression, and the customer metric projection".
  *No screens:* the operations routes `/marketing/content` (row `6.7`) and
  `/marketing/storefront` (row `6.8`) in `frontend/operations/src/app/app.routes.ts`
  render `NotBuiltPage`. *Nothing served:* both storefronts' `MenuService` return
  `offer: null`, `populars: []`, `populars_count: 0` and say in a comment that
  "nothing on the platform produces either"; `frontend/storefront`'s home template
  still carries a block guarded by `offerData()` with a hard-coded
  `/assets/home-promo.jpg` tile that therefore never renders;
  `frontend/storefront-milliy`'s home has no content area at all. *Media is
  image-only:* `media.assets` (V0015) admits owner scopes `TENANT`, `BRAND` and
  `LOCATION`; `MediaAssetService` allows only `image/jpeg`, `image/png`,
  `image/webp` and `image/avif` up to 10 MiB (`MAX_IMAGE_BYTES`) and excludes SVG
  deliberately; `ImageProbe` reads a header from the first 128 KiB and never
  decodes; the rendition set is the closed `DerivativeVariant` enum (`THUMBNAIL`
  w200, `CARD` w400, `DETAIL` w800, all JPEG); `StorefrontMediaController` answers a
  public asset with a 302 to a five-minute presigned URL and takes no `variant`;
  `MalwareScanner` is a port with no adapter; the `media.storage_bytes_included`
  entitlement is metered by `MediaUsageMeterTrigger`. `tenant.brand_media` (V0243)
  holds a brand's logo and its aggregator/QR banner, which is not a home-screen
  banner. *Neighbouring text stores:* `tenant.channel_pages` (V0404) holds four
  fixed slugs (`about`, `contacts`, `delivery-terms`, `privacy-offer`) as
  insert-only, versioned, plain-text bodies the storefront escapes. *Console
  primitives, per the gap map:* `q-localized-field-group` (`X.5`, BUILT),
  `q-tree-view` with drag-reorder (`X.23`, BUILT), `q-rich-text` with its
  sanitizer (`X.31`, BUILT; it emits an HTML string), `q-phone-frame`, and
  `q-media-uploader` (`X.12`, PARTIAL: it exports JPEG at a 960 px long edge and
  refuses a video with `videoNotSupported` before any network call). *Languages:*
  `tenant.brand_locales` (V0242) and `BrandLocaleLookup` exist; ADR 0149 (decided
  2026-10-07) is itself Not started. *Pricing:* `pricing.promotions` (V0093) and
  `PromotionActivated` / `PromotionSuspended` on `pricing.events` exist with no
  consumer. The legacy `ui_elements`, `ui_element_items` and `ui_offers` tables are
  an unresolved `DECIDE` row in `docs/domains/legacy-mapping.md`.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0010, ADR 0016, ADR 0021, ADR 0024, ADR 0025, ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0034, ADR 0035,
  ADR 0036, ADR 0044, ADR 0056, ADR 0057, ADR 0070, ADR 0073, ADR 0101,
  ADR 0112, ADR 0135, ADR 0140, ADR 0149
- Supersedes / Superseded by: — (amends ADR 0044 and edits nothing in it. It
  reopens exactly two things there: the `## Alternatives considered` row "A
  general editorial CMS with tenant-authored HTML" — only its *editorial scope*;
  tenant-authored markup stays rejected in every form — and the closing sentence of
  "Merchandising slots", "Not built, per the parity analysis: static pages, news,
  galleries, recipes, tenders, and job postings", of which this record takes news,
  galleries, recipes and a vacancy *listing* and leaves static pages (row `10.5`,
  built), tenders and applications out. It also moves the slot tables ADR 0044
  sketches under `marketing` into a new `content` module, and it reopens the two
  exclusion rows of `docs/delever-parity-matrix.md` ("Recruiting module: vacancies
  and candidate pipeline" and "General-purpose editorial CMS with tenant-authored
  raw HTML") to the extent Decision 7 states. It does not reopen ADR 0112: that
  record's personalised `presented_offers` become one more source of the home
  carousel later, and ADR 0112's claim that `marketing.offers` backs the storefront's
  `OfferItem` type is narrowed to the personalised part by Decision 5.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **Whether editorial surfaces (news, gallery albums, recipes, vacancies) are
    built at all** (platform owner; gap-map row `6.7a` says "an owner decision is
    required"). Proposed default: specified here, built last (Release 5), off for
    every brand until the platform owner switches an entitlement on for a tenant
    and the tenant switches the kind on for the brand; no tier-2 table is migrated
    before the first request. Blocks: only Release 5.
  - **Whether a vacancy listing is acceptable under the recruitment exclusion**
    (legal counsel, platform owner). Proposed default: a listing only. The platform
    collects no application, no CV, no name and no phone number from a candidate;
    it publishes an outbound contact the tenant typed. A candidate pipeline stays
    excluded. Vacancies are the last editorial kind built, and counsel may veto
    that kind without touching the other three. Blocks: only the vacancy kind.
  - **Which external hosts a link may point at** (platform owner, security).
    Proposed default: a brand may link to the tenant's own verified channel
    hostnames and nothing else; an author with `content.manage` adds a host one at a
    time (no wildcards, `https` only), and each addition is an audited security fact. `t.me`,
    `instagram.com` and similar are not preloaded.
  - **Whether video ships, and its caps** (platform owner, product). Proposed
    default: MP4 with an H.264 video track, at most 12 MiB, 20 seconds and 1920 px on
    the long edge, `moov` before `mdat`, an operator-supplied poster image, played
    muted; behind a feature entitlement `content.video.enabled` that defaults to
    off; at most five live videos per brand. Blocks: only Release 3.
  - **Video egress cost and a CDN** (operations, finance; ADR 0010 left "CDN
    provider" open). Proposed default: video is served through the existing
    presigned redirect from the platform's own object store and metered by the
    existing storage entitlement; the CDN question is not answered here; Release 3
    is revisited when monthly video egress passes 50 GB or ADR 0010's CDN lands,
    whichever is first. The 50 GB figure is a proposed tripwire, not a measurement.
  - **Who may author and who may publish** (platform owner, product). Proposed
    default: `content.read`, `content.author` and `content.publish` to the tenant
    owner, the tenant administrator and the brand manager at brand scope;
    `content.manage` (the host allowlist) to the owner and administrator only; no
    four-eyes approval (the `content.publishing` policy carries no approval field in
    Release 1), because nothing a banner does spends money or sends a message.
  - **Whether machine translation may fill a missing language** (platform owner,
    product; gap-map row `X.5`). Proposed default: no. The `source` of every text is
    `AUTHORED` and the schema admits no other value until the owner picks a provider
    and a review rule.
  - **Whether view counts are wanted and may be client-reported** (product).
    Proposed default: anonymous per-day counters (impressions, clicks, dismissals,
    story completions) reported by the storefront and labelled as client-reported;
    no device, customer or network identifier is stored. Blocks: only Release 2's
    counters.
  - **The default pop-up frequency cap** (product). Proposed default: once per
    device per 24 hours, never more than one pop-up in a session, never before 3
    seconds, never on cart or checkout; enforced on the device and published as a
    hint other storefronts may ignore. Blocks: only Release 2's pop-ups.
  - **How long superseded revisions and their media are kept** (operations, legal
    counsel). Proposed default: revision rows are kept indefinitely (they are small
    text and the audit evidence of what customers were shown); media owned only by
    superseded revisions is released 90 days after the revision was superseded
    (`content.revision_media_retention_days`); republishing an older revision whose
    media was released is refused with a stated reason.
  - **Whether any rule of the market's advertising law must be enforced by the
    platform rather than by the tenant's own terms** (legal counsel). Proposed
    default: none is enforced by the platform; the tenant is the advertiser of
    record; the platform keeps immutable revisions and an audit fact per
    publication as evidence, and a promo card bound to a promotion disappears when
    the promotion is not live.
  - **Whether the brand switcher and the marketplace layout are built** (product).
    Proposed default: not built until one tenant runs two brands on one storefront
    and asks; the `MARKETPLACE` layout value is reserved and refused, and the switcher
    has a sketch below and no migration.
  - **What becomes of the legacy `ui_elements` rows** (platform owner, migration
    program, ADR 0024). Proposed default: the mapping in Decision 13, applied only
    after production is profiled; everything imports as `DRAFT`, nothing is
    auto-published.

**To accept as written:** say "accept 0157". Every open input above is then
closed on its proposed default.

## Context

Four gap-map rows wait on this record, and each says why in its own words.
Row `6.7` ("Content — banners, stories, pop-ups, promotion content cards", NOT
BUILT, XL) says an operator "cannot put anything in front of a customer except
products": no banner with image or video, priority, per-channel placement and active
period; no story group with ordered slides and view counts; no entry pop-up with
frequency capping; no promo card linked to a promotion rule; "so every storefront
home screen shows whatever the app hardcodes". Row `6.8` ("Storefront merchandising",
NOT BUILT, L) says an operator cannot decide what a channel's home screen shows: no
manual or by-category product groups with priority ordering, no offer carousel, no
multi-brand switcher entries, no marketplace layout mode. Row `6.7a` ("editorial
surfaces", NOT BUILT, XL) is "a recorded exclusion, not a gap" that "an owner decision
is required to reinstate", because the parity matrix and ADR 0044 both excluded it.
Row `X.12` (MediaUploader, PARTIAL) says "no video pipeline exists yet".

**The rows' "Blocked by" text is stale in a useful way.** `6.7` blames a
MediaUploader with video, a LocalizedFieldGroup and a SortableList; `6.8` blames
the absence of a SortableList/TreeView; `6.7a` adds a block-based RichTextEditor.
Since those sentences were written the gap map reads `X.5` (LocalizedFieldGroup)
BUILT, `X.23` (SortableList / TreeView with drag-reorder) BUILT and `X.31`
(RichTextEditor, block-based, sanitized) BUILT, and `X.12` PARTIAL. What still blocks
the four rows is therefore three things only: the tables and the decision about their
shape, an owner decision on editorial scope, and a video path. This record decides
all three and adds no design-system component the console does not already have, with
one change to two existing ones (Decision 11).

**What the code says about the customer side.** The platform's storefront contract is
a published OpenAPI group, `storefront`, and ADR 0070 makes a storefront a client of
it, including storefronts nobody here wrote. The first-party storefront once had a
promo banner carousel and a "populars" rail, both assembled by the legacy backend
(`ui_elements`, `ui_element_items` and `ui_offers`; the legacy profile records
`order_button_action` as one of `open_items`, `open_category`, `open_cart` and
`open_url`, and that `banner`, the fourth element type, had no reader). Its
`MenuService` resolves both to empty on purpose: a "popular" list that is really the
first five products "is a lie the screen tells confidently". So the honest state is
a storefront with a place to put content and nothing to put in it.

**What earlier records already decided, and this one does not reopen.** ADR 0044
says the platform builds "four bounded merchandising placements — home carousel,
story rail, promo grid, entry modal — each a typed record with localised strings,
one media reference, a closed union of link targets, a schedule, and an optional
audience", with "no tenant-authored markup", an external link validated against a
host allowlist because "a link target is an open redirect out of the storefront",
and translation assistance that a human accepts. ADR 0010 says business tables
reference a media asset id and never a URL, buckets stay private, and a client's
claim is not evidence. ADR 0149 says the languages a brand authors are the languages
of its `tenant.brand_locales`, that a bare `uz` is never stored, and that "required"
means the brand's own locales. ADR 0070 says the storefront group is additive within
a major version. ADR 0112 owns *personalised* offers selected per guest with a
decision log and a contact policy. ADR 0140 owns the promotion rule. ADR 0033 says a
cache is registered, bounded and never on a correctness path.

**Why an amendment is still needed.** Three things are left open by ADR 0044's
sketch, and one by its exclusion.

- *Where.* The sketch puts the slots in `marketing`, a module whose job is to
  "never send a message". A slot is read by an anonymous storefront on its own
  cadence and cache; it has nothing to do with audiences or consent. The platform
  has already split a module off ADR 0044's list once (reviews, ADR 0071) and
  the referral program (ADR 0067); `AGENTS.md` applies the same rule to `recovery` and
  `commercial` — a module declaration when the owning ADR starts, and no capability
  hidden "inside an unrelated aggregate".
- *Draft and live.* The sketch is one table with a `status` column. Edited in place,
  a banner is half-written in front of customers, and nothing records what they were
  shown. The platform's own precedents for text a customer reads are insert-only
  versions (`tenant.channel_pages`, `legal.terms_versions`) and the immutable menu
  publication (ADR 0016).
- *What a slot may carry.* The sketch has no video, no pop-up caps, no story slide,
  no way to keep a promo card honest against its promotion, and no answer to "which
  locales must be filled".
- *The editorial exclusion* conflates two different dangers. The parity matrix
  rejects Delever's static-page editor because it "accepts raw HTML, which is an XSS
  surface pointed at the tenant's own customers", and rejects the recruiting module
  because it "collects name, phone and free-text CVs from anonymous public users".
  Both are properties of an *input format* and of *collecting applications*, not of
  publishing a news post, an album, a recipe or a job listing. A typed document with
  no markup removes the first; publishing a contact and collecting nothing removes the
  second. Whether to publish editorial content *at all* is a product decision the
  owner has not made, which is why Decision 7 makes it a switch.

**The constraint that makes the shape non-obvious.** Everything here is authored by a
tenant and read, unauthenticated and cacheable, by every visitor of that tenant's
storefront and by third-party storefronts, on one rented machine (8 vCPU, 16 GB, one
operator, ADR 0073). Authoring freedom, freshness and operability pull apart. More
freedom (markup, any video format, per-customer targeting) is stored XSS aimed at the
tenant's own customers, a decoder attack surface on the one VM, and an uncacheable
response. More freshness (live edits, instant expiry) is a draft in front of
customers and a sweeper that can fail open. The smallest design that gives merchants
the rows is typed content with explicit publication, expiry computed when read, and
a public read that is the same for everyone.

**Video is the one place a decision needs a table.** The facts are what could be
established from this repository and general knowledge; cost and compatibility on the
actual customer devices are *unmeasured here*.

| Option | Cost to operate on the one VM | Attack surface | Playback in storefront webviews | Billing from Uzbekistan |
|---|---|---|---|---|
| Accept an MP4/H.264 file as uploaded, probe its headers, refuse the rest | None beyond storage and egress | A pure-Java, bounded box parser; the customer's own decoder plays the bytes | H.264 in MP4 is the format every target webview is expected to play; unmeasured per device | None |
| Transcode on the VM (ffmpeg or similar) | CPU and RAM contention with ordering on a stack already budgeted at about 13 GB of 16 GB | A native decoder run on hostile input | Best, because every upload becomes one profile | None |
| A managed transcoding or video-hosting service | Per-minute fees | Third-party script and cookies on the storefront (ADR 0034 processors) | Best | ADR 0073 records international payment as "a real obstacle" |
| Embed a third-party player by link | None | Third-party script, tracking and cookies on every customer | Varies | Not ours |
| Images only | None | None | None | None |

The record chooses the first row, because it is the only one that adds no operated
system and no vendor; it pays in operator friction (a phone that records HEVC by
default is refused with an instruction), which is measurable and is the trigger that
reopens the second row.

## Decision

**Add one `content` module that owns brand-authored storefront content as a single
item model with immutable revisions, in two tiers: tier 1, bounded merchandising items
(banners, promo cards and home product groups first, then stories and pop-ups), and
tier 2, editorial entries (news, gallery albums, recipes, a vacancy listing) that a
brand does not have until the platform owner and the brand both switch them on.
Content is localized per the brand's own languages, scheduled by instants evaluated
when read, written as typed documents and never as markup, and videos are MP4/H.264
or refused. Ship the smallest first release: image banners, promo cards and home
product groups, authored in the console and read by both storefronts.**

1. **One module, one item model.** A new application module `content` (schema
   `content`) owns every table in the Specification. It replaces the
   `marketing.slot_items` sketch of ADR 0044; `marketing` keeps audiences, campaigns,
   suppression, attribution links and the metric projection. `content` depends one way
   on `media.api`, `tenancy.api`, `iam.api` (entitlements through its
   `EntitlementGate`, so no edge to `commercial` and no cycle) and `audit.api`, and
   declares the two small ports it needs from others (a link target lookup
   implemented by `catalog`, and a promotion liveness lookup implemented by `pricing`)
   in its own `api` package, the direction every existing port of this kind points.
   Every kind (`BANNER`, `PROMO_CARD`, `PRODUCT_GROUP`, `STORY_GROUP`, `POPUP`, `NEWS`,
   `GALLERY_ALBUM`, `RECIPE`, `VACANCY`) is a row of `content.items`
   with the same lifecycle, schedule, locale and audit machinery; a kind that needs
   more than that carries it inside its typed document.
2. **Draft, publish, withdraw, archive; revisions are immutable.** An item has at
   most one mutable draft and any number of immutable revisions. *Publish* writes the
   draft as the next revision (insert-only, enforced by grants) and points the item at
   it; *withdraw* removes the live pointer and keeps every revision; *archive* retires
   the item from lists; *republish* clones an older revision into a new draft. The
   stored states are `DRAFT`, `PUBLISHED`, `WITHDRAWN` and `ARCHIVED`. **"Scheduled",
   "live" and "expired" are never stored**: they are computed from the live revision's
   `starts_at` and `ends_at` at the moment of every read, so no sweeper exists that can
   fail and leave a banner up past its end. Publishing with nothing changed is refused.
   Order within a placement is a number on the item, changed by an audited reorder that
   takes effect at once and is not a revision. A platform administrator holds the same
   `content.publish` capability and can withdraw any item as a takedown; there is no
   moderation queue and no automatic review.
3. **Scope is brand and channel; targeting is not personal.** An item belongs to one
   brand and names the channel codes it appears on (empty means every content-capable
   channel): channels of system type `WEB`, `IOS`, `ANDROID` and `TELEGRAM`. A
   `QR_TABLE` channel shows the menu only and `KIOSK`, `AGGREGATOR`, `POS` and
   `CALL_CENTRE` are refused as targets until a decision gives them a surface. There
   is no audience targeting and no per-branch scope in Release 1: both make the read
   differ per visitor or per location, and the public read is deliberately the same
   for everyone (Decision 9). A link is a closed union: `NONE`, `PRODUCT`,
   `CATEGORY`, `CART`, `PROMOTION` or `EXTERNAL`, mapping the four legacy actions plus
   the one the IA adds. An `EXTERNAL` link is `https`, carries no credentials, and its
   host must be on the brand's allowlist at publish time; the storefront opens it in a
   new context with `rel="noopener noreferrer"` and the platform never redirects.
4. **Languages are the brand's languages.** Texts are authored per locale in
   `tenant.brand_locales` and keyed by the BCP 47 tag ADR 0149's registry defines
   (`uz-Latn`, never a bare `uz`; the alias is accepted at the API boundary and never
   stored). Publishing requires text in the brand's **default** locale; other brand
   locales may be empty and the console's completeness indicator says so. A reader
   whose language the item lacks receives the brand default and is told which language
   the text is in; where that too is missing, the registry fallback; never another
   arbitrary language and never an error. A brand may make completeness mandatory with
   the policy `content.publishing` (`requireAllBrandLocales`). No text is machine
   translated; the `source` of every text is `AUTHORED`. A creative whose image
   carries its own words may name a different image per locale.
5. **The home screen is items, and the offer carousel is a placement, not a second
   feature.** Row `6.8`'s "offer carousel" is the `HOME_CAROUSEL` placement that
   banners and promo cards share; "home groups" are items of kind `PRODUCT_GROUP`,
   either *manual* (an ordered list of product ids) or *by category* (a category,
   an ordering and a limit). Group members are product and category ids, and the
   server drops any id absent from the brand's active publication for the channel, so
   the content read and the menu read cannot disagree about existence; availability
   (a stopped dish) stays the menu's business and is not folded into this read. A
   `PROMO_CARD` may bind a promotion: it is then visible only while that promotion is
   live (`ACTIVE` and inside its window), and inherits the promotion's window when its
   own is empty — the IA's "ads cannot drift out of sync with pricing" made a
   property, not a habit. The platform never injects a coupon code into a card; an
   author who wants one shown types it. ADR 0112's personalised `presented_offers`
   will join the same `carousel` array as an authenticated-only source when that
   record is built; this record does not change its tables. **The brand switcher and
   the marketplace layout are not built** until a tenant runs two brands on one
   storefront and asks; `home_layout = MARKETPLACE` is reserved and refused.
6. **Editorial text is a typed block document, never markup.** Wherever tier 2 needs a
   body, it is a JSON document of a closed set of block types (paragraph, heading,
   list, quote, image, divider) over plain-text runs with `bold`, `italic` and `href`
   flags, validated by the server on write with unknown keys rejected, sized and
   nested within fixed limits, and rendered by a fixed component per block type. HTML,
   Markdown, SVG, iframes, scripts, `data:` and `javascript:` links, and embedded
   third-party players are not representable and so cannot be stored. The console's
   existing block editor (`q-rich-text`) becomes the authoring surface and emits this
   document instead of an HTML string.
7. **Editorial surfaces are a second tier a brand switches on.** News, gallery
   albums, recipes and a vacancy listing are kinds of the same item, with a slug, a
   display window and a block document. They exist for a brand only when *both* the
   entitlement `content.editorial.enabled` (the platform owner's switch per tenant,
   default off) and the brand's policy `content.editorial` (a tenant's choice of which
   of the four kinds, default none) say yes. A vacancy carries a title, a body, an
   employment type, an optional branch and one outbound contact (a phone number, a
   `t.me` or `https` address on the allowlist, or an e-mail address); the platform
   stores no candidate data and offers no application form, which is what keeps the
   recruitment exclusion standing. Gallery albums hold at most 30 images; there are no
   comments, no reactions and no user-submitted content of any kind.
8. **Video is MP4/H.264 or refused; the platform never transcodes.** The media
   module learns one more verified kind. It accepts `video/mp4` whose own header says
   an `avc1` video track, at most 12 MiB and 20 seconds and 1920 px on the long edge,
   with `moov` before `mdat` inside the first 256 KiB (so the probe is a bounded ranged
   read that never decodes and the file starts playing before it finishes loading).
   Anything else is `REJECTED` with a named code and a console sentence that says how
   to export a compliant file. A poster image is required and is captured in the
   operator's own browser from the operator's own file, so the server needs no frame
   extractor. Storefronts play video muted, inline and without autoplay under reduced
   motion. Video is gated by `content.video.enabled` (default off), counted by the
   existing storage entitlement, and limited to five live videos per brand.
9. **The storefront read is one public document, cacheable for everyone.**
   `GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/content/home` takes the
   channel code and a locale and returns the live items for that brand and channel at
   this instant, in order, with ready-made image rendition URLs. It is additive to the
   `storefront` OpenAPI group (ADR 0057, ADR 0070), carries no personal data, is
   computed per request from two indexed queries, and is protected by a body-digest
   `ETag` and `Cache-Control: public, max-age=60`. **No new cache is registered**;
   a 30-second in-process entry is added only if the measured budget is missed
   (Specification). A failure of this read never blanks the menu: the storefront
   renders without content.
10. **Stories and pop-ups (Release 2) keep the same read and add two honest limits.**
    A story is a group with up to ten slides; a pop-up has a delay, an optional daily
    window and a dismissal rule. Their frequency cap is a hint *enforced on the
    device* and published in the document, because an anonymous browser has no
    identity the server could count against; view counts are anonymous per-day
    counters the storefront reports, shown in the console as client-reported.
11. **The console gets two screens and two changed primitives.** `/marketing/content`
    (row `6.7`) lists, filters, reorders, edits, previews and publishes items;
    `/marketing/storefront` (row `6.8`) edits home groups. The editor reuses
    `q-localized-field-group`, `q-media-uploader`, `q-tree-view` as a flat sortable
    list, `q-date-range-picker`, `q-combobox`, `q-phone-frame` and `q-diff-viewer`; the
    two changes are `q-media-uploader` (a per-use export edge, because it exports at
    960 px today, and video with poster capture in Release 3) and `q-rich-text` (a
    block-document mode in Release 5).
12. **Smallest first, then on demand.** Release 1: `BANNER`, `PROMO_CARD` and
    `PRODUCT_GROUP` for the home carousel and home groups, image-only, brand and
    channel scope, both storefronts, the console. Release 2: stories, pop-ups and
    counters. Release 3: video. Release 4: switcher, marketplace layout and branch
    order, on a tenant's request. Release 5: the editorial tier, on a tenant's request.
    Each release states what it does not do (Rollout).
13. **Legacy storefront content maps in, as drafts, after production is profiled.**
    `ui_elements` of type `offer` become `PROMO_CARD` items in `HOME_CAROUSEL`
    (`order_button_text` becomes the call-to-action label; `open_items`, `open_category`,
    `open_cart` and `open_url` become `PRODUCT`, `CATEGORY`, `CART` and `EXTERNAL`
    respectively, an external host not on the allowlist importing the item with the
    link cleared and a flagged problem); type `popular` becomes a manual
    `PRODUCT_GROUP`; type `banner` (no reader) and type `category` (derived from the
    menu) are retired; `visibility_distance`, read by no query, is dropped. Every row
    imports as `DRAFT` in the brand's default locale; nothing is published by the
    migration. This resolves the `DECIDE` row for `ui_elements`, `ui_element_items` and
    `ui_offers` in `legacy-mapping.md` once the program starts (ADR 0055 defers it).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Build the slots inside `marketing`, as ADR 0044 sketches (`marketing.slot_items`) | `marketing` exists to select, cost and never send; a slot is read by an anonymous storefront on its own cadence and cache and has no consent or audience semantics. Editorial is not marketing at all. The sketch also has no draft and no revision | The `content` module is still a single kind a year after Release 1 and its boundary costs more than it earns |
| Edit live in place with a status flag, no revisions | The smallest thing to build, and what ADR 0044's sketch implies. A banner half-edited is in front of customers, there is no rollback, and nothing records what a customer was shown when a promo claim is disputed | Operators report that the publish step is skipped so often that live edits are wanted; then add a per-placement auto-publish, keeping the revisions |
| Brand-wide immutable publication snapshots, like the menu (ADR 0016) | Gives atomic go-live of a coordinated launch. But items here schedule themselves, a one-banner change would become a publication ceremony, and one unfinished draft would hold every other item back | Merchants ask for coordinated launches the schedule cannot express; add a bundle that groups items' go-live rather than snapshotting the brand |
| One table per kind (banners, stories, pop-ups, news, ...) | Nine copies of draft, revision, window, locale and audit code, and nine places for a rule to drift. Kind-specific facts live in the typed document, which the server validates per kind | Two kinds diverge so far (for instance, relational queries on story slides) that the document is the wrong container for one of them |
| Do nothing for editorial: an `EXTERNAL` link to the tenant's own site or Telegram channel (the status quo of row `6.7a`) | Free, safe, and the right answer for a tenant that already has a site. It fails the tenant that has none and keeps news out of the storefront's own surface. This is the fallback while tier 2 is off, which is why tier 2 is last and off by default | Never as a replacement; it stays the answer for any brand that leaves tier 2 off |
| Embed a hosted CMS behind an allowlisted iframe (ADR 0044's own fallback) | A vendor and a billing relationship per tenant (ADR 0073 records international payment as "a real obstacle"), no connection to brand locales, frames inside Telegram and mobile webviews, and a third party on every customer's page | A tenant already runs a CMS it will not leave, and a link (already supported) is not enough |
| Store sanitized HTML (the output of the existing `q-rich-text`) | Allowlist sanitizers have a long history of bypasses by mutation, and a client-side sanitizer is not evidence about what arrived. Storing markup also ties storage to one renderer. A document of known blocks has nothing to sanitize | A customer-facing need for formatting the block set cannot express appears; then add a block type, never a markup path |
| Store a Markdown subset (as `tenant.channel_pages` does) | Cheap to author, but two parsers (server and every storefront) must agree on every edge case, and links and images in Markdown are the XSS surface the exclusion was about | The block editor proves too heavy for authors and one shared, fuzz-tested Markdown subset exists on both sides |
| Accept raw HTML from the tenant, as Delever does | Stored XSS aimed at the tenant's own customers with a marketing user as the unwitting attacker (ADR 0044's words) | Never |
| Transcode every video on the VM (ffmpeg or similar) | A native decoder run on hostile input, on a machine with about 13 GB of 16 GB already budgeted and one operator; a queue that competes with order-taking for CPU | More than a fifth of video uploads in a month are refused for codec or layout reasons, *and* a separate worker machine or a payable managed service exists |
| Use a managed video service or an embedded third-party player | Per-minute fees nobody can pay from Uzbekistan today (ADR 0073), and third-party script and cookies on customer pages (ADR 0034 processors) | International payment is solved and a processor assessment is written |
| Also accept WebM (VP8/VP9) | A second container, a second probe, and unmeasured support across the webviews customers use; H.264 in MP4 is the one format every target is expected to play | A measured device census shows WebM plays on every target and operators are blocked by MP4 export |
| Images only, no video | The cheapest, and the whole of Release 1 and 2. Row `X.12` and the IA's "image *or* video" banner stay open, and a merchant's phone video of a new dish has nowhere to go | Never as a final answer; Release 3 is behind an entitlement and can stay off |
| Audience-targeted content (ADR 0044's `audience_id`) | Makes the public read differ per visitor, forces customer identity before browse, and makes the response uncacheable at an edge (ADR 0034 allows no personal data cached). ADR 0112 owns personalised offers | ADR 0112's `presented_offers` is built and the carousel still needs a segment filter it cannot express |
| Count pop-up frequency per customer on the server | An anonymous browser has no identity to count; a per-customer counter would need sign-in before the first screen | The storefront gains a durable anonymous visitor identity decided in ADR 0015/0051 terms |
| Machine-translate a missing language at read time | ADR 0149 rules out a silent machine fallback; dish names here are transliterated, not translated, and an automatic rendering is the error a customer screenshots | The owner picks a provider and a review rule (row `X.5`) |
| Reuse `tenant.channel_pages` for editorial | Four fixed slugs, one document per slug per channel, plain-text body, no list, no window, no images, per channel not per brand | The four pages need a window or an image; then move them onto the block document, not the reverse |
| Serve content inside the menu document | One round trip. But the menu is per location, its `ETag` digests live stock, and every stop would re-send every banner; third-party storefronts want to fetch the two independently | The measured cost of the second request exceeds the first-paint budget at the 75th percentile on a slow mobile profile |
| Register an in-process cache for the content read now | The read is two indexed queries over a few dozen rows; ADR 0033 wants a cache registered, bounded and justified | The measured server time exceeds the budget in Specification; then register a 30-second entry invalidated by publish and withdraw |

## Consequences

### Positive

- Four gap-map rows (`6.7`, `6.7a`, `6.8`, the video half of `X.12`) stop waiting on a
  decision, and each release closes something an operator can see.
- Both storefronts, and any third-party storefront on the published contract, get the
  same home content from one additive endpoint instead of a hard-coded tile and two
  `offer: null` stubs.
- What customers were shown is evidence: revisions are insert-only by grant, and every
  publication is an audited fact.
- A promo card cannot outlive its promotion, and a banner cannot outlive its window,
  because both are decided when the response is built.
- A typed block document closes the stored-XSS surface by construction rather than by
  a sanitizer that has to keep up, and the existing editor already thinks in blocks.
- The public read has no personal data, so it is safe for the CDN edge ADR 0034 and
  ADR 0010 anticipate.
- Video adds no operated system and no vendor.

### Negative

- A new module, seven tables over the releases, a public unauthenticated read
  surface to rate-limit and scrape-proof, and two new client renderers to keep in step
  with a growing block set. Clients must skip a block type they do not know.
- Publishing is a deliberate step. An operator who saves and forgets to publish sees
  nothing change, and the console has to make unpublished changes obvious.
- Refusing HEVC will annoy operators whose phones record it by default. The refusal
  says how to export a compliant file, and the rejection rate is the measure that
  reopens transcoding.
- Locale-keyed documents drift: a Russian edit does not change the Uzbek text, and
  nothing can say which is right.
- The platform now carries a takedown duty for content tenants write. There is no
  moderation queue; the control is a capability, an audit trail and a withdraw action.
- Tier 2 widens the platform's surface (news, vacancies) for tenants most of whom want
  none of it; the switch and the order of releases are what contain that.

### Accepted trade-offs

- A banner can appear or disappear up to a minute late, because the read is cacheable
  for `max-age=60` and expiry is computed, not pushed.
- A pop-up's frequency cap is advisory: our storefronts honour it, another storefront
  may not.
- Story and banner counts are reported by the client and can be inflated by anyone; they
  are labelled that way and are not a metric the reporting layer (ADR 0043) consumes.
- There is no audience targeting, no branch scope in Release 1, no machine translation,
  no scheduled-publish bundle and no CDN: each has a trigger above.
- Editorial pages are client-rendered storefront routes, not server-rendered pages, so
  search engines see less of them than a CMS would show.
- A withdrawn item stays in browser and edge caches until they revalidate, within the
  minute.

## Specification

### Module and boundaries

`uz.horecaos.platform.content` with `api`, `application`, `domain`,
`infrastructure.persistence` and `web`, declared with
`@ApplicationModule(displayName = "Content")` when Release 1 starts (`AGENTS.md`).
Allowed edges, all one way: `media.api` (`MediaAvailability.allDisplayable`, and a new
narrow `MediaRenditions` read that says which renditions of an asset exist);
`tenancy.api` (`BrandLocaleLookup`, `SalesChannelLookup`, `PolicyResolver`,
`ConfigurationResolver`); `iam.api` (`Capability`, `CurrentActor`, `EntitlementGate`);
`audit.api`. There is no edge to `catalog` or `pricing`: the two ports below are declared
in `content.api` and implemented by those modules, exactly as `catalog.api`'s lookups
are implemented by `inventory` and `pricing`. The two ports `content` declares:

```text
ContentTargetLookup     existing(tenantId, brandId, channelCode, productIds, categoryIds)
                        -> the subset present in the brand's active publication   (catalog)
PromotionLiveLookup     live(tenantId, brandId, promotionIds, at) -> the subset ACTIVE and
                        inside their window at that instant                      (pricing)
```

`ModularArchitectureTests` stays green and nothing outside `content` imports its
internals. No provider adapter is introduced by this record: there is no transcoder,
no scanner and no translator to adapt, and the three are named as *not adopted* in
Alternatives with their triggers, so ADR 0026 and ADR 0007 have nothing to extend.

### Physical model

Every table carries a non-null `tenant_id`, every foreign key names it
(`TenantScopedReferenceCatalogTests`), every instant is `timestamptz`, and each table
ends with an explicit `GRANT` to `horecaos_application`. Row-level security (ADR 0056)
is enrolled schema by schema; `content` joins when that rollout reaches it, and every
query carries an explicit tenant predicate until then. The shape, not a migration:

```text
content.items                      -- identity, lifecycle, order; no DELETE grant
  id uuid PK, tenant_id, brand_id         FK (tenant_id, brand_id) -> tenant.brands
  kind        BANNER | PROMO_CARD | PRODUCT_GROUP                       -- Release 1
              | STORY_GROUP | POPUP                                      -- Release 2
              | NEWS | GALLERY_ALBUM | RECIPE | VACANCY                  -- Release 5
  placement   HOME_CAROUSEL | HOME_GROUPS                                -- Release 1
              | STORY_RAIL | PROMO_GRID | ENTRY_MODAL                    -- Release 2
              | null for editorial kinds
  internal_name varchar(120)              -- staff-facing, never sent to a customer
  display_title varchar(200)              -- the brand-default-locale title, for lists
  slug varchar(80) null                   -- editorial kinds only
  state       DRAFT | PUBLISHED | WITHDRAWN | ARCHIVED
  live_revision_no integer null           -- set exactly when state = PUBLISHED
  sort_key integer, version integer       -- ADR 0031 optimistic concurrency
  created_by, created_at, updated_at
  UNIQUE (id, tenant_id, brand_id)
  CHECK ((kind, placement) is one of the pairs above)
  CHECK ((state = 'PUBLISHED') = (live_revision_no IS NOT NULL))
  UNIQUE (tenant_id, brand_id, kind, slug) WHERE slug IS NOT NULL AND state <> 'ARCHIVED'
  INDEX  (tenant_id, brand_id, placement, sort_key) WHERE state = 'PUBLISHED'
  GRANT SELECT, INSERT, UPDATE

content.item_drafts                -- the one mutable copy; one row per item at most
  item_id PK, tenant_id, brand_id, based_on_revision_no null
  document jsonb, channel_codes varchar(32)[], starts_at, ends_at
  promotion_id uuid null, updated_by, updated_at, version
  GRANT SELECT, INSERT, UPDATE, DELETE

content.item_revisions             -- insert-only; what a customer may have been shown
  item_id, revision_no integer                                  PK (item_id, revision_no)
  tenant_id, brand_id                     FK (item_id, tenant_id, brand_id) -> content.items
  document jsonb NOT NULL                 -- typed, schemaVersion'd, grammar below
  channel_codes varchar(32)[] NOT NULL DEFAULT '{}'             -- empty = every content channel
  starts_at timestamptz null, ends_at timestamptz null
  promotion_id uuid null    FK (promotion_id, tenant_id, brand_id) -> pricing.promotions
  location_id uuid null     FK (tenant_id, brand_id, location_id) -> tenant.locations  -- VACANCY
  content_hash char(64), published_by varchar(255), published_at timestamptz
  CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
  CHECK (octet_length(document::text) <= 262144)
  GRANT SELECT, INSERT                     -- no UPDATE, no DELETE: immutability by grant

content.revision_media             -- which asset each revision uses; one row per use
  item_id, revision_no, tenant_id, media_asset_id
  role PRIMARY | POSTER | SLIDE | AVATAR | BODY_IMAGE | ALBUM_IMAGE, position integer
  PK (item_id, revision_no, media_asset_id, role)
  FK (item_id, revision_no) -> content.item_revisions
  FK (media_asset_id, tenant_id) -> media.assets (asset_id, tenant_id)
  GRANT SELECT, INSERT

content.allowed_hosts              -- the external-link allowlist, per brand
  tenant_id, brand_id, host varchar(253), created_by, created_at   PK (brand_id, host)
  CHECK (host ~ lowercase DNS labels; no wildcard, no port, no userinfo)
  GRANT SELECT, INSERT, DELETE

content.item_daily_stats           -- Release 2; anonymous counters only
  tenant_id, brand_id, item_id, revision_no, day date (tenant timezone), channel_code
  impressions bigint, clicks bigint, dismissals bigint, completions bigint
  PK (item_id, revision_no, day, channel_code); GRANT SELECT, INSERT, UPDATE
```

Release 4, only on its trigger, adds `content.brand_switcher_entries` (`tenant_id`,
`brand_id` showing the switcher, `target_brand_id`, the `channel_id` whose *verified*
hostname the entry links to, `sort_key`, `state`; each entry shows the target
brand's name and its `LOGO` from `tenant.brand_media`) and one nullable
`storefront_sort_order integer` on `tenant.locations`, used as the branch order when
no customer point is known and as the tie-break after distance
(`JdbcStorefrontPickupLocationStore` orders by distance today); that column is the one
asked for by `brands-and-locations.md`'s "Порядок в списке" row, "IA 6.8, no ADR".

The effective allowlist for a brand is its `content.allowed_hosts` rows plus the tenant's
verified `tenant.channel_hostnames`, read through `tenancy.api` and never copied, so an
unverified or released hostname cannot be linked to.

Why a typed `jsonb` document and not a table per fact: the read is the whole document
of a few dozen items, nothing queries inside it, and the kinds differ in shape. What
must be queried or referentially checked is lifted into columns: the window, the
channel set, the promotion, the vacancy branch and, through `revision_media`, every
asset. `media` already refuses to serve an asset that is not `AVAILABLE`, so a deleted
asset's row staying behind (`DELETED`) satisfies the foreign key and cannot be shown.

### The typed documents

Release 1 and 2 documents (`schemaVersion` 1), one per locale inside the revision:

```text
BANNER / PROMO_CARD   texts{locale: {title<=80, subtitle<=160 null, ctaLabel<=24 null,
                      source: AUTHORED, mediaAssetId null}}
                      media{primaryAssetId, aspect 3:1 for BANNER, 3:2 for PROMO_CARD}
                      link{type, targetId null, url null}
PRODUCT_GROUP         texts{locale: {title<=60}}
                      mode MANUAL | BY_CATEGORY
                      members[{type PRODUCT, id}] <=40            (MANUAL)
                      categoryId, order MENU_ORDER | NEWEST, limit 1..40   (BY_CATEGORY)
STORY_GROUP           texts{locale: {title<=40}}, avatarAssetId 1:1
                      slides[<=10]{position, media{kind IMAGE|VIDEO, assetId, posterAssetId},
                      durationSeconds 3..15, texts{locale: {caption<=140}}, link null}
POPUP                 texts{locale: {title<=80, body<=240, ctaLabel<=24}}, media 3:2, link
                      showAfterSeconds 3..60, daily{from, until, daysOfWeek} null,
                      cap{mode ONCE_EVER | ONCE_PER_DAY | EVERY_N_DAYS, n 1..30}
                      dismiss DISMISSIBLE
```

Optional flags are boxed with a stated default, and the console's real JSON with every
optional key omitted is a test: a missing primitive under Jackson 3 is a 400
`MALFORMED_BODY`, a defect this platform has met twice. Unknown keys are rejected.

Release 5 adds `body{locale: BlockDocument}` to `NEWS` and `RECIPE`;
`albumImages[<=30]{assetId, captions{locale}}` to `GALLERY_ALBUM`; and
`vacancy{employmentType FULL_TIME | PART_TIME | SHIFT | INTERNSHIP, apply{type PHONE |
TELEGRAM | URL | EMAIL, value}}` plus the revision's `location_id` to `VACANCY`. The
block document:

```text
BlockDocument  { schemaVersion: 1, blocks: [Block] }          <= 200 blocks
Block          PARAGRAPH{inlines} | HEADING{level 2|3, inlines} | QUOTE{inlines}
               | LIST{ordered: Boolean, items: [[Inline]]}     <= 50 items, one level only
               | IMAGE{assetId, caption: [Inline] null} | DIVIDER
Inline         { text, bold?: Boolean, italic?: Boolean, href?: String }   <= 40 runs a block
```

At most 50,000 characters of text per locale (the figure `ChannelPageService` already
uses). Text is a plain string: C0 controls other than newline, `U+2028`/`U+2029` and
the bidirectional override and isolate controls (`U+202A`-`U+202E`, `U+2066`-`U+2069`,
which spoof text direction) are refused. An `href` is `https` with a host on the
allowlist, or `tel:`, or `mailto:`; `http`, `javascript:`, `data:` and relative links are
refused. Each `IMAGE` asset must be `PUBLIC`, `AVAILABLE` and the tenant's. The
storefront renders one Angular component per block type with text through
interpolation, never `innerHTML` or a trust-bypass call, and sets `href` only after
applying the same scheme check again; a build-time test forbids both in the renderer.

### Publication gates

Publish is one transaction. It refuses, with `422 UNPROCESSABLE_STATE` and `errors[].code`
from this closed list, when: `CONTENT_DEFAULT_LOCALE_MISSING`, `CONTENT_LOCALE_INCOMPLETE`
(only when `requireAllBrandLocales`), `CONTENT_MEDIA_NOT_DISPLAYABLE`,
`CONTENT_MEDIA_ASPECT_MISMATCH` (more than 3% from the placement's ratio; minimum width
960 px for `BANNER`, 600 px for `PROMO_CARD`, 720 px for a story slide, read from
`media.assets.width_px`/`height_px`), `CONTENT_TARGET_NOT_FOUND`,
`CONTENT_PROMOTION_NOT_FOUND`, `CONTENT_LINK_HOST_NOT_ALLOWED`,
`CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE`, `CONTENT_PLACEMENT_FULL` (platform guardrails,
code constants: carousel 10, promo grid 12, story groups 20, home groups 8, pop-ups 1,
non-archived items per brand 500), `CONTENT_NOTHING_TO_PUBLISH` (the document hash
equals the live revision's), `CONTENT_VIDEO_NOT_ENABLED`, `CONTENT_VIDEO_LIMIT`,
`CONTENT_POSTER_REQUIRED`, `CONTENT_EDITORIAL_NOT_ENABLED` and
`CONTENT_REVISION_MEDIA_RELEASED`. The same checks run, as warnings, on every draft
save and feed the console's per-item problem list.

### Staff API (surface `operations`, ADR 0031)

All under `/api/v1/tenants/{tenantId}/brands/{brandId}/content`, brand scope, camelCase,
cursor pagination, `Idempotency-Key` on every `POST`, `If-Match` with the item's `ETag`
on every change (`STALE_VERSION` on a mismatch):

| Request | Capability | Notes |
|---|---|---|
| `GET /items?kind=&placement=&state=&visibility=&cursor=&limit=` | `content.read` | `visibility` is computed: `LIVE`, `SCHEDULED`, `EXPIRED`; each row carries `problems[]` |
| `POST /items` | `content.author` | Creates the item and its first draft |
| `GET /items/{itemId}` | `content.read` | Item, live revision, draft, `ETag` |
| `PUT /items/{itemId}/draft` | `content.author` | Replaces the draft; validates the grammar, warns on the gates |
| `DELETE /items/{itemId}/draft` | `content.author` | Discards the draft; the item and its revisions remain |
| `POST /items/{itemId}/publications` | `content.publish` | The gates above; writes the revision and moves the live pointer |
| `POST /items/{itemId}/withdrawals` | `content.publish` | Live pointer cleared; revisions kept |
| `POST /items/{itemId}/archives` | `content.publish` | Withdraws first if live |
| `GET /items/{itemId}/revisions`, `GET .../revisions/{n}` | `content.read` | Immutable history |
| `POST /items/{itemId}/revisions/{n}/republications` | `content.author` | Clones revision `n` into the draft; publish is separate |
| `PUT /placements/{placement}/order` | `content.publish` | Body: ordered ids and an order token; `STALE_VERSION` if the order moved |
| `GET /preview?channel=&locale=&asOf=` | `content.read` | The storefront document built from drafts, by the same assembler |
| `GET /health` | `content.read` | Live items with an unresolved target, a promotion not live, missing locales |
| `GET /allowed-hosts`, `POST /allowed-hosts`, `DELETE /allowed-hosts/{host}` | `content.read`, `content.manage` | Audited as security facts |
| `GET /items/{itemId}/stats` | `content.read` | Release 2 |

### Storefront API (surface `storefront`, additive to the published contract)

| Request | Notes |
|---|---|
| `GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/content/home?channel=&locale=` | Anonymous. `channel` is the tenant's channel code (as the menu read takes it); `locale` is a BCP 47 tag, a bare `uz` read as `uz-Latn`. Unknown brand or tenant mismatch is `404` |
| `POST .../content/events` | Release 2. Anonymous counters; a batch of at most 20 `{itemId, revisionNo, event}`; rate-limited; never stores an identifier |
| `GET .../content/features` | Release 5. Which editorial kinds are on for this brand |
| `GET .../content/news`, `.../news/{slug}`, `.../gallery`, `.../gallery/{slug}`, `.../recipes`, `.../recipes/{slug}`, `.../careers`, `.../careers/{slug}` | Release 5; cursor pagination, newest first, `ETag` by body digest |

The response of the first request (Release 1), the same document every visitor of that
brand, channel and language receives:

```text
{ "locale": "uz-Latn",
  "carousel": [ { "id": "...", "kind": "BANNER" | "PROMO_CARD", "title": "...",
                  "subtitle": null, "ctaLabel": null, "textLocale": "ru",
                  "media": { "kind": "IMAGE", "alt": "...", "widthPx": 1600, "heightPx": 533,
                             "renditions": [ { "widthPx": 800, "url": "/api/v1/storefront/tenants/{t}/media/{asset}?variant=w800" } ] },
                  "link": { "type": "CATEGORY", "targetId": "..." } | { "type": "NONE" } } ],
  "groups":   [ { "id": "...", "title": "...", "textLocale": "uz-Latn",
                  "productIds": ["..."], "categoryId": null } ] }
```

There is no timestamp in the body, so the digest `ETag` moves only when content does.
Release 2 adds `stories`, `promoGrid` and `popup`; Release 3 adds `"kind": "VIDEO"` with
`posterUrl`. Clients skip an unknown field, block or kind.

How the document is built, once, by one assembler with two sources (`LIVE`, and
`DRAFT_OVERLAY` for the preview, so preview cannot drift from production): select the
brand's `PUBLISHED` items joined to their live revisions where the channel set is empty
or holds the channel, `starts_at <= now`, `now < ends_at` (start inclusive, end
exclusive, from the injected `Clock`); for promo cards bound to a promotion, ask
`PromotionLiveLookup` and drop the card if it is not live; resolve link targets with
`ContentTargetLookup` and degrade an unresolved `PRODUCT`/`CATEGORY` link to `NONE`
while keeping the item (the console's `health` flags it); resolve each text through
brand locale, brand default, registry fallback; attach only the renditions `MediaRenditions` says exist;
order by placement, `sort_key`, `created_at`, `id`. A fixture test gives every item an
explicit, distinct `sort_key` and timestamp so ordering never depends on insertion
time (repeated `now()` fixtures tie locally and reorder under CI load).

Media: the storefront media redirect gains an optional `variant` parameter (additive),
and the closed rendition set gains one member, `HERO` (`w1600`), because a 3:1 banner
on a desktop channel needs more than `DETAIL`'s 800 px. The read model returns
rendition URLs, never the original, so a 10 MiB source cannot reach a phone's home
screen. `q-media-uploader` exports 960 px on the long edge today; it gains an input for
the export edge (1600 for `BANNER`).

### Capabilities and roles (ADR 0025)

Four code-owned capabilities, all at brand scope: `content.read`, `content.author`,
`content.publish`, `content.manage`. Bundles: tenant owner, tenant administrator and
brand manager hold the first three; owner and administrator also hold `content.manage`;
no location role holds any. A platform administrator already holds every capability.
`EndpointCapabilityDeclarationTests` and the role-bundle tests enforce the table; the
storefront endpoints declare none, like the menu.

### Entitlements, policy and configuration keys (ADR 0021, ADR 0030)

| Key | Kind | Default | Meaning |
|---|---|---|---|
| `content.editorial.enabled` | entitlement, feature | off | The platform owner's switch for tier 2, per tenant |
| `content.video.enabled` | entitlement, feature | off | Video upload and playback |
| `content.editorial` | policy document v1, brand | `{news,gallery,recipes,vacancies}` all `false` | The tenant's choice of kinds, effective only with the entitlement |
| `content.publishing` | policy document v1, brand | `{requireAllBrandLocales: false}` | Tightening only |
| `content.revision_media_retention_days` | configuration, integer | 90 | Release of superseded revisions' media |
| `content.video.max_live_per_brand` | configuration, integer | 5 | Live videos |

Each configuration key is declared in `content.api` and mirrored in the tenancy
registry, with the drift test `OrderingConfigurationKeys` documents. Both policies
publish through ADR 0030's author, which audits and evicts its own cache. The
`content.publishing` document has no `requireApproval` field in Release 1: a field that
parses and does nothing is worse than no field, so a four-eyes path (ADR 0027's approval
service, `content.publish` as the approving capability, a different principal as
approver) is added with the first tenant that asks for it.

### Events (ADR 0032)

One new topic, `content.events` (3 partitions, replication factor 1, retention
`PT168H`, `delete`, producing module `content`, classification `INTERNAL`, key
`itemId`), added to `KafkaTopicCatalog`, `docs/domains/events.md` and
`src/main/resources/events/content.events/`:

| Event | Payload (version 1) |
|---|---|
| `ContentItemPublished` | `itemId`, `brandId`, `kind`, `placement`, `revisionNo`, `startsAt`, `endsAt`, `channelCodes` |
| `ContentItemWithdrawn` | `itemId`, `brandId`, `kind`, `reason` (`WITHDRAWN`, `ARCHIVED`) |

Written to the outbox in the same `BEFORE_COMMIT` transaction as the change and its
audit fact; never text, never a URL. No consumer is specified (the same restraint
`PromotionActivated` carries); the read model is computed, not event-fed, and
`PromotionActivated`/`PromotionSuspended` are deliberately *not* consumed, because the
liveness lookup is one indexed query (`ix_promotions_active`).

### Audit (ADR 0027)

Facts, class and target: `content.item.published`, `content.item.withdrawn`,
`content.item.archived`, `content.placement.reordered` (`BUSINESS`, target
`content.item`); `content.host.allowed`, `content.host.removed` (`SECURITY`, because the
allowlist is the open-redirect control). The change document names the revision, the
locales, the window, the channel codes and the promotion; **the text stays out of the
history**, as `channel.page.published` does, because the revision row already holds it
and an audit row is the wrong place for a second copy.

### Personal data (ADR 0029)

The `content` schema holds no customer data. Published content is `PUBLIC` by the act of
publication and a draft is `INTERNAL`; `created_by` and `published_by` are staff
subjects. A vacancy's contact is a business contact the tenant chose to publish, the
field help says so, and the platform stores no applicant-side data of any kind, so no new
retention class exists. The anonymous counters carry no device, customer, address or
network identifier. `ClassificationScanner` and `EventPayloadClassificationTests` pass
with no classified field. Nothing in `content` joins `customer.*`.

### Media and video (ADR 0010, ADR 0135)

Release 3 changes `media` as follows. `MediaAssetService` admits `video/mp4` with its own
cap (12 MiB) beside the image cap (10 MiB); `UploadRequest`'s `@Max` becomes per type.
A `VideoProbe` (pure Java, bounded, never decodes) reads the first 256 KiB with
`readPrefix` and refuses by named code:

| Property | Accepted | Rejection code |
|---|---|---|
| Container | MP4; `ftyp` major brand `isom`, `iso2`, `mp41`, `mp42`, `avc1` or `M4V ` | `VIDEO_TYPE_NOT_ALLOWED` |
| Video track | one, sample entry `avc1` (H.264); HEVC, VP9, AV1 refused | `VIDEO_CODEC_NOT_ACCEPTED` |
| Other tracks | at most one audio track, `mp4a`; no subtitle or data track | `VIDEO_MALFORMED` |
| Layout | `moov` before `mdat`, inside the first 256 KiB | `VIDEO_NOT_FASTSTART` |
| Duration | at most 20 s, from `mvhd` | `VIDEO_TOO_LONG` |
| Size | at most 12 MiB | `VIDEO_TOO_LARGE` |
| Dimensions | long edge at most 1920, short edge at least 480, from `tkhd` | `VIDEO_DIMENSIONS` |
| Boxes | nesting at most 6, at most 2,000 boxes, no box larger than the bytes remaining | `VIDEO_MALFORMED` |

A nullable `duration_ms` is added to `media.assets`; `MediaAssetAvailable` gains an
optional `durationMs` (additive within its version). The derivative job written when an
asset becomes `AVAILABLE` is not written for video. Playback uses the existing storefront
redirect with a video-specific presign window of 30 minutes, because a browser plays by
repeated range requests against the final URL and the five-minute image window can
expire mid-play; the redirect itself stays cached for five minutes. The object is stored
with `video/mp4` and `nosniff`. The console captures the poster from the operator's own
file in the operator's own browser and uploads it as an image. A file that is a valid MP4
can still carry a hostile stream; the customer's decoder is the exposure, the same as any
tenant-uploaded video, reduced by H.264-in-MP4 only and an authenticated uploader. The
`MalwareScanner` port stays without an adapter, as ADR 0010 left it.

A superseded revision's media is released (`DELETION_REQUESTED`, then the existing
`MediaAssetDeletionWorker`) by a `ContentRevisionMediaSweeper` once it has been
superseded for `content.revision_media_retention_days` and no live or draft revision uses
the asset; the check is a query over `content.revision_media`.

### Caching and performance (ADR 0033)

`Cache-Control: public, max-age=60` and a body-digest `ETag` (the digest approach
`StorefrontCatalogController` uses), `304` on a matching `If-None-Match`. The read is two
queries (items with live revisions; one media-rendition lookup) and a bounded in-memory
assembly. **Proposed budget, unmeasured:** server time under 50 ms at the 95th percentile
at 50 requests a second on the reference machine, response under 64 KiB. If it is missed,
register one `CacheRegistry` entry (30 seconds, bounded by brand and channel and locale,
invalidated in-process by publish, withdraw, archive and reorder). The read is added to
the `@storefront_browse` path list in both Caddyfiles
(`deploy/infra/caddy/Caddyfile`, `platform/infra/production/caddy/Caddyfile`), which sets
its per-IP limit; `check_storefront_api_routing` must still pass.

### Front ends

*Operations.* Routes `/marketing/content` and `/marketing/storefront` replace
`NotBuiltPage` and load lazily; nothing joins the initial chunk. The list shows a
derived pill (`LIVE`, `SCHEDULED`, `EXPIRED`, `DRAFT`, `WITHDRAWN`), problem badges, an
"unpublished changes" marker and a flat drag-reorder of the placement
(`q-tree-view` with reparenting off). The editor: language tabs with the completeness dot
(`q-localized-field-group`), the uploader with the placement's ratio, a link picker
(`q-combobox` over products, categories and live promotions, or an allowlisted URL), a
schedule (`q-date-range-picker`), a channel chip set, a `q-phone-frame` preview from the
preview endpoint, revision history with `q-diff-viewer`, and publish, withdraw and archive
behind `q-confirm-dialog`. Home groups: manual member search or a category with an
order and a limit. All strings typed in ru, uz-Latn and en.

*Storefronts* (`frontend/storefront`, `frontend/storefront-milliy`). A `ContentService`
reads the document once per session start and on revalidation; the carousel is
scroll-snap with `srcset` from the renditions; groups join their ids against the menu the
page already holds and skip an id the menu lacks; a failed read renders no content and
leaves the menu untouched. The hard-coded `home-promo.jpg` tile leaves the template when
the carousel lands. Video is `muted playsinline preload="metadata"` with the poster, and
poster only under `prefers-reduced-motion`. Pop-up caps are kept in `localStorage` under a
key of item and revision (every read and write in `try/catch`, the page correct without
it), so editing the item resets the cap.

### Observability

Metrics carry no tenant and no text: `content.read` by outcome, `content.publish` by kind
and outcome, `content.publish.refused` by reason code, `content.video.probe` by outcome
and rejection code, `content.events.accepted`. A log line names an item id and a code,
never a title. The health read above is the operator's view of the same problems.

### Testing

- Migration and catalog tests: every table granted, every foreign key tenant-scoped,
  `item_revisions` refuses `UPDATE` and `DELETE` for the application role.
- Assembler tests against a real PostgreSQL with a controlled `Clock`: window start
  inclusive and end exclusive, channel scope, empty means all, promotion not live, an
  unresolved link degrading, locale fallback order, rendition filtering, ordering with
  explicit timestamps.
- Gate tests, one per code in the list above; two concurrent publishes, exactly one
  winner and a `409`; republish after media release.
- Document tests: unknown keys, nesting and size limits, every refused `href` scheme, a
  bidi control, a `<script>` string rendered as text in the storefront renderer, and a
  corpus run asserting the renderer creates no element or attribute outside the block set.
- Video probe tests over fixtures: a valid faststart H.264 file, `moov` at the end, HEVC,
  a truncated file, a box whose size lies, a nesting bomb, a zero dimension, a huge
  duration; the probe never reads past 256 KiB.
- Contract tests: the OpenAPI baseline is additive (`make openapi-baseline` reviewed),
  `EndpointCapabilityDeclarationTests`, event schema, catalogue and classification tests.
- Front end: console specs for the editor, reorder and preview; storefront specs for
  render, skip-unknown, failure isolation and the pop-up cap with storage unavailable.

## Rollout and rollback

Order is the roadmap's, not this record's: nothing here blocks the pilot's payments or
onboarding rows, and the releases are independent after the first.

- **Release 1 — home content, smallest (rows `6.7` banners and promo cards, `6.8` home
  groups and the offer carousel).** The module, `content.items`, `item_drafts`,
  `item_revisions`, `revision_media`, `allowed_hosts`; `BANNER`, `PROMO_CARD` and
  `PRODUCT_GROUP`; image only; brand and channel scope; the four capabilities; the
  publish gates; the events and audit facts; the `HERO` rendition and the storefront
  `variant` parameter; the storefront read; both storefronts' home; the two console
  screens. *Does not:* stories, pop-ups, counters, video, location scope, editorial,
  switcher, marketplace layout. Ships dark: with no item published the storefront
  document is empty and the home looks as it does today. *Rollback:* withdraw items;
  the storefront renders nothing for an empty document; the schema is additive and
  stays. A bad release is reverted by redeploying the previous image; no existing
  table changes.
- **Release 2 — stories and pop-ups, with counters (rest of `6.7`).** `STORY_GROUP`,
  `POPUP`, `PROMO_GRID`, the daily window and the cap hint, `item_daily_stats` and the
  events endpoint. Images only. *Rollback:* withdraw the kinds; the storefront ignores
  unknown fields; the counters endpoint is turned off at the edge.
- **Release 3 — video (row `X.12`'s video half; `6.7`'s "image or video").** The probe,
  the video cap, `duration_ms`, the 30-minute presign window, the poster flow, the
  uploader's video mode, `content.video.enabled`, the revision-media sweeper. Behind the
  entitlement, off for every tenant until the platform owner turns it on. *Rollback:*
  turn the entitlement off; live videos stop being returned (the assembler drops a video
  item when the entitlement is off), uploads of video are refused, nothing is deleted.
- **Release 4 — brand switcher, marketplace layout, branch order (rest of `6.8`).**
  Built only when a tenant runs two brands on one storefront and asks. *Rollback:* drop
  the entries; `home_layout` stays `RESTAURANT`.
- **Release 5 — editorial tier (`6.7a`).** `NEWS`, `GALLERY_ALBUM`, `RECIPE`, then
  `VACANCY` last, behind `content.editorial.enabled` and the `content.editorial` policy;
  the block document and the editor's block mode; the storefront routes. Built only on
  the owner's instruction or the first tenant's request. *Rollback:* turn the policy off;
  the kinds disappear from the storefront and the console, and the rows stay.

Whatever the release, a tenant that never publishes sees no change, and a withdrawal
reaches customers within one cache window. The legacy import (Decision 13) is a
separate, later program step and is rehearsed against the profiled production data
before it is run.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Release 1: `content` module declaration; migration for `items`, `item_drafts`,
      `item_revisions`, `revision_media`, `allowed_hosts` with grants and the
      tenant-scoped foreign keys; `ModularArchitectureTests` and
      `TenantScopedReferenceCatalogTests` green.
- [ ] `content.api` ports (`ContentTargetLookup`, `PromotionLiveLookup`) with their
      `catalog` and `pricing` implementations; `media.api` `MediaRenditions`.
- [ ] Capabilities `content.read`, `content.author`, `content.publish`, `content.manage`
      in `Capability` and the role bundles; `EndpointCapabilityDeclarationTests`.
- [ ] Authoring, publication, reorder, preview and health services and controllers;
      the publish gates and their error codes; the typed documents with strict decoding
      and the omitted-optional-flag test.
- [ ] The assembler (one implementation, two sources) and the storefront read; the
      `variant` parameter on the storefront media redirect; the `HERO` rendition.
- [ ] `content.events` topic, two event types, schemas, catalogue and
      `docs/domains/events.md` rows; audit facts; entitlement and policy keys declared in
      both registries with the drift test.
- [ ] Both Caddyfiles' `@storefront_browse` list; `check_storefront_api_routing` passes;
      the OpenAPI baseline and generated clients refreshed and reviewed.
- [ ] Operations `/marketing/content` and `/marketing/storefront`; `q-media-uploader`
      export-edge input; ru, uz-Latn and en strings; initial-bundle budget unchanged.
- [ ] Both storefronts: `ContentService`, carousel, groups, failure isolation; the
      hard-coded promo tile removed.
- [ ] Release 2: story and pop-up documents, the device-local cap, counters and the
      events endpoint, the stats view.
- [ ] Release 3: `VideoProbe` and its fixtures, `video/mp4` in `MediaAssetService`,
      `duration_ms`, the derivative job skip, the presign window, poster capture,
      `content.video.enabled`, the sweeper.
- [ ] Release 4 (on request): switcher entries, reserved `MARKETPLACE` refused,
      `storefront_sort_order`.
- [ ] Release 5 (on request): the block document and its renderer test corpus,
      `q-rich-text` block mode, the four kinds, the storefront routes, `content.editorial`.
- [ ] Update `docs/operations-gap-map.md` rows `6.7`, `6.7a`, `6.8` and `X.12` against the
      code as each release lands (never from a wave report), the IA's struck-through `6.7`
      sub-features, and the `ui_elements` row of `docs/domains/legacy-mapping.md`.
- [ ] ADR 0044's status line and `marketing` module descriptor note that slots moved here.
- [ ] Add `content` to ADR 0056's RLS enrolment list when that rollout reaches it.

## Exit criteria

An operator with `content.publish` uploads a 3:1 image, writes a title in the brand's
default language, links it to a category, schedules it for a week and publishes it; both
storefronts show it in the home carousel on the next revalidation, in the customer's
language where it exists and the brand default where it does not, from a rendition and
not the original; withdrawing it removes it within a minute. A promo card bound to a
promotion disappears when the promotion is suspended. A "Popular" group of hand-picked
dishes shows on the home screen and silently skips a dish the menu no longer holds. An
older revision can be read and republished. No publish writes text into the audit
history, `UPDATE` on `item_revisions` is refused, and a request to publish an `EXTERNAL`
link to a host not on the allowlist is refused. For Release 3: an MP4 of the stated
shape plays muted on both storefronts, and an HEVC file, a file with `moov` at the end and
a 13 MiB file are each refused with the named code and a sentence that says how to fix
it. For Release 5: with the entitlement and the policy on, a news post written with the
block editor renders on the storefront with no element outside the block set, and with
either off, no editorial route exists.

## References

- ADR 0007, ADR 0010 (media lifecycle, CDN origin not built), ADR 0016 (immutable
  publication), ADR 0021 (entitlements, storage meter), ADR 0024 and ADR 0055
  (legacy migration, greenfield scope), ADR 0025, ADR 0026, ADR 0027, ADR 0029,
  ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0034 (processors, CDN edge), ADR 0035,
  ADR 0036 (channels), ADR 0044 (placements, the editorial exclusion), ADR 0056,
  ADR 0057, ADR 0070 (storefront as a client of a published contract), ADR 0073 (the
  one machine, international payment), ADR 0101, ADR 0112 (personalised offers),
  ADR 0135, ADR 0140 (promotions), ADR 0149 (brand languages)
- [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md),
  [ADR 0010](../partial/0010-s3-media-lifecycle-and-filesystem-migration.md),
  [ADR 0112](../not-started/0112-campaigns-offers-and-contact-policy.md),
  [ADR 0149](../not-started/0149-languages-beyond-ru-uz-latn-and-en.md),
  [ADR 0070](../not-started/0070-a-storefront-is-a-client-of-a-published-contract.md)
- `platform/docs/operations-gap-map.md` rows `6.7`, `6.7a`, `6.8`, `X.5`, `X.12`,
  `X.23`, `X.31`, `10.5`
- `platform/docs/frontend-information-architecture.md` §6 rows `6.7` and `6.8`, PART 4
  (LocalizedFieldGroup, MediaUploader, SortableList / TreeView, RichTextEditor)
- `platform/docs/delever-parity-matrix.md` (the recruiting and editorial-CMS exclusions;
  the banner, story, pop-up, promotion-card and home-page rows)
- `platform/docs/operations-spec/brands-and-locations.md` ("Порядок в списке")
- `platform/docs/domains/legacy-profile-findings.md` §11 and
  `platform/docs/domains/legacy-mapping.md` (`ui_elements`, `ui_element_items`, `ui_offers`)
- `V0015` (`media.assets`), `V0093` (`pricing.promotions`), `V0242`
  (`tenant.brand_locales`), `V0243` (`tenant.brand_media`), `V0404`
  (`tenant.channel_pages`), `V0451` (`catalog.channel_media_overrides`)
- `MediaAssetService`, `ImageProbe`, `DerivativeVariant`, `StorefrontMediaController`,
  `MalwareScanner`, `MediaUsageMeterTrigger`, `StorefrontCatalogController`,
  `ChannelPageService`, `JdbcStorefrontPickupLocationStore`, `OrderingConfigurationKeys`
- `frontend/storefront/src/app/services/menu.service.ts`,
  `frontend/storefront/src/app/pages/home/home.component.html`,
  `frontend/storefront-milliy/src/app/services/menu.service.ts`,
  `frontend/operations/src/app/app.routes.ts`,
  `frontend/operations/src/app/shared/ui/` (`localized-field-group`, `media-uploader`,
  `rich-text`, `rich-text-sanitizer`, `tree-view`, `phone-frame`)
