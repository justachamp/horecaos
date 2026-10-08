package uz.horecaos.platform.marketing.domain;

/**
 * Why a customer did not get the message (ADR 0044).
 *
 * <p>The five subtractions, in the order they are applied, plus the one that only
 * exists at send. They are applied twice: at snapshot build, so the approver sees
 * truthful reach and cost, and again per recipient at send, so the unsubscribe
 * that arrived in between wins.
 *
 * <p>Every one of them is <em>recorded</em> rather than filtered. A dropped
 * recipient leaves no row, and a tenant asking "why did this customer not get it"
 * about somebody who never became a recipient has no answer at all. That is the
 * failure this enum exists to make impossible: the reason is on the snapshot
 * member row when the subtraction happened at build, and on the campaign
 * recipient row when it happened at send.
 */
public enum RefusalReason {

    /** Not {@code ACTIVE}, merged away, or anonymised under ADR 0029. */
    ACCOUNT_NOT_ACTIVE,

    /**
     * ADR 0015 has no positive decision for this purpose at this scope.
     *
     * <p>Absence is not consent. "Nobody ever asked" and "they said yes" are the
     * two states a default-true would merge, and the migrated base carries no
     * marketing consent at all because no legacy table records one.
     */
    CONSENT_WITHHELD,

    /**
     * An active suppression for the tenant, brand scope, and channel.
     *
     * <p>Outranks consent. Consent is legal permission and suppression is a
     * deliverability or abuse fact; one customer can carry both, and a positive
     * consent decision must not overcome a complaint.
     */
    SUPPRESSED,

    /** Already at the rolling cap, counted across every channel together. */
    FREQUENCY_CAP_REACHED,

    /** No verified endpoint of the kind this channel addresses. */
    NO_VERIFIED_ENDPOINT,

    /**
     * The campaign stopped before this recipient was reached.
     *
     * <p>Only ever a send-time reason: at snapshot build there is no campaign to
     * halt. It exists so a halted campaign's untouched recipients are a stated
     * outcome rather than a set of rows left in {@code PENDING} forever, which
     * reads to an operator as a stuck job rather than a deliberate stop.
     */
    CAMPAIGN_HALTED,

    /**
     * ADR 0112: the step would hand this guest a second offer while another live
     * scenario's offer is still fresh. Scenario-only, which is why it is not a
     * value broadcast-only code ever has to handle.
     */
    SCENARIO_CONFLICT,

    /**
     * ADR 0112: a simultaneously due broadcast outranks this step under the
     * tenant's {@code marketing.channel.priority.order}. The step is deferred to
     * the next eligible slot and never dropped.
     */
    SCENARIO_PRIORITY_LOST,

    /**
     * ADR 0112: the scenario no longer applies to this guest or at all — its
     * offer has expired or been retired, its channel can no longer deliver, its
     * campaign has been halted, or the guest's own continuation or stop condition
     * has been met.
     */
    SCENARIO_STOPPED;

    /** Whether this reason can be reached while building a snapshot. */
    public boolean appliesAtSnapshotBuild() {
        return this != CAMPAIGN_HALTED && !isScenarioOnly();
    }

    /**
     * The three ADR 0112 additions. A separate predicate rather than a second enum
     * (the record's own choice): a full block history is read from this enum alone,
     * and broadcast code asks this question to know it can never meet them.
     */
    public boolean isScenarioOnly() {
        return this == SCENARIO_CONFLICT || this == SCENARIO_PRIORITY_LOST || this == SCENARIO_STOPPED;
    }
}
