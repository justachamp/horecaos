# ADR 0075: A customer orders from the chat they are already in

- Decision status: Accepted
- Implementation status: Partial — the tap boundary and the ordering port are
  built and tested. V0172 adds the `CUSTOMER_ACTION` token kind, restating both
  of V0106/V0119's CHECK constraints in full and adding a closed command set and
  an order-id shape rule of its own. `TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED`
  ships `safeDefault(FALSE)`. `ordering.api.CustomerBotOrderingPort` and its
  adapter (latest order, repeat, cart, cash checkout) run over the same
  `CartService`, `CheckoutService` and `ReorderPlanService` the storefront calls;
  `reviews.api.CustomerReviewPort` does the same for ADR 0071's rating.
  `CustomerBotActionAuthorizer` resolves the token, refuses a non-private chat,
  checks the entitlement, resolves the chat to a customer account through the
  ADR 0026 binding, rate-limits per chat, and records an ADR 0027 fact on the two
  actions that change something. `TelegramUpdateHandler` dispatches customer taps
  ahead of the staff decision path and renders every outcome in three languages.
  `TelegramChannelAdapter` attaches a status button to `ORDER_CONFIRMED`.
  Nine PostgreSQL-backed authorizer tests and three ordering-port tests, all
  proven to bite.
  The rating prompt now rides its own message: `ordering.api.OrderCompleted`
  (contract, schema and baseline), published on the COMPLETED transition, drives
  an `ORDER_COMPLETED` notification whose Telegram rendering carries five star
  buttons.
  **Not built**: a delivery repeat whose source cart's address has since been
  archived still hands off (by design, below); and no Mini App URL is configured
  anywhere on the installation, so every "finish in the app" answer is a sentence
  rather than a button.
- Date proposed: 2026-09-06
- Date decided: 2026-09-06
- Deciders: Ayubkhon Abbosov (platform owner, directed the feature);
  Claude (architecture)
- Depends on: ADR 0018 (deterministic pricing), ADR 0019 (cart and checkout),
  ADR 0025 (capabilities), ADR 0027 (audit), ADR 0029 (PII), ADR 0039 (order
  amendment and cancellation), ADR 0058 (Telegram channels and the customer
  1:1 link), ADR 0059 (conversational engagement), ADR 0060 (the interactive
  bot and its callback discipline), ADR 0063 (Telegram-native identity),
  ADR 0070 (a storefront is a client of a published contract), ADR 0071 (order
  reviews), ADR 0074 (the reorder plan)
- Supersedes / Superseded by: —
- Open inputs:
  - **Closed** by the owner's instruction of 2026-09-06 to implement this
    record: the surface has its own entitlement key
    (`telegram.customer_inline_actions.enabled`, distinct from the staff bot's),
    and cash confirms in the chat. Both are one flag and one branch to reverse.
  - The Uzbek and Russian button labels and message copy — owner (Ayubkhon
    Abbosov) to review. Real copy is written in `TelegramBotMessages`, in the
    three-language `pick` shape every other bot message uses; what is open is
    the wording, not its absence.
  - Where the tenant's Mini App lives — owner. Nothing on the Telegram
    installation carries a URL today, so the handoffs are text. The natural home
    is the installation's own `non_sensitive_config`, beside `botUsername`.

## Context

The customer bot already exists and already knows who is on the other end.
ADR 0058 stage 2 links a customer's private chat to their account
(`TelegramCustomerLinkService`, `/start <code>`, or a verified Mini App
`initData` handshake), and ADR 0063 lets them sign in through Telegram in the
first place. The platform sends that chat real transactional messages.

What it cannot do is receive an intention. Every message the customer bot sends
today is one-way. A customer who reads "Buyurtmangiz tayyor" and wants the same
thing again has to open the Mini App, wait for a menu, find the dish, choose the
variant, choose the modifiers, and check out — six screens to repeat something
they have ordered eleven times. In this market a large share of customers live
in Telegram and treat a Mini App as a website they did not ask for.

Three things landed that make the low-friction path buildable rather than
aspirational:

- **ADR 0060 built the callback discipline.** `integration.bot_action_tokens`
  holds a server-side action record; the button carries only an opaque token,
  because Telegram caps `callback_data` at 64 bytes and, in that review's own
  words, nothing signed travels in the button. `BotCallbackAuthorizer` resolves
  the token, resolves the tapper, re-checks authority live, and only then acts.
- **ADR 0074 built the resolver.** `GET .../orders/{orderId}/reorder` answers
  whether an order can be placed again, against the live publication, this
  location's offerings, the price book and the kitchen's 86 list. The bot has no
  menu in hand and cannot compute any of that; now it does not have to.
- **ADR 0071 built reviews**, so "how was it?" has somewhere to land.

One fact settles a question this record was expected to open. **There is no
separate customer bot.** A tenant has one Telegram `NOTIFICATION` installation
with one token; staff and customers DM the same bot, and
`TelegramUpdateHandler` tells them apart by which link table holds the sender —
`integration.telegram_staff_links` or the ADR 0026 customer binding. So the
question is not which bot, it is which authority a tap carries, and the answer
is different in kind: a staff tap exercises delegated authority over somebody
else's order, and a customer tap is a person buying lunch.

## Decision

**Build a fixed, small set of inline actions on the customer's own linked chat,
and deep-link everything else into the Mini App.**

The bot is a *shortcut for repeat business*, not a second storefront. It does
what chat is genuinely better at — one tap on a message the customer was
already reading — and hands off the moment the interaction needs a screen.

Five actions in v1:

| Action | Offered when |
|---|---|
| **Order status** | an order of theirs is live |
| **Repeat last order** | ADR 0074's plan for it says `READY` |
| **View cart** | an open cart has lines |
| **Checkout** | a cart is priceable and a destination is resolvable |
| **Rate** | a completed order has no review yet |

**Menu composition is not one of them.** Choosing a dish means variants,
modifier groups with minimums and maximums, and photographs; rendering that as
inline keyboards produces a worse menu than the Mini App and a second place for
ADR 0016's selection rules to be enforced. The "Open the menu" button is a
`url` button, and that is the whole of it.

**Buttons ride the messages the platform already sends.** The order-confirmed
message carries Status; the completed message carries Rate; a re-engagement
message carries Repeat. A customer who types nothing still gets the shortcut,
which is the point — the win is not a chat interface, it is one tap on a
notification.

**Every tap is authorized as a customer, not as a principal.** A sibling of
`BotCallbackAuthorizer` resolves the token, resolves the private chat to a
customer account through the ADR 0026 binding, checks the entitlement, and then
calls the same application services the storefront controller calls. It
requires **no ADR 0025 capability**, for exactly the reason
`StorefrontOrderingController` declares none: capabilities are delegated staff
authority, there is no grant row per customer, and requiring one would refuse
every caller the surface exists for.

**One tap is one server-side call, and it is not a second cart builder.** A
Telegram callback must be acknowledged in seconds; a repeat of eight lines
cannot be eight round trips. So the bot calls one `ordering.api` port, which
loops over the *same* `CartService.putLine` in one transaction. ADR 0074's rule
that there is no second way to build a cart holds — the loop moved inside the
process, not outside the service.

**Money never enters the chat.** Cash confirms with a button. CLICK and Payme
end in a `url` button to the provider's own page. No card number, no
confirmation code, nothing of the sort is ever typed into or read from Telegram.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Full menu browsing and composition in chat | Inline keyboards cannot render a modifier group with a minimum of one and a maximum of three, priced options and a photograph, and trying produces a menu worse than the one a tap away. Worse, it puts ADR 0016's selection rules in a second enforcer | Never for composition. A single-variant, no-modifier "quick add" for a brand's top three dishes is a v2 question |
| Free-text ordering ("2 lavash, 1 cola") parsed by the assistant | ADR 0069's assistant is grounded in platform facts and answers questions; turning text into a priced basket is a different risk — a misparse spends the customer's money. Buttons cannot misparse | ADR 0069 is built, measured, and the owner accepts a confirmation step before every parsed basket |
| Signed, self-describing `callback_data` | Telegram caps it at 64 bytes, and ADR 0060's review already settled this: nothing signed travels in the button. A token indexing a server-side record is smaller, revocable, and expires | Never |
| A second bot for customers | A tenant would provision, fund and be verified for two Telegram bots, and a customer's link would break the moment a tenant migrated one of them. The single installation already distinguishes audiences by link table | A tenant needs a customer-facing bot under a different brand identity from the staff one |
| The bot calls the storefront HTTP API for each line | Eight sequential HTTP calls behind a callback that must ack in seconds, with no transaction across them — a repeat that half-succeeds leaves a partial cart the customer did not ask for | Never; the in-process port is strictly better and uses the same service |
| Let the customer cancel from the chat in v1 | Destructive on a mis-tap, needs its own two-step confirm, and once payment is captured the refund consequences belong to ADR 0039. The reason picker ADR 0060 built for staff Reject is the shape it would need | First item of v2 |

## Consequences

### Positive

- The shortest possible path from "order confirmed" to "order it again": one
  tap, on a message already on screen, with no app to open.
- The repeat is exact — ADR 0074 resolves the ids, so it is the same variants
  and the same modifiers, not a name match.
- The surface is small enough to be correct. Five actions, each a call into a
  service the storefront already calls, with no business logic in the bot.
- Reviews get a response path where response rates are actually high. A rating
  prompt in a chat outperforms one behind a login.
- Nothing new is learned about the customer. Every action runs inside a chat
  already bound to their account.

### Negative

- **A third client of the ordering contract.** Storefront, Milliy and now the
  bot; every future change to checkout has three consumers to consider, and the
  bot is the one with no screen to explain a new step in.
- **Every refusal has to be a sentence.** A storefront can grey out a control; a
  bot must say "that dish is sold out" in the customer's own language, so every
  failure path needs copy in three languages before it can ship.
- **The token table grows a second audience.** `integration.bot_action_tokens`
  was written for staff decisions and a tenant picker; customer actions widen
  its `kind` CHECK and its shape CHECK, and a mistake there is a token one kind
  can redeem as another.
- **Deep-linking is a seam customers will feel.** "Open the menu" leaves the
  chat, and a customer who wanted to stay in it experiences that as the feature
  stopping.
- **Chat is not a transcript of intent.** A customer who taps Repeat and then
  scrolls away has a cart they may not remember making. The cart's own
  abandonment path is what catches that, and it was written for a browser.

### Accepted trade-offs

- **Nothing about the recipient is inferred, and this record originally said it
  would be.** The first draft accepted resolving the recipient's name and phone
  from the account, against `DestinationCommand`'s own javadoc, on the grounds
  that a bot cannot ask. Implementation found a better answer and this trade-off
  is withdrawn: carts are expired rather than deleted, so the order being
  repeated still has its own `ordering.cart_fulfillment` row, holding the exact
  `customer_address_id` and the recipient the customer themselves confirmed.
  A repeat carries that forward. Nothing is guessed, no address picker is
  needed, and ADR 0015 keeps sole ownership of what an address is.
- **A delivery repeat whose old address is gone hands off.** Archived, or its
  pin removed: `setDestination` refuses, the cart is built without a
  destination, and the customer finishes in the app. Worse than a tap, far
  better than a delivery to an address they did not choose.
- **Buttons expire and taps after that say so.** A token has a TTL, and a
  customer returning to an old message is told the button is stale rather than
  silently acting on week-old state.
- **The plan is still a snapshot.** ADR 0074's window between reading and
  rebuilding is narrower here, not closed; a dish 86'd in between makes the
  repeat fail with a named reason.

## Specification

### Tokens

New kinds in `integration.bot_action_tokens`, in a forward migration that
**restates the full value list of both CHECK constraints** — the existing
`ck_bot_action_token_kind` and `ck_bot_action_token_shape` — rather than adding
to them, since a CHECK cannot be extended in place:

- `CUSTOMER_ACTION` — `order_id` optional, `telegram_user_id` **required**,
  `pending_command` carrying the action (`STATUS`, `REPEAT`, `CART`,
  `CHECKOUT`, `RATE`), `pending_argument` carrying its parameter (a rating
  value, a payment method code, an address id).

Scoped to the Telegram account it was rendered for, exactly as `TENANT_SELECT`
already is, so a forwarded message's button cannot be tapped by somebody else.
The record's own `tenant_id` is the tenant the action runs against; the chat
binding must resolve to a customer account **in that tenant**, or the tap is
refused as unlinked.

TTL: 24 hours for `STATUS` and `RATE`, 15 minutes for `REPEAT`, `CART` and
`CHECKOUT` — the three that spend money or move a cart.

### The authorizer

`CustomerBotActionAuthorizer`, beside `BotCallbackAuthorizer` and deliberately
not inside it:

1. resolve the token, or answer expired;
2. check `TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED` for the token's tenant;
3. resolve the private chat through `TelegramBindingStore#customerAccountFor`,
   or answer not-linked;
4. refuse any update that is not a **private** chat — a customer action in a
   group would act on an account in front of strangers;
5. rate-limit per chat through the existing `RateLimiter`, at ADR 0033's
   policies, because a button can be tapped as fast as a finger moves;
6. call the port; record the ADR 0027 fact with the customer as actor.

No `AuthorizationService.require`. The comment saying why belongs in the class,
because its sibling's whole purpose is the opposite.

### The port

`ordering.api.CustomerBotOrderingPort`, implemented in `ordering.application`
over `CartService`, `CheckoutService`, `ReorderPlanService` and
`OrderQueryService`. Every method takes `(tenantId, brandId,
customerAccountId, …)` and resolves nothing from ambient state.

```
Optional<StatusView>   latestOrderStatus(...)
Optional<ReorderOffer> repeatableLastOrder(...)   // ADR 0074's plan, verdict included
CartView               repeatInto(..., UUID orderId)  // one transaction, CartService.putLine per line
CartView               currentCart(...)
CheckoutOffer          checkoutOptions(..., UUID cartId)  // methods, destination, total
CheckoutOutcome        checkout(..., UUID cartId, String paymentMethodCode)
void                   rate(..., UUID orderId, int rating)
```

`repeatInto` refuses unless the plan is `READY` at the moment it runs — the
plan read when the button was rendered is not the plan when it is tapped.

### Checkout from the chat

1. **Destination.** `PICKUP` needs none. `DELIVERY` uses the account's single
   active address; with several, an address-label picker; with none, a `url`
   handoff. Labels are shown only in the customer's own linked private chat.
2. **Recipient.** The account's display name and phone, decrypted at the point
   of use and **never rendered into the chat** beyond the masked tail the
   confirm step shows.
3. **Price.** `CartService.price` produces the ADR 0018 quote; the confirm
   message shows the total the customer is about to accept, and the button
   carries the quote's own context hash so a changed basket cannot be checked
   out against a stale total.
4. **Payment.** `CASH` confirms in chat. Every provider method answers with a
   `url` button and nothing else.
5. **Confirmation.** The order number, the promise, and a Status button.

### Privacy

The chat is bound to one customer account, so the customer's own data may be
shown in it. Nothing else may: no other customer's order, no staff view, no
address that is not theirs. The bot renders no full phone number and no full
address line — a label and a masked tail are enough to confirm, and a chat log
lives on a device the platform does not control.

### Testing

Against real PostgreSQL with `FakeTelegramBotApi`, in the genre ADR 0060's own
tests already established:

- a token minted for one Telegram account, tapped from another, is refused
- a customer action in a group chat is refused even from the linked account
- an expired token answers stale and changes nothing
- `REPEAT` on an order whose plan is no longer `READY` refuses and names why,
  proving the re-check at tap and not only at render
- a repeat builds a cart whose lines match the order's variants and modifier
  option ids exactly
- a repeat of eight lines is one transaction: an induced failure on the last
  line leaves no cart lines at all
- a `CASH` checkout from the chat produces an order; a CLICK checkout produces
  a `url` button and no order until the provider settles
- `RATE` twice on one order is refused by ADR 0071's own one-per-order
  constraint rather than by a check in the bot
- the entitlement off answers politely and acts on nothing
- no message the bot sends contains a full phone number or address line

## Rollout and rollback

Entitlement-gated off. One tenant, one brand, one location first, with the
buttons attached only to the order-confirmed message; Repeat and Checkout stay
dark until Status and Rate have run a week. Rollback is the entitlement flag —
the tokens expire on their own and the messages already sent lose their
buttons harmlessly.

## Implementation checklist

- [x] Migration: `CUSTOMER_ACTION` kind, both CHECK constraints restated in full
- [x] `TELEGRAM_CUSTOMER_INLINE_ACTIONS_ENABLED` entitlement key
- [x] `ordering.api.CustomerBotOrderingPort` and its application adapter
- [x] `reviews.api.CustomerReviewPort` for the rating
- [x] `CustomerBotActionAuthorizer`
- [x] `TelegramUpdateHandler` dispatch and rendering for customer callbacks
- [x] Uzbek, Russian and English copy for every action and every refusal
- [x] A status button on the `ORDER_CONFIRMED` message
- [x] An `ORDER_COMPLETED` customer notification, so a rating prompt has a
      message to ride on. This needed an `OrderCompleted` event first:
      `OrderStateService` had deliberately published none for COMPLETED, saying
      in place that PREPARING, READY, FULFILLING and COMPLETED "have no external
      consumer in this slice… rather than being published now to a catalogue
      nobody reads". This record is that consumer, so COMPLETED left the list
      and the other three stay unpublished for the reason that comment gives.
      The Telegram rendering of the new template carries the five-star row
- [ ] A re-engagement message carrying Repeat
- [ ] A Mini App URL on the installation, and the deep links that need one

## Exit criteria

A customer who ordered yesterday opens Telegram, taps **Yana buyurtma** on the
message the platform sent them, sees the same basket priced, taps **Naqd**, and
has an order number — without opening anything. The same customer, on a day the
kitchen has 86'd one of those dishes, sees no such button and is told which dish
is unavailable.

## References

- ADR 0060 §4 and V0106 — the token discipline this extends
- ADR 0074 — the plan every Repeat button is rendered from
- `TelegramUpdateHandler`, `BotCallbackAuthorizer`, `TelegramBindingStore`
