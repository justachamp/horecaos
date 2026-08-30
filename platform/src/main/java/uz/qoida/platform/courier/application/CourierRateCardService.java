package uz.qoida.platform.courier.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.courier.domain.RateCard;
import uz.qoida.platform.courier.domain.RateCardValidator;
import uz.qoida.platform.courier.domain.RateComponent;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Authoring and activating rate cards (ADR 0042).
 *
 * <p>Validation happens at activation rather than at authoring, because a draft
 * with one band typed in is a normal intermediate state and refusing it would
 * make the screen unusable. Activation is the moment the card becomes capable of
 * paying somebody, and that is where the band ladder has to be complete: a gap
 * means an order at exactly the boundary earns nothing for its distance, and the
 * courier finds it before the tenant does.
 */
@Service
public class CourierRateCardService {

    private final JdbcCourierRateCardStore cards;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierRateCardService(JdbcCourierRateCardStore cards, AuditRecorder audit, Clock clock) {
        this.cards = cards;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID author(NewRateCard command) {
        UUID cardId = UUID.randomUUID();
        cards.insertCard(cardId, command.tenantId(), command.brandId(), command.locationId(),
                command.courierTypeId(), command.code(), command.cardVersion(), command.currency());
        for (RateComponent component : command.components()) {
            cards.insertComponent(UUID.randomUUID(), command.tenantId(), cardId, component);
        }
        return cardId;
    }

    /**
     * Activates the card after checking its band coverage. Any earlier active
     * card with the same code is superseded in the same transaction.
     */
    @Transactional
    public RateCard activate(UUID tenantId, UUID cardId, ActorRef actor, String reason) {
        RateCard card = cards.findCard(tenantId, cardId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such rate card: " + cardId));
        try {
            RateCardValidator.validateForActivation(card);
        } catch (RateCardValidator.InvalidRateCardException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }

        if (!cards.activate(tenantId, cardId, actor.subject(), clock.instant())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "Only a DRAFT card can be activated");
        }

        audit.record(AuditFact.of("courier.ratecard.activated", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_rate_card", cardId)
                .because(reason)
                .changed(Map.of("cardVersion", card.version(),
                        "componentCount", card.components().size()))
                .usingCapability("courier.ratecard.manage")
                .correlatedBy("courier-rate-card")
                .occurredAt(clock.instant())
                .build());

        return card;
    }

    public record NewRateCard(UUID tenantId, UUID brandId, UUID locationId, UUID courierTypeId,
            String code, int cardVersion, String currency, List<RateComponent> components) { }
}
