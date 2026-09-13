package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * T12 (7.5): who a leaderboard row credits, and how a pseudo-operator is told
 * apart from a staff subject with no second column.
 */
class OperatorAttributionTests {

    @Test
    void anAcceptingOperatorOutranksTheCreatingOne() {
        // ADR 0039's approval-decision path: whoever confirmed the order did
        // real work on it, even when somebody — or something — else created
        // it (an aggregator order a call-centre operator had to approve).
        String resolved = OperatorAttribution.resolve("CUSTOMER", "cust-1", "USER", "staff-9", "TELEGRAM");

        assertThat(resolved).isEqualTo("staff-9");
    }

    @Test
    void theCreatingOperatorIsCreditedWhenNobodyAccepted() {
        // OperatorOrderingService: a phone order the operator both entered and
        // confirmed automatically (AUTO_CONFIRM), so accepted_by is never set.
        String resolved = OperatorAttribution.resolve("USER", "staff-1", null, null, "ADMIN");

        assertThat(resolved).isEqualTo("staff-1");
    }

    @Test
    void aCustomerCheckoutWithNoHumanActorBecomesAPseudoOperatorNamedAfterItsChannel() {
        // StorefrontOrderingController / CustomerBotOrderingAdapter: both name
        // the account as CUSTOMER, never a member of staff.
        String bot = OperatorAttribution.resolve("CUSTOMER", "cust-1", null, null, "BOT");
        String website = OperatorAttribution.resolve("CUSTOMER", "cust-2", null, null, "WEBSITE");

        assertThat(bot).isEqualTo("channel:BOT");
        assertThat(website).isEqualTo("channel:WEBSITE");
        assertThat(OperatorAttribution.isPseudoOperator(bot)).isTrue();
        assertThat(OperatorAttribution.channelOf(bot)).isEqualTo("BOT");
    }

    @Test
    void everyActorTypeWithNoUserAtEitherEndFallsBackToTheChannel() {
        // SERVICE, SYSTEM_JOB, PROVIDER, or simply nothing recorded (a
        // pre-ADR-0039 order) — none of these is a person, so none is credited
        // as one.
        for (String actorType : new String[] {"SERVICE", "SYSTEM_JOB", "PROVIDER", null}) {
            String resolved = OperatorAttribution.resolve(actorType, "some-id", null, null, "AGGREGATOR");
            assertThat(resolved).as("actorType=%s", actorType).isEqualTo("channel:AGGREGATOR");
        }
    }

    @Test
    void aUserActorTypeWithNoIdIsNotCreditedAsAPerson() {
        // Defensive: USER with a blank/null id is not a usable subject, so this
        // falls through to the channel rather than crediting an empty string.
        String resolved = OperatorAttribution.resolve("USER", null, "USER", "  ", "TELEGRAM");

        assertThat(resolved).isEqualTo("channel:TELEGRAM");
    }

    @Test
    void aRealStaffSubjectIsNeverMistakenForAPseudoOperator() {
        assertThat(OperatorAttribution.isPseudoOperator("018f6f4e-1000-7000-8000-00000000aaaa"))
                .isFalse();
    }

    @Test
    void channelOfRefusesAStaffSubject() {
        assertThatThrownBy(() -> OperatorAttribution.channelOf("staff-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
