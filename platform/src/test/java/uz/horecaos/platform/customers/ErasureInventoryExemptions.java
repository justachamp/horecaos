package uz.horecaos.platform.customers;

import java.util.Map;
import java.util.TreeMap;

/**
 * The tables that carry a customer key or a PERSONAL column and are deliberately not erased by a customer's erasure, each
 * with the reason (ADR 0029). {@link ErasureInventoryTests} requires an entry to be a real table that still needs one, and
 * requires every table it finds to be here or on a participant.
 *
 * <p>An exemption is a claim about the code, and the claims are of five kinds. <em>Another data subject</em>: staff,
 * couriers and a branch's contact persons have lifecycles of their own. <em>Financial or commercial history</em>: ADR 0029
 * decides that "financial history survives and the person does not", so a ledger, a redemption or a tax record keeps its
 * rows and identifies the customer only by an account id that belongs to an anonymised account. <em>Evidence</em>: the
 * consent trail and the erasure's own proof. <em>Expires by itself</em>: a sweeper or a purge removes it, named here so a
 * reader can check it. <em>Holds no personal value</em>: the column that matched the guard's pattern is an identifier, a
 * counter or a hash. A reason that stops being true is a defect in the exemption, not in the guard.
 */
final class ErasureInventoryExemptions {

    static final Map<String, String> REASONS = new TreeMap<>();

    private static final String STAFF =
            "Staff are a different data subject: this data leaves with an employment end (iam), never with a customer's"
                    + " erasure, and the table holds no customer's data.";

    private static final String COURIER =
            "Couriers are a different data subject with their own lifecycle (applicant retention sweeper, track partitions,"
                    + " settlement-point purge); the table holds the courier's data, not a customer's.";

    private static final String POINTS_LEDGER =
            "Points ledger: financial history survives the person (ADR 0029, ADR 0046); a ledger that forgets an entry no"
                    + " longer reconciles. It names the customer by an account id of an anonymised account and holds no name,"
                    + " number or address; the accounts are suspended by the loyalty participant.";

    private static final String MARKETING_EVIDENCE =
            "Delivery and decision evidence of what the tenant sent or refused, keyed by the id of an anonymised account;"
                    + " it holds no name, number or address, and the tenant must be able to show what it did. The profile it"
                    + " was built from is erased by the marketing participant.";

    private static final String REDEMPTION_LEDGER =
            "Redemption ledger or usage counter: discount given, for which order, within which limit; financial history"
                    + " that survives the person (ADR 0029). Names the customer only by the id of an anonymised account.";

    private static final String REPORTING_FACT =
            "A reporting fact keyed by a one-way keyed hash of the account id; V0031 states that no PERSONAL field exists"
                    + " anywhere in the reporting schema, and the hash identifies nobody once the account is anonymised.";

    static {
        // ------------------------------------------------------------------ customers' own, kept on purpose
        REASONS.put(
                "customer.brand_profiles",
                "Commercial aggregates per brand (status, first and last order time); ADR 0029 says commercial facts survive"
                        + " the person and CustomerErasureService leaves the table by design. Holds the id of an anonymised"
                        + " account and no personal value.");
        REASONS.put(
                "customer.consent_decisions",
                "Append-only evidence of what the person agreed to, when and under which policy; UPDATE and DELETE are"
                        + " withheld by grant (V0017) so it cannot be rewritten. The tenant must be able to prove what it did;"
                        + " the row holds an account id, a purpose and a version, no name or number.");
        REASONS.put(
                "customer.contact_attempts",
                "The call-desk journal records that a call was made, its outcome and the operator, with no number or name"
                        + " (LeadErasureParticipant's doc); the lead it hangs off is erased by the leads participant.");
        REASONS.put(
                "customer.customer_import_run_rows",
                "A CSV import report row: the row number, its outcome and the account created or matched. It holds no name,"
                        + " number or address; the uploaded content is nulled when the run ends (JdbcCustomerImportStore).");
        REASONS.put(
                "customer.customer_import_runs",
                "encrypted_content is the uploaded CSV, and JdbcCustomerImportStore sets it to NULL when a run completes or"
                        + " fails, so no finished run retains it; it is keyed by the importing staff member, not by a customer.");
        REASONS.put(
                "customer.erasure_requests",
                "The request and its proof (who asked, when, how many rows per table). It is the evidence that an erasure"
                        + " happened and holds no personal value.");
        REASONS.put(
                "customer.legal_holds",
                "The hold itself: an account id, a closed reason code and the people who agreed. It is the reason an erasure"
                        + " waits, so an erasure cannot remove it; it holds no personal value.");
        REASONS.put(
                "customer.verification_challenges",
                "A one-time sign-in code challenge, not keyed by an account; VerificationChallengeSweeper deletes settled and"
                        + " lapsed challenges (ADR 0015), which is the only reason its phone ciphertext exists.");

        // ------------------------------------------------------------------ tax and payment evidence
        REASONS.put(
                "fiscal.fiscal_documents",
                "A fiscal document is a tax record the tenant must keep for the statutory period (ADR 0038, ADR 0166); its"
                        + " protected references are the provider's request and response, keyed by order, and erasing them"
                        + " would destroy tax evidence.");
        REASONS.put(
                "fiscal.fiscal_sent_requests",
                "The request as sent to the fiscal provider, kept as evidence of what was filed (ADR 0166); tax evidence"
                        + " survives the person (ADR 0029) and the row is keyed by document, not by account.");
        REASONS.put(
                "payments.payment_transactions",
                "Provider request and response references of a payment: financial evidence kept for reconciliation (ADR 0029:"
                        + " financial history survives); keyed by intent and attempt, not by customer account.");
        REASONS.put(
                "payments.provider_callbacks",
                "Provider callback references kept to prove what the provider told us (ADR 0029: financial history"
                        + " survives); keyed by provider reference and attempt, not by customer account.");
        REASONS.put(
                "payments.remedy_entitlements",
                "A remedy the tenant owes the customer (a discount or a compensation) and how much of it was used: a"
                        + " commercial liability and financial history. Names the customer by the id of an anonymised account.");

        // ------------------------------------------------------------------ couriers, staff, third parties
        REASONS.put("fulfillment.couriers", COURIER);
        REASONS.put("fulfillment.courier_engagements", COURIER);
        REASONS.put("fulfillment.courier_location_tracks", COURIER);
        REASONS.put("fulfillment.courier_shifts", COURIER);
        REASONS.put(
                "fulfillment.courier_assignment_earnings",
                "Courier pay evidence keyed by courier and shipment, not by customer account; its pickup and delivery"
                        + " confirmation points are deleted by ConfirmationPointRetentionJob 30 days after the settlement"
                        + " period settles (ADR 0045), so a delivery coordinate does not outlive the dispute window.");
        REASONS.put(
                "fulfillment.courier_track_summaries",
                "Courier pay evidence kept with the shipment under FINANCIAL retention (V0041): two encrypted confirmation"
                        + " points per delivery, keyed by courier and shipment, not reachable from a customer account without"
                        + " a cross-module join. RESIDUAL RISK, reported to the owner: the delivery point is a building"
                        + " coordinate and has no purge job; whether erasure outranks that retention is a decision.");
        REASONS.put("iam.staff_emergency_contacts", STAFF);
        REASONS.put("iam.staff_members", STAFF);
        REASONS.put("iam.staff_preferences", STAFF);
        REASONS.put(
                "tenant.location_contact_persons",
                "A branch's contact persons: a colleague or a third party the tenant named. They are not the tenant's"
                        + " customers, and the tenant edits and removes them itself.");

        // ------------------------------------------------------------------ integration
        REASONS.put(
                "integration.telegram_bindings",
                "Binds an operations Telegram chat to a location (V0099: audience OPERATIONS; customer 1:1 audiences are out of"
                        + " its scope), so the chat is staff's, not a customer's.");
        REASONS.put(
                "integration.telegram_chat_locks",
                "A per-chat send lease (tenant, chat id, lease owner and expiry) that serialises Bot API calls (ADR 0058). It"
                        + " carries no account link, so it cannot be found from an account, and holds no content.");

        // ------------------------------------------------------------------ loyalty
        REASONS.put("loyalty.clawbacks", POINTS_LEDGER);
        REASONS.put("loyalty.entries", POINTS_LEDGER);
        REASONS.put("loyalty.lots", POINTS_LEDGER);
        REASONS.put("loyalty.reservations", POINTS_LEDGER);
        REASONS.put("loyalty.pos_sync_movements", POINTS_LEDGER);
        REASONS.put(
                "loyalty.pos_sync_discrepancies",
                "A reconciliation finding between the platform's balance and the till's. The till's own customer id is an"
                        + " INTERNAL provider id (V0555), not a phone or name; the link that produced it is retired by the"
                        + " loyalty participant, and the finding is financial evidence of a difference.");

        // ------------------------------------------------------------------ marketing
        REASONS.put("marketing.automation_runs", MARKETING_EVIDENCE);
        REASONS.put("marketing.campaign_recipients", MARKETING_EVIDENCE);
        REASONS.put("marketing.marketing_sends", MARKETING_EVIDENCE);
        REASONS.put("marketing.scenario_step_decisions", MARKETING_EVIDENCE);
        REASONS.put(
                "marketing.presented_offers",
                "Counters of how often an offer was shown to the id of an anonymised account, with no personal value; the"
                        + " profile used to choose the offer is erased by the marketing participant.");
        REASONS.put(
                "marketing.scenario_participant_state",
                "Where an anonymised account sits in a scenario and whether it was in the control group: experiment"
                        + " bookkeeping that the campaign's results are computed from. No personal value.");
        REASONS.put(
                "marketing.suppressions",
                "A do-not-contact record. That a person must not be contacted is not forgotten because she asked to be"
                        + " forgotten; keyed by the id of an anonymised account, with a channel and a reason code.");

        // ------------------------------------------------------------------ notifications, ordering, partner, printing
        REASONS.put(
                "notifications.notifications",
                "The delivery log holds no recipient name, number or body by design (V0026); only the account id, the"
                        + " template, frozen variables from non-personal event payloads and hashes. It is evidence that a"
                        + " message was sent.");
        REASONS.put(
                "notifications.unsubscribe_tokens",
                "The SHA-256 of an opaque token in a campaign email's way-out link, and what it withdraws: a consent"
                        + " purpose, the channel and a locale. It holds no address, no name and no token, and names the customer"
                        + " only by the id of an anonymised account once she is erased. V0671 gives it no foreign key to the"
                        + " account on purpose, so an erasure is never blocked by a row whose only job is to withdraw a"
                        + " consent. A token is valid for 400 days by default and EmailUnsubscribeTokenSweeper then removes"
                        + " the row, so what remains of an erased customer is an anonymised id for at most that long.");
        REASONS.put(
                "ordering.orders",
                "The commercial record of the order: totals, tax, status, tender. ADR 0019 and ADR 0029: an order never stops"
                        + " having happened. Its copies of the customer's identity are blanked by the ordering participant.");
        REASONS.put(
                "ordering.order_line_marks",
                "A product marking code (the code printed on the item), which identifies a unit of goods and not a person.");
        REASONS.put(
                "partner.inbound_orders",
                "The aggregator's raw payload for an order it sent, keyed by the aggregator's order id; the aggregator's"
                        + " customers have no account here, so it cannot be reached from one. RESIDUAL RISK, reported to the"
                        + " owner: no retention job removes it.");
        REASONS.put(
                "printing.print_jobs",
                "protected_document is purged at payload_purge_after by PrintMaintenance.purgeDocuments; a job is keyed by its"
                        + " source document and device, not by a customer account.");

        // ------------------------------------------------------------------ pricing, referral, reporting
        REASONS.put("pricing.coupon_customer_usage", REDEMPTION_LEDGER);
        REASONS.put("pricing.coupon_redemptions", REDEMPTION_LEDGER);
        REASONS.put("pricing.promotion_customer_usage", REDEMPTION_LEDGER);
        REASONS.put("pricing.promotion_redemptions", REDEMPTION_LEDGER);
        REASONS.put(
                "pricing.quotes",
                "A priced basket (amounts, publication, expiry) that an order was created from, with the id of an anonymised"
                        + " account; it holds no name, number or address. Financial history survives the person (ADR 0029).");
        REASONS.put(
                "referral.redemptions",
                "The referral programme's ledger: who was rewarded how much for which order. Financial history that survives the"
                        + " person (ADR 0029); both parties are named only by account ids, and the referrer's code is"
                        + " disabled by the referral participant.");
        REASONS.put("reporting.fact_order", REPORTING_FACT);
        REASONS.put("reporting.fact_promotion_redemption", REPORTING_FACT);
        REASONS.put("reporting.fact_review", REPORTING_FACT);
        REASONS.put(
                "reporting.report_exports",
                "encrypted_query is the filter a staff member ran for a report, keyed by the requester and a report, not by a"
                        + " customer account; the exported artifact in object storage has its own retention.");
    }

    private ErasureInventoryExemptions() {}
}
