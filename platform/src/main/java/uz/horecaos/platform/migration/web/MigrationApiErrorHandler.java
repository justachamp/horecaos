package uz.horecaos.platform.migration.web;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.horecaos.platform.migration.application.MigrationConflictException;
import uz.horecaos.platform.migration.application.MigrationPreconditionException;
import uz.horecaos.platform.migration.application.MigrationResourceNotFoundException;
import uz.horecaos.platform.migration.domain.ScopeStateMachine.IllegalTransitionException;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Maps the migration control plane's own failures onto ADR 0031 Problem Details.
 *
 * <p>Everything a controller shares with the rest of the platform — validation,
 * access denial, malformed bodies, unparseable arguments — is handled once in
 * {@code GlobalApiErrorHandler}. Only the failures this module alone can raise
 * belong here, and there are three that matter.
 */
@RestControllerAdvice(
        assignableTypes = {
            MigrationProgramController.class,
            MigrationScopeController.class,
            MigrationOwnershipController.class,
            MigrationRunController.class,
            MigrationQuarantineController.class
        })
public class MigrationApiErrorHandler {

    @ExceptionHandler(MigrationResourceNotFoundException.class)
    ProblemDetail notFound(MigrationResourceNotFoundException exception) {
        return ApiProblem.of(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    /**
     * A stale read, or a claim something else already holds.
     *
     * <p>Reported as {@code STALE_VERSION} when the failure was an
     * optimistic-concurrency one and as {@code RESOURCE_CONFLICT} otherwise,
     * because the two ask the caller for different things. A losing operator
     * re-reads the scope at the version that actually won; an operator whose
     * program name is taken has to choose a different name, and telling them to
     * retry would send them round the same loop forever.
     */
    @ExceptionHandler(MigrationConflictException.class)
    ProblemDetail conflict(MigrationConflictException exception) {
        if (exception.expectedVersion() == null || exception.actualVersion() == null) {
            return ApiProblem.of(ErrorCode.RESOURCE_CONFLICT, exception.getMessage());
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("expectedVersion", exception.expectedVersion());
        properties.put("currentVersion", exception.actualVersion());
        return ApiProblem.withProperties(ErrorCode.STALE_VERSION, exception.getMessage(), properties);
    }

    /**
     * A move the state machine has an edge for, refused because the evidence it
     * rests on is not there.
     *
     * <p>The registered {@code code} stays {@code RESOURCE_CONFLICT}, because
     * clients branch on the code and the ADR 0031 registry is code-owned
     * elsewhere. What distinguishes an open critical reconciliation from an
     * unapproved cutover travels as the stable {@code reason}, and the {@code
     * type} URI is derived from it so a console can link an operator to the page
     * describing the specific gate rather than printing a sentence.
     *
     * <p>Deliberately not {@code STALE_VERSION}: a conflict means read again and
     * retry, and this means do the work first. Putting a retry button in front of
     * an operator whose reconciliation has not run is how the reconciliation ends
     * up being skipped.
     */
    @ExceptionHandler(MigrationPreconditionException.class)
    ProblemDetail precondition(MigrationPreconditionException exception) {
        ProblemDetail problem = ApiProblem.withProperties(
                ErrorCode.RESOURCE_CONFLICT, exception.getMessage(), Map.of("reason", exception.reasonCode()));
        problem.setType(URI.create("https://docs.horecaos.uz/problems/migration/"
                + exception.reasonCode().toLowerCase(Locale.ROOT).replace('_', '-')));
        return problem;
    }

    /**
     * A move the canonical state machine does not have at all.
     *
     * <p>An {@code IllegalStateException} by inheritance, so without this it
     * would surface as a 500 and an operator would be paging somebody about an
     * outage that is really a button the console should not have offered. Both
     * states travel, because the first thing they need to know is which move was
     * refused rather than that one was.
     */
    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException exception) {
        return ApiProblem.withProperties(
                ErrorCode.RESOURCE_CONFLICT,
                exception.getMessage(),
                Map.of(
                        "fromState",
                        exception.from().name(),
                        "toState",
                        exception.to().name()));
    }
}
