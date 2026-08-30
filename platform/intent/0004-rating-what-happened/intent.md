# Intent: rating what happened

- **Originator:** surfaced while scoping the storefront against the legacy client, 2026-08-28; the decision is the platform owner's
- **Date:** 2026-08-28
- **Status:** Draft
- **Delivered by:** —

## The problem

A customer cannot say whether the food or the delivery was any good, and a tenant cannot find
out. The legacy client had two separate screens, `orders/pages/rate-cafe-page` and
`orders/pages/rate-driver-page`, and the new platform has neither them nor anything behind
them.

Without it, a tenant learns about a bad branch or a bad delivery only when somebody complains
loudly enough to reach them, which selects for the angriest customers rather than the most
common problem.

**Correcting the record on what exists.** An earlier scoping pass of mine reported that
"ratings and reviews have seventeen migrations behind them". That was wrong, and the way it was
wrong is worth writing down: it came from `grep -c review src/main/resources/db/migration/`,
and every one of those hits is the word *review* in migration prose — V0024's migration control
plane, V0026's notification templates, V0037's POS staging. Asked properly, the live schema
holds no table matching `review|rating|feedback|survey`. There is nothing here at all.

## Evidence

- `SELECT ... FROM information_schema.tables WHERE table_name ~ 'review|rating|feedback|survey'`
  against the migrated database returns nothing.
- No service, controller, ADR or event mentions a rating as a domain concept.
- The legacy client rated the cafe and the driver on **separate screens**, which is a design
  decision it made and this platform has not: they are different subjects, answered by
  different people, about different things.

## What "solved" looks like

A tenant can see, per branch and over time, whether their food and their delivery are getting
better or worse — and can tell a bad week from a bad branch. A customer who had a poor
experience has somewhere to put it that is cheaper than a phone call.

## Scope

- **In:** what may be rated, by whom, when the window opens and closes, whether a rating can be
  edited or withdrawn, what a tenant sees, and what a rating is allowed to affect.
- **Out:** free-text public reviews visible to other customers — a different product with
  moderation, abuse and defamation attached, and it should be its own intent if it is wanted.
  Support chat. NPS or survey instruments.

## Affected areas

A new module or an extension of `ordering`; ADR 0042 and ADR 0045 for anything touching
couriers; ADR 0029 if a rating carries free text; reporting for the tenant-facing view; the
storefront's order-detail screen.

## Constraints and open questions

- **Rating the driver is not the same decision as rating the cafe, and this is the crux.** ADR
  0042 establishes couriers as self-employed, and is explicit that directing when they work is
  the fact pattern that reclassifies the engagement. A score that affects how much work a
  courier is offered is a long way down that road. Whether a courier rating exists at all,
  whether the courier can see it, whether it reaches dispatch, and whether a low score can
  reduce someone's income are questions for the owner and probably for legal — not for a
  spec, and certainly not for an implementation.
- **What a rating may affect.** The safe answer is "nothing automatic — it is reporting". Any
  other answer needs to say what happens to a courier or a branch on a bad week, and who
  decides.
- **Who may rate, and once.** Presumably the customer on the order, after it completed, once.
  Establish what a cancelled, refunded or remedied order can be rated — an order that went
  wrong and was put right is exactly the one a tenant most wants to hear about, and exactly the
  one a naive "completed orders only" rule excludes.
- **Guests.** A guest checkout has no account. Can they rate, and if so what identifies them
  later?
- **Free text or not.** A star is a number. A comment is a person writing about another person
  by name, which is ADR 0029 material and a moderation surface. Starting with a score and no
  text is a legitimate v1 and should be an explicit choice rather than an omission.
- **The prompt.** A rating nobody is asked for does not happen. Where the ask lives — order
  detail, a notification, the next visit — decides whether this is worth building at all, and
  it interacts with the notification preferences a customer does not yet have.

## Why not do nothing

Weakest of the storefront gaps, and honestly so: nobody is blocked, no money is at risk, and
the platform functions. The argument for it is that a tenant running several branches has no
instrument at all for "is this one getting worse", and every month without one is a month of
that question being answered by anecdote.

The argument against doing it *soon* is that the courier half needs a decision with employment
weight behind it, and shipping the cafe half alone is a smaller and cleaner change that could
be made first — which is itself a reasonable outcome of this intent.
