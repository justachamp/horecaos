package uz.horecaos.platform.assistant.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.support.FakeAssistantModel;

/**
 * The real {@link AssistantTurnService} -- real ledger, real knowledge store, real
 * audit trail, real checks -- over scripted ports of the modules it reads, for
 * suites in other packages that need the assistant behind the conversations
 * engine (the Telegram end-to-end test).
 *
 * <p>A facade over the package-private {@link AssistantFixture} so that the
 * assistant's own constructors stay package-private in production code and test
 * wiring does not widen them.
 */
public final class AssistantTestWiring {

    private final AssistantFixture fixture;

    public AssistantTestWiring(JdbcClient jdbc, Instant start) {
        this.fixture = new AssistantFixture(jdbc, start);
    }

    /** Creates the tenant, brand and two branches the wiring answers about. */
    public AssistantTestWiring seeded() {
        fixture.seedTenancy();
        return this;
    }

    public ConversationParticipant participant() {
        return fixture.service;
    }

    public UUID tenantId() {
        return fixture.tenantId;
    }

    public UUID brandId() {
        return fixture.brandId;
    }

    public FakeAssistantModel model() {
        return fixture.model;
    }

    /** One dish at the Chilonzor branch, at a price in whole som. */
    public AssistantTestWiring sellsPlovAt(long amountMinor) {
        fixture.menu.offer(fixture.chilonzor, AssistantFixture.dish("Плов", amountMinor, UUID.randomUUID()));
        return this;
    }

    public AssistantTestWiring onlyOneBranch() {
        fixture.branches.only(
                AssistantFixture.branch(fixture.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        return this;
    }

    /** The zone algorithm says Chilonzor delivers to wherever the customer shares. */
    public AssistantTestWiring deliversToSharedLocations() {
        fixture.coverage.matches = List.of(
                new uz.horecaos.platform.fulfillment.api.BranchResolutionPort.DeliveryBranchMatch(
                        fixture.chilonzor, "Chilonzor", UUID.randomUUID(), 1, 1, 1_000));
        return this;
    }

    public AssistantTestWiring someoneIsOnline(boolean online) {
        fixture.presence.someoneOnline = online;
        return this;
    }

    public AssistantTestWiring entitled(boolean assistant) {
        fixture.entitlements.assistant = assistant;
        return this;
    }

    public AssistantTestWiring modelWillInvent(String reply, String... citations) {
        fixture.model.replyingWith(reply, citations);
        return this;
    }

    public AssistantTestWiring advance(Duration by) {
        fixture.mutableClock.advance(by);
        return this;
    }

    public List<Map<String, Object>> ledger() {
        return fixture.ledger();
    }
}
