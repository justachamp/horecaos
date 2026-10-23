package uz.horecaos.platform.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;
import uz.horecaos.platform.fiscal.infrastructure.FakeFiscalTerminalAdapter;

/** The production fake against the port contract: the cases it can honour, with no scenario switch to throw. */
class FakeFiscalTerminalAdapterContractTests extends FiscalTerminalPortContract {

    private final FiscalTerminalPort fake =
            new FakeFiscalTerminalAdapter(Clock.fixed(Instant.parse("2026-10-23T09:00:00Z"), ZoneOffset.UTC));

    @Override
    protected FiscalTerminalPort port() {
        return fake;
    }
}
