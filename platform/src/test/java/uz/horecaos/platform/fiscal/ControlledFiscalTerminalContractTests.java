package uz.horecaos.platform.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;

/**
 * The controlled double against the port contract, scenarios included: the vocabulary the bridge and sweeper tests
 * script a terminal with is itself held to the contract, so a test that says «the reply was lost» means what a real
 * lost reply means.
 */
class ControlledFiscalTerminalContractTests extends FiscalTerminalPortContract {

    private final ControlledFiscalTerminal terminal =
            new ControlledFiscalTerminal(Clock.fixed(Instant.parse("2026-10-23T09:00:00Z"), ZoneOffset.UTC));

    @Override
    protected FiscalTerminalPort port() {
        return terminal;
    }

    @Override
    protected boolean supportsScenarios() {
        return true;
    }

    @Override
    protected void next(Scenario scenario) {
        terminal.next(scenario);
    }
}
