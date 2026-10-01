package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;

/**
 * {@code fulfillment.delivery_plans.dispatch_decision} as JSON (V0465, ADR 0142).
 *
 * <p>Its own mapper rather than the application's, so a stored decision reads back the same
 * whatever the web layer's Jackson settings are changed to. Unknown properties are ignored: a
 * plan written by a newer build and read by an older one during a rollback must still load, and
 * the fields it does not know are exactly the ones an older sourcing tick could not have acted on.
 *
 * <p>The decision carries installation ids and rule ids only -- no coordinates, no names, no money
 * (ADR 0029) -- so serialising the whole record cannot leak anything it should not.
 */
final class DispatchDecisionCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private DispatchDecisionCodec() {}

    static String write(DispatchDecision decision) {
        return MAPPER.writeValueAsString(decision);
    }

    static DispatchDecision read(String json) {
        return MAPPER.readValue(json, DispatchDecision.class);
    }
}
