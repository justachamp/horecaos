package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fiscal.domain.FiscalTerminal;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalHealth;
import uz.horecaos.platform.fiscal.domain.FiscalTerminalKind;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalTerminalStore;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Registering a tenant's fiscal-capable equipment, and recording whether it
 * still answers (ADR 0038 lines 503-513, Settings 10.7 Tab 2).
 *
 * <p>The whole point of this table is one boolean:
 * {@link FiscalTerminal#capable()}, read by {@link
 * uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory} on the checkout
 * path. Everything else here — kind, provider binding, health history — is
 * what an IT admin needs to keep that boolean honest, never a second copy of
 * it.
 */
@Service
public class FiscalTerminalService {

    private final JdbcFiscalTerminalStore store;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    public FiscalTerminalService(
            JdbcFiscalTerminalStore store, Clock clock, AuditRecorder audit, CurrentActor currentActor) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
    }

    @Transactional
    public FiscalTerminal register(UUID tenantId, RegisterTerminalCommand command) {
        FiscalTerminal terminal = FiscalTerminal.register(
                Ids.newId(),
                tenantId,
                command.brandId(),
                command.locationId(),
                command.legalEntityId(),
                command.kind(),
                command.providerBindingId(),
                command.terminalReference(),
                command.capabilitySnapshot());

        Instant now = clock.instant();
        try {
            store.insert(terminal, now);
        } catch (DataIntegrityViolationException violation) {
            throw explain(violation, command.terminalReference());
        }

        audit.record(AuditFact.of("fiscal-terminal.registered", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.location(tenantId, command.brandId(), command.locationId()))
                .target("FiscalTerminal", terminal.id())
                .because("Registered %s terminal '%s'".formatted(command.kind(), command.terminalReference()))
                .changed(Map.of(
                        "kind", command.kind().name(),
                        "capable", String.valueOf(terminal.capable())))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return terminal;
    }

    @Transactional(readOnly = true)
    public List<FiscalTerminal> listForBrand(UUID tenantId, UUID brandId) {
        return store.listForBrand(tenantId, brandId);
    }

    /**
     * A terminal by tenant, brand and id.
     *
     * <p>{@code brandId} is not decoration: {@code
     * OperationsFiscalTerminalController}'s {@code @RequiresCapability} is
     * {@code BRAND}-scoped and checked against the {@code brandId} named in
     * the URL — never against the terminal this method returns. Without this
     * predicate, a caller who genuinely holds {@code FISCAL_TERMINAL_MANAGE}
     * on their own brand could name any terminal id and act on a different
     * brand's equipment purely by knowing it. A mismatch is reported
     * identically to "does not exist" (ADR 0031's not-found-not-forbidden),
     * so the response gives no signal that the terminal exists under a
     * different brand.
     */
    @Transactional(readOnly = true)
    public FiscalTerminal require(UUID tenantId, UUID brandId, UUID terminalId) {
        return store.find(tenantId, brandId, terminalId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No fiscal terminal " + terminalId + " for this tenant"));
    }

    /** Settings 10.7 Tab 2's "Проверить связь". Never scheduled; always an operator action. */
    @Transactional
    public FiscalTerminal checkHealth(
            UUID tenantId, UUID brandId, UUID terminalId, int expectedVersion, FiscalTerminalHealth outcome) {
        Instant now = clock.instant();
        FiscalTerminal terminal =
                transition(tenantId, brandId, terminalId, expectedVersion, t -> t.recordHealthCheck(outcome, now));
        audit.record(AuditFact.of("fiscal-terminal.health-checked", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.location(tenantId, terminal.brandId(), terminal.locationId()))
                .target("FiscalTerminal", terminal.id())
                .targetVersion((long) terminal.version())
                .because("Checked connectivity for terminal " + terminal.terminalReference())
                .changed(Map.of("lastHealthStatus", outcome.name()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return terminal;
    }

    /** Settings 10.7 Tab 2's "Отключить". */
    @Transactional
    public FiscalTerminal suspend(UUID tenantId, UUID brandId, UUID terminalId, int expectedVersion) {
        return transitionAudited(
                tenantId, brandId, terminalId, expectedVersion, FiscalTerminal::suspend, "fiscal-terminal.suspended");
    }

    @Transactional
    public FiscalTerminal reactivate(UUID tenantId, UUID brandId, UUID terminalId, int expectedVersion) {
        return transitionAudited(
                tenantId,
                brandId,
                terminalId,
                expectedVersion,
                FiscalTerminal::reactivate,
                "fiscal-terminal.reactivated");
    }

    @Transactional
    public FiscalTerminal retire(UUID tenantId, UUID brandId, UUID terminalId, int expectedVersion) {
        return transitionAudited(
                tenantId, brandId, terminalId, expectedVersion, FiscalTerminal::retire, "fiscal-terminal.retired");
    }

    private FiscalTerminal transitionAudited(
            UUID tenantId,
            UUID brandId,
            UUID terminalId,
            int expectedVersion,
            Consumer<FiscalTerminal> change,
            String actionCode) {
        Instant now = clock.instant();
        FiscalTerminal terminal = transition(tenantId, brandId, terminalId, expectedVersion, change);
        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.location(tenantId, terminal.brandId(), terminal.locationId()))
                .target("FiscalTerminal", terminal.id())
                .targetVersion((long) terminal.version())
                .because(actionCode + " for terminal " + terminal.terminalReference())
                .changed(Map.of("status", terminal.status().name()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return terminal;
    }

    private FiscalTerminal transition(
            UUID tenantId, UUID brandId, UUID terminalId, int expectedVersion, Consumer<FiscalTerminal> change) {
        FiscalTerminal terminal = require(tenantId, brandId, terminalId);
        change.accept(terminal);
        if (!store.update(terminal, expectedVersion, clock.instant())) {
            throw ApiException.staleVersion(expectedVersion, terminal.version());
        }
        return terminal;
    }

    private static ApiException explain(DataIntegrityViolationException violation, String reference) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("uq_fiscal_terminal_reference")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Terminal reference '" + reference + "' is already registered");
        }
        if (message.contains("ck_fiscal_terminal_capable_needs_binding")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A terminal cannot claim " + FiscalTerminal.ISSUE_FISCAL_RECEIPT + " without a provider binding");
        }
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Could not register this terminal");
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    public record RegisterTerminalCommand(
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            String terminalReference,
            Map<String, Boolean> capabilitySnapshot) {}
}
