package uz.horecaos.platform.voice.api;

import java.util.List;
import java.util.UUID;

/**
 * Who is online right now, for a routing-capable adapter to skip a paused
 * operator, and for the ADR 0064 exit criteria's "operator roster of that
 * moment" snapshot on an offered/missed call (ADR 0064).
 *
 * <p>Deliberately read-only and deliberately not telephony-private: this is
 * the same presence ADR 0059's future inbox assignment reads, so it lives on
 * {@code voice.api} rather than behind a call-specific name.
 */
public interface OperatorPresenceQueryPort {

    List<OnlineOperator> online(UUID tenantId, UUID locationId);

    record OnlineOperator(String operatorPrincipalId, String state) {}
}
