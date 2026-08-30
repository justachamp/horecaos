# Operations spec — Staff and access

**Section 9 of [the operations information architecture](../frontend-information-architecture.md).**
The restaurant's own people, what each of them may do, and the record of what
they did.

Audience: whoever builds these screens. Everything here is sourced from the
Delever parity matrix, Delever's own documentation, the legacy HorecaOS dashboard,
and the built backend. Where a source is thin or wrong, this document says so
and decides.

---

## 0. The one thing to get right first

HorecaOS's authorization model is built and is good. It is also unspeakable.

`iam.grants` binds a *principal subject* to a *role* at a *resource scope*, and
a role is a set of *capabilities* like `order.cancel` and `catalog.publish`.
That vocabulary is correct for the codebase and unusable at a restaurant. A
manager in Chilonzor does not think "grant `location-manager` at
`ResourceScope.LOCATION`". She thinks "Aziza runs the Chilonzor branch."

**Nowhere in this section does the word capability, scope, grant, principal or
role appear on screen.** The translation is fixed and total:

| Model (code, API, audit) | On screen (RU / UZ) | Definition given to the user |
|---|---|---|
| `PlatformRole` | **Должность** / **Lavozim** — "job" | A named job. Everyone doing that job can do the same things. |
| `Capability` | **что можно делать** / **nima qilish mumkin** | One plain sentence per line: "Отменить заказ" / "Buyurtmani bekor qilish". Never a dotted code. |
| `ResourceScope` | **Где работает** / **Qayerda ishlaydi** | Вся компания · Бренд *Milliy* · Филиал *Чиланзар* |
| `iam.grants` row | **назначение** / **tayinlash** | A sentence: «Азиза — Управляющий филиалом в Чиланзаре, с 12.03» |
| scope covers downward | — | "Права даются вниз: должность на всю компанию работает в каждом филиале; должность в одном филиале работает только там." |
| `capability_registry_snapshot` | never shown | — |

The whole model reduces to one sentence a manager can repeat: **a person has one
or more jobs, and each job is given somewhere — the whole company, one brand, or
one branch. A job given at one branch works only at that branch.**

That sentence is printed, verbatim, as the empty state of the People screen and
as the helper text on the Invite form. It is the only place the rule is
explained, and it is explained in the place where the user is about to need it.

The scope rule's second half — *never sideways, never up* — is never explained,
only demonstrated: a Chilonzor branch manager opening People sees Chilonzor's
team and nothing else, and the branch picker on the Invite form contains one
option. A rule you cannot violate needs no paragraph.

### Corollary: the granter can only give away what they hold

`GrantManagementService.requireGrantable` refuses a job whose capability set is
not a subset of the granter's own, at the target scope. This is enforced
server-side and must be **prevented in the UI, never surfaced as an error.** On
the Invite form and the Change-job dialog, jobs the signed-in user cannot confer
are absent from the picker, with one line under it:

> Здесь только те должности, которые вы можете назначить сами.

Not disabled options with a tooltip. Absent. A manager offered `Владелец` and
told she may not use it learns only that the software is taunting her.

---

## 1. Screen inventory

| # | View | Shape | Tier | Used |
|---|---|---|---|---|
| 9.1 | **Люди** — staff list | List, with a branch pivot | Pilot | Weekly, sitting down |
| 9.2 | **Карточка сотрудника** — person record | Master-detail, 4 tabs | Pilot | Weekly |
| 9.3 | **Пригласить** — invite | Form (create-modal, 560px) | Pilot | Monthly |
| 9.4 | **Должности** — the job library | List → read-only detail | Pilot | Rarely, but decisive |
| 9.5 | **Проверка доступа** — access check | Single-question form | 3 | When something is wrong |
| 9.6 | **Смены** — staff shifts and attendance | Week grid + day list | 2 | Daily, standing |
| 9.7 | **Терминалы** — shared devices and PINs | List → device record | 2 | Daily, standing |
| 9.8 | **Журнал действий** — activity log | List → event detail | 2 | After an incident |
| 9.9 | **Мой профиль** — self-service | Form, two sections | 2 | Rarely |

9.1–9.4 are the pilot. They are what makes it possible to hand the console to a
second person. 9.6 and 9.7 are the only ones used during service; they are
designed for standing use with large targets, and everything else in this
section is designed for a desk.

Approvals (IA 9.4) is deliberately **not** in this spec. A maker-checker queue
over refunds, discretionary discounts and PII exports is a finance and support
workflow that happens to be backed by `audit.approval_requests`; putting it
under Staff because both involve people is a filing error. Section 9.8 links to
it from any audit event carrying an `approval_request_id`.

---

## 2. Люди — the staff list (9.1)

### What it is for

Who works here, what each of them may do, and who has access they should not.

### Layout

A list, not a board and not cards. The question is always about one row against
its neighbours — who is missing, who has too much — and a table is the only
shape that answers a comparison across twenty rows at a glance. The Togora
prototype's card-per-person moderation layout is wrong here: it consumes four
times the vertical space to carry less information.

The list is a **pivot, not a set of screens.** One control at the top left
switches between:

- **Все** (flat list, default when the tenant has one branch)
- **По филиалам** (grouped by branch, each group a collapsible header carrying
  the branch name and a count; default when the tenant has more than one
  location)

Grouping by branch is per-location assignment made visible, and it removes any
need for a separate "branch team" screen. A person with a company-wide job
appears in a pinned group at the top labelled **Вся компания**, once, not
repeated under every branch. A person with jobs at two branches appears under
both, with the row's job cell showing only the job relevant to that group.

### Columns

| # | Column | Type | Source |
|---|---|---|---|
| 1 | (selection) | checkbox | — |
| 2 | **Сотрудник** | Name over a muted second line carrying the phone in mono | **Not built.** No `display_name`, `phone` or `email` exists for a staff person anywhere. See §11.1 |
| 3 | **Должность** | One or more job chips, each carrying its scope as a suffix: «Управляющий · Чиланзар» | `iam.roles.name` joined through `iam.grants.role_id`; scope label resolved from `grants.scope_type` + `scope_id` against `tenant.brands.display_name` / `tenant.locations.display_name` |
| 4 | **Где работает** | Text: `Вся компания` \| brand name \| branch name; multiple scopes render as «Чиланзар +2» with the rest in the row's detail | `iam.grants.scope_type`, `iam.grants.scope_id` |
| 5 | **Доступ** | Status pill — see §2.4 | Derived: `iam.grants.status`, `valid_until`, and Keycloak `enabled` (**not projected — §11.3**) |
| 6 | **Последний вход** | Relative time («2 ч назад», «12.08»), `—` when never | **Not built.** Keycloak holds it; nothing projects it. §11.6 |
| 7 | **Заказов сегодня** | Integer, right-aligned, mono, `—` for people who do not take orders | **Not built.** `ordering.orders` has no `created_by_actor_id`. §11.5 |

Column 7 earns its place only because it answers the question that actually
brings a manager to this screen — *is this account still being used* — faster
than "last sign-in" does. It is shown only when the tenant has at least one
person holding a job with `order.approve`; otherwise the column is omitted, not
blanked.

Columns 6 and 7 are the first to drop at narrow widths. Columns 2, 3 and 5 never
drop.

### Sort order

Default sort is **by attention, then by name**, not alphabetical and not by
hire date. The weights, applied after filtering, in the Togora §2e manner:

```
0  Доступ отозван, но человек ещё числится   (grants revoked, account enabled)
1  Приостановлен                             (account disabled, grants still active)
2  Доступ заканчивается ≤ 7 дней             (valid_until within 7 days)
3  Ни разу не входил, приглашён > 3 дней     (invited, never signed in)
4  Нет должности                             (a person with zero active grants)
5  всё в порядке                             — then by display name, RU collation
```

Weights 0 and 1 are the two states that mean *the access model and reality have
diverged*, which is the only genuinely urgent thing this screen can tell anyone.
Weight 4 is separated from 0 because "never had a job" and "had one taken away"
are different situations with different fixes.

Alphabetical order is available as an explicit second sort, and is the right
default once a tenant passes roughly forty staff — at which point the manager is
looking someone up, not scanning. Switch the default at that threshold rather
than making the user discover the control.

### Filters — one row, mixed controls, counts inside the control

Following the Togora §2b pattern: a single wrapping flex row, a 1px vertical
divider separating axes, counts computed **before** filtering so they do not
collapse as the selection narrows.

1. **Status pills** (primary axis, dark fill when active):
   `Все (24)` · `Активные (19)` · `Приостановлены (2)` · `Приглашены (2)` ·
   `Без должности (1)`
2. — divider —
3. **Филиал** — dropdown. Options are exactly the locations the signed-in user's
   own scopes cover; a branch manager sees one option and the control is
   rendered as static text instead of a dropdown. Live counts per option.
4. **Должность** — dropdown, multi-select, grouped by scope level with
   `<option disabled>` separators: `── Компания ──` / `── Бренд ──` /
   `── Филиал ──`. Counts per option.
5. **Поиск** — text input, `/` focuses it. Matches name and phone. Does **not**
   match the Keycloak subject; nobody types a UUID.

No date range. There is no time axis on a staff directory worth filtering by —
"joined this month" is a report, not a filter.

### Row states

- **Приостановлен** — row background `--q-surface-1`, name in `--q-ink-muted`,
  a 4px `--q-warning` left rule, and a caption under the name giving the reason
  from `iam.grants.reason` of the revocation. Togora §2d: the reason text is the
  point, a bare badge is not.
- **Доступ отозван, аккаунт активен** — 4px `--q-error` left rule, caption
  «Может войти, но ничего не увидит». This is the state that produces support
  calls.
- **Заканчивается 25.08** — 4px `--q-warning` left rule, caption with the date.
  Time-bounded jobs exist (`iam.grants.valid_until`) and are the correct tool
  for a temp or a consultant; they are useless if their expiry is a surprise.
- **Это вы** — the signed-in user's own row carries a quiet `Вы` chip and its
  destructive actions are absent (§2.6).
- Everything else: transparent 4px left rule so normal rows stay aligned with
  flagged ones.

### Actions

Row actions are inline on hover and in a per-row overflow menu; both render only
when valid, following the Togora §2n rule as corrected by the IA — affordances
are **omitted, not disabled**, and their presence is driven by the server's
capability view, not by the client's guess.

| Action | Does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| **Открыть** | Navigates to 9.2 | — | never | no |
| **Изменить должность** | Opens the job dialog (§3.2) | `iam.grant.manage` at a scope covering the target's scope | the target holds a job the actor could not confer; the target is the actor | no |
| **Приостановить доступ** | Revokes every active grant of this person **within the actor's covered scopes**, requiring a reason | `iam.grant.manage`; a non-empty reason | the person has no active grants; the target is the actor | **yes** — names the person and states what stops working |
| **Вернуть доступ** | Re-creates the grants recorded on the most recent revocation, requiring a reason | as above | no revoked grants inside the actor's scopes | yes |
| **Отправить приглашение заново** | Re-issues the Keycloak invitation | `iam.grant.manage`; the person has never signed in | the person has signed in | no |
| **Скопировать номер** | Copies the phone | — | no phone recorded | no |

Two notes that are load-bearing:

**«Приостановить доступ» is not the same as disabling the account.** Revoking
grants leaves the person able to authenticate and see an empty console. That is
the `Доступ отозван, аккаунт активен` row state above, and it is a real state
today because ADR 0009's per-user disable is unbuilt (§11.3). Until it is, the
confirmation dialog must say so plainly:

> Азиза Каримова больше не сможет ничего делать в системе. Войти она пока
> сможет — экран будет пустым. Причина обязательна, она попадёт в журнал.

Do not paper over this with a label that implies more than it does.

**Every mutation carries a reason.** `iam.grants.reason` is `NOT NULL varchar(1000)`
and `AuditFact` throws when a `USER` actor supplies no reason. The reason field
is therefore a required control on every grant and revoke dialog, not an
optional note. Give it a label that makes it answerable — «Почему?» with the
placeholder «Например: перевод в Юнусабад» — and a 200-character soft counter.
Reason-collection that produces «asdf» is worse than none, because it looks like
evidence.

### Bulk actions

Selection is a checkbox column with a header select-all that selects **the
filtered set, not the page**, and states which it did: «Выбрано 8 из 8
отфильтрованных».

Offered only when valid for *every* selected row:

| Bulk action | Offered when |
|---|---|
| **Приостановить доступ** | every selected row has ≥1 active grant inside the actor's scopes, and none is the actor |
| **Добавить должность в филиале** | the actor can confer the chosen job at the chosen branch, for every selected row |
| **Экспорт списка** | always (CSV; name, jobs, scopes, status — never the subject id) |

The partial-failure rule from ADR 0039 applies unchanged: the bulk operation is
**N independent audited operations, not one transaction.** The result is a
per-row outcome list — «6 выполнено, 2 отклонено» with the two named and their
reasons — and never a silent partial success. There is no bulk grant endpoint
today (§11.8); the client issues N calls with N idempotency keys and composes
the result itself.

There is deliberately **no bulk delete and no bulk role removal across mixed
scopes.** Removing "the job Aziza has" is unambiguous; removing "a job" from
eight people who hold different jobs at different branches is not, and the
undo for it does not exist.

### States

| State | What is shown |
|---|---|
| Loading | Skeleton rows at the row height, header and filter bar live. Never a spinner over the whole page: the filter bar is usable before the data lands. |
| Empty (no staff) | The rule sentence from §0, plus a primary **Пригласить** button. This is the tenant's first day. |
| Empty (filtered) | An empty *table row* spanning the columns — «Никого не найдено» + **Сбросить фильтры**. Togora §2p: the table keeps its header and frame. |
| Denied | The signed-in user lacks `iam.grant.manage` anywhere: the section is absent from the rail entirely. See §9. |
| Partially denied | The signed-in user holds it at one branch only: the screen renders, scoped, with a quiet line above the table — «Вы видите сотрудников филиала Чиланзар». Not a warning, a fact. |
| Error | Inline banner above the table with the correlation id in mono and a **Повторить** button. The filter bar and any previously loaded rows stay on screen. |

### Keyboard

`/` search · `j`/`k` row · `Enter` open · `Esc` clear search then back ·
`x` toggle row selection · `Shift+?` shortcut sheet. No shortcut for suspend or
for any destructive action — this screen is not a queue, and a muscle-memory
keystroke that removes someone's access is a hazard with no compensating speed.

---

## 3. Карточка сотрудника — the person record (9.2)

### What it is for

Everything about one person: what they can do, where, since when, and what they
have done.

### Layout

Master-detail. A route (`/staff/:id`), not a modal — this page is deep-linked
from the activity log, from an order's attribution line, and from the branch
record, and a modal cannot be linked to. Back is a text link *above* the title
(Togora §2a), and the list's filters survive in query params.

Identity block, then four tabs (Togora §2k, rendered as 2.5px bottom-border
underlines inside the header block):

`Доступ` · `Активность` · `Смены` · `Безопасность`

Four, not nine. Nine is Delever's observed ceiling and a bad target.

### Identity block

| Field | Type | Source |
|---|---|---|
| Photo or initials | 48px square, no radius | **Not built** — §11.1 |
| Full name | Title | **Not built** — §11.1 |
| Phone | Mono, tap-to-call on touch | **Not built** — §11.1 |
| Access status | Pill (§2.4 vocabulary) | derived |
| «В системе с» | Date, DD.MM.YYYY | `min(iam.grants.valid_from)` for this subject — a proxy, and honest enough |
| Internal identifier | Mono, `--q-ink-subtle`, 10 chars + copy button, shown **only** on the Безопасность tab | `iam.grants.principal_subject` |

The Keycloak subject is a support artefact. It belongs behind a tab with a copy
button and nowhere else on the page.

### Tab 1 — Доступ

The primary tab and the page's default. A list of **assignments**, one card each,
in scope order (company → brand → branch), each carrying:

| Field | Type | Source |
|---|---|---|
| Job name | Text | `iam.roles.name` |
| Where | Text with a scope icon | `grants.scope_type` + `scope_id` → `tenant.brands` / `tenant.locations` |
| «Действует с» | Date | `iam.grants.valid_from` |
| «До» | Date or «бессрочно» | `iam.grants.valid_until` |
| «Назначил» | Person name | `iam.grants.granted_by` → resolves to a subject id today; renders as a name once §11.1 lands |
| «Причина» | Text, muted | `iam.grants.reason` |
| **Что можно делать** | Disclosure, collapsed | `iam.role_capabilities` for the role, rendered as plain sentences |

The **Что можно делать** disclosure is where the capability set finally becomes
visible, and it is the only place in the console where it does. Expanded, it
lists sentences grouped by area, in the tenant's language:

```
Заказы
  Видеть заказы филиала
  Принимать заказы
  Отменять заказы

Меню
  Видеть меню
  Ставить блюда на стоп
```

One capability, one sentence. `order.approve` → «Принимать заказы». Never the
code, never «order.approve (Принимать заказы)». The mapping is a static
translation table shipped with the frontend, keyed by `Capability.code()`, with
a build-time test asserting every enum constant has RU and UZ strings — the same
discipline `PlatformRoleTests` already applies to orphan capabilities.

Actions on this tab:

| Action | Does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| **Добавить должность** | Opens the job dialog scoped to one new assignment | `iam.grant.manage` at the target scope, and the actor holds every capability in the job at that scope | the actor covers no scope where a job could be added | no |
| **Убрать** (per card) | `DELETE /grants/{id}` with a reason | as above, at *that card's* scope | the card's scope is outside the actor's cover — then the card renders read-only with a quiet «вне вашего доступа» | **yes**, naming the job and the branch |
| **Продлить** | Opens a date picker, revokes and re-grants with a new `valid_until` | as above | the assignment has no `valid_until` | no |

`Убрать` on the actor's own last assignment is absent. Locking yourself out of
your own console at 20:40 on a Friday is a self-inflicted incident the software
should simply not permit; the owner does it for you.

Empty state: «У Азизы пока нет ни одной должности. Она может войти, но ничего не
увидит.» + **Добавить должность**. Say the consequence, not the absence.

### Tab 2 — Активность

The person-scoped slice of 9.8, pre-filtered, with the same columns and the same
detail drawer. It is a filter of one screen, not a second implementation.

`GET /api/v1/control-plane/tenants/{tenantId}/audit-events?actorSubject={subject}&limit=50`

Two additions over the global log:

- A **counts strip** above it, derived from the loaded rows in the Togora §2o
  manner so it cannot disagree with the table: «За 30 дней: 412 действий · 3
  отклонено · 1 под согласованием».
- Each count links to the log filtered to produce it (Togora §2j).

### Tab 3 — Смены

This person's roster and worked hours; see §7 for the model. For a person with
no shift-tracked job, the tab is **absent**, not empty.

### Tab 4 — Безопасность

| Field | Type | Source |
|---|---|---|
| Способ входа | Text: «Логин и пароль» \| «Логин, пароль и код» | Keycloak — **not projected, §11.9** |
| Последний вход | Timestamp + coarse device string | **Not built — §11.6** |
| Активные сеансы | Count + **Завершить все** | **Not built — §11.6** |
| PIN на терминале | Set / not set + **Сбросить** | **Not built — §11.7** |
| Внутренний идентификатор | Mono + copy | `iam.grants.principal_subject` |

Actions: **Сбросить пароль** (triggers Keycloak's own reset flow; HorecaOS never
handles the password), **Сбросить PIN**, **Завершить все сеансы**. All three
require confirmation and a reason. None of the three exists in the backend
today.

### States

Loading: identity block skeleton, tabs live. Not found: a full-page empty with
«Такого сотрудника нет» and a link back — never a redirect, because the link
that got here was probably from an audit record and the user needs to know it
dangled. Denied: 403 renders the identity block with jobs hidden and one line —
«У вас нет доступа к правам этого сотрудника» — because knowing the person
exists is not the sensitive part; knowing what they may do is.

---

## 4. Пригласить — invite (9.3)

### What it is for

Get a new person working today.

### Layout

A create-modal at 560px (Togora §2h, depth 3), not a page and not a wizard. It
is four fields. A wizard for four fields is an insult delivered in three steps.

### Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| **Имя и фамилия** | Text | yes | **Not stored anywhere today — §11.1** |
| **Телефон** | Phone, `+998 __ ___ __ __` mask | yes | The identifier this market actually uses. **Not stored — §11.1** |
| **Email** | Email | no | Optional, and it must stay optional: the legacy dashboard logged in by `username`, most branch staff have no work email, and requiring one to invite a cook is how this feature goes unused. Keycloak's invitation flow needs a channel — where there is no email, the invite is delivered as a one-time link the manager reads out or forwards over Telegram. |
| **Должность** | Select, grouped by scope level | yes | Only jobs the actor can confer (§0). Each option carries a one-line description under the name. |
| **Где** | Select — company / brand / branch | yes | Options constrained by both the chosen job's `scopeType` and the actor's own cover. Pre-selected and read-only when only one option exists. |
| **До какого числа** | Date, optional | no | Labelled «Временный доступ». Empty = permanent. → `iam.grants.valid_until` |
| **Причина** | Text | yes | → `iam.grants.reason` |

Under the job select, a live preview panel that updates on selection:

> **Управляющий филиалом** — Чиланзар
> Сможет: принимать и отменять заказы, ставить блюда на стоп, назначать
> курьеров, видеть отчёты по филиалу.
> Не сможет: менять цены, менять меню, возвращать деньги.

The **Не сможет** half is the more useful half and is usually omitted from
products like this. It is computed as the complement of this job's capabilities
against the union of all tenant-scoped jobs, capped at five lines, ordered by
how often people ask for them (money, then menu, then other branches).

### Actions

**Отмена** · **Пригласить** (primary). On success the modal closes, the new row
appears in the list with an `Приглашён` pill and a toast carrying the invite
link and a copy button — the manager will need to send it themselves more often
than not.

Unavailable when: the actor holds `iam.grant.manage` at no scope. Then the
button is absent from the People screen entirely.

### States

Submitting: the primary button goes to a loading state, fields lock, the modal
does not close. Error: an inline banner inside the modal, fields preserved,
nothing retyped. Duplicate phone: an inline field error naming the existing
person with a link to their record — «У Азизы Каримовой уже есть доступ» — not a
generic conflict. Idempotency: the request carries an `Idempotency-Key`
(ADR 0031); a double-submit returns the first outcome rather than a second
invitation.

`Esc` closes with a confirm if anything was typed. Click-outside closes the same
way. The Togora prototype's overlay has neither an escape key nor a focus trap;
both are gaps to fill, not patterns to copy.

---

## 5. Должности — the job library (9.4)

### What it is for

Understand what a job actually permits before giving it to someone, and see who
holds it.

### Layout

A list of the eight tenant-visible jobs, each opening a read-only detail. Not a
grid, not a matrix. The permission grid Delever ships — capabilities down,
roles across, checkboxes in the cells — is the wrong artefact for this audience:
it is dense, it invites editing that v1 does not support, and it answers a
question ("which roles have `catalog.publish`?") that a restaurant manager never
asks. She asks "what does a manager get?", which is a page per job.

The grid earns its place exactly once — when tenant-defined jobs ship (§11.4) —
and should be built then, not now.

### List columns

| Column | Type | Source |
|---|---|---|
| **Должность** | Name + one-line description | `iam.roles.name`, description from the frontend translation table |
| **Уровень** | Chip: `Компания` / `Бренд` / `Филиал` | `iam.roles.scope_type` |
| **Что охватывает** | Sentence: «Заказы, кухня, курьеры, отчёты» — area names, not counts | derived from `iam.role_capabilities` → `Capability.resourceType()`, mapped to area names |
| **Сколько человек** | Integer, clickable | count of active `iam.grants` for the role; the number links to People filtered by that job (Togora §2j) |

Sort: by `scope_type` (company first), then by holder count descending. A job
nobody holds sinks, and that is informative.

The eight visible jobs, with their shipped names:

| `PlatformRole` | RU | UZ | Level |
|---|---|---|---|
| `tenant-owner` | Владелец | Egasi | Компания |
| `tenant-admin` | Администратор | Administrator | Компания |
| `tenant-finance` | Финансы | Moliya | Компания |
| `support-agent` | Оператор поддержки | Qoʻllab-quvvatlash operatori | Компания |
| `brand-manager` | Менеджер бренда | Brend menejeri | Бренд |
| `courier-dispatcher` | Диспетчер | Dispecher | Бренд |
| `location-manager` | Управляющий филиалом | Filial boshqaruvchisi | Филиал |
| `location-staff` | Сотрудник филиала | Filial xodimi | Филиал |

`platform-admin` and `platform-support` are never listed, never returned to a
tenant client, and never grantable — `GrantManagementService` refuses the first
outright and the second fails the subset check. Filter them out of the list
response server-side; do not rely on the client to hide them.

### Job detail

Read-only. Three blocks:

1. **Что можно делать** — the capability set as plain sentences, grouped by area,
   in a two-column layout at desktop width. Same translation table as §3.
2. **Чего нельзя** — the complement, same computation as the invite preview,
   uncapped here.
3. **Кто занимает** — a compact table of holders: name, where, since when, with
   rows linking to 9.2. Empty state: «Пока никто».

Actions: **Назначить кому-то** (opens the job dialog with the job preselected).
No edit, no duplicate, no delete — jobs are code-owned in v1 and the screen
should not imply otherwise by showing greyed-out edit controls.

### The one thing this screen must beat Delever on

Delever's role page is a video with no prose, and its permission model is a flat
`get`/`post` grid per sidebar section. The consequence, visible in its own route
map, is that permission is expressed as *what menu you can open*. HorecaOS's
capabilities are verbs on resources, which means this screen can answer "what
will happen if I give her this?" rather than "what will she see?". Write the
sentences as **actions**, never as screen names: «Отменять заказы», not «Раздел
"Заказы"».

---

## 6. Проверка доступа — access check (9.5)

### What it is for

Answer «почему Азиза не может отменить заказ?» in one place, so nobody has to
reason about scope inheritance out loud.

Three clicks away, reached from a link at the bottom of 9.4 and from the 403
screen. It is a debugging tool used a handful of times a year, and it must not
occupy space on the main path.

### Layout

One row of three controls and an answer block:

`[Кто ▾]` `[Что ▾]` `[Где ▾]` → **Проверить**

- **Кто** — person picker, searching the tenant's staff
- **Что** — the action list, as plain sentences, searchable
- **Где** — company / brand / branch picker

### The answer

Not a boolean. A sentence and a chain:

> **Да.** Азиза может отменить заказ в филиале Чиланзар.
> Потому что: должность **Управляющий филиалом** дана ей **в филиале Чиланзар**
> с 12.03.2026.

or

> **Нет.** Азиза не может отменить заказ в филиале Юнусабад.
> У неё есть должность **Управляющий филиалом**, но только **в филиале
> Чиланзар**. Права даются вниз, а не вбок: должность в одном филиале не
> работает в другом.
> → **Дать должность в Юнусабаде**

The negative answer is where the scope rule gets taught, at the exact moment
someone needs to learn it, and it ends in the action that fixes it.

Backed by `AuthorizationService.has(subject, capability, scope)` plus the grant
rows behind the decision. There is no such endpoint today — `viewFor` returns the
actor's own view only. A new read endpoint is required:
`GET /control-plane/tenants/{id}/access-check?subject&capability&scopeType&scopeId`,
guarded by `iam.grant.manage`, returning the decision and the covering grant.
This is a small addition to ADR 0025's surface, not a new decision.

**Entitlement is a distinct answer.** When the capability passes but the tenant's
plan does not permit the feature, the answer is neither yes nor no:

> **Функция не входит в ваш тариф.** Права у Азизы есть. Модуль «Курьеры» не
> подключён.  → **Подключить**

`ErrorCode.INSUFFICIENT_CAPABILITY` and `ErrorCode.ENTITLEMENT_REQUIRED` already
exist as separate codes; the entitlement source does not (§11.10).

---

## 7. Смены — staff shifts and attendance (9.6)

### What it is for

Who is working now, who was supposed to be, and how many hours to pay.

### How this differs from couriers, and why it is a separate screen

ADR 0042 models courier shifts and it models them well: roster entry
(`DRAFT → PUBLISHED → CONSUMED | MISSED | CANCELLED`) is the plan, shift
(`OPEN → CLOSE_REQUESTED → RECONCILING → CLOSED → SETTLED`) is the fact, paid
seconds come only from the shift, and an open shift is an **authorization gate**
on receiving offers where `courier.shift.enforcement` resolves to `ENFORCED`.

Three of those four properties are wrong for kitchen and counter staff:

| | Courier (ADR 0042) | Branch staff |
|---|---|---|
| Who opens the shift | The courier, in the app, inside a GPS radius | A manager or a terminal sign-in; a cook has no phone in their hand and no radius to be inside |
| What it gates | Offer eligibility — an off-shift courier gets no orders | **Nothing.** A cook off shift who marks a dish ready has done the restaurant a favour. Gating kitchen actions on a roster stops service to enforce a timesheet |
| Cash | A cash declaration and shortfall reconciliation closes it | No cash. Closing is a time entry |
| Variance | `variance_seconds` against the roster feeds an approval before pay | Same. This one carries over unchanged |

So: **reuse ADR 0042's roster/shift split and its variance-approval discipline;
drop the authorization gate and the cash reconciliation.** The shift for branch
staff is a timesheet, not a permission. Anyone who proposes gating `order.approve`
on an open shift should be shown a Friday evening where the rota was not
published.

Delever gives none of this: its `Посещаемость` is courier-only, is documented as
a single screenshot with no prose, and the parity matrix's own open questions
record that nobody knows whether it is a plan, a log, or both. There is nothing
here to match. There is something to beat.

### Layout

Two views on one screen, switched by a segmented control, because the planning
question and the service question are different:

**«Неделя»** — a grid. Rows are people (grouped by branch when more than one is
in scope), columns are the seven days, cells carry the planned interval as text
(`09:00–18:00`) and are tinted by outcome once the day has passed. The Togora
§2g lattice discipline applies: **empty cells render a dashed outline, not
blank** — unstaffed hours are the actual planning question, and blank space
hides them. The `isToday` guard is likewise mandatory: a future day never renders
as `Не вышел`.

**«Сегодня»** — a list, sorted by who needs attention:

```
0  Опаздывает        — rostered, start time passed by > 10 min, no shift open
1  Не вышел          — rostered, day nearly over, no shift
2  Работает без смены — actions recorded today with no open shift
3  Смена не закрыта   — open past the branch's closing time
4  Работает           — open shift matching a roster entry
5  Смена закрыта
```

Weight 2 is the one to build carefully. It is the honest way to reconcile "the
timesheet is not a gate" with "we still need to know who was here": if someone is
working, the system should notice and let the manager fix the record afterwards,
not refuse the work at the time.

### Columns — «Сегодня»

| Column | Type | Source |
|---|---|---|
| Сотрудник | Name + job | §11.1 for the name; `iam.roles.name` for the job |
| Филиал | Text | `tenant.locations.display_name` |
| План | `09:00–18:00` or `—` | **Not built** — staff roster; §11.11 |
| Факт | `09:04–` or `09:04–18:22` | **Not built** — staff shift; §11.11 |
| Отклонение | `+18 мин` / `−4 мин`, coloured | **Not built** — §11.11 |
| Часы | Decimal, mono | **Not built** — §11.11 |
| Статус | Pill, from the weights above | derived |

### Actions

| Action | Does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| **Открыть смену** | Manager opens a shift on someone's behalf, with a start time | shift-manage capability (new, §11.11) at the branch | a shift is already open | no |
| **Закрыть смену** | Records the end time | as above | no open shift | no, unless the recorded hours differ from plan by > 30 min — then yes, with the variance stated |
| **Исправить время** | Edits start or end with a reason | as above | shift is settled | yes |
| **Опубликовать неделю** | `DRAFT → PUBLISHED` for the whole week | as above | nothing is in draft | yes — names the count and the people |
| **Скопировать прошлую неделю** | Duplicates the previous week's roster as draft | as above | previous week is empty | no |

Bulk: closing several open shifts at once (offered only when every selected row
has an open shift), and publishing a week. Approving variance is deliberately
**not** bulk — the entire point of a variance approval is that a person looked at
each one.

### Keyboard

This screen is used standing. `1`/`2` switch view, `t` jumps to today, arrow keys
move the grid cursor, `Enter` opens the cell editor, `Esc` closes it.

---

## 8. Терминалы — shared devices and PINs (9.7)

### What it is for

Let six people share one screen in the kitchen without six of them sharing one
login.

### The problem, stated exactly

A kitchen tablet, an expo screen, and a counter till are used by whoever is
standing there. Today's only options are a shared account — which destroys every
audit record in the building, because `audit_events.actor_subject` becomes one
anonymous identity — or individual logins, which nobody will type on a greasy
touchscreen forty times a shift.

The answer is standard in this category and HorecaOS does not have it: **the device
holds a long-lived session; the person holds a short PIN.** The device
authenticates as itself; each action is attributed to the person whose PIN
unlocked it. `audit_events.actor_subject` stays a real human being, which is the
entire property that makes §9.8 worth building.

None of this exists. See §11.7. It is specified here because the alternative is
that a tenant invents the shared account on their own and nobody finds out until
the first dispute.

### Layout

A list of devices, opening a device record. Short — a tenant has three to twenty.

| Column | Type | Source |
|---|---|---|
| Устройство | Name («Кухня — планшет 1») | **Not built** — §11.7 |
| Филиал | Text | `tenant.locations.display_name` |
| Роль устройства | Chip: `Кухня` / `Касса` / `Экспо` / `Киоск` | **Not built** |
| Кто сейчас | Name of the person currently unlocked, or `—` | **Not built** |
| Последняя активность | Relative time | **Not built** |
| Состояние | `В сети` / `Не в сети 12 мин` / `Отозвано` | **Not built** |

Sorted by state: revoked, then offline longest first, then online by branch.
An offline kitchen tablet during service is the only urgent row this screen can
produce.

### Device record

Fields: name, branch, device role, which jobs may unlock it, PIN length (4 or 6,
tenant-wide, set once), auto-lock timeout in minutes, enrolment date, last seen.
Actions: **Отозвать устройство** (immediate; confirm; ends the device session),
**Перевыпустить код привязки** (shows a one-time enrolment code, never a
password), **Посмотреть журнал устройства** (§9.8 filtered).

### PIN rules — non-negotiable

- A PIN **is not a password**. It is a second factor on a device that is already
  authenticated, it is only valid on enrolled devices at the person's own branch,
  and it can never be used to sign in to the console in a browser.
- PINs are set by the person, not by the manager. A manager may **reset** a PIN
  (clearing it, forcing the person to set a new one at the terminal) and may
  never see or choose one.
- 5 wrong attempts locks that person out of that device for 15 minutes and
  raises a `SECURITY`-class audit event. Locking the *person on that device*, not
  the device, so one fat-fingered cook does not stop the kitchen.
- A PIN is never derived from anything — not a phone number, not a birth date,
  not an employee number. The IA already records this rule for courier app
  passwords («password is never derived from the passport number»); it is the
  same rule and it is broken the same way.
- Sequential and repeated digits are refused at entry with the reason stated.

---

## 9. Журнал действий — the activity log (9.8)

### What it is for

Answer «кто это сделал?» with evidence, after something went wrong.

### Layout

A list with a detail drawer, not a detail page. The user is scanning for one row
among hundreds and then reading it; a route transition per row loses the scan
position. The drawer is 480px, opens from the right, `Esc` closes, and the row
stays highlighted behind it.

### Columns

All of these are real. `audit.audit_events` is built and partitioned.

| # | Column | Type | Source |
|---|---|---|---|
| 1 | **Когда** | `DD.MM HH:mm:ss`, mono, in the tenant's timezone | `audit_events.occurred_at`; `recorded_at` shown in the drawer when the two differ by more than a second |
| 2 | **Кто** | Name, linking to 9.2; an icon distinguishes person / система / фоновая задача / перенос данных | `actor_type`, `actor_subject`, `actor_display` |
| 3 | **Что** | Plain sentence: «Отменил заказ 4819» | `action_code` + `target_type` + `target_id`, through the translation table |
| 4 | **Где** | Branch or brand name; `Вся компания` for tenant scope | `scope_type`, `scope_id` |
| 5 | **Объект** | Chip linking to the thing acted on | `target_type`, `target_id` |
| 6 | **Итог** | Pill: `Выполнено` / `Отклонено` / `Ошибка` | `outcome` |
| 7 | **Почему** | Truncated to one line, full text in the drawer | `reason` |

`capability_used`, `correlation_id`, `causation_id`, `request_id`,
`approval_request_id`, `target_version`, `source_ip_hash` and `user_agent_hash`
all exist on the row and all belong in the drawer, never in the table.
`audit_class` drives a filter, not a column.

### The actor column is the whole point

ADR 0027's `ActorRef.Type` distinguishes `USER`, `SERVICE`, `SYSTEM_JOB` and
`MIGRATION` precisely so a backfill is never mistaken for a person. Render that
distinction: a person gets a name and a link, a scheduled job gets its job name
and a gear icon, a migration gets its run reference and a plainly different
treatment. Delever's release notes record fixing exactly this — print jobs
logged as anonymous system events until someone complained. The IA's requirement
is stronger and correct: **a named human actor even for background paths, never
"сервер"**. Where a background path acted on a person's behalf,
`on_behalf_of_subject` is populated and the cell reads «Система (по поручению
Азизы)».

`actor_display` is nullable and is currently written as `null` by
`AuditController.recordTheRead`. Until §11.1 lands, the Кто column shows a
truncated subject id, which is close to useless. Fixing the name resolution is
the highest-value item in this section.

### Filters

1. **Class tabs** (primary axis, counts inside): `Всё (1 240)` ·
   `Действия людей (1 118)` · `Безопасность (122)` — mapping to `audit_class`
   plus an actor-type predicate.
2. — divider —
3. **Кто** — person picker → `actorSubject`
4. **Что** — action picker, grouped by area, showing sentences → `actionCode`
5. **Где** — branch/brand dropdown → filtered client-side against `scope_id`;
   the API takes no scope parameter today and should (§11.12)
6. **Итог** — `Все` / `Отклонено` / `Ошибка` → `auditClass` is separate; outcome
   is **not a supported query parameter today** (§11.12)
7. **Период** — date range, **defaulting to today**, with presets
   `Сегодня` · `7 дней` · `30 дней`. Never default to all time: the query is
   bounded at 200 rows and an unbounded default silently truncates the answer.

Sort: `recorded_at DESC, id DESC`, matching the service's own ordering. This is
the one screen in the section where reverse-chronological is right — the question
is "what happened", and time *is* the severity axis.

### Detail drawer — the diff

The drawer shows the full row plus **what changed**, field by field, before and
after:

```
Статус       CONFIRMED   →   CANCELLED
Причина      —           →   Клиент не отвечает
```

`audit_events.change_document` is a `jsonb` object holding exactly this. It is
**deliberately excluded** from the list response by `AuditQueryService` — the
comment is explicit that it can carry redacted structure that is still revealing
in bulk, so retrieving it must be a separate, individually audited read.

That endpoint does not exist (§11.13). Build it as
`GET /control-plane/tenants/{id}/audit-events/{eventId}`, guarded by
`audit.read`, recording its own `audit.read` fact with the event id. The drawer
therefore has two loading phases: the row's fields render instantly from the list
data, and the diff block loads separately with its own skeleton. Do not block the
drawer on the diff.

Delever's `История изменений` has a «Что изменилось?» column opening a
before/after view; that is the feature to match. Its documentation page is
empty, so the column set above is HorecaOS's, sourced from the built schema.

### A bulk action produces N records, not one

The IA states this and it is right. When an operator suspends eight people at
once, the log contains eight rows, each naming its target and each individually
linkable. A single «Массовое действие (8)» row cannot answer "was Aziza in that
batch?", which is the only question anyone asks about a bulk action afterwards.
The drawer on any of the eight shows a **«Часть массового действия»** chip
linking to the other seven, resolved by `correlation_id`.

### Actions

**Экспорт** — CSV of the current filter, capped at 200 rows to match
`AuditQueryService.MAXIMUM_PAGE`, requiring a reason, and itself audited. Say
the cap in the dialog before the export runs; a truncated audit export handed to
a lawyer is worse than no export.

There is no delete, no edit, and no retention control on this screen. The table
is append-only at the database grant level (`REVOKE UPDATE, DELETE, TRUNCATE`),
and the UI should not imply otherwise.

### States

Empty: «За выбранный период ничего не происходило» + a **Расширить до 30 дней**
button. Denied: a person without `audit.read` never sees the section — this is
one of the few places where a hard hide is right, because the log's existence is
not the sensitive part but its contents are, and there is nothing to show
someone who cannot read any of it. Error: banner with the correlation id.

**Reading the log is itself logged.** `AuditController.recordTheRead` records the
returned count and the filters. Tell the user, once, in a quiet line under the
filter bar: «Просмотр журнала тоже записывается.» That is not a warning, it is
the honest description of a system with this property, and hiding it produces a
worse surprise later.

---

## 10. Мой профиль — self-service (9.9)

### What it is for

Change my own phone number and password without asking an administrator.

### Layout

Two sections on one short page, reached from the user's own chip at the bottom
of the rail.

**Личные данные** — name, phone, email, interface language (`ru` / `uz`), all
editable by the person themselves. **Not built — §11.1.**

**Безопасность** — «Сменить пароль» (hands off to Keycloak's own flow, HorecaOS
never sees the value), «Мой PIN» (set or change, at a terminal only — the browser
shows the state and a reset, never an entry field), active sessions with a
«Выйти везде» action.

**Мои должности** — read-only. The same assignment cards as §3, without any
action. A person should be able to see what they have; they should never be able
to change it, and the absence of controls is how that is communicated.

### Deliberately absent

Delever puts personal order and revenue statistics on this page, and puts a
tenant-wide beta toggle on it too. The statistics belong to Home 0.2 («Моя
работа»), where the operator already is — a person checks their own numbers
between calls, not from a settings page. The beta toggle is a tenant-scoped flag
on a user-scoped screen and is excluded by the IA. Neither appears here.

---

## 11. What the backend does not have

Named precisely, in the order that blocks the most screens.

### 11.1 A staff person record — no name, no phone, no photo, no ADR

**Blocks: every screen in this section.**

`iam.grants.principal_subject` is a `varchar(255)` holding a Keycloak subject
UUID. There is no table anywhere in `V0001`–`V0022` holding a staff person's
display name, phone, email, photo, employment status, employee number, spoken
languages, or POS operator id. `GrantManagementService.GrantView` returns the
subject string and nothing else.

ADR 0009 specifies `iam.principals` (`keycloak_realm`, `keycloak_subject_id`,
`status`) and `iam.tenant_membership_links` (`tenant_id`, `principal_id`,
`keycloak_organization_id`, `keycloak_membership_id`, `status`,
`last_reconciled_at`) — **neither has been migrated**, and neither carries a
profile field even when it is. The ADR's checklist item "Add IAM principal,
membership-link, and reconciliation migrations" is unticked.

So two things are needed and only one has an owner:

- `iam.principals` and `iam.tenant_membership_links` — **ADR 0009**, specified,
  unbuilt.
- A staff profile: display name, phone (PII, ADR 0029 envelope-encrypted, and
  therefore a `CUSTOMER_PII_REVEAL`-equivalent question for staff data too),
  email, photo `media_asset_id`, employment status, employee number, POS operator
  id, spoken languages. **No ADR owns this.** ADR 0025 is authorization, ADR 0009
  is Keycloak reconciliation, ADR 0042 is couriers. A new ADR is required, and
  its scope should be *staff identity, the employment record, and terminal
  access* — folding in §11.7 and §11.11 rather than spawning three records.

Until it exists, every name on every screen in this section is a UUID, and the
activity log — the most valuable thing here — is unreadable.

### 11.2 Grant history

`GrantManagementService.listForTenant` filters `WHERE status = 'ACTIVE'`. Revoked
grants are retained in the table (revocation is an `UPDATE` to `REVOKED`, not a
delete) but no read path returns them. The person record's "past access" and the
list's `Доступ отозван` state both need them. **ADR 0025** — a query-parameter
addition, not a decision.

### 11.3 Disabling a person

ADR 0009's "Disable, suspend and delete" section covers *tenant* suspension. There
is no per-user disable: no endpoint, no projection of Keycloak's `enabled` flag,
no `iam.principals.status` to read. Revoking grants is not the same thing and
this spec is explicit about the difference (§2.6). **ADR 0009.**

### 11.4 Tenant-defined jobs

`iam.roles` carries `tenant_id` and `is_platform_defined`, and
`uq_role_tenant_code` exists — the schema is ready. `GrantManagementService.grant`
resolves through `PlatformRole.find(command.roleCode())` and therefore accepts
platform roles only. ADR 0025 defers this deliberately: "Tenants may later define
custom roles from the same capability catalogue." This is the trigger for
building the permission grid described in §5. **ADR 0025**, explicitly deferred.

### 11.5 Order attribution

ADR 0039 §"Attribution is written once" specifies `created_by_actor_type/id` and
`accepted_by_actor_type/id` on the order. `V0022`'s `ordering.orders` has neither.
`ordering.order_state_history` has `actor_type` and `actor_id`, which can
reconstruct "who confirmed it" but not "who typed it in" — a cart that became an
order carries no author. Blocks the People list's `Заказов сегодня` column, the
person record's activity counts, and the operator leaderboard in Reports 7.5.
**ADR 0039.**

### 11.6 Last sign-in, active sessions, session termination

Keycloak holds all three. Nothing projects them and no endpoint exposes them.
ADR 0009's scheduled drift report is the nearest existing mechanism and is not a
substitute — it compares memberships, not sessions. **ADR 0009**, with the
projection likely belonging to the new staff-identity ADR from §11.1.

### 11.7 Shared-terminal identity and PINs

Nothing. No device registry, no PIN, no device session, no per-device audit
attribution. The nearest thing in the documents is the kiosk «service PIN» under
IA 10.5, which is a device *maintenance* code, not a staff identity, and must not
be conflated with this. **No ADR owns it** — see the proposed scope in §11.1.

Everything in §8 is specified against nothing built. That is deliberate: the
alternative is that the first tenant with a kitchen tablet creates a shared login
called `kuxnya`, and every audit record in §9 becomes worthless without anyone
noticing.

### 11.8 Bulk grant operations

`POST .../grants` takes one grant. Bulk assignment is N calls with N idempotency
keys and client-composed partial-failure reporting, per ADR 0039's rule that bulk
mutations are never one transaction. Acceptable for v1, and worth a server-side
endpoint once a tenant regularly onboards ten people at once. **ADR 0025.**

### 11.9 Staff MFA state

Whether a person has a second factor is a Keycloak fact with no projection. The
IA's component gap list already names `OtpInput` for staff MFA, so the intent
exists. **ADR 0003 / ADR 0009.**

### 11.10 Entitlement state

`ErrorCode.ENTITLEMENT_REQUIRED` exists and is distinct from
`INSUFFICIENT_CAPABILITY`, exactly as ADR 0025 requires. Nothing computes an
entitlement: ADR 0021 is `Not started` and `CapabilityView` has no entitlement
field despite ADR 0025 stating that `/session/context` returns an "entitlement
summary". The locked-by-plan versus denied-by-permission distinction — which the
IA lists as something HorecaOS owns and Delever ships as "module locks" — cannot be
rendered until this lands. **ADR 0021**, with a `CapabilityView` change under
**ADR 0025**.

### 11.11 Staff rosters and shifts

`fulfillment.courier_shifts`, `courier_roster_entries` and the courier ledger are
ADR 0042's, are courier-scoped, and are unbuilt in any case (ADR 0042 is
`Proposed` / `Not started`). Nothing models a non-courier person's planned or
worked hours. §7 argues these should reuse 0042's roster/shift split and drop its
gate and cash reconciliation, which is either an amendment to ADR 0042 or a
section of the new staff-identity ADR. It should not be a third shift model.

New capabilities are implied and do not exist in `Capability`:
`staff.shift.manage`, `staff.shift.approve`, `terminal.manage`. ADR 0042 already
proposes `courier.shift.open`, `courier.shift.approve` and
`courier.ratecard.manage` on the same pattern.

### 11.12 Audit query surface

`AuditQueryService.AuditQuery` supports `tenantId`, `actorSubject`, `actionCode`,
`targetId`, `auditClass`, `from`, `to`, `limit`. §9's filter bar additionally
needs **outcome** and **scope** predicates, and its «Часть массового действия»
chip needs a `correlationId` predicate — the index `ix_audit_correlation` already
exists for it. Cursor pagination is also absent: `Page.last(events)` returns a
terminal page, so 200 rows is a hard ceiling rather than a page size. **ADR 0027**
+ **ADR 0031** for the cursor.

### 11.13 Audit event detail endpoint

There is no `GET .../audit-events/{id}`, so `change_document` — the before/after
diff that makes the log useful — is unreachable from any API. The exclusion from
the list response is correct and deliberate; the missing single-record read is
not. **ADR 0027.**

### 11.14 Enforcement is on

`horecaos.authorization.enforce` now defaults to true, so a `RequiresCapability`
declaration refuses: a principal without the grant is answered
`INSUFFICIENT_CAPABILITY` with the capability and the scope level named. Every
denied state specified in this document is reachable, and a QA pass that
exercises them is exercising the real decision rather than a shadow log. Setting
the flag to false restores the comparison, and is an opt-out for re-measuring an
estate rather than a mode to develop against.

Two consequences a frontend will meet immediately. Nobody holds a grant until one
is created, so a freshly provisioned operator sees 403 on every section until
their role is granted at a scope — the Keycloak role alone is no longer enough.
And the storefront's customer principal holds no grant at all and never will,
which is a gap in the model rather than in the seeding; see the open item in
ADR 0025's checklist.

---

## 12. Delever: match, beat, skip

### Match

| Delever | Why |
|---|---|
| Permission-gated navigation — a section renders only when the user holds the associated permission | Correct, and the IA already requires it. HorecaOS's version is server-driven from `CapabilityView.capabilities` and enforced again per request, unlike Delever's route-visibility flag |
| Module locks as a second, independent gate | This is the entitlement layer. ADR 0025 is emphatic that entitlement never grants permission and permission never satisfies entitlement, and that the two produce distinguishable errors. Match the concept; §11.10 blocks the implementation |
| «История изменений» with a «Что изменилось?» before/after view | The single most valuable thing in Delever's settings area. HorecaOS's `change_document` is better structured than a rendered diff, and §9's drawer should use it |
| Filters by parameter and by period on the change log | Match, with outcome and scope added (§11.12) |
| Attendance for staff, at all | Delever ships it courier-only and undocumented; §7 covers everyone and separates plan from fact |

### Beat

| | |
|---|---|
| **One person record instead of two screens** | Delever splits RBAC (Настройки → Роль и доступ) from the person (Персонал → Оператор). Hiring one person therefore means visiting two unrelated sections and reconciling them by name. HorecaOS merges them: §3's Доступ tab is the RBAC screen, attached to the human it concerns |
| **Jobs described as actions, not as menus** | Delever's model is a `get`/`post` grid per sidebar section, so a permission is "which menu you can open". HorecaOS's capabilities are verbs on resources, which lets §5 answer "what will happen if I give her this?" |
| **«Чего нельзя»** | The complement of a job's capabilities, shown next to the job. Nobody ships this and it is the half of the question a manager is actually asking when she hesitates |
| **A named human actor for background paths** | ADR 0027's `ActorRef.Type` and `on_behalf_of_subject` make this structural rather than a bug fix. Delever's release notes record patching it case by case — print jobs, then something else |
| **A bulk action producing N audit records** | Delever's granularity here is unknown; the IA requires N and §9 specifies the `correlation_id` chip that makes N navigable |
| **Access check with a reason** (§6) | Nothing comparable exists. It converts the scope rule from something people mis-explain to each other into something the system explains once, correctly, at the moment of failure |
| **Reason mandatory on every access change** | Enforced by the schema and by `AuditFact`, not by a form validator |
| **Terminal PIN with real attribution** (§8) | Delever's kiosk PIN is a device service code. The staff-on-shared-device problem is unaddressed, and the default outcome is a shared login |

### Skip

| | Why |
|---|---|
| **User type «Aggregator» as a role** | Delever mints partner integration credentials by creating a *user* of type Aggregator under Users and roles. A partner integration is a machine client, not an employee, and ADR 0040 gives it a proper OAuth client with a rotatable secret under Integrations. Putting it in Staff means a partner's credentials get revoked when someone tidies the staff list |
| **Beta-version toggle on the personal account page** | A tenant-scoped flag on a user-scoped screen. Excluded by the IA |
| **Per-module v1/v2 version toggles** | A symptom of running two frontends at once. HorecaOS builds once |
| **Personal sales statistics on the profile page** | Right feature, wrong location. It belongs to Home 0.2, where the operator already is |
| **The «Сотрудники» top-10 dashboard** | That is a staff *report* (IA 7.5), not staff administration. Cross-link from §5's holder counts; do not duplicate it here |
| **A full role × capability permission grid, now** | Correct artefact for authoring custom jobs; wrong artefact for choosing among eight fixed ones. Build it with §11.4, not before |

### On the sources

Delever's documentation for this area is almost entirely absent. `Роль и доступ`
(v1) is a single embedded video dated 22.10.2024 with no prose;
`Пользователи и роли` (v2) is a single Loom embed; `Оператор` is a video;
`Посещаемость` is one screenshot with no text; `История изменений` (v2) has a
title and nothing else; `Личный кабинет`'s Личные данные and Персонализация
sections are both video-only. Only the `Сотрудники` dashboard page carries real
prose, and it is a report, not an administration screen.

**Nothing in this document is a reconstruction of a Delever form.** Where a
Delever behaviour is claimed above, it comes from the parity matrix's own
reading of the v2 route map and release notes — permission-gated navigation,
module locks, the change-log diff column, the Aggregator user type, the split
between Настройки and Персонал — and not from a documented field list, because
there is no documented field list. Any future claim about Delever's role form
fields must come from the product itself.

---

## 13. What the legacy dashboard did

The previous console (`legacy-archive/qoida-dashboard`) is thin here, and the
thinness is itself the finding.

**There was no user administration screen at all.** `AppRouter.tsx` registers
orders, couriers and settings. No `/users`, no `/staff`, no roles page. Accounts
were created outside the product. Every screen in this spec is therefore net new
to these users, and the invite flow (§4) is the first time they will have added a
colleague themselves.

**Three hard-coded roles**, in `types/User.ts`: `admin`, `manager`, `dispatcher`,
displayed as Администратор / Менеджер / Диспетчер. Staff will look for those
three words. The mapping is not one-to-one and should not pretend to be — the
migration mapping is `admin → tenant-admin`, `manager → location-manager` at each
location the person actually worked, `dispatcher → courier-dispatcher`. The
middle one is the interesting case: the legacy `manager` was tenant-wide by
accident, and giving every ex-manager a company-wide job would silently widen
access at cutover. Migration must scope them per branch and produce a report of
anyone who cannot be scoped.

**Permission enforcement was client-side only.** Every settings page computes
`const isDispatcher = useMemo(() => authUser?.role === UserRole.DISPATCHER)` and
hides its write controls. The API was not enforcing it. HorecaOS's is server-side
(ADR 0025, once §11.14 flips), and this is a genuine security improvement that
will be invisible to users — except that a dispatcher who used to be able to
`curl` a write will no longer be able to. Say so in cutover notes.

**Login is by username and password**, not email — `schemas/login.schema.ts`
validates a `username` of ≤64 characters with a ≥5-character password. This
must survive. Branch staff do not have work email addresses, and a Keycloak
configuration that demands one at sign-in will strand them on day one. §4 keeps
email optional for exactly this reason.

**The role was visible in the header**, in `DropdownUser.tsx`, colour-coded —
dispatcher blue, manager amber, admin red — under the person's name. Staff know
their colour. Keep the role name under the name in the rail's user chip; drop the
colour coding, which under the HorecaOS palette would spend `--q-error` on a person
who has done nothing wrong.

**A dedicated `/permission_denied` page existed.** Users have seen it. The
replacement should be better than a dead end: name the action that was refused,
name who can grant it (resolved from `iam.grants` at a covering scope), and offer
«Запросить доступ» where §11 permits it. A denial screen that names a person to
ask is worth ten that apologise.

**`Vendor.managers`** — the branch record carried a list of managers as a field
on the branch. Per-location assignment therefore already exists in their mental
model as *a property of the branch*, not of the person. §2's «По филиалам»
grouping serves that reading, and Settings 10.2's location record must carry a
**Команда** tab listing the same people with an **Добавить** action that opens
the same job dialog. One model, two doors, and the door they already know is the
branch.

**`Status.ON / OFF / ARCHIVED`** was the universal record state, including on
users. `Приостановлен` maps to `OFF`; there is no HorecaOS equivalent of `ARCHIVED`
for a person and there should not be one — ADR 0009 is explicit that permanent
deletion of identity records is out of scope, and a person who has left is a
person with no active jobs and an intact audit trail.

---

## 14. Build order

1. **§11.1.** Nothing else in this section is worth building against UUIDs.
2. **9.1 Люди + 9.2 Карточка + 9.3 Пригласить.** The pilot. This is the whole of
   "hand the console to a second person".
3. **9.4 Должности.** Cheap, static, and it is what makes step 2's job picker
   trustworthy.
4. **§11.14** — flip enforcement, after the shadow comparison is quiet.
5. **9.8 Журнал**, once §11.13 exists. Wave 2.
6. **9.6 Смены** and **9.7 Терминалы**, on the new staff-identity ADR. Wave 2.
7. **9.5 Проверка доступа** and **9.9 Мой профиль**. Wave 3.
