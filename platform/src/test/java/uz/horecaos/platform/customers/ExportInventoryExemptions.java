package uz.horecaos.platform.customers;

import java.util.Map;
import java.util.TreeMap;

/**
 * The tables that carry a customer key or a PERSONAL column and are deliberately not part of a data-subject export, each
 * with the reason (ADR 0029). {@link ExportInventoryTests} requires an entry to be a real table that no participant exports,
 * and requires every table the erasure inventory knows to be exported by a participant or here.
 *
 * <p>The reasons are of five kinds, and none of them is "later". <em>Another data subject</em>: staff, couriers and a
 * branch's contact persons are not the customer, and their data has its own access path. <em>A credential</em>: a session
 * token, a one-time code or a sign-in binding authenticates, and handing it out in a file would be handing out the account.
 * <em>Working state of something exported</em>: a lot, a reservation or a recipient row is the bookkeeping behind a ledger or
 * a send log that the export already carries whole. <em>Evidence of the tenant's own acts</em>: a tax document, a provider
 * callback or an import report is the tenant's record, not keyed to her and not about her beyond what the order already
 * says. <em>Withheld pending legal</em>: a legal hold is the tenant's decision to keep records for a claim, and whether a
 * subject is told is the open input legal owns; the export names the withholding in its manifest instead of deciding.
 * A reason that stops being true is a defect in the exemption, not in the guard.
 */
final class ExportInventoryExemptions {

    static final Map<String, String> REASONS = new TreeMap<>();

    private static final String STAFF =
            "Staff are a different data subject: this data is the employee's, with an access path of its own (iam), and the"
                    + " table holds no customer's data.";

    private static final String COURIER =
            "Couriers are a different data subject with their own lifecycle and their own access path; the table holds the"
                    + " courier's data, not a customer's.";

    private static final String CREDENTIAL =
            "A credential or a binding that authenticates the account: handing it to the person in a file would hand over the"
                    + " account, and she already knows how she signs in. Not information about her.";

    private static final String LEDGER_STATE =
            "Working state behind the loyalty ledger (lots, reservations, clawbacks, till reconciliation): the movements and"
                    + " balances it is bookkeeping for are in the loyalty section (loyalty.entries, loyalty.accounts), whole.";

    private static final String CAMPAIGN_STATE =
            "Campaign machinery behind marketing_sends (runs, recipients, decisions, scenario state, offers shown): which"
                    + " messages reached her and when is in marketing.marketing_sends, and the segments she was placed in are in"
                    + " marketing.audience_snapshot_members.";

    private static final String DISCOUNT_LEDGER =
            "Usage counter or redemption record of a discount: what was taken off each order is on the order itself"
                    + " (ordering.orders: discount) and the benefits granted to her are in pricing.benefit_grants.";

    private static final String PROVIDER_EVIDENCE =
            "The tenant's evidence of what a payment or fiscal provider said, keyed by order or intent and not by customer"
                    + " account; what she bought and how it was settled is on the order (ordering.orders).";

    private static final String REPORTING_FACT =
            "A reporting fact keyed by a one-way keyed hash of the account id (V0031): it adds nothing to the order, review"
                    + " or redemption it summarises, all of which are exported.";

    static {
        // ------------------------------------------------------------------ customers' own
        REASONS.put(
                "customer.contact_attempts",
                "The call desk's journal: that a call was made, its outcome and the operator who made it, with no number or"
                        + " name. It is the tenant's working record of its own staff, and the lead it hangs off is exported.");
        REASONS.put(
                "customer.customer_import_run_rows",
                "A CSV import report row (row number, outcome, account created or matched): the importing staff member's"
                        + " bookkeeping. It holds no name, number or address; the account it created is exported.");
        REASONS.put(
                "customer.customer_import_runs",
                "An import run belongs to the staff member who ran it, and its uploaded content is nulled when the run ends."
                        + " It is not keyed by a customer.");
        REASONS.put("customer.customer_sessions", CREDENTIAL);
        REASONS.put("customer.principal_links", CREDENTIAL);
        REASONS.put(
                "customer.verification_challenges",
                "A one-time sign-in code challenge, not keyed by an account and deleted when settled or lapsed (ADR 0015):"
                        + " a credential in flight.");
        REASONS.put(
                "customer.legal_holds",
                "WITHHELD pending legal. A hold is the tenant's decision to keep a customer's records because it is or may be"
                        + " obliged to produce them; whether and when a data subject is told is the open input legal owns (ADR 0029),"
                        + " and the export does not decide it by including or omitting the hold silently.");

        // ------------------------------------------------------------------ tax and payment evidence
        REASONS.put("fiscal.fiscal_documents", PROVIDER_EVIDENCE);
        REASONS.put("fiscal.fiscal_sent_requests", PROVIDER_EVIDENCE);
        REASONS.put("payments.payment_transactions", PROVIDER_EVIDENCE);
        REASONS.put("payments.provider_callbacks", PROVIDER_EVIDENCE);
        REASONS.put(
                "payments.remedy_entitlements",
                "A remedy the tenant owes (a discount or a compensation) and how much was used: shown to her as the"
                        + " discount on the order it was used against, and as the outcome of her service-recovery case in the"
                        + " reviews section.");

        // ------------------------------------------------------------------ couriers, staff, third parties
        REASONS.put("fulfillment.couriers", COURIER);
        REASONS.put("fulfillment.courier_engagements", COURIER);
        REASONS.put("fulfillment.courier_location_tracks", COURIER);
        REASONS.put("fulfillment.courier_shifts", COURIER);
        REASONS.put("fulfillment.courier_assignment_earnings", COURIER);
        REASONS.put(
                "fulfillment.courier_track_summaries",
                COURIER + " Its delivery confirmation points are the courier's pay evidence.");
        REASONS.put("iam.staff_emergency_contacts", STAFF);
        REASONS.put("iam.staff_members", STAFF);
        REASONS.put("iam.staff_preferences", STAFF);
        REASONS.put(
                "tenant.location_contact_persons",
                "A branch's contact persons are colleagues or third parties the tenant named; they are not the tenant's"
                        + " customers.");
        REASONS.put(
                "partner.inbound_orders",
                "The aggregator's raw payload for an order it sent, keyed by the aggregator's order id; the aggregator's"
                        + " customers have no account here, so it cannot be found from one.");
        REASONS.put(
                "printing.print_jobs",
                "A print job is keyed by its source document and device, not by a customer account, and its protected"
                        + " document is purged on a schedule (PrintMaintenance.purgeDocuments).");

        // ------------------------------------------------------------------ integration
        REASONS.put(
                "integration.telegram_bindings",
                "Binds an operations chat to a location (audience OPERATIONS): the chat is staff's, not a customer's.");
        REASONS.put(
                "integration.telegram_chat_locks",
                "A per-chat send lease (tenant, chat id, owner, expiry) that serialises Bot API calls: no account link and"
                        + " no content.");
        REASONS.put(
                "integration.telegram_pending_links",
                CREDENTIAL + " It is a one-time code that links a Telegram chat to an account and expires.");

        // ------------------------------------------------------------------ loyalty, marketing
        REASONS.put("loyalty.clawbacks", LEDGER_STATE);
        REASONS.put("loyalty.lots", LEDGER_STATE);
        REASONS.put("loyalty.reservations", LEDGER_STATE);
        REASONS.put("loyalty.pos_sync_movements", LEDGER_STATE);
        REASONS.put("loyalty.pos_sync_discrepancies", LEDGER_STATE);
        REASONS.put(
                "loyalty.pos_balance_sync_state",
                LEDGER_STATE + " The till's own customer id is an internal provider identifier, not hers to carry.");
        REASONS.put("marketing.automation_runs", CAMPAIGN_STATE);
        REASONS.put("marketing.campaign_recipients", CAMPAIGN_STATE);
        REASONS.put("marketing.presented_offers", CAMPAIGN_STATE);
        REASONS.put("marketing.scenario_participant_state", CAMPAIGN_STATE);
        REASONS.put("marketing.scenario_step_decisions", CAMPAIGN_STATE);
        REASONS.put(
                "marketing.metric_drift_observations",
                "A projection audit: the metric, the value the projection held and the value recomputed, kept to detect a"
                        + " defect in the projection. It holds nothing that marketing.customer_metrics, exported, does not.");

        // ------------------------------------------------------------------ notifications
        REASONS.put(
                "notifications.unsubscribe_tokens",
                "The SHA-256 of an opaque token in a campaign email's way-out link, which authenticates a withdrawal of"
                        + " consent and holds nothing about her beyond ids: no address, no name, no token. The messages that carried"
                        + " the links are in marketing.marketing_sends and the consent she gave and withdrew is in"
                        + " customer.consent_decisions, both exported.");

        // ------------------------------------------------------------------ the till
        REASONS.put(
                "integration.pos_order_exports",
                "The tenant's record that an order was sent to its till and what came of each attempt: a state, a count,"
                        + " the provider's order id and a fingerprint of the lines. The one personal value on the row is a"
                        + " digest of the order's telephone number, which the customers section already hands her whole; a"
                        + " one-way copy of a value she is given adds nothing, and an erasure replaces it. What she bought is on"
                        + " the order (ordering.orders).");

        // ------------------------------------------------------------------ ordering, pricing, reporting
        REASONS.put(
                "ordering.order_line_marks",
                "A product marking code (the code printed on the item): it identifies a unit of goods, not a person.");
        REASONS.put("pricing.coupon_customer_usage", DISCOUNT_LEDGER);
        REASONS.put("pricing.coupon_redemptions", DISCOUNT_LEDGER);
        REASONS.put("pricing.promotion_customer_usage", DISCOUNT_LEDGER);
        REASONS.put("pricing.promotion_redemptions", DISCOUNT_LEDGER);
        REASONS.put(
                "pricing.quotes",
                "A priced basket that an order was created from: its amounts are the order's, which the ordering section"
                        + " carries, and it holds no name, number or address.");
        REASONS.put("reporting.fact_order", REPORTING_FACT);
        REASONS.put("reporting.fact_promotion_redemption", REPORTING_FACT);
        REASONS.put("reporting.fact_review", REPORTING_FACT);
        REASONS.put(
                "reporting.report_exports",
                "The filter a staff member ran for a report, keyed by the requester and the report, not by a customer account;"
                        + " the exported artifact has its own retention.");
    }

    private ExportInventoryExemptions() {}
}
