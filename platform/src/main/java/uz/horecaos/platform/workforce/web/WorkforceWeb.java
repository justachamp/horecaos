package uz.horecaos.platform.workforce.web;

import java.util.UUID;
import org.slf4j.MDC;

/** Small helpers the workforce controllers share. */
final class WorkforceWeb {

    private WorkforceWeb() {}

    /** The request's correlation id (ADR 0027), or a fresh one when none is set. */
    static String correlation() {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? UUID.randomUUID().toString() : fromRequest;
    }
}
