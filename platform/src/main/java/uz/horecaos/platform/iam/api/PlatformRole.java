package uz.horecaos.platform.iam.api;

import static uz.horecaos.platform.iam.api.Capability.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * The platform-defined role bundles shipped with the product (ADR 0025).
 *
 * <p>Tenants compose grants from these roles; they cannot define their own in
 * the first release. The schema supports tenant-defined roles, so adding them
 * later is not a migration.
 *
 * <p>The set is deliberately granular, separating finance, support, and courier
 * dispatch from general administration. The trade-off recorded when this was
 * decided is that some bundles are guesswork until real tenants exist, so
 * {@code PlatformRoleTests} asserts that no capability is left unheld: an orphan
 * capability means either a missing bundle or a dead capability, and both should
 * be visible rather than silently rotting.
 *
 * <p>That assertion excludes {@link #PLATFORM_ADMIN}, and the exclusion is the
 * whole of its value. The superuser holds {@code allOf(Capability.class)}, so
 * while it counted, every capability was held the moment it was declared and the
 * check could never fail. When enforcement was turned on, sixty of the hundred
 * and five capabilities turned out to be held by no bundle but the superuser, and
 * forty-four of those were plain gaps — a line cook could not read a kitchen
 * ticket, a waiter could not open a table, a tenant could not read its own
 * subscription — because nothing had ever refused a request and so nothing had
 * ever needed a bundle to be right. Each capability below is placed by
 * the argument its own declaration in {@link Capability} makes about who holds
 * it, and the two classes that deliberately have no bundle are listed in the
 * test rather than left to be inferred from their absence.
 */
public enum PlatformRole {

    /**
     * Global control-plane administration. Issued by Keycloak per ADR 0003 and
     * never grantable through tenant administration.
     *
     * <p>Everything except {@link Capability#COURIER_TRACK_REVEAL}, and the
     * exception is ADR 0045's, not an oversight. This platform runs on one
     * machine with one operator (ADR 0034), so the superuser bundle is the one
     * place where "holds every capability" and "one person holds it" are the same
     * sentence. A stored track is a movement history of identified self-employed
     * individuals, and ADR 0045 requires every reveal of one to be granted
     * deliberately, per person, with a declared purpose and an ADR 0027 audit
     * entry. A superuser who already implicitly holds it never has to ask, so
     * nothing is ever recorded, and the control ADR 0045 designed reduces to a
     * comment. Granting it is therefore always an explicit act, even here.
     */
    PLATFORM_ADMIN("platform-admin", ScopeType.PLATFORM, EnumSet.complementOf(EnumSet.of(COURIER_TRACK_REVEAL))),

    /** Read-only cross-tenant support. Deliberately excludes every mutation. */
    PLATFORM_SUPPORT(
            "platform-support",
            ScopeType.PLATFORM,
            EnumSet.of(
                    TENANT_READ,
                    BRAND_READ,
                    LOCATION_READ,
                    CATALOG_READ,
                    CHANNEL_READ,
                    INVENTORY_READ,
                    PRICING_READ,
                    ORDER_READ,
                    CUSTOMER_READ,
                    DELIVERY_PLAN_READ,
                    INTEGRATION_FAILURE_READ,
                    REPORTING_READ,
                    AUDIT_READ)),

    TENANT_OWNER(
            "tenant-owner",
            ScopeType.TENANT,
            EnumSet.of(
                    // ADR 0027: which of this tenant's actions need a second signature,
                    // and above what. Held here alone among the tenant bundles, and
                    // deliberately not by finance or the administrator: both execute
                    // actions the policies gate, and an executor who can move their own
                    // threshold has a control that stands down whenever it matters.
                    // The owner still holds refund.execute, so the separation is between
                    // roles rather than absolute; the residual case — an owner raising
                    // their own bar — is what a platform-scoped policy over
                    // approval.policy.author is for, and it needs the decide path ADR
                    // 0027 records as missing.
                    APPROVAL_POLICY_MANAGE,
                    // ADR 0027: and the console that path was missing. Admission only —
                    // which of the tenant's pending requests this owner may actually sign
                    // is the policy's required_approver_capability, checked per request,
                    // and never one the owner raised themselves.
                    APPROVAL_DECIDE,
                    // ADR 0044: the second signature on a send, and the power to lift a
                    // suppression. Both are deliberately away from whoever writes the
                    // campaign — the approver must not be the author, and a marketer who
                    // could clear their own bounce list could inflate reach while making
                    // the removal look like the customer never bounced.
                    CAMPAIGN_APPROVE,
                    SUPPRESSION_MANAGE,
                    // ADR 0044: downloading a snapshot's members. Held here and nowhere
                    // else, because an unrestricted download of the customer base is how
                    // a tenant's list ends up on a competitor's desk — and contact values
                    // additionally need CUSTOMER_PII_REVEAL with a stated purpose, so
                    // this alone yields metrics and pseudonymous ids.
                    AUDIENCE_EXPORT,
                    // ADR 0046: what a point is worth, and how long it lasts. A currency
                    // decision rather than an operational one.
                    LOYALTY_POLICY_MANAGE,
                    // ADR 0042: a rate card is what a courier is paid, and authorising a
                    // payout is the second signature on money leaving. Held apart from
                    // COURIER_SETTLEMENT_CLOSE, which finance holds, so no one person
                    // both closes a period and releases its money.
                    COURIER_RATECARD_MANAGE,
                    COURIER_PAYOUT_AUTHORISE,
                    TENANT_READ,
                    TENANT_WRITE,
                    TENANT_ONBOARDING_MANAGE,
                    // ADR 0038: registering a legal entity and naming it a location's
                    // seller, held here alone among the tenant bundles for the reason
                    // PAYMENT_MERCHANT_BINDING_MANAGE is: it decides whose name appears
                    // on every fiscal receipt a branch issues, and neither the
                    // administrator nor finance may move that on their own.
                    LEGAL_ENTITY_READ,
                    LEGAL_ENTITY_MANAGE,
                    BRAND_READ,
                    BRAND_WRITE,
                    LOCATION_READ,
                    LOCATION_WRITE,
                    CATALOG_READ,
                    CATALOG_AUTHOR,
                    CATALOG_PUBLISH,
                    OFFERING_MANAGE,
                    CHANNEL_READ,
                    CHANNEL_MANAGE,
                    SERVICEABILITY_MANAGE,
                    LOCATION_SERVICE_STATE_CHANGE,
                    INVENTORY_READ,
                    INVENTORY_ADJUST,
                    PRICING_READ,
                    PRICING_AUTHOR,
                    PRICING_ACTIVATE,
                    ORDER_READ,
                    ORDER_APPROVE,
                    ORDER_ADVANCE,
                    ORDER_AMEND,
                    ORDER_CANCEL,
                    ORDER_STATE_OVERRIDE,
                    ORDER_OUTCOME_REASON_MANAGE,
                    ORDER_ACCEPTANCE_POLICY_MANAGE,
                    REFUND_REQUEST,
                    REFUND_APPROVE,
                    REFUND_EXECUTE,
                    RECOVERY_CASE_MANAGE,
                    RECOVERY_REMEDY_APPROVE,
                    PAYMENT_READ,
                    PAYMENT_ATTEMPT_RESOLVE,
                    PAYMENT_MERCHANT_BINDING_MANAGE,
                    FISCAL_DOCUMENT_READ,
                    FISCAL_DOCUMENT_RESOLVE,
                    DELIVERY_PLAN_READ,
                    DELIVERY_MANUAL_ASSIGN,
                    SHIPMENT_CANCEL,
                    DELIVERY_ZONE_READ,
                    DELIVERY_ZONE_MANAGE,
                    DELIVERY_ZONE_ACTIVATE,
                    DELIVERY_TARIFF_MANAGE,
                    DELIVERY_TARIFF_ACTIVATE,
                    DELIVERY_FEE_EVIDENCE_READ,
                    KITCHEN_STATION_MANAGE,
                    KITCHEN_TICKET_READ,
                    KITCHEN_TICKET_ADVANCE,
                    KITCHEN_TICKET_RECALL,
                    KITCHEN_TICKET_RELEASE,
                    KITCHEN_TICKET_RELEASE_OVERRIDE,
                    DINEIN_FLOORPLAN_MANAGE,
                    DINEIN_QR_ROTATE,
                    DINEIN_SESSION_READ,
                    DINEIN_SESSION_MANAGE,
                    DINEIN_SESSION_FORCE_CLOSE,
                    RESERVATION_READ,
                    RESERVATION_MANAGE,
                    MARKETPLACE_MENU_PUSH,
                    MARKETPLACE_AVAILABILITY_PUSH,
                    MARKETPLACE_ORDER_CREATE_MANUAL,
                    MARKETPLACE_HANDOVER_BYPASS,
                    MARKETPLACE_LIVENESS_READ,
                    CUSTOMER_READ,
                    CUSTOMER_MANAGE,
                    CUSTOMER_PII_REVEAL,
                    MEDIA_READ,
                    MEDIA_UPLOAD,
                    INTEGRATION_INSTALLATION_MANAGE,
                    INTEGRATION_BINDING_ACTIVATE,
                    INTEGRATION_TELEGRAM_LINK_ISSUE,
                    INTEGRATION_FAILURE_READ,
                    INTEGRATION_FAILURE_RETRY,
                    POS_SYNC_READ,
                    POS_SYNC_EXECUTE,
                    POS_SYNC_APPLY,
                    POS_EXPORT_READ,
                    POS_EXPORT_RESOLVE,
                    NOTIFICATION_TEMPLATE_AUTHOR,
                    NOTIFICATION_TEMPLATE_ACTIVATE,
                    NOTIFICATION_READ,
                    NOTIFICATION_RETRY,
                    COMMERCIAL_SUBSCRIPTION_MANAGE,
                    COMMERCIAL_PLAN_READ,
                    COMMERCIAL_USAGE_READ,
                    IAM_GRANT_MANAGE,
                    REPORTING_READ,
                    AUDIT_READ)),

    /** Everything the owner has except commercial and financial execution. */
    TENANT_ADMIN(
            "tenant-admin",
            ScopeType.TENANT,
            EnumSet.of(
                    // ADR 0044: authoring campaigns and reading audiences. Export is
                    // not here — an unrestricted download of the customer base is how a
                    // tenant's list reaches a competitor, so it sits with the owner.
                    AUDIENCE_READ,
                    CAMPAIGN_AUTHOR,
                    // ADR 0046: correcting a balance by hand, which support cannot do.
                    LOYALTY_READ,
                    LOYALTY_ADJUST,
                    // ADR 0027: admission to the approvals console. The administrator
                    // holds loyalty.adjust and tenant.onboarding.manage, which are two of
                    // the capabilities a policy is most likely to name as the second
                    // signature, so shutting them out of the console would leave those
                    // requests with no one able to reach them.
                    APPROVAL_DECIDE,
                    // ADR 0042: taking a courier on, and checking their self-employment
                    // registration is current. The reveal of the registration number
                    // itself is not here; it is granted per person with a purpose.
                    COURIER_ENGAGEMENT_MANAGE,
                    COURIER_REGISTRATION_VERIFY,
                    TENANT_READ,
                    TENANT_ONBOARDING_MANAGE,
                    BRAND_READ,
                    BRAND_WRITE,
                    LOCATION_READ,
                    LOCATION_WRITE,
                    CATALOG_READ,
                    CATALOG_AUTHOR,
                    CATALOG_PUBLISH,
                    OFFERING_MANAGE,
                    CHANNEL_READ,
                    CHANNEL_MANAGE,
                    SERVICEABILITY_MANAGE,
                    LOCATION_SERVICE_STATE_CHANGE,
                    INVENTORY_READ,
                    INVENTORY_ADJUST,
                    PRICING_READ,
                    PRICING_AUTHOR,
                    PRICING_ACTIVATE,
                    ORDER_READ,
                    ORDER_APPROVE,
                    ORDER_ADVANCE,
                    ORDER_AMEND,
                    ORDER_CANCEL,
                    ORDER_STATE_OVERRIDE,
                    ORDER_OUTCOME_REASON_MANAGE,
                    ORDER_ACCEPTANCE_POLICY_MANAGE,
                    REFUND_REQUEST,
                    RECOVERY_CASE_MANAGE,
                    // Reading a fiscal document, never asking a tax authority's agent for
                    // a second receipt: fiscal.document.resolve stays with the owner and
                    // with finance.
                    FISCAL_DOCUMENT_READ,
                    // Reading which companies exist and which sells where, never
                    // registering one or moving an assignment: legal-entity.manage
                    // stays with the owner alone.
                    LEGAL_ENTITY_READ,
                    DELIVERY_PLAN_READ,
                    DELIVERY_MANUAL_ASSIGN,
                    SHIPMENT_CANCEL,
                    DELIVERY_ZONE_READ,
                    DELIVERY_ZONE_MANAGE,
                    DELIVERY_ZONE_ACTIVATE,
                    DELIVERY_TARIFF_MANAGE,
                    DELIVERY_FEE_EVIDENCE_READ,
                    KITCHEN_STATION_MANAGE,
                    KITCHEN_TICKET_READ,
                    KITCHEN_TICKET_ADVANCE,
                    KITCHEN_TICKET_RECALL,
                    KITCHEN_TICKET_RELEASE,
                    KITCHEN_TICKET_RELEASE_OVERRIDE,
                    DINEIN_FLOORPLAN_MANAGE,
                    DINEIN_QR_ROTATE,
                    DINEIN_SESSION_READ,
                    DINEIN_SESSION_MANAGE,
                    DINEIN_SESSION_FORCE_CLOSE,
                    RESERVATION_READ,
                    RESERVATION_MANAGE,
                    MARKETPLACE_MENU_PUSH,
                    MARKETPLACE_AVAILABILITY_PUSH,
                    MARKETPLACE_ORDER_CREATE_MANUAL,
                    MARKETPLACE_HANDOVER_BYPASS,
                    MARKETPLACE_LIVENESS_READ,
                    CUSTOMER_READ,
                    CUSTOMER_MANAGE,
                    CUSTOMER_PII_REVEAL,
                    MEDIA_READ,
                    MEDIA_UPLOAD,
                    INTEGRATION_INSTALLATION_MANAGE,
                    INTEGRATION_BINDING_ACTIVATE,
                    INTEGRATION_TELEGRAM_LINK_ISSUE,
                    INTEGRATION_FAILURE_READ,
                    INTEGRATION_FAILURE_RETRY,
                    POS_SYNC_READ,
                    POS_SYNC_EXECUTE,
                    POS_SYNC_APPLY,
                    POS_EXPORT_READ,
                    POS_EXPORT_RESOLVE,
                    NOTIFICATION_TEMPLATE_AUTHOR,
                    NOTIFICATION_TEMPLATE_ACTIVATE,
                    NOTIFICATION_READ,
                    NOTIFICATION_RETRY,
                    COMMERCIAL_PLAN_READ,
                    IAM_GRANT_MANAGE,
                    REPORTING_READ)),

    /**
     * Money and commercial settings without catalogue authority. Holds
     * {@code refund.execute}, gated above a threshold by the ADR 0027
     * maker-checker, so a second person still signs off on large refunds.
     */
    TENANT_FINANCE(
            "tenant-finance",
            ScopeType.TENANT,
            EnumSet.of(
                    // ADR 0042: what delivery cost, what is owed to each courier, and
                    // closing a settlement period. Authorising the payout is the owner's,
                    // so closing and releasing are never the same pair of hands.
                    COURIER_LEDGER_READ,
                    COURIER_SETTLEMENT_CLOSE,
                    COURIER_ADJUSTMENT_APPROVE,
                    DELIVERY_COST_READ,
                    PARTNER_INVOICE_MANAGE,
                    // ADR 0027: admission to the approvals console. Finance holds
                    // refund.approve, recovery.remedy.approve and
                    // courier.adjustment.approve — the named second signature on most of
                    // what the thresholds gate. It deliberately does not hold
                    // approval.policy.manage: finance may clear the bar, never move it.
                    APPROVAL_DECIDE,
                    TENANT_READ,
                    BRAND_READ,
                    LOCATION_READ,
                    PRICING_READ,
                    PRICING_AUTHOR,
                    PRICING_ACTIVATE,
                    ORDER_READ,
                    REFUND_REQUEST,
                    REFUND_APPROVE,
                    REFUND_EXECUTE,
                    RECOVERY_CASE_MANAGE,
                    RECOVERY_REMEDY_APPROVE,
                    // "The people qualified to read a provider's status response are
                    // finance and integration staff" — ADR 0013 on the uncertain attempt.
                    PAYMENT_READ,
                    PAYMENT_ATTEMPT_RESOLVE,
                    FISCAL_DOCUMENT_READ,
                    FISCAL_DOCUMENT_RESOLVE,
                    // Which company sells where is exactly what a fiscal document and a
                    // merchant binding both resolve against; finance reads it for the
                    // same reason it reads those, and registering one stays with the
                    // owner.
                    LEGAL_ENTITY_READ,
                    // Activating a rate table is money; drawing one is not, which is why
                    // finance activates tariffs without being able to author them.
                    DELIVERY_TARIFF_ACTIVATE,
                    DELIVERY_FEE_EVIDENCE_READ,
                    COMMERCIAL_SUBSCRIPTION_MANAGE,
                    COMMERCIAL_OVERRIDE_APPROVE,
                    COMMERCIAL_PLAN_READ,
                    COMMERCIAL_USAGE_READ,
                    REPORTING_READ,
                    AUDIT_READ)),

    BRAND_MANAGER(
            "brand-manager",
            ScopeType.BRAND,
            EnumSet.of(
                    // ADR 0044: a brand markets itself, and needs to see who it would
                    // be reaching before it does.
                    AUDIENCE_READ,
                    CAMPAIGN_AUTHOR,
                    // ADR 0042: delivery cost is a brand's own operating number.
                    DELIVERY_COST_READ,
                    BRAND_READ,
                    LOCATION_READ,
                    CATALOG_READ,
                    CATALOG_AUTHOR,
                    CATALOG_PUBLISH,
                    OFFERING_MANAGE,
                    CHANNEL_READ,
                    SERVICEABILITY_MANAGE,
                    LOCATION_SERVICE_STATE_CHANGE,
                    INVENTORY_READ,
                    PRICING_READ,
                    PRICING_AUTHOR,
                    ORDER_READ,
                    // ADR 0037 splits drawing from activating precisely so that the
                    // person who drew a polygon is not the only one who ever reads it.
                    // The brand manager draws; the tenant decides it governs sales.
                    DELIVERY_ZONE_READ,
                    DELIVERY_ZONE_MANAGE,
                    DELIVERY_TARIFF_MANAGE,
                    MARKETPLACE_AVAILABILITY_PUSH,
                    NOTIFICATION_TEMPLATE_AUTHOR,
                    REPORTING_READ)),

    LOCATION_MANAGER(
            "location-manager",
            ScopeType.LOCATION,
            EnumSet.of(
                    // ADR 0042: the branch end of a shift. Closing and approving hours
                    // are a manager's, and opening is deliberately absent — a manager who
                    // can create shift state can create paid hours for somebody who was
                    // at home, and directing when a self-employed person works is the
                    // fact pattern that reclassifies the engagement.
                    COURIER_SHIFT_APPROVE,
                    COURIER_CASH_CONFIRM,
                    COURIER_ADJUSTMENT_CREATE,
                    // ADR 0046: answering a customer at the counter about their points.
                    LOYALTY_READ,
                    LOCATION_READ,
                    CATALOG_READ,
                    OFFERING_MANAGE,
                    // ADR 0036: a branch manager closes their own branch with a reason,
                    // and cannot touch the network's timetables to do it.
                    LOCATION_SERVICE_STATE_CHANGE,
                    INVENTORY_READ,
                    INVENTORY_ADJUST,
                    PRICING_READ,
                    ORDER_READ,
                    ORDER_APPROVE,
                    ORDER_ADVANCE,
                    ORDER_AMEND,
                    ORDER_CANCEL,
                    REFUND_REQUEST,
                    RECOVERY_CASE_MANAGE,
                    // ADR 0041: the pass and the office. Recall, release and the late
                    // release are held here and not by the line, because each of them
                    // undoes or delays something the line has already reported.
                    KITCHEN_STATION_MANAGE,
                    KITCHEN_TICKET_READ,
                    KITCHEN_TICKET_ADVANCE,
                    KITCHEN_TICKET_RECALL,
                    KITCHEN_TICKET_RELEASE,
                    KITCHEN_TICKET_RELEASE_OVERRIDE,
                    // ADR 0047: the floor plan is the physical property of this branch,
                    // and a walkout is this shift's cash shortfall.
                    DINEIN_FLOORPLAN_MANAGE,
                    DINEIN_QR_ROTATE,
                    DINEIN_SESSION_READ,
                    DINEIN_SESSION_MANAGE,
                    DINEIN_SESSION_FORCE_CLOSE,
                    RESERVATION_READ,
                    RESERVATION_MANAGE,
                    MARKETPLACE_AVAILABILITY_PUSH,
                    DELIVERY_PLAN_READ,
                    DELIVERY_MANUAL_ASSIGN,
                    // ADR 0045: a branch that dispatches its own couriers has a
                    // dispatcher's problem whether or not it has a dispatcher, and a
                    // manager closing a session for someone who went home is the reason
                    // duty management is not the courier's alone.
                    COURIER_POSITION_READ,
                    COURIER_DUTY_MANAGE,
                    CUSTOMER_READ,
                    CUSTOMER_PII_REVEAL,
                    INTEGRATION_FAILURE_READ,
                    REPORTING_READ)),

    /** Works the order feed. Deliberately cannot touch catalogue or money. */
    LOCATION_STAFF(
            "location-staff",
            ScopeType.LOCATION,
            EnumSet.of(
                    LOCATION_READ,
                    CATALOG_READ,
                    INVENTORY_READ,
                    ORDER_READ,
                    ORDER_APPROVE,
                    ORDER_ADVANCE,
                    // ADR 0041: the line cook's screen. Reading the board and starting
                    // and readying a line is the whole of it — a recall undoes a
                    // readiness the pass may have acted on, and a release decides when
                    // a kitchen cooks, so neither belongs on this bundle.
                    KITCHEN_TICKET_READ,
                    KITCHEN_TICKET_ADVANCE,
                    // ADR 0047: the waiter and the host stand. Session manage stops
                    // short of closing a table that still owes money.
                    DINEIN_SESSION_READ,
                    DINEIN_SESSION_MANAGE,
                    RESERVATION_READ,
                    RESERVATION_MANAGE,
                    DELIVERY_PLAN_READ)),

    COURIER_DISPATCHER(
            "courier-dispatcher",
            ScopeType.BRAND,
            EnumSet.of(
                    // ADR 0042: the dispatcher runs the board and raises adjustments for
                    // what happened on it, but does not approve them and never opens a
                    // shift on somebody's behalf.
                    COURIER_SHIFT_APPROVE,
                    COURIER_ADJUSTMENT_CREATE,
                    DELIVERY_COST_READ,
                    LOCATION_READ,
                    ORDER_READ,
                    DELIVERY_PLAN_READ,
                    DELIVERY_MANUAL_ASSIGN,
                    SHIPMENT_CANCEL,
                    // ADR 0045. Assigning an in-house fleet without knowing where it is
                    // is not a job anyone can do, so the live map is this role's tool and
                    // is granted at the locations it dispatches for. The stored track is
                    // not here: a dispute answered from a thirty-day history is a
                    // different act from dispatching the next order, and ADR 0045 makes
                    // it a deliberate, audited grant rather than a standing one.
                    COURIER_POSITION_READ,
                    COURIER_DUTY_MANAGE,
                    // A courier is sent to an address, so the one role whose entire job
                    // is sending them needs the reveal rather than the masked view.
                    CUSTOMER_READ,
                    CUSTOMER_PII_REVEAL)),

    SUPPORT_AGENT(
            "support-agent",
            ScopeType.TENANT,
            EnumSet.of(
                    // ADR 0046: "where did my points go" is the question support is
                    // called about; adjusting the balance is not theirs to do.
                    LOYALTY_READ,
                    BRAND_READ,
                    LOCATION_READ,
                    CATALOG_READ,
                    ORDER_READ,
                    ORDER_CANCEL,
                    // "Only somebody who can talk to the customer should have this" —
                    // ADR 0039 on amendment, which describes this role exactly.
                    ORDER_AMEND,
                    REFUND_REQUEST,
                    RECOVERY_CASE_MANAGE,
                    DELIVERY_PLAN_READ,
                    // The three questions a support call is: why was my delivery that
                    // much, where is my confirmation, and can you send it again.
                    DELIVERY_FEE_EVIDENCE_READ,
                    NOTIFICATION_READ,
                    NOTIFICATION_RETRY,
                    CUSTOMER_READ,
                    CUSTOMER_MANAGE,
                    CUSTOMER_PII_REVEAL));

    private static final Map<String, PlatformRole> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(PlatformRole::code, Function.identity()));

    private final String code;
    private final ScopeType scopeType;

    // Guava's ImmutableSet isn't a project dependency and adding it only to
    // satisfy this checker's declared-type allowlist would be a heavier change
    // than the field warrants; the constructor below copies into an
    // unmodifiable Set (Set.copyOf), so every enum constant's capabilities is
    // genuinely immutable at runtime even though java.util.Set's declared type
    // does not prove it statically.
    @SuppressWarnings("ImmutableEnumChecker")
    private final Set<Capability> capabilities;

    PlatformRole(String code, ScopeType scopeType, Set<Capability> capabilities) {
        this.code = code;
        this.scopeType = scopeType;
        this.capabilities = Set.copyOf(capabilities);
    }

    public String code() {
        return code;
    }

    /** The scope level at which this role is normally granted. */
    public ScopeType scopeType() {
        return scopeType;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean grants(Capability capability) {
        return capabilities.contains(capability);
    }

    public static Optional<PlatformRole> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code.toLowerCase(Locale.ROOT)));
    }
}
