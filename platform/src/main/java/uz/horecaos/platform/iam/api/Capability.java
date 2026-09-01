package uz.horecaos.platform.iam.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The code-owned capability registry (ADR 0025).
 *
 * <p>A capability is a verb on a resource type. Capabilities are declared in
 * code rather than the database so an unknown capability fails at startup
 * instead of silently denying, and so the same name means the same thing in the
 * API, the frontend, the audit trail, and the tests.
 *
 * <p>Tenants compose roles from this catalogue; they can never invent a
 * capability. Adding one is a release.
 */
public enum Capability {
    TENANT_READ("tenant.read", "tenant", "read"),
    TENANT_WRITE("tenant.write", "tenant", "write"),
    TENANT_ONBOARDING_MANAGE("tenant.onboarding.manage", "tenant", "onboarding.manage"),

    BRAND_READ("brand.read", "brand", "read"),
    BRAND_WRITE("brand.write", "brand", "write"),
    LOCATION_READ("location.read", "location", "read"),
    LOCATION_WRITE("location.write", "location", "write"),

    /**
     * ADR 0038: reading which companies a tenant has registered and which one
     * sells at a branch on a given date.
     */
    LEGAL_ENTITY_READ("legal-entity.read", "legal-entity", "read"),

    /**
     * ADR 0038: registering, activating, suspending or archiving a legal entity,
     * and assigning one as a location's seller.
     *
     * <p>Not folded into {@link #TENANT_WRITE}. That capability administers the
     * tenant's own identity, brand and location tree; this one decides whose
     * name appears on every fiscal receipt a branch issues from the date it is
     * granted, which is a fiscal-identity decision rather than a tenant-profile
     * edit — the same distinction {@link #PAYMENT_MERCHANT_BINDING_MANAGE} draws
     * from {@link #INTEGRATION_INSTALLATION_MANAGE} for the merchant account
     * that settles under it.
     */
    LEGAL_ENTITY_MANAGE("legal-entity.manage", "legal-entity", "manage"),

    /** ADR 0036: the tenant-owned sales channel registry and its three matrices. */
    CHANNEL_READ("channel.read", "channel", "read"),
    CHANNEL_MANAGE("channel.manage", "channel", "manage"),

    /** ADR 0036: opening timetables, exceptions, bindings, and preparation bands. */
    SERVICEABILITY_MANAGE("serviceability.manage", "serviceability", "manage"),

    /**
     * ADR 0036: closing or force-opening one branch. Separate from
     * {@link #SERVICEABILITY_MANAGE} and granted at {@code LOCATION} scope, so a
     * branch manager can close their own branch when the fryer dies without
     * holding rights over the network's timetables.
     */
    LOCATION_SERVICE_STATE_CHANGE("location.service-state.change", "location", "service-state.change"),

    CATALOG_READ("catalog.read", "catalog", "read"),
    CATALOG_AUTHOR("catalog.author", "catalog", "author"),
    CATALOG_PUBLISH("catalog.publish", "catalog", "publish"),
    OFFERING_MANAGE("offering.manage", "offering", "manage"),

    MEDIA_READ("media.read", "media", "read"),
    MEDIA_UPLOAD("media.upload", "media", "upload"),

    INVENTORY_READ("inventory.read", "inventory", "read"),
    INVENTORY_ADJUST("inventory.adjust", "inventory", "adjust"),

    PRICING_READ("pricing.read", "pricing", "read"),
    PRICING_AUTHOR("pricing.author", "pricing", "author"),
    PRICING_ACTIVATE("pricing.activate", "pricing", "activate"),

    ORDER_READ("order.read", "order", "read"),

    ORDER_APPROVE("order.approve", "order", "approve"),

    /**
     * ADR 0019: moving a confirmed order along the kitchen path — preparing,
     * ready, out for delivery, completed.
     *
     * <p>Distinct from {@link #ORDER_STATE_OVERRIDE}, which is the power to force
     * a transition the ordinary path does not offer. Every line cook needs the
     * first; almost nobody should have the second.
     */
    ORDER_ADVANCE("order.advance", "order", "advance"),

    ORDER_CANCEL("order.cancel", "order", "cancel"),
    ORDER_STATE_OVERRIDE("order.state.override", "order", "state.override"),

    /**
     * ADR 0039: proposing and applying an amendment to a live order.
     *
     * <p>Separate from {@link #ORDER_ADVANCE} because the two are different
     * powers over the same order. Advancing moves an agreed order along the
     * kitchen path; amending changes what was agreed, and every one of the ten
     * commands has a declared consequence in the quote, the inventory hold, the
     * payment, the fiscal receipt and the POS export. A line cook needs the
     * first. Only somebody who can talk to the customer should have the second,
     * because a change the customer never agreed to is what an amendment can
     * produce and a status change cannot.
     */
    ORDER_AMEND("order.amend", "order", "amend"),

    /**
     * ADR 0039: authoring the tenant's cancellation and completion reasons.
     *
     * <p>Held by an administrator, never by the operator who picks from the list.
     * The whole argument for putting the stock disposition and the liable party on
     * the reason is that they are decided once, in advance, by somebody who can be
     * asked to justify them — and an operator who can edit the reason at 20:30 on
     * a Friday is an operator who can pick the write-off rate.
     */
    ORDER_OUTCOME_REASON_MANAGE("order.outcome-reason.manage", "order", "outcome-reason.manage"),

    /**
     * ADR 0030, Gap D of the 2026-08-30 proving run: authoring and versioning
     * the tenant's order acceptance policy — whether a paid order confirms
     * itself or waits for staff.
     *
     * <p>Held by an administrator, on the same argument {@link
     * #ORDER_OUTCOME_REASON_MANAGE} carries: this is decided once, in
     * advance, by somebody who can be asked to justify it, not from the order
     * board mid-shift. Never folded into {@link #TENANT_WRITE} — the tenant's
     * profile and its order-acceptance behaviour are different decisions with
     * different consequences, the same distinction that keeps {@link
     * #LEGAL_ENTITY_MANAGE} and {@link #PAYMENT_MERCHANT_BINDING_MANAGE}
     * their own capabilities.
     */
    ORDER_ACCEPTANCE_POLICY_MANAGE("order.acceptance-policy.manage", "order", "acceptance-policy.manage"),

    /**
     * ADR 0013: reading an order's payment — the intent, its attempts, and the
     * provider states recorded beside HorecaOS's own.
     *
     * <p>Separate from {@link #ORDER_READ} because an attempt names the merchant
     * account the money went through and the provider's identifiers for it. Staff
     * who work a branch's order queue need to know whether an order is paid; they
     * do not automatically need to know which legal entity's Click service settled
     * it.
     */
    PAYMENT_READ("payment.read", "payment", "read"),

    /**
     * ADR 0013: settling a payment attempt whose outcome is unknown.
     *
     * <p>Its own capability, and a narrow one, because of what the action is. An
     * uncertain attempt is a charge that may or may not have happened, and
     * resolving it wrongly in either direction costs real money: recorded as
     * failed when it succeeded, the customer is charged and uncredited; recorded
     * as succeeded when it failed, the restaurant cooks for free. The people
     * qualified to read a provider's status response are finance and integration
     * staff, not everyone who can see that a payment is stuck.
     */
    PAYMENT_ATTEMPT_RESOLVE("payment.attempt.resolve", "payment", "attempt.resolve"),

    /**
     * ADR 0013: registering and retiring a legal entity's merchant account.
     *
     * <p>The highest-consequence configuration action in the module. A binding
     * decides which restaurant's Click service or Payme cashbox settles a payment
     * and therefore whose name appears on the fiscal receipt, so pointing one at
     * the wrong legal entity is a tax error rather than a support ticket. It is
     * also the row that carries the ADR 0028 secret reference, which is why it is
     * not folded into {@link #INTEGRATION_INSTALLATION_MANAGE}: installing a
     * provider and deciding which seller it sells for are different powers.
     */
    PAYMENT_MERCHANT_BINDING_MANAGE("payment.merchant-binding.manage", "payment", "merchant-binding.manage"),

    /**
     * ADR 0013 and ADR 0038: reading an order's fiscal documents and the evidence
     * on them, including the recorded fact that a cash order has none.
     *
     * <p>Held by finance and by whoever answers a tax inspection. It is the read
     * that makes the {@code NOT_APPLICABLE} decision auditable rather than merely
     * stored.
     */
    FISCAL_DOCUMENT_READ("fiscal.document.read", "fiscal", "document.read"),

    /**
     * ADR 0038: acting on a fiscal document that owes a receipt and has not got
     * one — asking the partner again, or returning a blocked document to the
     * queue.
     *
     * <p>Separate from {@link #FISCAL_DOCUMENT_READ} because the two are different
     * powers held by different people. Reading the blocked worklist is something a
     * branch manager can usefully do; asking Click to fiscalize a payment again is
     * a request to a tax authority's agent, and a second sale receipt for one
     * payment can only be corrected with that authority, never withdrawn.
     *
     * <p>Also separate from {@link #PAYMENT_ATTEMPT_RESOLVE}, which settles whether
     * money moved. Whether a receipt exists and whether a charge succeeded are
     * independent questions with independent evidence, and a person qualified to
     * read a provider's payment status is not automatically the person who should
     * decide that a receipt obligation has been discharged.
     */
    FISCAL_DOCUMENT_RESOLVE("fiscal.document.resolve", "fiscal", "document.resolve"),

    REFUND_REQUEST("refund.request", "refund", "request"),
    REFUND_APPROVE("refund.approve", "refund", "approve"),
    REFUND_EXECUTE("refund.execute", "refund", "execute"),

    RECOVERY_CASE_MANAGE("recovery.case.manage", "recovery", "case.manage"),
    RECOVERY_REMEDY_APPROVE("recovery.remedy.approve", "recovery", "remedy.approve"),

    DELIVERY_PLAN_READ("delivery.plan.read", "delivery", "plan.read"),

    /** ADR 0037: reading zones, their versions, and the rate tables bound to them. */
    DELIVERY_ZONE_READ("delivery.zone.read", "delivery", "zone.read"),

    /** ADR 0037: drawing zones and authoring new versions of them, all of which stay DRAFT. */
    DELIVERY_ZONE_MANAGE("delivery.zone.manage", "delivery", "zone.manage"),

    /**
     * ADR 0037: making a zone version live.
     *
     * <p>Separate from {@link #DELIVERY_ZONE_MANAGE} for the reason ADR 0037 gives
     * for making activation an audited fact: activating a bad polygon stops sales
     * in a district, silently, and the person who drew it is the last person able
     * to notice that it is wrong. Drawing is routine work; deciding that a drawing
     * governs what the platform will sell is not.
     */
    DELIVERY_ZONE_ACTIVATE("delivery.zone.activate", "delivery", "zone.activate"),

    /** ADR 0037: authoring delivery tariffs, bands, and peak-hour rules as drafts. */
    DELIVERY_TARIFF_MANAGE("delivery.tariff.manage", "delivery", "tariff.manage"),

    /**
     * ADR 0037: making a tariff version live.
     *
     * <p>Split from authoring on the same argument as the zone, and with a
     * sharper edge: a rate table is money. The person who typed a per-kilometre
     * figure with a misplaced digit cannot be the only one who ever reads it.
     */
    DELIVERY_TARIFF_ACTIVATE("delivery.tariff.activate", "delivery", "tariff.activate"),

    /**
     * ADR 0037: reading why one address was charged one fee — the zone version,
     * tariff version, band, time rule, distance, and the candidates that lost.
     *
     * <p>Its own capability rather than part of {@link #DELIVERY_ZONE_READ}
     * because it is the support and finance view rather than the configuration
     * view, and the people who answer "why was my delivery 18,000 so'm" are not
     * usually the people who draw polygons.
     */
    DELIVERY_FEE_EVIDENCE_READ("delivery.fee.evidence.read", "delivery", "fee.evidence.read"),
    DELIVERY_MANUAL_ASSIGN("delivery.manual_assign", "delivery", "manual_assign"),
    SHIPMENT_CANCEL("shipment.cancel", "shipment", "cancel"),

    /**
     * ADR 0045: the live position of the on-duty in-house couriers of one branch.
     *
     * <p>Always granted at a {@code LOCATION} scope, so a dispatcher sees the
     * couriers of the branches they dispatch for and no others. It reads a
     * working set — rows exist only while a duty session is open and are deleted
     * an hour after it closes — which is what pays for storing those coordinates
     * unencrypted, and it is deliberately not audited per refresh: auditing a
     * five-second map produces more audit rows than the tenant has orders and
     * buries the reveal that matters.
     *
     * <p>No customer token and no partner surface carries it. Customers see the
     * ADR 0019 milestones and never a position.
     */
    COURIER_POSITION_READ("courier.position.read", "courier", "position.read"),

    /**
     * ADR 0045: opening one named courier's stored track, for one declared
     * purpose.
     *
     * <p><strong>In no default role bundle, including
     * {@link #PLATFORM_ADMIN}.</strong> A live map is where the fleet is now; a
     * track is where one identified self-employed person went, minute by minute,
     * for up to thirty days. Every use is an ADR 0029 bulk reveal with a declared
     * purpose and an ADR 0027 audit entry naming the actor, the courier, the
     * window, and the reason. A solo operator holding unbounded standing access
     * to a fleet's movement history is exactly the ADR 0034 concentration risk,
     * and the audit record is what makes the access answerable rather than
     * assumed — which it cannot be if the superuser bundle confers it silently.
     */
    COURIER_TRACK_REVEAL("courier.track.reveal", "courier", "track.reveal"),

    /**
     * ADR 0045: opening or closing a duty session on a courier's behalf.
     *
     * <p>A duty session is the window in which telemetry is collected at all, so
     * this is the power to start and stop collection. It is separate from
     * {@link #COURIER_POSITION_READ} because seeing where the fleet is and
     * deciding that a named person is now being tracked are different acts.
     *
     * <p>It cannot manufacture the prerequisite: ADR 0042 owns the shift and the
     * self-employment registration record, and a session refuses to open without
     * a valid one no matter who asks.
     */
    COURIER_DUTY_MANAGE("courier.duty.manage", "courier", "duty.manage"),

    /**
     * ADR 0041: configuring a branch's production stations and the rules that
     * route dishes onto them.
     *
     * <p>Not named by ADR 0041, which puts station and device creation on a
     * control-plane path without saying what guards it. It is separate from
     * {@link #LOCATION_WRITE} because the two powers belong to different people:
     * the station layout is the head chef's knowledge of their own kitchen, while
     * {@code location.write} opens and closes branches. It is separate from
     * {@link #KITCHEN_TICKET_ADVANCE} for a sharper reason — a routing rule
     * decides where every future order of that dish appears, so a line cook
     * editing one mid-service moves dishes off other people's screens.
     */
    KITCHEN_STATION_MANAGE("kitchen.station.manage", "kitchen", "station.manage"),

    /** ADR 0041: reading a branch's production tickets. Line, expo, and manager. */
    KITCHEN_TICKET_READ("kitchen.ticket.read", "kitchen", "ticket.read"),

    /**
     * ADR 0041: starting and readying lines, restricted to the principal's own
     * stations.
     *
     * <p>The everyday power on a kitchen screen, and the only one a line cook's
     * bundle needs. Held apart from the four below because each of those does
     * something a start button does not.
     */
    KITCHEN_TICKET_ADVANCE("kitchen.ticket.advance", "kitchen", "ticket.advance"),

    /**
     * ADR 0041: undoing a readiness the pass may already have acted on.
     *
     * <p>Expo and manager, never the line. By the time a recall is possible the
     * ticket has been called ready, a courier may have been dispatched against
     * it, and the person who can judge whether the dish can still be pulled back
     * is the one standing at the pass.
     */
    KITCHEN_TICKET_RECALL("kitchen.ticket.recall", "kitchen", "ticket.recall"),

    /**
     * ADR 0041: holding a ticket in the buffer and firing it.
     *
     * <p>Separate from {@link #KITCHEN_TICKET_ADVANCE} because it changes
     * <em>when</em> food is cooked rather than reporting that it is being cooked.
     * Releasing a whole evening's preorders at once is how a branch is buried.
     */
    KITCHEN_TICKET_RELEASE("kitchen.ticket.release", "kitchen", "ticket.release"),

    /**
     * ADR 0041: firing later than the promise permits.
     *
     * <p>Manager only, and it always travels with a reason and an ADR 0027 audit
     * fact. The distinction it draws is the whole point of splitting it from
     * {@link #KITCHEN_TICKET_RELEASE}: moving a ticket earlier breaks no promise,
     * while moving it later is a decision to be late, taken quietly, by someone
     * protecting their own kitchen's throughput number, and producing an order
     * nobody was warned about.
     */
    KITCHEN_TICKET_RELEASE_OVERRIDE("kitchen.ticket.release.override", "kitchen", "ticket.release.override"),

    /**
     * ADR 0047: authoring a branch's sections and tables, and configuring what a
     * scanned code does there.
     *
     * <p>A floor plan is physical property of a branch, so the grant that changes
     * one belongs to whoever runs that room. It is separate from
     * {@link #LOCATION_WRITE} because renaming a branch and rearranging its
     * dining room are different jobs done by different people.
     */
    DINEIN_FLOORPLAN_MANAGE("dinein.floorplan.manage", "dinein", "floorplan.manage"),

    /**
     * ADR 0047: reading bookings and table availability.
     *
     * <p>Held by a host stand, which needs to see who is coming and which tables
     * are free, and by nobody else — a booking carries a guest's name and phone
     * number, which is why reading the list is a capability of its own and not a
     * corollary of working in the building.
     */
    RESERVATION_READ("reservation.read", "reservation", "read"),

    /**
     * ADR 0047: taking, confirming, rejecting, cancelling, seating, and marking a
     * booking as a no-show.
     *
     * <p>Confirming is the transition that takes a table out of the market for an
     * interval, which is why it is a manage capability rather than a write on
     * whatever aggregate happens to hold it.
     */
    RESERVATION_MANAGE("reservation.manage", "reservation", "manage"),

    /** ADR 0047: reading a table's live session, its rounds, and its balance. */
    DINEIN_SESSION_READ("dinein.session.read", "dinein", "session.read"),

    /**
     * ADR 0047: opening a session, seating a party, attaching a round, taking the
     * bill request, and settling.
     *
     * <p>This is the waiter's grant, and it stops short of closing a session that
     * still owes money — see {@link #DINEIN_SESSION_FORCE_CLOSE}.
     */
    DINEIN_SESSION_MANAGE("dinein.session.manage", "dinein", "session.manage"),

    /**
     * ADR 0047: closing a session that has not been paid.
     *
     * <p>Deliberately not folded into {@link #DINEIN_SESSION_MANAGE}. A walkout is
     * a shift's cash shortfall, and the difference between a shortfall somebody
     * signed for and one that merely happened is whether the person who closed the
     * table needed a grant to do it. The transition also requires a reason code
     * and writes an ADR 0027 audit record; the capability is what makes the record
     * attributable to a role rather than to whoever was nearest the terminal.
     */
    DINEIN_SESSION_FORCE_CLOSE("dinein.session.force_close", "dinein", "session.force_close"),

    /**
     * ADR 0047: issuing or rotating the bearer token behind a table's QR code.
     *
     * <p>Separate from {@link #DINEIN_FLOORPLAN_MANAGE} because rotation is
     * destructive in the physical world: it invalidates card that is already
     * printed and sitting on tables, and a room whose codes were rotated by
     * accident cannot take a QR order until somebody has walked round it with a
     * printer.
     */
    DINEIN_QR_ROTATE("dinein.qr.rotate", "dinein", "qr.rotate"),

    /**
     * ADR 0040: an aggregator pushing an order into a branch it is bound to.
     *
     * <p>Held by a machine, never by a person, which is the whole difference
     * this group draws. ADR 0025's model is unchanged — a capability at a
     * scope — but the principal is an ADR 0026 installation's confidential
     * client rather than a member of an organization, and its reach is the set
     * of bindings that installation holds. A partner token therefore reads
     * nothing a branch operator at those locations could not, and can reach no
     * branch that installation is not bound to.
     */
    MARKETPLACE_ORDER_RECEIVE("marketplace.order.receive", "marketplace", "order.receive"),

    /**
     * ADR 0040: HorecaOS pushing a menu or an availability change out to a partner
     * that will not pull one.
     *
     * <p>Held by tenant staff and by the outbound job, not by the partner. It is
     * the capability behind an operator action with a wide blast radius —
     * republishing a menu to an aggregator changes what is sold on that channel
     * across every branch the binding covers.
     */
    MARKETPLACE_MENU_PUSH("marketplace.menu.push", "marketplace", "menu.push"),

    /**
     * ADR 0040: stopping or restarting one item on one aggregator.
     *
     * <p>Separate from {@link #MARKETPLACE_MENU_PUSH} on frequency: taking the
     * plov off Uzum for the evening is an act somebody at the branch performs
     * several times a week, while republishing a menu is not, and folding the
     * first into the second would put a menu republish in every branch bundle.
     */
    MARKETPLACE_AVAILABILITY_PUSH("marketplace.availability.push", "marketplace", "availability.push"),

    /**
     * ADR 0040: overriding handover verification.
     *
     * <p>Deliberately not folded into the capability that completes a handover.
     * Completing one is a daily act at the pass and everyone who works it needs
     * the grant; overriding verification is the decision to hand a bag over
     * without proof, and one capability covering both would put the override in
     * every expo bundle in the country. It always travels with a reason code and
     * an ADR 0027 audit fact naming the supervisor.
     */
    MARKETPLACE_HANDOVER_BYPASS("marketplace.handover.bypass", "marketplace", "handover.bypass"),

    /**
     * ADR 0040: an operator keying in an aggregator's order by hand.
     *
     * <p>For an unintegrated partner, or to recover a failed sync. Its own
     * capability rather than the customer's ownership path because of what the operator is
     * typing: a total the platform cannot verify against anything. The order is
     * booked at {@code pricing_authority = EXTERNAL} on somebody's word, so the
     * creation is an ADR 0027 audit fact and the grant is one an administrator
     * gives deliberately.
     */
    MARKETPLACE_ORDER_CREATE_MANUAL("marketplace.order.create.manual", "marketplace", "order.create.manual"),

    /**
     * ADR 0040: reading the liveness matrix — which branch has heard from which
     * aggregator, and how long ago.
     *
     * <p>Not named by ADR 0040, which specifies the endpoint without saying what
     * guards it. It is separate from {@link #INTEGRATION_FAILURE_READ} because
     * the two answer different questions: that one lists failures that happened,
     * and this one lists work that stopped arriving. A dead marketplace
     * integration produces no failures at all, which is exactly why it needs a
     * read of its own.
     */
    MARKETPLACE_LIVENESS_READ("marketplace.liveness.read", "marketplace", "liveness.read"),

    CUSTOMER_READ("customer.read", "customer", "read"),
    CUSTOMER_MANAGE("customer.manage", "customer", "manage"),
    /**
     * Reveals a customer's decrypted contact details or address (ADR 0029).
     * Separate from CUSTOMER_READ because seeing that a customer exists and
     * reading their phone number are different levels of access, and every
     * reveal is recorded with the purpose it was made for.
     */
    CUSTOMER_PII_REVEAL("customer.pii.reveal", "customer", "pii-reveal"),

    /**
     * ADR 0059 stage 3: importing a SendPulse contact export — creating or
     * matching customer accounts in bulk, binding their Telegram chats, and
     * recording consent provenance for every row in one call.
     *
     * <p>Its own capability rather than {@link #CUSTOMER_MANAGE}, and held
     * only by {@link PlatformRole#TENANT_OWNER} — see that constant's own
     * comment. A bulk write of customer accounts and consent decisions from
     * an external, about-to-be-retired vendor is the same weight class as
     * {@link #AUDIENCE_EXPORT} ("an unrestricted download of the customer
     * base is how a tenant's list ends up on a competitor's desk"): this is
     * the write-side mirror of that risk, and a tenant administrator who can
     * manage individual customers does not automatically get to bulk-create
     * thousands of them from a file with an invented consent record attached.
     */
    CUSTOMER_IMPORT("customer.import", "customer", "import"),

    INTEGRATION_INSTALLATION_MANAGE("integration.installation.manage", "integration", "installation.manage"),
    INTEGRATION_BINDING_ACTIVATE("integration.binding.activate", "integration", "binding.activate"),

    /**
     * ADR 0058: issuing a short-lived {@code /link <code>} for the Telegram
     * group-linking handshake.
     *
     * <p>Separate from {@link #INTEGRATION_INSTALLATION_MANAGE} because issuing a
     * code creates nothing by itself — the binding only exists once the bot
     * verifies its own rights in the group and the code is redeemed. It is closer
     * to "generate an invite" than to "configure a provider account".
     */
    INTEGRATION_TELEGRAM_LINK_ISSUE("integration.telegram-link.issue", "integration", "telegram-link.issue"),

    /**
     * ADR 0060: issuing a short-lived {@code /link <code>} that binds the
     * issuing staff member's own Telegram account to their own principal.
     *
     * <p>Separate from {@link #INTEGRATION_TELEGRAM_LINK_ISSUE}, which issues a
     * code for a <em>group</em> to receive operations alerts and is scoped to a
     * brand. This one is self-service and per-principal — nobody can request a
     * code that would link somebody else's account — so it is granted at
     * {@code TENANT} scope to every staff bundle that can act on an order or the
     * stop list, rather than reserved to an administrator.
     */
    INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE(
            "integration.telegram-staff-link.issue", "integration", "telegram-staff-link.issue"),
    /**
     * ADR 0012: reading a POS catalog synchronization run, its staged snapshot,
     * its differences, and its conflicts.
     *
     * <p>Separate from {@link #CATALOG_READ} because a run is integration
     * evidence rather than the menu. It names the provider's own identifiers, the
     * raw shape of what arrived, and every field where HorecaOS's value and the
     * provider's disagree — which is the diagnostic view, not the authoring one.
     */
    POS_SYNC_READ("pos.sync.read", "pos", "sync.read"),

    /**
     * ADR 0012: starting, resuming, and dry-running a catalog synchronization.
     *
     * <p>Deliberately not the power to change anything. A run reads the
     * provider, stages a snapshot and computes a comparison; on its own it
     * produces a report. Held by whoever operates the integration, which is not
     * necessarily whoever is allowed to accept what the report says.
     */
    POS_SYNC_EXECUTE("pos.sync.execute", "pos", "sync.execute"),

    /**
     * ADR 0012: recording review decisions on a run's differences and applying
     * the approved ones.
     *
     * <p>Split from {@link #POS_SYNC_EXECUTE} on the argument ADR 0012 makes for
     * a reviewed import in the first place: there is no safe undo for a menu that
     * was live and wrong during a lunch rush. Running the comparison is routine;
     * deciding that a provider's version of the menu becomes HorecaOS's is not, and
     * a single grant covering both would make the review a formality performed by
     * the same person who triggered the import.
     */
    POS_SYNC_APPLY("pos.sync.apply", "pos", "sync.apply"),

    /**
     * ADR 0011: reading an order's POS export, its attempts, and the candidate
     * orders a recovery read found at the provider.
     *
     * <p>Its own capability rather than part of {@link #ORDER_READ} because the
     * answer to "did the kitchen get this" is a list of provider order
     * identifiers and match evidence, which is integration diagnostics rather
     * than the branch's order queue.
     */
    POS_EXPORT_READ("pos.export.read", "pos", "export.read"),

    /**
     * ADR 0011: settling a POS export whose outcome is unknown.
     *
     * <p>Narrow, and for the same reason {@link #PAYMENT_ATTEMPT_RESOLVE} is. The
     * POS this platform integrates against offers no idempotency key of any kind,
     * so an export whose response was lost may or may not have printed a kitchen
     * ticket, and the only available recovery is a heuristic match on phone,
     * time, and line composition that cannot distinguish a double export from a
     * customer who ordered the same thing twice. Resolving it wrongly costs real
     * food in one direction and a hungry customer in the other, and the judgement
     * belongs to somebody who can pick up a telephone and ask the branch.
     */
    POS_EXPORT_RESOLVE("pos.export.resolve", "pos", "export.resolve"),

    INTEGRATION_FAILURE_READ("integration.failure.read", "integration", "failure.read"),
    INTEGRATION_FAILURE_RETRY("integration.failure.retry", "integration", "failure.retry"),
    INTEGRATION_FAILURE_RESOLVE("integration.failure.resolve", "integration", "failure.resolve"),

    NOTIFICATION_TEMPLATE_AUTHOR("notification.template.author", "notification", "template.author"),
    NOTIFICATION_TEMPLATE_ACTIVATE("notification.template.activate", "notification", "template.activate"),

    /**
     * ADR 0020: reading a notification's intent, its suppression reason, its
     * attempts, and the statuses the provider gave.
     *
     * <p>Separate from {@link #ORDER_READ} because the answer to "why did the
     * customer not get their confirmation?" names the endpoint the message was
     * addressed to. That is a reference and a hash rather than a phone number, but
     * it still says something about a person, and staff who work a branch's order
     * queue do not automatically need it.
     */
    NOTIFICATION_READ("notification.read", "notification", "read"),

    /**
     * ADR 0020: putting a settled notification back in the queue.
     *
     * <p>Never a power to send anyway. A retry re-runs the eligibility gate from
     * the start, so consent withdrawn since the message was suppressed still
     * refuses it — which is what stops a well-meaning support action from
     * overriding a customer's decision.
     */
    NOTIFICATION_RETRY("notification.retry", "notification", "retry"),

    /**
     * ADR 0058: raising a control-plane alert from outside the Java
     * process — {@code ops/control_band_watch.py}'s tier escalations
     * (ADR 0023, {@code ops/bands.yaml}) today, and any future non-Java
     * platform signal tomorrow. Platform-scoped only: a control-band metric
     * is arithmetic over the whole fleet, never one tenant's concern, so
     * there is no tenant-scoped grant of this capability to withhold.
     */
    CONTROL_PLANE_ALERT_RAISE("control-plane-alert.raise", "control-plane-alert", "raise"),

    /**
     * ADR 0044: defining an audience, and reading the segments already defined.
     *
     * <p>Read-only over a query, not over the customers themselves — the result is
     * counts and pseudonymous account ids. It is still separate from
     * {@link #CUSTOMER_READ}: an audience is a description of a group of people,
     * and being able to ask "how many customers have not ordered in ninety days"
     * is a different power from opening one customer's record.
     */
    AUDIENCE_READ("audience.read", "audience", "read"),

    /**
     * ADR 0044: downloading a snapshot's members.
     *
     * <p>Metrics and pseudonymous ids only. Contact values additionally require
     * {@link #CUSTOMER_PII_REVEAL} with a stated purpose and an audit record,
     * because an unrestricted download of the customer base is how a tenant's list
     * ends up on a competitor's desk. No amount of this capability produces a
     * segment upload to a third party: that is refused in code and is a disclosure
     * to a new controller rather than a permission level.
     */
    AUDIENCE_EXPORT("audience.export", "audience", "export"),

    /** ADR 0044: authoring a campaign, choosing its audience, template, and caps. */
    CAMPAIGN_AUTHOR("campaign.author", "campaign", "author"),

    /**
     * ADR 0044: the second signature, and the power to stop a running send.
     *
     * <p>Separate from {@link #CAMPAIGN_AUTHOR} and useless in the same hands: the
     * approver must not be the author, which the campaign table enforces in a CHECK
     * as well. The failure being prevented is a marketer testing a template and
     * sending forty thousand real SMS, and there is no undo for an SMS.
     */
    CAMPAIGN_APPROVE("campaign.approve", "campaign", "approve"),

    /**
     * ADR 0044: recording a suppression, and lifting one.
     *
     * <p>The lift is the dangerous half. A marketer who could clear their own
     * bounce list could inflate reach, and the removal would look like the customer
     * never bounced — so lifting is capability-gated and audited, and most
     * marketers will not hold this.
     */
    SUPPRESSION_MANAGE("suppression.manage", "suppression", "manage"),

    COMMERCIAL_SUBSCRIPTION_MANAGE("commercial.subscription.manage", "commercial", "subscription.manage"),
    COMMERCIAL_OVERRIDE_APPROVE("commercial.override.approve", "commercial", "override.approve"),

    /**
     * ADR 0021: reading the plan catalogue and one tenant's own subscription,
     * entitlements, and usage.
     *
     * <p>Composed into tenant roles, unlike everything else in this group. A
     * restaurant that is about to be told it has passed its included orders is
     * owed the ability to see the number first, and a limit a tenant cannot read
     * is a limit it will only discover by being refused.
     */
    COMMERCIAL_PLAN_READ("commercial.plan.read", "commercial", "plan.read"),

    /**
     * ADR 0021: drafting plans and plan versions, which stay DRAFT.
     *
     * <p>Platform staff only. Authoring a price list is routine commercial work;
     * deciding that one governs what tenants are sold is not, which is why
     * activation is {@link #COMMERCIAL_PLAN_ACTIVATE} and not this.
     */
    COMMERCIAL_PLAN_MANAGE("commercial.plan.manage", "commercial", "plan.manage"),

    /**
     * ADR 0021: making a plan version live, and thereby immutable.
     *
     * <p><strong>Never held by the person who drafted the version.</strong> The
     * split is the same one ADR 0037 draws over a delivery tariff and for the
     * same reason: a rate table is money, and the person who typed a figure with
     * a misplaced digit is the last person able to notice it. The four-eyes
     * constraint on {@code commercial.plan_versions} catches one subject doing
     * both; it cannot catch two accounts held by one team granted both
     * capabilities.
     */
    COMMERCIAL_PLAN_ACTIVATE("commercial.plan.activate", "commercial", "plan.activate"),

    /**
     * ADR 0021: reading a tenant's metered usage and the movements behind it.
     *
     * <p>Separate from {@link #COMMERCIAL_PLAN_READ} because this is the evidence
     * an invoice is defended with rather than the price list, and the people who
     * answer "why is this 19 640 orders" are finance rather than everyone who can
     * see which plan a tenant is on.
     */
    COMMERCIAL_USAGE_READ("commercial.usage.read", "commercial", "usage.read"),

    /**
     * ADR 0021: correcting metered usage with a signed adjustment.
     *
     * <p>The only way a consumed figure ever changes, and it always leaves a
     * reason, an author and an approver behind it. There is deliberately no
     * capability anywhere that can edit or delete a recorded movement, because
     * the ledger's value is entirely that nothing can.
     */
    COMMERCIAL_USAGE_ADJUST("commercial.usage.adjust", "commercial", "usage.adjust"),

    /**
     * ADR 0046: reading a customer's points balance, their movements, and the
     * brand's outstanding liability.
     *
     * <p>Read by the storefront through customer ownership, and by support staff
     * answering "where did my points
     * go". A balance a customer cannot read is a balance they will only discover
     * by being refused at a checkout.
     */
    LOYALTY_READ("loyalty.read", "loyalty", "read"),

    /**
     * ADR 0046: crediting or debiting a points balance by hand.
     *
     * <p><strong>The only capability in the platform that can create points
     * outside an order.</strong> An unbounded manual credit is a cash drawer that
     * any operations console login can open, so it carries an ADR 0027 approval
     * above a configured threshold, and every use leaves a reason, an actor and
     * an approver behind it.
     *
     * <p>It cannot move points between people. The adjustment command takes one
     * account and one signed amount and has no paired form, so a "transfer" is
     * two separate acts, each individually approved and each individually
     * countable on a report. That is deliberate: it does not make the manoeuvre
     * impossible, it makes it visible.
     *
     * <p>There is deliberately no capability that pays a balance out, and none
     * that edits or deletes a recorded movement. The ledger's value is entirely
     * that nothing can.
     */
    LOYALTY_ADJUST("loyalty.adjust", "loyalty", "adjust"),

    /**
     * ADR 0046: authoring a brand's accrual rules and redemption policy.
     *
     * <p>Separate from {@link #LOYALTY_ADJUST} because these are the numbers
     * rather than one customer's balance, and because a redemption reduces
     * declared revenue and VAT: raising an accrual rate is a tax decision, and the
     * person who makes it will usually not know that. Granted at brand scope,
     * since a brand's outstanding points are its own legal entity's liability.
     */
    LOYALTY_POLICY_MANAGE("loyalty.policy.manage", "loyalty", "policy.manage"),

    /** Granting and revoking roles within a scope the granter already covers. */
    IAM_GRANT_MANAGE("iam.grant.manage", "iam", "grant.manage"),

    /**
     * ADR 0043 names this capability {@code report.read}. It is the one already
     * registered here: reading a report and reading a metric definition are the
     * same power, and a second code for it would let a role hold one name and not
     * the other while a reader assumed they were equivalent.
     */
    REPORTING_READ("reporting.read", "reporting", "read"),

    /**
     * ADR 0043: recording finance's signature over a metric definition version.
     *
     * <p>Platform-scoped and never composed into a tenant role. A signature is
     * the statement that a number's definition has been read and agreed, and it
     * is what moves a metric from provisional to settled on every tenant's
     * screen at once. A tenant signing its own definition of average check would
     * make the registry a per-tenant setting, which is the failure the registry
     * exists to prevent.
     *
     * <p>It cannot edit a definition. Definitions are code, and a change to one
     * is a new version and a release.
     */
    METRIC_MANAGE("metric.manage", "metric", "manage"),

    AUDIT_READ("audit.read", "audit", "read"),

    /**
     * ADR 0027: authoring the maker-checker thresholds themselves — listing the
     * policies that govern a tenant, publishing a new version of one, and
     * end-dating one.
     *
     * <p>Its own capability rather than a use of {@link #AUDIT_READ} or of the
     * capability whose action it governs, because the person who decides that a
     * refund above a million so'm needs a second signature must not be the person
     * who signs. Reusing {@code refund.approve} here would mean the approver
     * could raise the bar above whatever they were about to approve and then
     * approve it alone, which is the control inverted rather than applied.
     *
     * <p>Held by {@code tenant-owner} and by nobody who executes what the
     * policies gate: not finance, which executes refunds and closes settlement
     * periods, and not the tenant administrator, who adjusts loyalty balances and
     * raises refunds. Reading is behind the same capability as writing, because
     * knowing a threshold is knowing exactly how much can be moved without
     * anyone else seeing it.
     */
    APPROVAL_POLICY_MANAGE("approval.policy.manage", "approval", "policy.manage"),

    /**
     * ADR 0027: reaching the approvals console — seeing what is waiting for a
     * second signature in a tenant, and posting a decision on one of them.
     *
     * <p><strong>Holding this authorizes no particular approval.</strong> It is
     * admission to the surface and nothing more. What decides whether this
     * principal may sign <em>this</em> request is the policy's own
     * {@code required_approver_capability}, evaluated at the scope the request
     * was raised at, plus the rule that the requester is never the approver. A
     * console that let anybody holding one flat "approve" capability decide any
     * pending request would let a refund approver sign off a loyalty adjustment
     * or an onboarding step by pasting a different identifier, which is the
     * failure {@code OperationsRemedyController} refused to introduce by leaving
     * the decide surface unbuilt.
     *
     * <p>Separate from {@link #APPROVAL_POLICY_MANAGE} for the same reason that
     * one exists at all: the person who sets the bar must not be the person who
     * clears it. The overlap is deliberate and narrow — the owner holds both, and
     * a platform-scoped policy over the policy-authoring action is what covers an
     * owner raising their own bar.
     */
    APPROVAL_DECIDE("approval.decide", "approval", "decide"),

    /**
     * ADR 0024: reading the migration control plane — programs, scopes, runs,
     * quarantine, reconciliation evidence, and cutover decisions.
     *
     * <p>Held by HorecaOS staff running the migration, never composed into a tenant
     * role. A scope names the source estate being retired and the reconciliation
     * evidence that decides whether it may be, which is the platform's operational
     * record and not the tenant's.
     */
    MIGRATION_READ("migration.read", "migration", "read"),

    /**
     * ADR 0024: planning the migration and driving a scope through the states
     * that do not transfer ownership — discovery, mapping approval, backfill,
     * catch-up, shadow reads, canary, and the two holding states.
     *
     * <p>This is the maker of ADR 0027's maker-checker. It deliberately stops
     * short of the transition that moves the writer.
     */
    MIGRATION_SCOPE_MANAGE("migration.scope.manage", "migration", "scope.manage"),

    /**
     * ADR 0024: starting, resuming, and cancelling backfill, catch-up,
     * remediation, and reconciliation runs.
     *
     * <p>Separate from {@link #MIGRATION_SCOPE_MANAGE} because running a migrator
     * against production data is a power an on-call engineer needs during a
     * window, while re-planning a scope's route through the states is not.
     */
    MIGRATION_RUN_EXECUTE("migration.run.execute", "migration", "run.execute"),

    /**
     * ADR 0024: deciding a cutover — transferring the writer to the target,
     * reopening a soaking cutover, and rolling one back.
     *
     * <p><strong>Never granted to whoever holds {@link #MIGRATION_SCOPE_MANAGE}
     * on the same program.</strong> ADR 0027 requires the person who requests a
     * change of this weight not to be the person who approves it, and the whole
     * force of that rule here is that the operator who brought a scope to
     * {@code CUTOVER_READY} is the one whose judgement the second pair of eyes
     * exists to check. Collapsing the two into one capability leaves the approval
     * record intact and the approval itself meaningless: a single grant would let
     * one person prepare the evidence, sign it, and move a capability's writer
     * without anyone else reading a line of it. The four-eyes constraint on
     * {@code migration.cutover_decisions} catches the same subject approving their
     * own request; it cannot catch two accounts held by one team that was granted
     * both capabilities.
     */
    MIGRATION_CUTOVER_APPROVE("migration.cutover.approve", "migration", "cutover.approve"),

    /**
     * ADR 0024: settling a quarantined legacy row — re-imported after a source
     * fix, mapped by hand under review, or accepted as not migratable.
     *
     * <p>Distinct because an open quarantine backlog blocks cutover, so the power
     * to close items is the power to clear that gate. It is granted to the people
     * who can actually judge a broken source row, which is rarely the same person
     * who approves the cutover it unblocks.
     */
    MIGRATION_QUARANTINE_RESOLVE("migration.quarantine.resolve", "migration", "quarantine.resolve"),

    /**
     * ADR 0042: a courier opening their own shift, and taking their own breaks.
     *
     * <p>Two capabilities rather than one "shift manage", and neither is ever
     * granted to a manager. A self-employed person decides when they work: a
     * manager who could open a shift could create paid hours for somebody who was
     * at home, and a manager who could end a break would be directing rest
     * periods, which is the fact pattern that reclassifies the engagement. Held
     * by the courier over their own record only.
     */
    COURIER_SHIFT_OPEN("courier.shift.open", "courier", "shift.open"),
    COURIER_SHIFT_BREAK("courier.shift.break", "courier", "shift.break"),

    /**
     * ADR 0042: approving a shift's hours, and closing somebody else's shift.
     *
     * <p>A manager's half of the shift. Closing is here because ending service
     * and sending somebody home is a safety and premises matter; approving is
     * here because hours that vary need a second person, and it is never the
     * courier.
     */
    COURIER_SHIFT_APPROVE("courier.shift.approve", "courier", "shift.approve"),

    /** ADR 0042: opening, suspending, and ending a courier's engagement. */
    COURIER_ENGAGEMENT_MANAGE("courier.engagement.manage", "courier", "engagement.manage"),

    /**
     * ADR 0042: attesting that a courier's self-employment registration was
     * sighted. Separate from {@link #COURIER_ENGAGEMENT_MANAGE} because a false
     * attestation is what makes an undeclared arrangement look compliant, and
     * the person who can onboard is not automatically the person who may swear
     * to a document.
     */
    COURIER_REGISTRATION_VERIFY("courier.registration.verify", "courier", "registration.verify"),

    /**
     * ADR 0042 and ADR 0029: revealing the registration identifier itself, for
     * the accountant export. Every use is an audited reveal under a declared
     * purpose, and almost nobody needs it.
     */
    COURIER_REGISTRATION_REVEAL("courier.registration.reveal", "courier", "registration.reveal"),

    /** ADR 0042: authoring and activating courier rate cards. */
    COURIER_RATECARD_MANAGE("courier.ratecard.manage", "courier", "ratecard.manage"),

    /**
     * ADR 0042: requesting a bonus or a penalty, and approving one.
     *
     * <p>Split because a manager who can both request and approve a penalty can
     * silently debit a courier's pay, which is a labour dispute and a fraud
     * vector in one instrument.
     */
    COURIER_ADJUSTMENT_CREATE("courier.adjustment.create", "courier", "adjustment.create"),
    COURIER_ADJUSTMENT_APPROVE("courier.adjustment.approve", "courier", "adjustment.approve"),

    /** ADR 0042: a branch cashier confirming what cash was actually received. */
    COURIER_CASH_CONFIRM("courier.cash.confirm", "courier", "cash.confirm"),

    /**
     * ADR 0042: reading a courier's ledger. A courier reads their own and nobody
     * else's, which is a property of the query rather than of this grant.
     */
    COURIER_LEDGER_READ("courier.ledger.read", "courier", "ledger.read"),

    /** ADR 0042: closing a settlement period and producing its statement. */
    COURIER_SETTLEMENT_CLOSE("courier.settlement.close", "courier", "settlement.close"),

    /**
     * ADR 0042: authorising the payout for a closed period. Distinct from
     * closing, because a period carrying the compliance flag needs four eyes and
     * the person who closed it must not be able to supply the second pair.
     */
    COURIER_PAYOUT_AUTHORISE("courier.payout.authorise", "courier", "payout.authorise"),

    /**
     * ADR 0042: reading delivery cost across the in-house and partner paths. The
     * query refuses to answer without a stated basis.
     */
    DELIVERY_COST_READ("delivery.cost.read", "delivery", "cost.read"),

    /** ADR 0042: importing and matching a delivery partner's invoice. */
    PARTNER_INVOICE_MANAGE("partner.invoice.manage", "partner", "invoice.manage"),

    /**
     * ADR 0059: authoring the next version of a brand's flow document, and
     * activating it. YAML is the only authoring surface — this is what gates
     * the control-plane endpoint that accepts it, in the {@link
     * #APPROVAL_POLICY_MANAGE}/{@code ApprovalPolicyController} genre:
     * versioned, never edited in place, and this capability alone (not
     * {@link #CUSTOMER_READ} or any other) decides who may publish a flow
     * that answers a customer before a person ever reads what it captured.
     */
    CONVERSATION_FLOW_MANAGE("conversation.flow.manage", "conversation-flow", "manage"),

    /**
     * ADR 0059 stage 2: the operator inbox — reading a conversation's
     * decrypted history, replying, taking a conversation over from the flow
     * engine, returning it, and closing it. Brand scope, because a
     * conversation belongs to a brand's bot, not to any one location
     * ({@code conversations.conversations} has no location column at all).
     *
     * <p>Held by {@link PlatformRole#TENANT_OWNER}, {@link
     * PlatformRole#TENANT_ADMIN}, and {@link PlatformRole#LOCATION_MANAGER} —
     * the intersection of two existing judgments rather than a fresh one:
     * every role that holds {@code order.approve} (the board's own
     * floor-staff bar) <em>and</em> every role that holds {@link
     * #CUSTOMER_PII_REVEAL} (this capability decrypts a customer's own words,
     * the same class of access). {@code location-staff} holds the former but
     * not the latter — it holds no customer capability anywhere in its
     * bundle — and is deliberately excluded here for the same reason.
     * {@code brand-manager} is the exact scope match but holds neither
     * {@code order.approve} nor {@code customer.pii.reveal}, and is excluded
     * too. One caveat worth naming rather than hiding: {@code
     * location-manager} is normally granted at {@code LOCATION} scope, which
     * does not on its own satisfy this capability's {@code BRAND}-scope
     * check ({@link uz.horecaos.platform.iam.api.ResourceScope#covers}
     * requires an equal-or-broader grant) — a tenant that wants a location
     * manager to actually use the inbox grants that person's role at
     * {@code BRAND} scope specifically, which {@code PlatformRole.scopeType}
     * documents as the normal case, not the only permitted one.
     */
    CONVERSATION_INBOX_MANAGE("conversation.inbox.manage", "conversation-inbox", "manage"),

    /**
     * Global control-plane administration. Issued by Keycloak as described in
     * ADR 0003 and never granted through tenant administration.
     */
    PLATFORM_ADMIN("platform.admin", "platform", "admin");

    private static final Map<String, Capability> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Capability::code, Function.identity()));

    private final String code;
    private final String resourceType;
    private final String action;

    Capability(String code, String resourceType, String action) {
        this.code = code;
        this.resourceType = resourceType;
        this.action = action;
    }

    public String code() {
        return code;
    }

    public String resourceType() {
        return resourceType;
    }

    public String action() {
        return action;
    }

    public static Optional<Capability> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code.toLowerCase(Locale.ROOT)));
    }

    public static Capability require(String code) {
        return find(code)
                .orElseThrow(() -> new UnknownCapabilityException(
                        "Unknown capability \"%s\". Declare it in Capability (ADR 0025).".formatted(code)));
    }

    /** Thrown when a stored role references a capability code that code does not declare. */
    public static final class UnknownCapabilityException extends IllegalStateException {
        public UnknownCapabilityException(String message) {
            super(message);
        }
    }
}
