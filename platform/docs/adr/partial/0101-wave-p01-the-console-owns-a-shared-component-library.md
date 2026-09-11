# ADR 0101: The operations console owns its shared component library, in-app and un-barrelled

- Decision status: Proposed
- Implementation status: Partial — wave `P01` creates
  `frontend/operations/src/app/shared/ui/` and the eleven primitives this
  record names (`q-modal`, `q-drawer`, `q-confirm-dialog`, `q-action-menu`,
  `q-split-pane`, `q-toast-host` with its `Toasts` service, `q-inline-alert`,
  `q-empty-state`, `q-denied-state`, `q-locked-state`, `q-status-pill`), each
  with its own spec. Migration is deliberately two call sites per component and
  no more: the two hand-written dialogs, the two master-detail pages, the order
  queue's status badge, one toast host in the shell, and one denied/empty pair.
  Not built: the remaining ~63 hand-written `denied` branches and the fourteen
  other templates carrying `aria-modal="true"`, which each owning wave migrates;
  `q-action-menu` has no migrated call site at all, because the only two in the
  application are the order board's row overflow menus and wave `P05` owns them.
  Not built: the second half of this decision — publishing the library as a
  package the control plane and the storefronts can consume.
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0035, ADR 0052
- Supersedes / Superseded by: —
- Open inputs: package registry for a published design system, if the library is
  ever to be shared between the four Angular applications (platform owner —
  this is ADR 0035's own open input, still open, and this record deliberately
  does not close it); whether `@angular/cdk` enters the console at all, which
  wave `P03` decides when it needs virtual scrolling (platform owner)

## Context

ADR 0035 fixed Angular as the single web framework and the HorecaOS Design
System as the visual contract, and it named the component gap precisely: eleven
primitives exist in the design system, and "neither console can be built from"
them. Its own implementation status line records the consequence — "No shared
Angular component library exists in any app" — and its component-gap table
lists `Modal`/`ConfirmDialog`/`Drawer`/`ActionMenu` as "Required throughout",
`Toast`/`InlineAlert` as "Every mutation", `LockedState`/`DeniedState` as
distinct from `EmptyState`, and a `StatusPill` overlay for lateness.

What the working tree shows a year later is exactly what that gap produces.
`frontend/operations/src/app` has `core/`, `features/` and `shell/` and no
shared component directory at all. Fifteen templates carry `aria-modal="true"`,
each with its own copy of a backdrop, a panel and a pair of buttons, and not one
of them closes on Escape or traps focus: the whole application has a single
`keydown` handler, `shell.ts`'s F2. Sixty-five templates carry a hand-written
`denied` branch, most of them one sentence with no statement of which capability
was missing or who can grant it. Five sections each own a copy of the same
master-detail CSS and the same `max-width: 1100px` media query, and the split is
neither resizable nor remembered. Mutations report success nowhere, and report
failure through per-feature notice bands that a screen reader is never told
about. The order board's status word and its lateness rail are two unrelated
visual systems that the operator has to correlate by eye.

Three constraints make the shape of the fix non-obvious rather than routine.

**The library has eleven later waves queued behind it.** The operations gap map
sequences `P02`, `P03`, `P08`, `P17`, `P18`, `P22`, `P24`, `P26`, `T09`, `T21`
and `T22` after this one for one reason only: they each add files to the
directory this decision creates. Anything in the library that every wave must
edit is a merge conflict on every one of those waves.

**Tokens are generated, not authored.** `frontend/design-tokens/tokens.css` is
copied verbatim from the design project and carries a closed type scale whose
own comment reads "Never set a font-size inline". A component library that
invents a token or a font size stops the design system being the source of
truth, which is the failure ADR 0035 exists to prevent.

**`@angular/cdk` is absent.** The console's `package.json` has `@angular/*`,
`rxjs` and two IBM Plex font packages, and nothing else. The CDK would supply
`A11yModule`'s focus trap and `Overlay`, and it is a real dependency decision
with a real cost — and wave `P03` needs it for virtual scrolling, which is a
better forcing function for that decision than three dialogs are.

## Decision

**The operations console's shared component library lives inside the
application, at `frontend/operations/src/app/shared/ui/`, as ordinary standalone
components — not as a package, not as an Angular library project, and not in
`frontend/design-tokens/`.** ADR 0035's published-package half stays open and
unbuilt; this decision is the console's local answer to a gap that blocks the
pilot, and it is deliberately reversible into a package later because nothing in
it depends on being inside this application.

**The directory has no barrel file.** Every consumer imports the component's own
module path (`../../shared/ui/modal`), and every later wave adds its own files
and edits nobody else's. A barrel is the one file all eleven queued waves would
have to write, so the library does not have one.

**Overlay behaviour is hand-rolled against the DOM, with no new dependency.**
One internal helper, `overlay.ts`, owns Escape, the focus trap and focus
restore, and `q-modal`, `q-drawer` and `q-confirm-dialog` each delegate to it.
It is roughly eighty lines against `HTMLElement`, `document.activeElement` and a
tab-order query; it is not a re-implementation of the CDK, and it does not
pre-empt `P03`'s decision about the CDK, because a component that keeps its
focus handling behind one helper can have that helper's body replaced.

**Every primitive takes its strings as `MessageKey` inputs or as already
translated text; none of them ships English.** The console's catalogues are
compile-time complete (`messages.en.ts` defines the key set and the other two
are typed against it), and a component library with hard-coded copy would be the
one place that guarantee does not hold.

**`q-status-pill` takes a status and an independent lateness overlay**, because
lateness is not a state. The IA says so in one line — "Lateness must not be
modelled as a state" — and the console has already proved it: `order-severity.ts`
computes severity per render and refuses to store it. The pill renders the
status word unchanged and puts lateness beside it as an overlay, so a `READY`
order that is late still reads `READY`.

**Migration is two call sites per component and stops there.** This wave proves
each primitive against real screens; it does not rewrite sixty-five templates
that eleven other waves are about to touch.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Publish the library as an npm package consumed by all four Angular apps | ADR 0035's own open input — "package registry for the published design system" — is still open, and there is no registry to publish to. A package also needs a versioning and release story before its first consumer, and the pilot needs `q-modal` this week | A registry exists **and** a second application needs the same primitive. The storefront's sheets are not drawers, so the control plane is the likelier first co-consumer |
| An Angular library project (`ng-packagr`) inside this repository, consumed by path mapping | Buys the package boundary's build cost with none of its distribution benefit while there is exactly one consumer, and doubles the test and lint surface | The same trigger as publishing: a second consumer |
| Put the components in `frontend/design-tokens/` beside `tokens.css` | That directory is *generated* — copied verbatim from the design project. Hand-written Angular components in it would be overwritten by the next sync, and would make a generated directory hand-editable, which is the property the sync depends on | Never, unless the design project itself starts emitting Angular |
| Adopt `@angular/cdk` now for `A11yModule` and `Overlay` | A dependency decision belongs to the wave that cannot be built without it. `P03` needs `cdk-virtual-scroll` for a ten-thousand-row grid and has no alternative; three dialogs have one, in eighty lines | `P03` lands the dependency. Then `overlay.ts`'s body is replaced by `FocusTrapFactory` and the components do not change |
| A barrel `index.ts` for the directory | It is the single file every one of the eleven queued waves would edit, turning a sequencing hazard the gap map already flags into a guaranteed conflict on each | The library stops growing — i.e. after the wave-2 component tail lands |
| Keep the per-feature copies and fix accessibility in place | Fifteen dialogs times Escape, focus trap and focus restore is fifteen chances to get it wrong, and the audit trail on this repository is that the second copy is the one that drifts. It also leaves `LockedState` and `DeniedState` undistinguished, which the IA calls out as different UX — one is an upsell, one is a wall | Never |

## Consequences

### Positive

- A keyboard operator can dismiss any overlay with Escape and cannot Tab out of
  it into the page behind, on every screen that uses the library.
- A mutation is announced: `q-toast-host` mounts once in the shell and its
  `role="status"` live region is read by a screen reader, including when the
  dialog that caused it has already closed.
- A denied operator is told which capability is missing and who can grant it,
  in one sentence written once instead of sixty-five times.
- Eleven later waves have somewhere to put their components and a convention for
  how to write them, and none of them blocks on another.
- Lateness is an overlay, so the order board can stop correlating two visual
  systems by eye, and a late `READY` order still reads `READY`.

### Negative

- The console carries its own focus-trap implementation, which is a class of
  code with genuinely hard edge cases (shadow roots, `inert`, iframes, elements
  that become focusable while the trap is open). It is tested, and it is
  deliberately eighty lines rather than a library, but it is code this project
  now owns and the CDK's is code it would not.
- The four Angular applications still each have their own components, so a fix
  made here does not reach the control plane. That divergence grows for as long
  as ADR 0035's package half stays open.
- Two call sites per component means the application now has *two* ways to draw
  a dialog — the library's and the thirteen remaining hand-written ones — until
  each owning wave migrates. A reader who meets the hand-written one first will
  copy it.
- `q-action-menu` ships with no call site at all this wave, so its roving
  tabindex is proved only by its spec until `P05` migrates the order board.

### Accepted trade-offs

- No barrel means longer import paths in every consumer. That is the price of
  eleven waves not editing one file, and it is paid by the consumer, not by the
  library.
- No CDK means no `Overlay` service, so a dialog renders where its host template
  puts it rather than in a detached overlay container. Every existing dialog in
  this application already does that, and `z-index: 50` on the backdrop is the
  convention they already share.
- The library does not attempt an `EmptyState`/`DeniedState`/`LockedState`
  hierarchy or a shared base class. They share a stylesheet shape and nothing
  else, because the IA's whole point about them is that they are different.

## Specification

### Directory

```
frontend/operations/src/app/shared/ui/
  overlay.ts            focus trap, Escape, focus restore — internal helper
  modal.{ts,html,css}   q-modal        + modal.spec.ts
  drawer.{ts,html,css}  q-drawer       + drawer.spec.ts
  confirm-dialog.*      q-confirm-dialog + confirm-dialog.spec.ts
  action-menu.*         q-action-menu  + action-menu.spec.ts
  split-pane.*          q-split-pane   + split-pane.spec.ts
  toast.ts              Toasts service
  toast-host.*          q-toast-host   + toast.spec.ts
  inline-alert.*        q-inline-alert + inline-alert.spec.ts
  empty-state.*         q-empty-state  ┐
  denied-state.*        q-denied-state ├ states.spec.ts
  locked-state.*        q-locked-state ┘
  status-pill.*         q-status-pill  + status-pill.spec.ts
```

### `overlay.ts`

`OverlayBehaviour` is instantiated by a component in its constructor with a
`Signal<HTMLElement | null>` for the panel and a close callback. On the first
render where the panel exists it records `document.activeElement` as the
invoker, moves focus to the panel's first tabbable descendant (or the panel
itself, which carries `tabindex="-1"`), and installs a `keydown` listener on the
document. `Escape` calls the close callback; `Tab` and `Shift+Tab` on the first
or last tabbable descendant wrap. On destroy it removes the listener and returns
focus to the recorded invoker if that element is still in the document.

Tabbable is `a[href], button, input, select, textarea, [tabindex]` minus
`[disabled]`, `[tabindex="-1"]` and anything with a zero-sized client rect, in
document order. There is no `inert` and no shadow-root traversal; this console
uses neither.

### `Toasts`

A root-provided service holding `Signal<readonly Toast[]>`. `show({ messageKey |
message, tone, timeoutMs })` appends one with an `Ids`-free local counter id and
schedules its own removal; `dismiss(id)` removes it early. `q-toast-host`
renders the list into a single `role="status" aria-live="polite"` region —
`role="alert"` for the `error` tone — mounted once in `shell.html`, above the
routed outlet so it survives navigation. The host is the only consumer of the
service's signal; features call `show` and never read the list.

### `q-split-pane`

Two projected slots and a drag handle. The primary pane is `1fr`; the secondary
pane's width is a signal in `px`, clamped to `[minSecondaryPx, 70 % of host]`,
persisted under `horecaos.operations.splitPane.<sectionKey>` in `localStorage`.
A stored value that does not parse as a finite number in range is ignored and
the default is used — a corrupt key must not blank a screen. The handle is a
`role="separator"` with `aria-orientation="vertical"` and arrow-key resizing, so
the split is reachable without a pointer. Below `1100px` the secondary collapses
under the primary, which is the media query the five hand-written copies already
share.

### `q-status-pill`

Inputs: `label` (already translated), `tone`, and an independent `overlayLabel`
with `overlayTone`, plus an optional `secondaryLabel` for the dual-state
order+cooking pill. The overlay renders as a second segment inside the pill's
border with its own tone; the status segment's text never changes because of it.
`aria-label` composes the status and, when present, the overlay and the
secondary, so a screen reader gets one string rather than three fragments.

### Testing

One spec per component, no shared fixture, built through
`TestBed.createComponent` and `componentRef.setInput` — the console is zoneless,
so a plain field on a host component never reaches a child's `input()` signal
(`order-reason-dialog.spec.ts` documents this). The load-bearing assertions:
Escape closes and focus returns to the invoker on all three overlay types; the
action menu's roving tabindex wraps at both ends and an outside click closes it;
the split pane restores a persisted width and survives a corrupt stored value;
`q-inline-alert` picks `role="alert"` for `error` and `role="status"` otherwise;
`q-status-pill` renders a lateness overlay **on a non-late status** without
changing the status word.

## Rollout and rollback

Additive. Every migrated call site keeps its `data-testid` and its existing
spec, so the migration is proved by tests that were written against the
hand-written markup. Rollback is reverting the wave: nothing outside
`shared/ui/` and the eight migrated files changes, no schema moves, no endpoint
is added, and no capability is minted.

## Implementation checklist

- [x] `shared/ui/` with the eleven primitives and `overlay.ts`
- [x] A spec per component
- [x] `q-toast-host` mounted once in `shell.html`
- [x] Two call sites per component, and no more
- [x] `ui.*` keys in all three catalogues with real ru and uz-Latn translations
- [ ] The remaining thirteen hand-written dialogs — each owning wave
- [ ] The remaining ~63 hand-written `denied` branches — each owning wave
- [ ] `q-action-menu`'s first call site — wave `P05`
- [ ] `overlay.ts`'s body replaced by the CDK's focus trap, if `P03` lands it

## Exit criteria

An operator pressing Escape in the cancel-reason dialog closes it and finds the
caret back on the button that opened it; Tab from the last field in that dialog
lands on its first, not on the order board behind it. The orders and inbox split
is dragged to a new width, the browser is reloaded, and the width is still
there. A `READY` order that is forty-six minutes old shows the word `READY` and
a lateness overlay beside it. A customer created from the dialog produces a
toast that a screen reader announces after the dialog has closed.

## References

- [ADR 0035](0035-angular-frontend-platform-and-design-system-adoption.md) — frontend platform and the component gap this record closes the console's half of
- [ADR 0052](0052-one-repository-for-the-whole-platform.md) — why the four applications are directories in this repository
- [The operations gap map](../../operations-gap-map.md) — wave `P01` and rows `X.8`, `X.15`, `X.16`, `X.17`, `X.30`
- [The frontend information architecture](../../frontend-information-architecture.md) — PART 4's component inventory
