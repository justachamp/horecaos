# ADR 0149: Languages beyond ru, uz-Latn and en

- Decision status: Accepted
- Implementation status: Not started — the platform speaks three languages and
  records that fact in more than sixty independent declarations, counted by grep on
  the integrated tree (wave 17) on 2026-10-03, before counting the catalogues
  themselves. None of them is wrong; no one is the source of the others. The first
  draft of this record counted twenty-seven on 2026-10-01; that count missed
  declarations that already existed and the ones that arrived with the staff member
  record (V0453), and its account of where the spelling splits was wrong. Both are
  corrected here.
  *Backend, fourteen lists, enums and switches:* `BrandProfile.KNOWN_LOCALES`
  (`tenancy.domain`, "the closed set the console can author content in today"),
  `TenantLocaleSet.PLATFORM_LOCALES` (`tenancy.api`), the enums
  `notifications.domain.MessageLocale`, `legal.domain.TermsLocale` and
  `tenancy.domain.channel.ChannelPageLocale` (each declared locally because "none of
  those types live in an `api` package another module may depend on"),
  `OrderOutcomeReasonService.REQUIRED_LOCALES`,
  `marketing.domain.AudiencePredicate.SUPPORTED_LOCALES`,
  `ReferenceDataController.LOCALES` (the list the control plane is already served),
  the Telegram bot's `TelegramBotMessages` and `TelegramUpdateHandler`, and four sets
  spelled with a bare `uz`: `OwnerInvitationService.LOCALES`,
  `StaffInvitationService.LOCALES`, `PasswordResetService.LOCALES` and
  `StaffMembers.UI_LOCALES`. *Four SQL orderings:* `CASE t.locale WHEN 'ru' …` in
  `JdbcCatalogStore`, two of them also knowing a bare `uz`. *Fourteen closed
  `CHECK`s on a locale column:* nine spelling the set `('ru', 'uz-Latn', 'en')`
  (V0026 twice, V0029, V0043, V0119, V0160, V0244, V0404, V0423) and five spelling it
  with a bare `uz`: `('uz', 'ru', 'en')` in V0210, V0213, V0215 and V0313 (the owner
  invitation, its event log, the password reset and the staff invitation) and
  `('ru', 'uz', 'en')` in V0453 (`ck_staff_member_locale`, the staff member's
  interface language). *Clients:* the operations and control-plane catalogues
  (`messages.ru.ts`, `messages.uz-latn.ts`, `messages.en.ts`, typed so a missing key
  fails the build), the two storefronts' `ru.json` / `uz.json` / `en.json`, the mobile
  app's `app_ru.arb` / `app_uz.arb` / `app_en.arb`, at least nineteen hand-kept
  lists and union types (operations ten lists and four unions, among them
  `i18n.ts`, `brand-profile-page.ts`, `locations-api.ts`, `template-editor.ts`,
  `notifications-page.ts`, `audience-predicates.ts`, `STAFF_LANGUAGES` and
  `OFFERED_LANGUAGES`; control plane three lists and two unions) and sixteen
  one-line conversions or comparisons between `uz` and `uz-Latn` in the two consoles.
  *Where the spelling splits.* A bare `uz` is the stored value in the five
  constrained columns above; in `customer.customer_accounts.preferred_locale`
  (V0017, free text), which the storefront's language screen fills with the id of the
  language it offers (`uz`, `ru`, `en`: `profile-language.ts`, and
  `StorefrontCustomerSurfaceTests` asserts that `uz` reads back); and, unconstrained,
  in the catalog's own vocabulary:
  `catalog.translations.locale` (V0016, free text), the `names` keys inside the
  published snapshots in `catalog.publication_items.immutable_content_json`
  (V0016 revokes `UPDATE` and `DELETE` on that table from the application role),
  `horecaos.catalog.default-locale` (default `uz`) and the storefront's
  `?locale=uz`. `CatalogLocales` and the console's `toCatalogLocale` translate that
  vocabulary to and from the platform's `uz-Latn`. Two things that look like the same
  split are not: `iam.staff_members.spoken_languages` holds ISO 639 language codes
  (`StaffMemberService.languagesOf` accepts only `[a-z]{2,3}`), where `uz` is right,
  and the payment page language in `PresentationRequest` is Payme's own vocabulary.
  The customer column is more than an inconsistency: the marketing metrics refresh
  copies it verbatim into `marketing.customer_metrics.preferred_locale`, whose V0043
  constraint admits `uz-Latn` and not `uz`, so a customer who picks Uzbek in the
  storefront profile hands that statement a value its target refuses. The metrics test
  seeds `uz-Latn`, so no test meets the two together.
  Already open: `tenant.brand_locales` (V0242: a brand's own supported set and
  default, subset of the known list), `tenant.location_content` (V0401),
  `catalog.comment_preset_translations`, `fulfillment.region_translations` and
  `fulfillment.service_zone_translations` (V0430, V0431) and
  `ordering.order_line_comment_preset_labels` (V0433), the last four with a BCP 47
  shape check and no closed set — "so a fourth language does not need a migration" —
  `TenantLocaleSet.union`, and a brand editor that can hold a set. Not built: any
  language beyond the three, a typeface for Georgian (and a checked one for Kazakh), a
  `dir` decision (the operations `index.html` ships `lang="ru"` and no `dir`), and a
  single place that says which languages exist.
- Date proposed: 2026-10-01
- Date decided: 2026-10-07
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0020, ADR 0035, ADR 0044, ADR 0090, ADR 0101, ADR 0146
- Supersedes / Superseded by: — (does not reopen ADR 0020's rule that a template
  needs wordings before it can be activated; changes which locales that rule counts)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written. **No language is activated by this record.**
  - **Which market comes first, and when** (platform owner, business). ADR 0090
    declares Kazakhstan and Georgia as markets the platform serves and notes that
    "no tenant trades there yet". Proposed default: none is scheduled; activation
    waits for a signed tenant, which is ADR 0090's own trigger.
  - **Whether Karakalpak (`kaa`) or Uzbek in Cyrillic (`uz-Cyrl`) belong on the
    list before Kazakh or Georgian** (product). They are inside the country the
    platform already serves. Proposed default: the registry has room for both
    (script subtags are first-class) and neither is declared.
  - **Whether the staff console must exist in a new language, or customer-facing
    content and messages suffice** (product). Proposed default: content and
    messages first; a staff catalogue is a separate, later activation, and Russian
    stays the staff default.
  - **The typefaces** (design). `@ibm/plex-sans` as bundled covers Latin, Latin
    Extended and Cyrillic and, per the operations README, neither Kazakh's extra
    Cyrillic letters nor Georgian. Proposed default: a glyph audit of the bundled
    face against Kazakh's alphabet decides whether Kazakh needs a second face;
    Georgian gets an openly licensed face chosen at activation, loaded only when
    the locale is active.
  - **Who translates and who reviews** (business). Proposed default: people, never
    a silent machine fallback; the auto-translate affordance (row `X.5`) is a
    separate, confirm-before-save feature.
  - **Whether a market needs a different SMS gateway** (integration, ADR 0146).
    Proposed default: yes until shown otherwise; the language is the cheap half of
    entering a market.

**To accept as written:** say "accept 0149". Every open input above is then
closed on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "lets finish all" over every record still Proposed on this date. Every open input above is closed on the default this record proposes for it; an input that names a person other than the owner, or an external fact (a licence term, a provider capability, a tax treatment, an account that does not exist yet), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation of what this record decides and has not yet built starts in operations batch 19 and 20 (2026-10-07).

## Context

Gap-map row `10.12` ("Languages and regional formats") is `PARTIAL`; row `X.40`
("a `dir`/script-safe type stack: Uzbek Latin, Cyrillic Russian, Latin English,
plus Kazakh and Georgian on the roadmap") is `PARTIAL` with the sentence "adding
kk or ka still means a font and layout change, not just a message file." Both
rows have shipped everything that needed no decision. What remains is a decision
about what the platform means by *a language*, and the operations README (the
section that prices a fourth locale) has already counted the cost honestly: "None
of it is a frontend-only change, which is why it stays a roadmap line and not a
task."

**The platform is further along than the list above suggests.** V0242 stores a
brand's own language set and default and says in a comment that "there is no
platform-wide locale registry yet, and the closed set is enforced in the domain
layer so it can grow without a migration once one exists." V0430 and V0431 went
the other way from the nine older constraints: a BCP 47 shape check
(`^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`) and the supported set enforced by the
console, with a comment that names `kaa` — Karakalpak — as its example. Batch 14
and 15 moved the readers a customer sees onto the brand default. So the direction
is set: the closed set belongs in code and in the brand's choice, not in the
schema. What is missing is the one place in code, and the answer to four questions
that a fourth language forces.

**Question one: what does "required" mean?** `MessageLocale.required()` is every
platform locale, and ADR 0020 refuses to activate a template version until all of
them have a wording. With a fourth language the rule becomes absurd: every tenant's
active templates are instantly incomplete, and a tenant that does not serve
Kazakhstan must author Kazakh to send an order confirmation. The rule was a good
one while "the platform's languages" and "this brand's languages" were the same
list. They no longer are: `tenant.brand_locales` exists.

**Question two: is there a spelling?** `uz` and `uz-Latn` are both stored. The five
tables for owner and staff invitations, password reset and a staff member's interface
language say `uz` (V0210, V0213, V0215, V0313, V0453), and so does a customer's
preferred language once the storefront has written it. So does the catalog, in a way
no constraint shows: `catalog.translations` is free text whose established Uzbek code
is `uz` (`CatalogLocales` says so in its own Javadoc), and the published menu
snapshots carry `uz` keys inside `immutable_content_json`, a table the application
role may not update. The tables that follow ADR 0035 (`tenant.brand_locales`, the
per-locale preset, region and zone tables, the nine closed `CHECK`s) say `uz-Latn`,
and so, mostly, do the clients that cross the API, except where a one-line conversion
turns it into `uz` and back: `CatalogLocales` and the console's `toCatalogLocale` for
the catalog, `OnboardingInputs.locale` for invitations, a conversion or a comparison
in each of sixteen places in the consoles. `JdbcCatalogStore` has a second `CASE` that
handles both; the storefront's catalogue file is `uz.json` and the mobile app's is
`app_uz.arb`, with a comment explaining why the script subtag is "load-bearing" in
code and absent from the file name. Uzbek is written in two scripts; a tag that does
not say which is a defect waiting for the first Cyrillic Uzbek tenant. That defect is
not the same size everywhere. In an invitation row a bare `uz` is a stray spelling
that one rewrite removes. In the catalog it is the key of data that is published once
and never edited, so the record has to decide, not tidy.

**Question three: what is a language's blast radius?** A language touches four
tiers, and a tenant may need only some of them:

| Tier | What it covers | Where it lives |
|---|---|---|
| Content | Tenant-authored data (dish and zone names, terms, presets) and the customer-facing storefront catalogue | `tenant.brand_locales`; the `*_translations` tables; storefront and mobile catalogues |
| Messages | Notification wordings, the Telegram bot, owner and staff emails, SMS | `notifications.template_versions`; `TelegramBotMessages`; the mail module |
| Staff UI | The operations and control-plane catalogues | `messages.*.ts`, typed per key |
| Script and layout | A face with the right glyphs, a direction, plural and number rules | `styles.css`, `index.html`, `Intl` |

A Kazakh restaurant on the platform needs content and messages first; its owner
may be perfectly happy with a Russian console. Treating "add a language" as one
all-or-nothing project is what keeps it a roadmap line.

**Question four: is direction a decision or a default?** Kazakh (Cyrillic) and
Georgian (Mkhedruli) are left-to-right, as are the existing three, so no bidirectional
work is needed for any language this record contemplates. But nothing today *says*
left-to-right: no `dir` attribute, and a stylesheet free to use `left` and `right`.
That is fine until a right-to-left language is requested and the cost is a full
audit. The cheap move is to say it now and write new CSS in logical properties.

**What this record does not do.** It does not make a language a tenant-configurable
free-for-all (the console can only author what it has a catalogue for), it does not
add a machine-translation fallback, and it does not treat language as the gate to a
new market: payments, fiscal receipts, an SMS gateway and tax treatment are each
larger than the translation, and ADR 0090 is explicit that a tenant's country records
the market only and leaves currency and timezone alone.

## Decision

**Make "a language" one registry entry with a lifecycle per tier; build the
registry, remove the duplicated lists and the closed `CHECK`s, change "all locales
required" to "all of this brand's locales required", and activate no language until
a tenant needs one.**

1. **One code-owned registry.** A single class in `tenancy.api` — `PlatformLocales`
   — replaces the enums, constants and `CASE` expressions listed in the status line.
   An entry carries: the BCP 47 tag (script-tagged where a language has two
   scripts: `uz-Latn`, never bare `uz`; `kk` and `ka` have one script each), its
   name in itself and in each active language, the script, the direction (`LTR`
   for every entry), the fallback rank, the face identifier, and the tiers in which
   it is active (`CONTENT`, `MESSAGES`, `STAFF_UI`). `ru`, `uz-Latn` and `en` are
   active in all three. `kk` and `ka` are declared with no tier active, which makes
   the cost of each visible in one place and costs nothing at runtime.

   Where a store predates the registry and cannot be rewritten, the entry also names
   the code that store holds, so the spelling lives in one place and not in a
   ternary. There is exactly one: `uz-Latn` carries `catalogCode` `uz`, the code
   `catalog.translations`, the published snapshots, `horecaos.catalog.default-locale`
   and the storefront's `?locale=` already use (Decision 3 says why it is not
   migrated). `CatalogLocales` and the consoles' `toCatalogLocale` become calls on the
   registry. A request that still sends a bare `uz` where a tag is expected (an
   invitation, a password reset, a staff profile) is read as an alias of `uz-Latn` at
   the API boundary for as long as old clients exist, and is never stored.

2. **Activation is per tier and each tier has a gate.**
   - *Content:* the storefront catalogue and the mobile catalogue contain the
     language, and the brand lists it in `tenant.brand_locales`.
   - *Messages:* every template key the brand uses has a wording in it
     (Decision 4), the Telegram bot and the transactional emails are translated,
     any SMS wording has cleared ADR 0091's gate, and the segment estimate has been
     checked for the script (`SmsSegments` already derives the encoding from the
     text, and Kazakh and Georgian both fall to UCS-2).
   - *Staff UI:* the console catalogue is complete (the type system already enforces
     it), the face is bundled and the glyph audit has passed.
   Activating a tier is a release, because it ships a catalogue and sometimes a
   face. A tenant then *chooses* it by adding it to its brands' supported set. A
   tenant never makes a language exist.

3. **The schema stops hard-coding the set, and the spelling is settled store by
   store.** One forward migration replaces the fourteen closed locale `CHECK`s with
   V0430's BCP 47 shape check, recreated in full per the repository's rule on CHECKs.
   Which tags are valid is enforced where it is decided: the registry for platform
   vocabularies and the brand's supported set for tenant content. Three groups of
   stores hold a bare `uz` today, and each is treated on its own terms.
   - *Migrated.* The same migration, before it recreates their constraints, rewrites
     `uz` to `uz-Latn` in the six columns that hold it: the owner invitation, its
     event log, the password reset, the staff invitation, `iam.staff_members.ui_locale`
     and `customer.customer_accounts.preferred_locale`. The three invitation and reset
     services, the mail module and `StaffMembers.UI_LOCALES` read the registry. The API
     still accepts a bare `uz` on input (Decision 1) and answers with the tag, so a
     client that has not been rebuilt keeps working, among them a storefront that
     still sends `uz` as a customer's language; the console's `own-profile.ts` mapping
     and its staff-profile conversions go, and the storefront sends the tag.
   - *Kept, as the registry's one named alias.* `catalog.translations` and the
     published menu snapshots keep `uz`, now declared as `uz-Latn`'s `catalogCode`.
     Migrating them is not a rewrite of one table. `catalog.publication_items` is
     "written once and never edited": V0016 revokes `UPDATE` and `DELETE` on it from
     the application role, and a publication's identity is its `content_hash` ("two
     publications with the same hash are the same menu"). That hash is computed over
     the snapshot's content with its map keys sorted
     (`CatalogPublicationService.contentHashOf`), so rewriting the `names` keys inside
     the snapshots would change the hash of every live menu and every one a rollback
     can return to. Rewriting `catalog.translations` alone would leave a brand's
     authoring rows saying `uz-Latn` and every published copy of the same menu saying
     `uz`, which is the split moved, not ended, and would make `previewDraft` report
     every brand's draft as different from what is live until it was republished. The catalog already reads
     through one mapping (`CatalogLocales`, and `CatalogNameLocales` for the readers in
     ordering and pricing), so folding that mapping into the registry removes the
     ternaries without touching a published byte. The cost is that `uz` stays, by
     name, in one place, and a new brand's first publication still writes it.
   - *Not locales.* `iam.staff_members.spoken_languages` holds ISO 639 language codes
     (`[a-z]{2,3}`, enforced by `StaffMemberService.languagesOf`), where a person who
     speaks Uzbek speaks it in either script and `uz` is correct. The payment page
     language in `PresentationRequest` is Payme's published vocabulary. The SMS text
     in `VerificationCodeText` keys on the language subtag alone. None is migrated,
     and the migration test names each so a later reader does not "fix" them.

   The marketing copy of the customer's language (V0043) is one of the fourteen
   constraints, so after the migration the two columns agree.

4. **"Required" means the brand's locales.** The rule ADR 0020 states — a template
   version must have a wording in every locale before it can be activated — now
   counts the locales of the template's brand (`tenant.brand_locales`, read through
   `TenantLocaleSet`), not the platform's list. A brand with no configured set
   counts the platform triple, exactly as the editors already fall back. A
   tenant-scoped vocabulary (outcome and reject reasons, payment-method names) counts
   the union of its brands' sets, as `TenantLocaleSet` already decides. A customer
   whose preferred language the brand does not support receives the brand default,
   and where that is missing, the registry fallback (`ru`). Nothing already active
   becomes invalid: every existing brand either has the triple or has chosen a
   subset it already satisfies.

5. **The clients read the list instead of mirroring it.** A read returns the
   registry's entries and their tiers (the control plane is already served a list,
   `ReferenceDataController.LOCALES`; this generalises it); the brand editor, the
   location editor, the audience predicate and the rest of the nineteen hand-kept
   lists stop carrying their own `['ru', 'uz-Latn', 'en']` or `['ru', 'uz', 'en']`,
   and the sixteen `uz-Latn` conversions go, except the catalog's, which become a
   call on the registry's `catalogCode`. A client's
   *catalogues* stay compile-time and typed (the build failing on a missing key is
   the point), and a client offers a language when its build contains a catalogue and
   the registry says the tier is active. Catalogue file names follow the registry's
   tags (`uz-Latn.json` beside `uz.json` during the move; the mobile arrangement the
   code comment already explains stays).

6. **Direction is stated and cheap to keep.** Every entry says `LTR`; `<html dir>`
   is set from it next to `lang`, which the operations shell already sets from the
   stored locale; new and touched CSS uses logical properties (`margin-inline-start`
   and its siblings). No right-to-left language is registered, and registering one
   needs its own record because it reaches order lines, receipts, fiscal documents
   and SMS.

7. **Faces are per script and load with the locale.** The bundled face stays
   bundled (the console must paint its first frame on a restaurant's connection,
   and `styles.css` says why). A script outside Latin and Cyrillic gets its own
   `@font-face` with a `unicode-range`, loaded only when that locale is active, so a
   Russian-speaking cashier never downloads Mkhedruli. Georgian's face is chosen at
   its activation; Kazakh's depends on the glyph audit.

8. **Plurals and numbers go through one mechanism.** Number, date and money
   formatting uses `Intl` with the registry tag. Message catalogues keep avoiding
   plural-bearing sentences (today's `{n} мин` style); the first language that
   genuinely needs ICU plural rules in a sentence adopts one library in one place,
   not hand-written branches.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Add Kazakh and Georgian everywhere now | No tenant in either market; two more catalogues of roughly four hundred kilobytes each to translate and keep in parity, a face, and half-translated screens in the meantime. The registry makes the cost explicit without paying it | The first tenant in either market signs |
| Do nothing until a tenant signs | Defers the cost but not the debt: fourteen constraints, a dozen lists and a spelling split that a first activation would have to fix under time pressure, and the template rule would break every active template on the day a fourth language is added | Never as the whole plan; activation does wait for demand |
| A `reference.locales` table with a foreign key from every locale column | Data-driven, but a language carries a font and a catalogue, which are code. V0242 already chose domain-layer enforcement, V0430 a shape check, and the platform keeps its other closed vocabularies in code (`Capability`, `PlatformRole`, `DevicePrincipalClass`) | Tenants must be able to add a language without a release |
| Free tenant-defined locales with fallback to the default | The translation tables already allow it for data, but the console, templates and SMS cannot render what they have no catalogue for, and an unreviewed language on a till is worse than a missing one | A translation-management product exists |
| Angular's `$localize` with one bundle per locale | Compiles a deployment per language; a shared terminal changes language mid-shift (the operations README rejects it for this reason) | Never |
| Bare `uz` as the tag | Five tables use it today; but Uzbek has two scripts and the mobile code records exactly why the subtag is load-bearing | Never |
| Migrate `catalog.translations` and the published snapshots to `uz-Latn` as well | A snapshot is written once and its identity is a hash over its keys: rewriting it changes the hash of every live and rollback-able menu, and rewriting only the authoring rows leaves one brand's menu in two spellings and makes every draft read as changed (Decision 3) | A re-publication of every brand is acceptable, or the publication model stops hashing keys |
| Right-to-left support now | No language in view needs it; it is an audit of every screen, document and SMS | A market needing Arabic or Persian script |
| Machine translation as the fallback for missing wordings | Unreviewed text on an order confirmation or a kitchen screen | A human-confirmed feature (row `X.5`), not a fallback |
| Keep "all platform locales required" and require authoring in every one | Forces a Georgian wording from a Tashkent restaurant; the rule exists to prevent a customer getting nothing, which a brand-scoped rule prevents just as well | Never |

## Consequences

### Positive

- A language becomes one entry and a checklist, and the cost of each is visible
  before anyone signs for it.
- The `uz` / `uz-Latn` split, a real inconsistency in five tables, the customer
  accounts and sixteen conversions in the consoles today, ends everywhere but the catalog's one named
  `catalogCode`.
- A brand serving two languages stops being forced to author three, and a brand
  serving four does not invalidate its neighbours' templates.
- At least nineteen hand-kept frontend lists of one backend list disappear.

### Negative

- A migration that recreates fourteen constraints across big append-only tables,
  and a data rewrite of `uz` in six columns, is a risk that buys nothing a customer
  sees. It must be rehearsed on a restored copy.
- A bare `uz` stays in the catalog for good, as a named alias, so a reader of
  `catalog.translations` still meets it. The registry says why and where; it does
  not remove it.
- Moving "required" from the platform to the brand shifts a guarantee. The old rule
  made it impossible to activate a template that left an Uzbek-reading customer
  with no wording; the new one makes it impossible only for the languages a brand
  has said it serves, and a customer whose preferred language the brand does not
  offer gets the brand default instead of their own.
- Per-tier activation gives three ways for a language to be half-live, and the
  registry can say what is live but cannot make a catalogue good.
- A registry in `tenancy.api` is a dependency every module that holds a locale now
  takes, replacing more than a dozen local copies that were each deliberately
  independent.

### Accepted trade-offs

- A new language remains a release. A tenant cannot add one by configuration.
- Staff may use a console in a language their customers do not see and the reverse;
  the tiers are independent on purpose.
- Direction is a statement and a CSS habit, not a tested guarantee, until a
  right-to-left language is real.

## Specification

### The registry

```text
PlatformLocale
  tag               "ru" | "uz-Latn" | "en" | "kk" | "ka" | …      BCP 47, script-tagged where ambiguous
  catalogCode       "uz" for uz-Latn, else = tag                    what catalog.translations and the snapshots hold
  inputAliases      "uz" for uz-Latn                                accepted at the API boundary, never stored
  script            CYRL | LATN | GEOR
  direction         LTR
  fallbackRank      int      replaces the SQL CASE
  face              identifier of the bundled or lazy face
  tiers             set of CONTENT | MESSAGES | STAFF_UI           empty = declared, not live
  names             map tag -> display name                          for pickers
PlatformLocales.all() / active(tier) / byTag(tag) / parse(input) / byCatalogCode(code) / fallback()
```

`GET /api/v1/{surface}/locales` returns the entries and tiers, readable by any
authenticated principal and by the storefront session. It is a read of code, not of
a table.

### Migration (forward only; numbers reserved by the wave that builds it)

One migration that, for each of the fourteen closed constraints, drops it and re-adds it
as `locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'` (with the `IS NULL OR` form where the
original had one), and `UPDATE`s `uz` to `uz-Latn` in the owner invitation, its event
log, the password reset, the staff invitation, `iam.staff_members.ui_locale` and
`customer.customer_accounts.preferred_locale` **before** the constraints are
recreated, and widens the five `varchar(8)` columns among them from
`varchar(8)` to the `varchar(16)` every other locale column uses (the customer's is
already that wide), because a shape check
that admits `kaa-Latn-KZ` is no use over a column that cannot hold it. It does not touch
`catalog.translations`, `catalog.publication_items` or `iam.staff_members.spoken_languages`
(Decision 3). The migration test enumerates every `ck_*_locale` constraint by name and
fails if one remains on a closed list. The build re-greps for a fifteenth: the count
above is a date-stamped fact, and the first draft of this record was already one
constraint short when it was written.

### Behaviour changes

- `NotificationTemplateService` counts `TenantLocaleSet` for the template's brand
  where it counted `MessageLocale.required()`; activation of a version fails with the
  list of the brand's missing locales.
- `OrderOutcomeReasonService`, `AudiencePredicate`, `CampaignTelegramDeliveryService`,
  `TermsLocale`, `ChannelPageLocale`, `ReferenceDataController`, `JdbcCatalogStore`'s
  ordering, the three invitation and reset services, `StaffMembers.UI_LOCALES`
  (whose message "uiLocale must be one of ru, uz, en" becomes the registry's list)
  and the mail module read `PlatformLocales`. `CatalogLocales` and
  `OnboardingInputs.locale` become calls on it.
- `BrandProfile` validates against `PlatformLocales.active(CONTENT)`.

### Frontend

`i18n.ts` and the other mirrors read the registry response; `own-profile.ts` and the
staff-profile editors stop converting `uz`; `<html dir>` is set beside
`lang`; a stylelint rule flags new physical `left`/`right` offsets and fixed
`margin-left`/`margin-right` in touched files only. The catalogue key-parity specs
(operations, control plane, both storefronts, mobile ARB parity) are the activation
gate for the staff and content tiers.

### Testing

- Registry: every entry has a script, a direction and a face; `uz` is not a tag (it
  parses as an input alias of `uz-Latn` and is stored only as that entry's
  `catalogCode`); the fallback is registered; no module declares a locale list of its own
  (an architecture test greps for the literal triple outside the registry, as
  `ChangeDocumentUsageTests` does for flat audit calls).
- Template rule: a brand with `ru, uz-Latn` activates a two-wording version; a brand
  with `ru, uz-Latn, kk` is refused with `kk` named; the other brand's active
  versions are untouched; a customer with an unsupported preference gets the default.
- Migration: each constraint accepts `kk` and rejects `Russian`; the six columns that
  held `uz` (the four invitation and reset tables, `iam.staff_members.ui_locale` and
  `customer.customer_accounts.preferred_locale`) hold only `uz-Latn` afterwards; **no row in any other table holds a bare `uz` in a
  locale column** — the test finds the columns by name (`%locale%`) and by the
  translation tables, so a store added after this record is caught too — with three
  named exemptions it asserts instead of skipping: `catalog.translations` and
  `catalog.publication_items` (the catalog's `catalogCode`) and
  `iam.staff_members.spoken_languages` (ISO 639 language codes). A staff member saved
  with `uiLocale` `uz` through the API reads back `uz-Latn`; a customer who picks Uzbek
  in the storefront profile reads back `uz-Latn`, and the marketing metrics refresh
  succeeds for that customer (seen failing first). The mail module sends the
  owner invitation in each of its three languages.
- Frontend: the brand editor lists exactly the registry's `CONTENT` entries;
  `dir` follows the active locale; a declared-but-inactive language is not offered.

## Rollout and rollback

Registry and readers first, with the three entries and identical behaviour — the
architecture test is what proves nothing was missed. Then the template rule, which
changes behaviour only for a brand whose set is smaller than the triple. Then the
migration, rehearsed on a restored copy. Then the frontend mirrors. Activation of a
real language is a separate change per language and tier, started by a tenant.
Rollback of the registry is a revert; the migration is not reversible in place and
its forward rollback is a re-add of the closed `CHECK`s, which is why it is rehearsed
and why it is the last step that touches data.

## Implementation checklist

- [ ] Owner accepts the record; no language is activated by it.
- [ ] `PlatformLocales` and `GET …/locales`; the architecture test against literal
      lists.
- [ ] Replace `BrandProfile.KNOWN_LOCALES`, `TenantLocaleSet.PLATFORM_LOCALES`,
      `MessageLocale`, `TermsLocale`, `ChannelPageLocale`, `REQUIRED_LOCALES`,
      `SUPPORTED_LOCALES`, `ReferenceDataController.LOCALES`, the bot's sets, the
      three invitation and reset `LOCALES`, `StaffMembers.UI_LOCALES` and the four
      `JdbcCatalogStore` `CASE`s; fold `CatalogLocales` into the registry's
      `catalogCode`.
- [ ] Brand-scoped "required" in `NotificationTemplateService` and its tests, each
      seen failing first.
- [ ] The migration and its by-name test; the `uz` data rewrite in six columns; the
      no-bare-`uz`-anywhere-else scan with its three named exemptions; the mail module
      on the registry.
- [ ] Frontend mirrors removed (the nineteen lists and the sixteen conversions, bar the
      catalog's); `dir` set; the logical-properties lint on touched files.
- [ ] Per-language activation checklist written into `frontend/operations/README.md`
      beside the section that prices a fourth locale, which this record replaces.
- [ ] Glyph audit of the bundled face against Kazakh; a Georgian face chosen with its
      licence (at activation, not now).
- [ ] Update `settings.md` §10.12 and the IA's `X.40` line.

## Exit criteria

There is one place in code that says which languages exist, and one command that
finds no other list, and no row anywhere holds a bare `uz` in a locale column except
the catalog's, which the registry names. A brand can serve two languages and activate templates in
exactly those. Declaring `kk` active in the registry, adding a Kazakh catalogue and
a brand's supported set makes that brand's storefront, notifications and (if the
tier is active) console speak Kazakh, with no migration and no change to any module
other than its catalogue; and a Russian-speaking cashier's console downloads no
Georgian face.

## References

- ADR 0020, ADR 0035, ADR 0044, ADR 0090 (countries served), ADR 0091, ADR 0101,
  ADR 0146
- `platform/docs/operations-gap-map.md` rows `10.12`, `X.40`, `X.5`
- `platform/docs/frontend-information-architecture.md` PART 4 (the type stack),
  `platform/docs/operations-spec/settings.md` §10.12
- `frontend/operations/README.md` (Localisation: what a fourth locale costs),
  `frontend/operations/src/styles.css`, `src/index.html`, `src/app/core/i18n/`
- `BrandProfile`, `TenantLocaleSet`, `MessageLocale`, `TermsLocale`,
  `OrderOutcomeReasonService`, `AudiencePredicate`, `NotificationTemplateService`,
  `JdbcCatalogStore`, `SmsSegments`, `CatalogLocales`, `CatalogNameLocales`,
  `CatalogPublicationService.contentHashOf`, `ChannelPageLocale`,
  `ReferenceDataController`, `StaffMembers`, `StaffMemberService.languagesOf`,
  `OwnerInvitationService`, `StaffInvitationService`, `PasswordResetService`,
  `OnboardingInputs`; `V0016` (`catalog.translations`, the `REVOKE` on
  `catalog.publication_items`), `V0026`, `V0029`, `V0043`, `V0119`, `V0160`,
  `V0210`, `V0213`, `V0215`, `V0242`, `V0244`, `V0313`, `V0401`, `V0404`, `V0423`,
  `V0430`, `V0431`, `V0433`, `V0453`; `mobile/lib/src/l10n/supported_locales.dart`;
  `frontend/operations/src/app/features/catalog/catalog-domain.ts`
  (`toCatalogLocale`), `core/auth/own-profile.ts`
