package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.EngagementPolicy.EngagementOverride;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;

/**
 * ADR 0044's policy vocabulary, without infrastructure.
 *
 * <p>These are the rules a tenant will argue with — the cap, the quiet window, the
 * refusal to accept a predicate the catalogue does not have — so they are asserted
 * where they can be read in one screen rather than inferred from a database
 * fixture.
 */
class EngagementPolicyTests {

    /** 2026-08-22T17:30Z is 22:30 in Tashkent, which is inside the closed window. */
    private static final Instant LATE_EVENING = Instant.parse("2026-08-22T17:30:00Z");

    /** 2026-08-22T09:00Z is 14:00 in Tashkent, which is not. */
    private static final Instant AFTERNOON = Instant.parse("2026-08-22T09:00:00Z");

    @Test
    @DisplayName("a message inside quiet hours is held to the next open boundary")
    void quietHoursDeferRatherThanDrop() {
        EngagementPolicy policy = EngagementPolicy.platformDefault();

        assertThat(policy.isQuiet(LATE_EVENING)).isTrue();
        assertThat(policy.isQuiet(AFTERNOON)).isFalse();

        Instant boundary = policy.nextOpenBoundary(LATE_EVENING);

        // 10:00 the following morning in Tashkent, which is 05:00 UTC. Held rather
        // than dropped: a marketer reading a delivered count cannot distinguish a
        // quiet-hour drop from a suppression.
        assertThat(boundary).isEqualTo(Instant.parse("2026-08-23T05:00:00Z"));
        assertThat(policy.isQuiet(boundary)).isFalse();

        // Outside the window the boundary is now, so nothing is deferred that did
        // not need to be.
        assertThat(policy.nextOpenBoundary(AFTERNOON)).isEqualTo(AFTERNOON);
    }

    @Test
    @DisplayName("an override may tighten the cap and may never loosen it")
    void theCapTightensOnly() {
        EngagementPolicy platform = EngagementPolicy.platformDefault();

        EngagementPolicy tighter = platform.tightenedBy(new EngagementOverride(
                LocalTime.of(20, 0), LocalTime.of(11, 0), ZoneId.of("Asia/Tashkent"), 1, 4, 200L, "UZS"));

        assertThat(tighter.messagesPer7Days()).isEqualTo(1);
        assertThat(tighter.messagesPer30Days()).isEqualTo(4);
        assertThat(tighter.quietHoursStart()).isEqualTo(LocalTime.of(20, 0));

        // Both numbers protect a sending reputation HorecaOS shares across tenants, so
        // one tenant's aggressive sending degrades delivery for every other tenant
        // on the same sender. That externality is what makes this refusal the
        // platform's decision rather than the tenant's.
        assertThatThrownBy(() -> platform.tightenedBy(new EngagementOverride(null, null, null, 10, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7-day cap");

        assertThatThrownBy(() -> platform.tightenedBy(
                        new EngagementOverride(LocalTime.of(23, 0), null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quiet hours");
    }

    @Test
    @DisplayName("nothing reaches SENDING except through an approval")
    void theCampaignStateMachineRefusesAnUnapprovedSend() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SENDING)).isFalse();
        assertThat(CampaignStatus.IN_REVIEW.canTransitionTo(CampaignStatus.SENDING))
                .isFalse();
        assertThat(CampaignStatus.APPROVED.canTransitionTo(CampaignStatus.SENDING))
                .isTrue();

        // A campaign that stopped at its ceiling and can be restarted is a ceiling
        // that only delays the overspend.
        assertThat(CampaignStatus.HALTED_BUDGET.isTerminal()).isTrue();
        assertThat(CampaignStatus.HALTED_OPERATOR.isTerminal()).isTrue();
        assertThat(CampaignStatus.SENT.isTerminal()).isTrue();
        assertThat(CampaignStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("the predicate catalogue refuses a shape it cannot translate")
    void malformedPredicatesAreRefusedWhereTheyAreWritten() {
        // An operator the type does not accept. Refused when the audience is saved
        // and a marketer is present to read the message, rather than at snapshot
        // build with an approval already granted.
        assertThatThrownBy(() ->
                        AudiencePredicate.textSet(PredicateType.RECENCY_DAYS, PredicateOperator.IN, List.of("30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not accept");

        assertThatThrownBy(() -> AudiencePredicate.numeric(
                        PredicateType.NET_SPEND_MINOR, PredicateOperator.BETWEEN, 500_000L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inverted range");

        // A locale outside the three HorecaOS sends in would produce an audience that
        // silently matches nobody, which is the failure mode that looks like a
        // working feature.
        assertThatThrownBy(() ->
                        AudiencePredicate.textSet(PredicateType.PREFERRED_LOCALE, PredicateOperator.IN, List.of("fr")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Locales must be among");

        assertThat(AudiencePredicate.numeric(PredicateType.RECENCY_DAYS, PredicateOperator.AT_LEAST, 90L, null)
                        .type())
                .isEqualTo(PredicateType.RECENCY_DAYS);
    }

    @Test
    @DisplayName("the catalogue has no predicate over free text or a raw date of birth")
    void theCatalogueIsNotABehaviouralProfile() {
        List<String> names =
                java.util.Arrays.stream(PredicateType.values()).map(Enum::name).toList();

        // A predicate over what somebody searched for, or over what they wrote in a
        // review, is a behavioural profile. ADR 0044 says this catalogue is
        // deliberately not one, and the assertion is here so adding such a
        // predicate is a deliberate act with a failing test in front of it.
        assertThat(names)
                .noneMatch(name -> name.contains("SEARCH")
                        || name.contains("REVIEW")
                        || name.contains("TEXT")
                        || name.contains("NOTE")
                        || name.equals("DATE_OF_BIRTH"));
    }
}
