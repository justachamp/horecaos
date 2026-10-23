package uz.horecaos.platform.ordering.api;

import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/**
 * The ordering module's ADR 0030 configuration keys.
 *
 * <p>{@link #CART_EXPIRY_MINUTES} was the first, wired 2026-09-10 for the
 * identical reason recorded on {@code pricing.api.PricingConfigurationKeys}:
 * the declared default (sixty minutes) disagreed with {@link
 * uz.horecaos.platform.ordering.application.CartService#CART_TTL} (four
 * hours) by 4x, harmlessly while the key was dead and not harmlessly the
 * moment it was wired without correcting the default.
 *
 * <p>Wave P46 (gap map row {@code 10.3b}, settings.md §10.3 cards 2–5) registered thirteen keys so
 * they could be authored and inherited through {@code OperationsConfigurationController} before
 * anything read them. ADR 0177 (batch 21) closes that: five of them (the business day's start hour, the
 * average and maximum order minutes, the VAT rate and the routing poll interval) are <strong>retired</strong>
 * -- each has a neighbour that already owns the behaviour (the reporting calendar, the preparation bands,
 * the dated tax profiles, the dispatch rules) and none had a reader -- and every key that remains is read:
 * the minimum order amount by {@code CheckoutEligibilityGuard}, the lateness scalars by {@code
 * OrderLatenessPolicyService}, the two auto-accept gates by {@code AutoAcceptGate} at checkout, the
 * pre-order branch selection by {@code BranchResolutionQueryService}, and the three operator-order toggles
 * by {@code OperatorOrderingService}. A key's default is the value that preserves behaviour: a registered
 * default is never read as a tenant's choice (ADR 0150 decision 2).
 *
 * <p>And, since ADR 0109 (Settings 10.11): how long an abandoned cart —
 * one that expired without becoming an order — is kept before {@code
 * CartRetentionSweeper} deletes it outright. A different question from the
 * one above: {@link #CART_EXPIRY_MINUTES} decides when a cart stops being
 * usable at checkout, this decides how long its record survives afterwards
 * for the data-privacy self-service screen's own "retention periods" gap.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * OrderingConfigurationKeyTests} fails the build if the two ever drift apart.
 */
public final class OrderingConfigurationKeys {

    /** The code both declarations share. */
    public static final String CART_EXPIRY_MINUTES_CODE = "ordering.cart_expiry_minutes";

    /** The code both declarations share. */
    public static final String CART_RETENTION_DAYS_CODE = "ordering.cart_retention_days";

    /**
     * Minutes an untouched cart stays active before expiring.
     *
     * <p>240 (four hours): long enough for a customer to be interrupted and
     * come back, deliberately far longer than the fifteen-minute quote TTL —
     * the cart survives an interruption, the price does not.
     */
    public static final ConfigurationKey<Integer> CART_EXPIRY_MINUTES = ConfigurationKey.of(
                    CART_EXPIRY_MINUTES_CODE, Integer.class)
            .defaultValue(240)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an untouched cart stays active before expiring.")
            .build();

    /** Card 2: "Заказ опаздывает с" — the single most-used value on the order board. */
    public static final String LATE_ORDER_THRESHOLD_MINUTES_CODE = "ordering.late_order_threshold_minutes";

    /**
     * Card 2's «Заказ без обещанного времени опаздывает через» (ADR 0150): the tenant-wide default
     * for the {@code ordering.lateness} document's no-promise fallback -- how many minutes from
     * creation an order <em>with no promised time</em> (every marketplace order, and a native one no
     * preparation band covered) runs before it counts as late. A mode's own document value wins; a
     * blank in the document takes this when it was <em>set</em> somewhere in the chain; the platform's
     * forty-five otherwise. 45: exactly the document's platform default of 2700 seconds, so the
     * reader changes nothing for a tenant that never set it, and a resolved default is never read as
     * something a tenant chose.
     *
     * <p>Not a second late line and not measured from acceptance: the promise starts at checkout
     * (ADR 0036) and acceptance never starts or shortens a lateness clock, so an order awaiting
     * approval and a scheduled order are late by the same rule as any other. A promised order's late
     * line is the document's {@code lateAfterSeconds}, and this key does nothing for it. Lateness is
     * a computed overlay on the order, never a status of its own (IA Part 4).
     */
    public static final ConfigurationKey<Integer> LATE_ORDER_THRESHOLD_MINUTES = ConfigurationKey.of(
                    LATE_ORDER_THRESHOLD_MINUTES_CODE, Integer.class)
            .defaultValue(45)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes from creation after which an order with no promised time counts as late.")
            .build();

    /** Row {@code X.39}: how far ahead of the promise the boards start warning. */
    public static final String AT_RISK_BEFORE_MINUTES_CODE = "ordering.at_risk_before_minutes";

    /**
     * Minutes before an order's promised time at which the boards show it as at
     * risk. 5: exactly the {@code ordering.lateness} platform default's 300
     * seconds, so registering the key changes nothing for a tenant that has not
     * set it. {@code OrderLatenessPolicyService} takes a value that was set
     * somewhere in the chain as the at-risk window of every fulfilment mode
     * whose {@code ordering.lateness} document does not carry a window of its
     * own (wave 16: the document's per-mode at-risk minutes replace this single
     * scalar, and this stays as the default for the modes not set) -- a scalar
     * the settings screen edits with the same inherit/override control as every
     * other card-2 field.
     */
    public static final ConfigurationKey<Integer> AT_RISK_BEFORE_MINUTES = ConfigurationKey.of(
                    AT_RISK_BEFORE_MINUTES_CODE, Integer.class)
            .defaultValue(5)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes before an order's promised time at which it shows as at risk on the boards.")
            .build();

    /** Row {@code X.39}: the tenant's own colour for a late order. */
    public static final String LATE_COLOUR_CODE = "ordering.late_colour";

    /**
     * A {@code #rrggbb} colour the order board and the kitchen board use for a
     * late order instead of the design-system SLA token; blank keeps the token.
     * The default is blank, not a colour: "unset" has to be distinguishable
     * from a tenant that chose the token's own red.
     *
     * <p>The value is validated at write time (see {@code
     * ConfigurationValueRules}) and again where it is served, because it ends up
     * in a style binding on the console: a string that is not exactly six hex
     * digits never reaches a stylesheet. Legibility is the screen's job -- the
     * order-policy card runs the chosen colour through the same WCAG contrast
     * helper the channel colours use and warns below 4.5:1 -- and is a warning,
     * not a refusal, because whether the colour is readable depends on the
     * surface it lands on.
     */
    public static final ConfigurationKey<String> LATE_COLOUR = ConfigurationKey.of(LATE_COLOUR_CODE, String.class)
            .defaultValue("")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Hex colour (#rrggbb) the boards use for late orders instead of the design-system "
                    + "token; blank keeps the token.")
            .build();

    /**
     * Wave P06 (gap map rows {@code 1.1g}/{@code X.39}): the policy document
     * orders.md §2.7 actually specifies, and the one both the order board and
     * the kitchen ticket queue read. {@link #LATE_ORDER_THRESHOLD_MINUTES} is
     * card 2's scalar for the one part of it a promise cannot cover (ADR 0150).
     * This is a {@link PolicyKey}, not a {@link ConfigurationKey}, because
     * §2.7 needs three numbers per {@link
     * uz.horecaos.platform.tenancy.api.FulfillmentMode}
     * (at-risk-before/late-after/no-promise-fallback), and ADR 0030 draws the
     * line at exactly this shape: "a setting is a scalar value... a policy is a
     * versioned document". Authoring this key is {@code
     * Capability.TENANT_CONFIGURATION_WRITE}: wave 16 (rows {@code X.39}/{@code
     * 10.3b}) ships the editor on the order-policy card, over {@code
     * OrderLatenessPolicyAuthoringService}. The document type is {@link
     * OrderLatenessDocument} — the authored form, whose per-mode at-risk window
     * is optional and falls back to {@link #AT_RISK_BEFORE_MINUTES}, and whose
     * per-mode no-promise fallback is optional and falls back to {@link
     * #LATE_ORDER_THRESHOLD_MINUTES} — while what
     * the boards read is the concrete {@code OrderLatenessPolicy} that service
     * resolves from it.
     */
    public static final String LATENESS_POLICY_CODE = "ordering.lateness";

    /** See {@link #LATENESS_POLICY_CODE}. Consumed by {@code OrderLatenessPolicyService}. */
    public static final PolicyKey<OrderLatenessDocument> LATENESS_POLICY = new PolicyKey<>(
            LATENESS_POLICY_CODE,
            OrderLatenessDocument.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "ordering",
            false,
            "Per-fulfilment-mode lateness thresholds: at-risk-before, late-after, and the "
                    + "no-promise fallback (orders.md §2.7).");

    /**
     * The unpaid-order window (ADR 0142 Decision 7): how long an order may sit in {@code
     * PAYMENT_AUTHORIZING} before it reaches the stuck list, and what the sweep does then. Replaces the
     * deploy property {@code horecaos.ordering.workers.payment.stale-after}, which survives as the
     * fallback when nothing is published. Consumed by {@code OrderPaymentProcess}; authored through
     * {@code PaymentWindowAuthoringService} behind {@code ORDER_PAYMENT_WINDOW_MANAGE}.
     *
     * <p>Settable at tenant, brand and location, never platform: how long to wait for a customer to pay
     * is a fact about one restaurant's customers, and a platform-wide wait would be every tenant's.
     */
    public static final String PAYMENT_WINDOW_POLICY_CODE = "ordering.payment_window";

    /** See {@link #PAYMENT_WINDOW_POLICY_CODE}. */
    public static final PolicyKey<PaymentWindowPolicy> PAYMENT_WINDOW_POLICY = new PolicyKey<>(
            PAYMENT_WINDOW_POLICY_CODE,
            PaymentWindowPolicy.class,
            Set.of(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "ordering",
            false,
            "How many minutes an order may sit awaiting payment before it reaches the stuck list, and "
                    + "whether it is only flagged (cancelling is refused until ADR 0019's open input is answered).");

    /** Card 2: "Минимальная сумма заказа" — pickup and dine-in; delivery is a zone concern. */
    public static final String MINIMUM_ORDER_AMOUNT_MINOR_CODE = "ordering.minimum_order_amount_minor";

    /**
     * The smallest order total accepted for pickup and dine-in, in minor
     * units of the tenant's own {@code defaultCurrency} (there is no paired
     * currency field here — {@link ConfigurationKey} carries no composite
     * money type, and every tenant this platform onboards today trades in
     * exactly one currency; see {@code Markets}). Settings.md §10.3 card 2
     * names an overlapping value, {@code fulfillment.service_zone_versions
     * .min_basket_minor}, and defers which one wins for delivery to a
     * decision this wave does not make: this key applies to pickup and
     * dine-in only, the zone value continues to own delivery. 0 (no
     * minimum) until a tenant sets one.
     */
    public static final ConfigurationKey<Long> MINIMUM_ORDER_AMOUNT_MINOR = ConfigurationKey.of(
                    MINIMUM_ORDER_AMOUNT_MINOR_CODE, Long.class)
            .defaultValue(0L)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The smallest order total accepted for pickup and dine-in, in minor units. "
                    + "Delivery's own minimum comes from the service zone, not this key.")
            .build();

    /** Card 4: "Подбор филиала для предзаказа вне рабочего времени." */
    public static final String PREORDER_BRANCH_RESOLUTION_CODE = "ordering.preorder_branch_resolution";

    /**
     * How a pre-order placed outside every candidate branch's working hours
     * picks which branch will fulfil it: {@code BY_DISTANCE} (nearest to the
     * customer) or {@code BY_OPENING_TIME} (soonest to open). {@code
     * BY_DISTANCE} is the default — a customer waiting for a pre-order cares
     * more about who is closest than about who opens first.
     *
     * <p><strong>Settable down to the brand and no further (ADR 0177 decision 5).</strong>
     * The question is asked of the brand's branches, and both the console's branch
     * resolver and {@code OperatorOrderingService#place} read it at brand scope. A
     * value written at a branch would be accepted, traced and shown as set there,
     * and nothing would read it, so the key refuses a branch scope instead.
     */
    public static final ConfigurationKey<String> PREORDER_BRANCH_RESOLUTION = ConfigurationKey.of(
                    PREORDER_BRANCH_RESOLUTION_CODE, String.class)
            .defaultValue("BY_DISTANCE")
            .ownedBy("ordering")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("How a pre-order outside working hours picks its fulfilling branch: "
                    + "BY_DISTANCE or BY_OPENING_TIME.")
            .build();

    /** Card 5: "Оператор может вводить промокод." */
    public static final String OPERATOR_PROMO_CODE_ALLOWED_CODE = "ordering.operator_promo_code_allowed";

    /**
     * Whether a call-centre operator hand-entering an order (settings.md
     * §10.3 card 5, ADR 0036's {@code CALL_CENTRE} channel) may apply an
     * ADR 0018 promo code.
     *
     * <p><strong>On by default (ADR 0177 decision 5), and read.</strong>
     * {@code OperatorOrderingService#fillAndPrice} -- which placing and quoting
     * an operator-keyed order both go through -- refuses a promo code with
     * {@code OPERATOR_PROMO_CODE_NOT_ALLOWED} when this resolves to {@code false}.
     * The registered default was {@code false} while nothing read the key, and
     * operators have applied promo codes since row {@code 1.3e}; a reader that
     * honoured that default would have locked every tenant out of something its
     * operators do today. A tenant that wants the lock sets {@code false}: a
     * discount a customer did not ask for, applied by whoever answers the phone,
     * is an abuse surface a tenant opts <em>out</em> of here, not into.
     */
    public static final ConfigurationKey<Boolean> OPERATOR_PROMO_CODE_ALLOWED = ConfigurationKey.of(
                    OPERATOR_PROMO_CODE_ALLOWED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Whether an operator hand-entering an order may apply a promo code.")
            .build();

    /** Card 5: «Оператор может отметить «перезвонить клиенту»». */
    public static final String OPERATOR_CALLBACK_FLAG_ALLOWED_CODE = "ordering.operator_callback_flag_allowed";

    /**
     * Whether an operator may raise «Перезвонить клиенту» on an order as she keys it in (ADR 0039's
     * callback flag, raised before this key only by the {@code SET_CALLBACK_REQUESTED} amendment
     * after the order existed). On by default: the flag only asks a person to ring the customer
     * back, so allowing it changes nothing a tenant did not already do through the amendment.
     * {@code OperatorOrderingService} refuses {@code callbackRequested: true} on an operator order
     * with {@code OPERATOR_CALLBACK_NOT_ALLOWED} when this resolves to {@code false}.
     */
    public static final ConfigurationKey<Boolean> OPERATOR_CALLBACK_FLAG_ALLOWED = ConfigurationKey.of(
                    OPERATOR_CALLBACK_FLAG_ALLOWED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Whether an operator keying in an order may flag it «call the customer back».")
            .build();

    /** Card 5: «Оператор вводит, сколько клиент передаст наличными (сдача)». */
    public static final String OPERATOR_CHANGE_DUE_ALLOWED_CODE = "ordering.operator_change_due_allowed";

    /**
     * Whether an operator may record the cash the customer will hand over, so «Сдача» is on the
     * order from its first read (row {@code 1.3e}). On by default, because the field exists and is
     * used; {@code OperatorOrderingService} refuses a {@code cashTenderedMinor} on an operator order
     * with {@code OPERATOR_CHANGE_DUE_NOT_ALLOWED} when this resolves to {@code false}, and the screen
     * hides the field.
     */
    public static final ConfigurationKey<Boolean> OPERATOR_CHANGE_DUE_ALLOWED = ConfigurationKey.of(
                    OPERATOR_CHANGE_DUE_ALLOWED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Whether an operator keying in a cash order may record the cash tendered (change due).")
            .build();

    /**
     * Card 1's own Delever-comparison gap: "auto-accept restricted to a set
     * of channels."
     */
    public static final String AUTO_ACCEPT_ELIGIBLE_CHANNELS_CODE = "ordering.auto_accept_eligible_channels";

    /**
     * Which sales channels {@code ordering.acceptance}'s {@code AUTO_CONFIRM}
     * mode applies to: the literal {@code "ALL"}, or a comma-separated list
     * of {@code tenant.sales_channels.code} values (ADR 0036) — "auto-accept
     * the bot, hand-check the aggregator." Defaults to {@code "ALL"} so
     * wiring this key changes nothing for a tenant that has not narrowed it:
     * {@code AUTO_CONFIRM} today has no channel filter at all.
     *
     * <p>Read at checkout (ADR 0177 decision 3) by {@code AutoAcceptGate}: under {@code
     * AUTO_CONFIRM}, an order on a channel outside the list is not auto-confirmed but follows the
     * acceptance document's own approval channel, timeout and timeout action. It does not apply to
     * a marketplace or aggregator order, which arrives already accepted by the aggregator and
     * bypasses checkout.
     */
    public static final ConfigurationKey<String> AUTO_ACCEPT_ELIGIBLE_CHANNELS = ConfigurationKey.of(
                    AUTO_ACCEPT_ELIGIBLE_CHANNELS_CODE, String.class)
            .defaultValue("ALL")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("\"ALL\", or a comma-separated list of sales channel codes eligible for auto-accept; "
                    + "an order on any other channel waits for the approval path of the acceptance policy.")
            .build();

    /** Card 1's other Delever-comparison gap: the anti-fraud gate. */
    public static final String AUTO_ACCEPT_MIN_PRIOR_ORDERS_CODE = "ordering.auto_accept_min_prior_orders";

    /**
     * How many prior successful orders a customer needs before {@code
     * AUTO_CONFIRM} applies to them at all — an anti-fraud gate, not a
     * convenience, per settings.md §10.3's own Delever comparison. 0 (no
     * gate) preserves today's behaviour. Read at checkout by {@code AutoAcceptGate} (ADR 0177
     * decision 3) against the customer's {@code COMPLETED} orders in the brand; a guest counts
     * as none, and an order below the minimum follows the acceptance document's own approval path.
     */
    public static final ConfigurationKey<Integer> AUTO_ACCEPT_MIN_PRIOR_ORDERS = ConfigurationKey.of(
                    AUTO_ACCEPT_MIN_PRIOR_ORDERS_CODE, Integer.class)
            .defaultValue(0)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Prior successful orders a customer needs before auto-accept applies to "
                    + "them. 0 means no gate; a new customer's order waits for the approval path instead.")
            .build();

    /**
     * The shortest abandoned-cart window the platform acts on (days). A value below it is raised to it. One day is the
     * smallest whole window, and it is also what keeps the sweep clear of a cart that is still live: the sweep deletes only
     * a cart whose own {@code expires_at} has passed too, so even this floor cannot reach a cart a customer can still check out.
     * Provisional until legal sets the periods (ADR 0029), like the default.
     */
    public static final int CART_RETENTION_FLOOR_DAYS = 1;

    /**
     * Days an abandoned cart is kept before {@code CartRetentionSweeper}
     * deletes it (ADR 0092). Ninety, matching that class's own {@code
     * @Value} default exactly — the same "a wired key's default is the live
     * value" discipline {@link #CART_EXPIRY_MINUTES} follows. Settable at the
     * platform and per tenant, and honoured per tenant since batch 21: {@code
     * CartRetentionSweeper} resolves the window for each tenant (its own value,
     * else the platform's, else this default), raised to {@link
     * #CART_RETENTION_FLOOR_DAYS}, so a tenant that sets thirty days has its
     * carts deleted at thirty and one that sets 180 keeps them for 180. It used
     * to sweep on the longer of the platform default and the largest tenant
     * value, which silently ignored a shorter one (ADR 0109).
     */
    public static final ConfigurationKey<Integer> CART_RETENTION_DAYS = ConfigurationKey.of(
                    CART_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(90)
            .ownedBy("ordering")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Days an abandoned cart (one that expired without becoming an order) "
                    + "is kept before it is deleted outright.")
            .build();

    /** The code both declarations share. */
    public static final String AUTO_ADD_ENABLED_CODE = "ordering.auto_add_enabled";

    /**
     * ADR 0158's rollout gate: whether the cart evaluates auto-add rules for this brand. With it off the
     * cart neither adds nor removes anything. A policy and not a deploy: rollback is setting it false.
     * Not {@code tenantVisible()}: the rollout is the platform administrator's, brand by brand.
     */
    public static final ConfigurationKey<Boolean> AUTO_ADD_ENABLED = ConfigurationKey.of(
                    AUTO_ADD_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("ordering")
            .settableAt(ScopeType.BRAND)
            .describedAs("Whether the cart evaluates auto-add rules (packaging, cutlery and the like) for this "
                    + "brand. Off means the cart neither adds nor removes anything.")
            .build();

    /** The code both declarations share. */
    public static final String AUTO_ADD_SHADOW_ENABLED_CODE = "ordering.auto_add_shadow_enabled";

    /**
     * ADR 0158's shadow mode: while {@link #AUTO_ADD_ENABLED} is off, count what would have fired
     * ({@code horecaos.autoadd.rules{outcome=shadow}}) without writing a line, so the owner can read the
     * numbers against what staff add by hand before turning the rule set on.
     */
    public static final ConfigurationKey<Boolean> AUTO_ADD_SHADOW_ENABLED = ConfigurationKey.of(
                    AUTO_ADD_SHADOW_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("ordering")
            .settableAt(ScopeType.BRAND)
            .describedAs("While auto-add is off, count what the rules would have added without adding it.")
            .build();

    /** The code both declarations share. */
    public static final String AMENDMENT_LINE_REMOVAL_ENABLED_CODE = "ordering.amendment_line_removal_enabled";

    /**
     * ADR 0169's rollout gate: whether an operator may remove a line from, or lower a line's quantity on, a
     * confirmed order. With it off the amendment is refused as it always was.
     */
    public static final ConfigurationKey<Boolean> AMENDMENT_LINE_REMOVAL_ENABLED = ConfigurationKey.of(
                    AMENDMENT_LINE_REMOVAL_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("ordering")
            .settableAt(ScopeType.BRAND)
            .describedAs("Whether an operator may remove a line from, or lower a quantity on, a confirmed order "
                    + "by amendment. Off means the command is refused.")
            .build();

    /** Wave 10 (gap map rows {@code 1.2c}/{@code 2.1d}): the ADR 0039 amendment cut point. */
    public static final String AMENDMENT_CUT_POINT_STATUS_CODE = "ordering.amendment_cut_point_status";

    /**
     * The last {@link uz.horecaos.platform.ordering.domain.OrderStatus} at which
     * a financial amendment command may still be carried out (ADR 0039 §3.11:
     * "Financial commands stop at a cut point, default {@code READY}, resolved
     * through ADR 0030 so a location can stop earlier"). {@code
     * OrderAmendmentService} refuses every {@code
     * AmendmentCommandType#financial()} command once the order's own status
     * reaches this one or a later one in {@code OrderStatus}'s declared order —
     * past it, "add a dessert" is a second order, honestly presented as one,
     * because the fiscal and POS consequences stop being reliably reversible.
     * A non-financial command (a note, the callback flag, change-due) is never
     * subject to this key.
     */
    public static final ConfigurationKey<String> AMENDMENT_CUT_POINT_STATUS = ConfigurationKey.of(
                    AMENDMENT_CUT_POINT_STATUS_CODE, String.class)
            .defaultValue("READY")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The last order status at which a financial amendment (adding, changing "
                    + "or removing lines, the address, the fulfilment time or the payment method) "
                    + "may still be carried out. A location may stop earlier than READY, never later.")
            .build();

    /** Wave 10: the ADR 0027 four-eyes threshold on a decreasing amendment. */
    public static final String AMENDMENT_DECREASE_APPROVAL_THRESHOLD_MINOR_CODE =
            "ordering.amendment_decrease_approval_threshold_minor";

    /**
     * The size, in minor units, above which a financial amendment that lowers
     * the order's total needs an ADR 0027 four-eyes approval before it may
     * apply (ADR 0039 §3.11: "A decrease above a configured amount needs ADR
     * 0027 four-eyes approval, or 'remove the two most expensive lines' is an
     * unreviewed refund path"). 200 000 (UZS): the same figure {@code
     * OrderRemedyService}'s own {@code horecaos.payments.remedy-approval
     * -threshold-minor} defaults to, since an amendment's decrease and a
     * refund are the identical exposure to the same failure.
     */
    public static final ConfigurationKey<Long> AMENDMENT_DECREASE_APPROVAL_THRESHOLD_MINOR = ConfigurationKey.of(
                    AMENDMENT_DECREASE_APPROVAL_THRESHOLD_MINOR_CODE, Long.class)
            .defaultValue(200000L)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("A financial amendment that lowers the order's total by at least this "
                    + "many minor units needs a second signature (ADR 0027) before it may apply.")
            .build();

    private OrderingConfigurationKeys() {}
}
