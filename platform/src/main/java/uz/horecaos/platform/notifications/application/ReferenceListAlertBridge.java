package uz.horecaos.platform.notifications.application;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.ReferenceListAlertPort;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;

/**
 * The catalog's reference-list alerts (ADR 0173), raised and resolved as control-plane incidents
 * (ADR 0058).
 *
 * <p>This is the whole of the bridge: {@code catalog} declares {@link ReferenceListAlertPort} and does
 * not import {@code notifications}, so this class is where the two meet, in the module that already
 * depends on {@code catalog.api}.
 */
@Component
public class ReferenceListAlertBridge implements ReferenceListAlertPort {

    private final ControlPlaneAlertPort alerts;

    public ReferenceListAlertBridge(ControlPlaneAlertPort alerts) {
        this.alerts = alerts;
    }

    @Override
    public void raise(
            String eventClass, String subjectType, String subjectId, Map<String, String> variables, Instant at) {
        alerts.raise(new ControlPlaneAlert(eventClass, subjectType, subjectId, variables, at));
    }

    @Override
    public void resolve(String eventClass, String subjectType, String subjectId, String by, String note) {
        alerts.resolve(eventClass, subjectType, subjectId, by, note);
    }
}
