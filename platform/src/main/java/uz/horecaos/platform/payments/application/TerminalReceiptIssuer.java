package uz.horecaos.platform.payments.application;

import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.payments.domain.FiscalDocument;

/**
 * Issues the receipt of a document the tenant's own fiscal terminal owes (ADR 0166 «Providers», ADR 0038 stage 5).
 *
 * <p>A document with no provider is a {@code TERMINAL} receipt: a cash sale at a location whose own equipment is to
 * issue it. {@link PartnerFiscalizationBridge} hands such a document here rather than answering that there is no
 * provider path, which is what it said before a terminal could be asked.
 */
public interface TerminalReceiptIssuer {

    /** Asks the document's terminal for its receipt and records what came back. Never issues twice. */
    PartnerFiscalizationPort.Outcome issue(FiscalDocument document);

    /** No terminal anywhere: the answer a provider-less document always got before terminals could be asked. */
    TerminalReceiptIssuer NONE = document -> PartnerFiscalizationPort.Outcome.NO_PROVIDER_PATH;
}
