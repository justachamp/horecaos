package uz.horecaos.platform.integration.camel.einvoicing;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Sends one {@link EInvoicingApiCall} through the ADR 0007 e-invoicing route.
 *
 * <p>Nothing is thrown. Every failure -- connection refused, read timeout, a 401, a 502,
 * an unparseable body -- comes back as one of the four canonical outcomes, and the adapter's
 * next move is decided from the classification. The outcome's {@code normalized()} map is the
 * operator's parsed JSON answer, unchanged: interpreting it is the adapter's job.
 *
 * <p>An interface so the two adapters are exercised against a recording fake of the
 * operator's documented answers, with no route, no HTTP and no secrets manager.
 */
public interface EInvoicingApiTransport {

    ProviderOutcome exchange(EInvoicingApiCall call);
}
