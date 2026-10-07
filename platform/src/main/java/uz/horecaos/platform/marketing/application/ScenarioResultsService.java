package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.domain.AttributionModel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.ContactRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.GoalRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Did the scenario work? The treated group's rate of reaching a goal against the withheld
 * control group's over the same window (ADR 0112, result measurement).
 *
 * <p>The goal event is the guest's next order. A guest enters; if they place an order
 * (not cancelled, rejected or expired) within the window, that is a conversion. For the
 * control group it is a raw conversion: they were never contacted by this scenario, so
 * there is nothing to attribute. For the treated group it is a conversion <em>of this
 * scenario</em> only if the named model credits it to this scenario:
 *
 * <ul>
 *   <li>{@link AttributionModel#FIRST_TOUCH} credits the first scenario or campaign that
 *       contacted the guest in the window;
 *   <li>{@link AttributionModel#LAST_TOUCH} credits the most recent one before the order.
 * </ul>
 *
 * <p>So a guest this scenario contacted and who then ordered after another campaign's
 * later message is a conversion under first touch and not under last touch, which is what
 * the two models are for. The difference between the two rates is the lift, and it is only
 * stated when there is a control group to state it against: a scenario run without one has
 * no baseline, and a number that pretended otherwise would be the false assurance ADR 0112
 * names as the cost of leaving the control group optional.
 */
@Service
public class ScenarioResultsService {

    /** The default window, in days, a goal event is measured over. */
    public static final int DEFAULT_WINDOW_DAYS = 14;

    static final int MAX_WINDOW_DAYS = 90;

    private final JdbcCampaignStore campaigns;
    private final JdbcScenarioStore scenarios;
    private final Clock clock;

    public ScenarioResultsService(JdbcCampaignStore campaigns, JdbcScenarioStore scenarios, Clock clock) {
        this.campaigns = campaigns;
        this.scenarios = scenarios;
        this.clock = clock;
    }

    /**
     * @param treatedRate converted over participants, null with no participants
     * @param controlRate null when there is no control group, or it is empty
     * @param lift {@code treatedRate - controlRate}, in percentage points of the guests, or null when either rate is
     */
    public record Results(
            AttributionModel model,
            int windowDays,
            int treatedParticipants,
            int treatedConverted,
            int controlParticipants,
            int controlConverted,
            @Nullable Double treatedRate,
            @Nullable Double controlRate,
            @Nullable Double lift,
            boolean hasControlGroup,
            int participantsWithOpenWindow) {}

    @Transactional(readOnly = true)
    public Results results(
            UUID tenantId, UUID brandId, UUID campaignId, AttributionModel model, @Nullable Integer windowDays) {
        CampaignRow campaign = campaigns
                .find(tenantId, campaignId)
                .filter(row -> row.brandId().equals(brandId) && row.isScenario())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No scenario " + campaignId + " belongs to this brand"));
        int window = windowDays == null ? DEFAULT_WINDOW_DAYS : windowDays;
        if (window < 1 || window > MAX_WINDOW_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The window is between 1 and %d days".formatted(MAX_WINDOW_DAYS));
        }

        List<GoalRow> goals = scenarios.goalRows(tenantId, brandId, campaign.id(), window);
        Map<UUID, List<ContactRow>> contacts = new HashMap<>();
        for (ContactRow contact : scenarios.contactsOfParticipants(tenantId, campaign.id(), window)) {
            contacts.computeIfAbsent(contact.customerAccountId(), key -> new ArrayList<>())
                    .add(contact);
        }

        Instant now = clock.instant();
        int treated = 0;
        int treatedConverted = 0;
        int control = 0;
        int controlConverted = 0;
        int open = 0;
        for (GoalRow goal : goals) {
            boolean windowOpen = goal.enteredAt().plus(Duration.ofDays(window)).isAfter(now);
            if (windowOpen) {
                open++;
            }
            if (goal.inControlGroup()) {
                control++;
                if (goal.firstOrderAt() != null) {
                    controlConverted++;
                }
            } else {
                treated++;
                if (goal.firstOrderAt() != null
                        && creditedTo(
                                campaign.id(),
                                goal,
                                contacts.getOrDefault(goal.customerAccountId(), List.of()),
                                model,
                                window)) {
                    treatedConverted++;
                }
            }
        }

        Double treatedRate = treated == 0 ? null : (double) treatedConverted / treated;
        Double controlRate = control == 0 ? null : (double) controlConverted / control;
        Double lift = treatedRate == null || controlRate == null ? null : treatedRate - controlRate;
        return new Results(
                model,
                window,
                treated,
                treatedConverted,
                control,
                controlConverted,
                treatedRate,
                controlRate,
                lift,
                control > 0,
                open);
    }

    /**
     * Whether the model credits this scenario with the guest's order.
     *
     * <p>Among every contact the guest received in the window before the order, from any
     * scenario or broadcast, the model picks one; the order is this scenario's only if the
     * pick is this scenario. A guest with no contact in the window (this scenario contacted
     * them, but outside it) is credited to nobody.
     */
    static boolean creditedTo(
            UUID campaignId, GoalRow goal, List<ContactRow> contacts, AttributionModel model, int windowDays) {
        Instant order = goal.firstOrderAt();
        if (order == null) {
            return false;
        }
        Instant windowStart = order.minus(Duration.ofDays(windowDays));
        List<ContactRow> candidates = contacts.stream()
                .filter(contact -> !contact.at().isAfter(order) && !contact.at().isBefore(windowStart))
                .sorted(Comparator.comparing(ContactRow::at))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        ContactRow credited = model == AttributionModel.FIRST_TOUCH ? candidates.getFirst() : candidates.getLast();
        return credited.campaignId().equals(campaignId);
    }
}
