package uz.horecaos.platform.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.web.OperationsOrderController;
import uz.horecaos.platform.partner.api.PartnerBound;
import uz.horecaos.platform.web.idempotency.Idempotent;
import uz.horecaos.platform.web.idempotency.NaturallyIdempotent;

/**
 * ADR 0031, ADR 0025 and ADR 0049: every mutating endpoint declares exactly one
 * authorization strategy, and every effectful one is replay-safe.
 *
 * <p>Scans the classpath rather than a running context, so it fails fast and
 * without infrastructure. A new endpoint that forgets either declaration breaks
 * the build rather than shipping with no authorization decision attached.
 *
 * <p>Staff use {@link RequiresCapability}; customers use {@link CustomerOwned};
 * partner clients use {@link PartnerBound}; and couriers use
 * {@link CourierSelfAuthorized}. What the test refuses is silence and ambiguity:
 * an endpoint declaring none has made no authorization decision, while one
 * declaring two will be refused by whichever interceptor runs first.
 */
class EndpointCapabilityDeclarationTests {

    private static final String BASE_PACKAGE = "uz.horecaos.platform";

    @Test
    void aDeclaredScopeIsNoWiderThanTheEndpointsPath() {
        // ADR 0025 scopes cover downwards and never up: a tenant-wide grant
        // reaches every brand, but a brand-scoped grant does not satisfy a
        // tenant-scoped requirement. So an endpoint under /brands/{brandId} that
        // requires TENANT — the annotation's default — silently locks out every
        // brand manager, and the only symptom is a 403 nobody can explain.
        List<String> tooWide = new ArrayList<>();

        for (Method handler : allHandlers()) {
            RequiresCapability declaration = handler.getAnnotation(RequiresCapability.class);
            if (declaration == null) {
                continue;
            }
            String path = pathOf(handler);
            String where = handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();

            if (path.contains("{locationId}") && declaration.scope() == ScopeType.TENANT) {
                tooWide.add(where + " names a location but requires TENANT");
            } else if (path.contains("{brandId}") && declaration.scope() == ScopeType.TENANT) {
                tooWide.add(where + " names a brand but requires TENANT");
            }
        }

        assertThat(tooWide)
                .as("declare the narrowest scope the path supports, or brand and location "
                        + "managers are refused by a grant that should have been enough")
                .isEmpty();
    }

    @Test
    void everyMutatingEndpointDeclaresHowItIsAuthorized() {
        List<String> undeclared = new ArrayList<>();

        for (Method handler : mutatingHandlers()) {
            if (isProviderCallback(handler)
                    || isGuestBearerEndpoint(handler)
                    || isPreAccountIdentityEndpoint(handler)) {
                continue;
            }
            if (authorizationDeclarationCount(handler) == 0) {
                undeclared.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName());
            }
        }

        assertThat(undeclared).as("""
                        A mutating endpoint must declare one of @RequiresCapability,
                        @CustomerOwned, @PartnerBound, or @CourierSelfAuthorized. None
                        ships with no authorization decision (ADR 0025, ADR 0049).""").isEmpty();
    }

    @Test
    void anEndpointDeclaresExactlyOneAuthorizationStrategy() {
        // More than one is not "belt and braces", it is the bug this model replaced. The
        // capability interceptor runs before the handler, so a storefront endpoint
        // or non-staff endpoint carrying a staff capability answers 403 to the
        // caller it was written for and never reaches the relationship check.
        List<String> ambiguous = new ArrayList<>();

        for (Method handler : allControllerMethods()) {
            if (authorizationDeclarationCount(handler) > 1) {
                ambiguous.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName());
            }
        }

        assertThat(ambiguous)
                .as("each endpoint has one principal model; combining strategies makes "
                        + "interceptor order decide which legitimate caller is refused")
                .isEmpty();
    }

    @Test
    void everyResourceCreatingEndpointDeclaresReplayProtection() {
        // Two ways to say it, because idempotency is not a property of the
        // authorization decision. It used to be readable only off
        // @RequiresCapability(mutating = true), so dropping a capability from a
        // storefront handler would have dropped its replay protection silently —
        // on checkout and payment-session creation, where a second run is a second
        // charge.
        List<String> notIdempotent = new ArrayList<>();

        for (Method handler : mutatingHandlers()) {
            if (isProviderCallback(handler)
                    || isGuestBearerEndpoint(handler)
                    || isPreAccountIdentityEndpoint(handler)) {
                continue;
            }
            if (!declaresReplayProtection(handler)) {
                notIdempotent.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName());
            }
        }

        assertThat(notIdempotent).as("""
                        POST, PUT, PATCH, and DELETE cause an effect, so ADR 0031 requires
                        replay protection. Mark the staff capability mutating = true, add
                        @Idempotent, or document a database natural key with
                        @NaturallyIdempotent.""").isEmpty();
    }

    @Test
    void theOperatorOrderSurfaceStillRequiresACapability() {
        // The storefront's move to ownership must not drift across to the surface
        // an agent uses. An operator acting on somebody else's order is precisely
        // the delegated authority a capability exists to express, and an ownership
        // check there would authorise nobody: the agent does not own the order.
        List<String> undeclared = new ArrayList<>();
        List<String> declared = new ArrayList<>();

        for (Method handler : OperationsOrderController.class.getDeclaredMethods()) {
            if (!isHandler(handler)) {
                continue;
            }
            if (handler.getAnnotation(RequiresCapability.class) == null) {
                undeclared.add(handler.getName());
            } else {
                declared.add(handler.getName());
            }
        }

        assertThat(declared)
                .as("a scan finding nothing here would pass however the surface was weakened")
                .isNotEmpty();
        assertThat(undeclared)
                .as("every operator order endpoint keeps its ADR 0025 declaration")
                .isEmpty();
    }

    private static boolean declaresReplayProtection(Method handler) {
        if (handler.getAnnotation(Idempotent.class) != null
                || handler.getAnnotation(NaturallyIdempotent.class) != null) {
            return true;
        }
        RequiresCapability declaration = handler.getAnnotation(RequiresCapability.class);
        return declaration != null && declaration.mutating();
    }

    @Test
    void naturalIdempotencyIsUsedOnlyWithPartnerBindingAuthorization() {
        List<String> misplaced = new ArrayList<>();

        for (Method handler : allControllerMethods()) {
            if (handler.getAnnotation(NaturallyIdempotent.class) != null
                    && handler.getAnnotation(PartnerBound.class) == null) {
                misplaced.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName());
            }
        }

        assertThat(misplaced)
                .as("a natural key exemption needs the partner binding that participates in "
                        + "the durable uniqueness constraint")
                .isEmpty();
    }

    private static int authorizationDeclarationCount(Method handler) {
        int count = 0;
        count += handler.getAnnotation(RequiresCapability.class) == null ? 0 : 1;
        count += handler.getAnnotation(CustomerOwned.class) == null ? 0 : 1;
        count += handler.getAnnotation(PartnerBound.class) == null ? 0 : 1;
        count += handler.getAnnotation(CourierSelfAuthorized.class) == null ? 0 : 1;
        return count;
    }

    @Test
    void theScanFindsTheEndpointsItClaimsToCheck() {
        assertThat(mutatingHandlers())
                .as("a scan that silently finds nothing would pass forever")
                .isNotEmpty();
    }

    /**
     * ADR 0047: the dine-in QR endpoints, where there is no principal to hold a
     * capability.
     *
     * <p>The caller is somebody who pointed a camera at a table. Requiring an
     * ADR 0025 grant would mean requiring an account before a guest can read a
     * menu, and a capability annotation whose scope resolved from no path
     * variables would be a declaration that decides nothing. Authorization on
     * these is the bearer token itself: each resolves it to a row before touching
     * anything, and neither accepts a table id or a tenant.
     *
     * <p>Deliberately narrow, and matched on the exact paths rather than on a
     * prefix. Everything else under {@code /api/v1/storefront} has a customer
     * principal and declares either a capability or {@link CustomerOwned}, and a
     * prefix match here would quietly exempt the next one somebody adds.
     */
    private static boolean isGuestBearerEndpoint(Method handler) {
        String path = pathOf(handler);
        return path.equals("/api/v1/storefront/dine-in/qr/token-exchanges")
                || path.equals("/api/v1/storefront/dine-in/sessions/{sessionId}/bill-requests");
    }

    /**
     * ADR 0015 and ADR 0051: the three endpoints that exist so that somebody with
     * no account can get one.
     *
     * <p>The caller has no token, and cannot: asking for a one-time code is what
     * happens <em>before</em> there is a principal, so requiring one would mean
     * requiring an account in order to create an account. An ADR 0025 capability
     * would be a declaration that decides nothing for the same reason, and
     * {@code @CustomerOwned} would be a lie — there is no row of theirs to own yet.
     *
     * <p>The third, {@code POST /sessions}, is the one ADR 0051 added, and it is
     * exempt for exactly the same reason rather than a weaker one. It is the step
     * that <em>produces</em> the credential every other customer endpoint requires,
     * so a caller who could satisfy an authorization declaration here would already
     * have what this endpoint exists to give them. Note what it is not: it is not
     * exempt because it is convenient, and the endpoint beside it that does have a
     * principal — {@code POST /registrations} — still declares
     * {@code @CustomerOwned} and {@code @Idempotent}.
     *
     * <p>What authorises all three instead is possession: of the handset the code
     * is sent to, of the unguessable challenge id it was sent against, and of the
     * single-use grant that proves both. Each is rate-limited per caller through
     * ADR 0033 and per destination in PostgreSQL, and the second spends a bounded
     * number of attempts against a row that dies when they run out.
     *
     * <p>None is exempted from idempotency by oversight. All three are outside the
     * resource server's principal model, and {@code IdempotencyInterceptor} scopes
     * a key by the calling subject — of which there is none, so the interceptor
     * would fail before the handler ran. Replay protection here is in the rows: a
     * second request for the same number supersedes the first rather than sending
     * twice, a code can be spent exactly once, and a grant is redeemed by a
     * conditional {@code UPDATE} that exactly one caller wins. Sign-out is
     * deliberately <em>not</em> in this set — its caller does hold a session — and
     * it declares both.
     *
     * <p>Matched on exact paths rather than on a prefix, so the next endpoint added
     * under {@code /identity} is not quietly exempted along with these three.
     */
    private static boolean isPreAccountIdentityEndpoint(Method handler) {
        String path = pathOf(handler);
        String base = "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/identity";
        return path.equals(base + "/verification-challenges")
                || path.equals(base + "/verification-challenges/{challengeId}/attempts")
                || path.equals(base + "/sessions");
    }

    /**
     * Whether this endpoint is one a payment provider calls (ADR 0013).
     *
     * <p>There is no actor to hold a capability. Click's SHOP API sends no
     * credential at all — an MD5 over a secret-prefixed concatenation is the whole
     * of its authentication — and Payme sends a Basic credential that belongs to a
     * cashbox rather than to a person. Both are verified inside the endpoint,
     * against the binding named in the path, before anything is dispatched.
     * Requiring a capability here would mean inventing an actor for a machine, and
     * ADR 0013 records the exemption for these paths rather than leaving it to be
     * found later as a violation.
     *
     * <p>The exemption has a second half that this test cannot see, and that
     * {@code ProviderCallbackReachabilityTests} exists to hold: {@code /providers}
     * is outside {@code /api}, which is the only pattern the enforcing
     * interceptor is registered on. Declaring nothing would not be enough if the
     * paths ever moved under {@code /api}.
     */
    private static boolean isProviderCallback(Method handler) {
        RequestMapping mapping = handler.getDeclaringClass().getAnnotation(RequestMapping.class);
        return mapping != null && mapping.value().length > 0 && mapping.value()[0].startsWith("/providers/");
    }

    /** Every handler on every controller that declares a capability. */
    private static List<Method> allHandlers() {
        List<Method> handlers = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RequiresCapability.class)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    /** Every request-mapped method on every controller, however it is authorized. */
    private static List<Method> allControllerMethods() {
        List<Method> handlers = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isHandler(method)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private static boolean isHandler(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }

    /** The full request path: the controller's class-level mapping plus the method's. */
    private static String pathOf(Method handler) {
        RequestMapping classMapping = handler.getDeclaringClass().getAnnotation(RequestMapping.class);
        String base = classMapping != null && classMapping.value().length > 0
                ? classMapping.value()[0]
                : "";
        return base + methodPath(handler);
    }

    private static String methodPath(Method handler) {
        if (handler.isAnnotationPresent(GetMapping.class)) {
            return first(handler.getAnnotation(GetMapping.class).value());
        }
        if (handler.isAnnotationPresent(PostMapping.class)) {
            return first(handler.getAnnotation(PostMapping.class).value());
        }
        if (handler.isAnnotationPresent(PutMapping.class)) {
            return first(handler.getAnnotation(PutMapping.class).value());
        }
        if (handler.isAnnotationPresent(PatchMapping.class)) {
            return first(handler.getAnnotation(PatchMapping.class).value());
        }
        if (handler.isAnnotationPresent(DeleteMapping.class)) {
            return first(handler.getAnnotation(DeleteMapping.class).value());
        }
        RequestMapping mapping = handler.getAnnotation(RequestMapping.class);
        return mapping == null ? "" : first(mapping.value());
    }

    private static String first(String[] values) {
        return values.length > 0 ? values[0] : "";
    }

    private static List<Class<?>> controllers() {
        List<Class<?>> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException unreachable) {
                throw new IllegalStateException(unreachable);
            }
        }
        return found;
    }

    private static List<Method> mutatingHandlers() {
        List<Method> handlers = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException unreachable) {
                throw new IllegalStateException(unreachable);
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (isMutating(method)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private static boolean isMutating(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)) {
            return true;
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        Set<RequestMethod> mutating =
                Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
        for (RequestMethod requestMethod : mapping.method()) {
            if (mutating.contains(requestMethod)) {
                return true;
            }
        }
        return false;
    }
}
