package uz.horecaos.platform.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The rule that has to survive the next author (ADR 0029 over ADR 0031).
 *
 * <p>The plaintext-address defect was not a mistake in a controller. Every
 * {@code @Idempotent} endpoint's response is stored for a day, and three of them
 * answered with a value the envelope had just decrypted; the endpoints did not
 * create the hazard, they inherited it. So the fix is not allowed to depend on
 * anyone remembering it either, and this is where that is checked.
 *
 * <p>{@link ResponseBodyProtection} reads the classification off the handler's
 * declared response type, which is exactly the thing an author changes when they
 * make an endpoint answer with an address. What this test holds shut is the
 * seam that reflection cannot see through: a handler answering with a
 * {@code Map} or a bare {@code Object} could carry anything, and would be
 * classified as clean by a scanner that can only read record components. Those
 * have to be listed and reasoned about, one line each, or the build fails.
 *
 * <p>Scans the classpath rather than a running context, so it fails in seconds
 * and without a database.
 */
class IdempotentResponseClassificationTests {

    private static final String BASE_PACKAGE = "uz.horecaos.platform";

    /**
     * Handlers whose response type cannot be read by reflection, each reviewed.
     *
     * <p>Every one of these answers with identifiers, counts or states assembled
     * in the handler itself. None reaches a {@code FieldProtection.reveal}, and
     * none is built from a {@code Revealed*} value. A new entry here is a
     * deliberate act with a name attached to it, which is the point: the
     * alternative is a scanner silently returning "clean" for a map somebody
     * later puts a phone number into.
     */
    private static final Set<String> REVIEWED_UNSCANNABLE = Set.of(
            // Onboarding: a run id, and a count of reopened steps.
            "OnboardingController#start",
            "OnboardingController#resume",
            // Integration failure operations: whether a dead letter changed state.
            "FailureOperationsController#retryOutbox",
            "FailureOperationsController#retryInbox",
            "FailureOperationsController#resolveOutbox",
            "FailureOperationsController#resolveInbox",
            // Provider installation: installation and binding ids and their status.
            // Provider credentials are ADR 0028 references, never values.
            "ProviderInstallationController#install",
            "ProviderInstallationController#bind",
            "ProviderInstallationController#activateBinding",
            "ProviderInstallationController#suspendBinding",
            // Commercial administration: one newly created identifier each.
            "CommercialAdminController#createPlan",
            "CommercialAdminController#draftVersion",
            "CommercialAdminController#startSubscription",
            "CommercialAdminController#override",
            "CommercialAdminController#adjust",
            // A courier invoice id.
            "OperationsCourierController#importInvoice",
            // A grant id, and whether a revocation changed anything.
            "GrantController#grant",
            "GrantController#revoke",
            // Audience export: customer account identifiers and no attribute of
            // them. ADR 0032's rule exactly -- an id travels, the person does not.
            "OperationsMarketingController#export",
            // POS export and sync: a status, a count, and a provider `detail`
            // string. The weakest entries in this list, and named as such: the
            // detail is an adapter's own diagnostic, so what it can contain is a
            // property of the adapter rather than of this type. They carry no
            // customer field today. Turning these four into records would move
            // them out of this list and under the scanner, which is the right
            // fix and a larger one than this change.
            "PosOrderExportController#discover",
            "PosOrderExportController#resolve",
            "PosSyncRunController#reconcileCapabilities",
            "PosSyncRunController#start");

    @Test
    @DisplayName("every idempotent handler's response is either scannable or reviewed")
    void noIdempotentResponseEscapesClassificationUnnoticed() {
        List<String> unreviewed = new ArrayList<>();

        for (Method handler : idempotentHandlers()) {
            if (ResponseBodyProtection.isScannable(handler)) {
                continue;
            }
            String name = nameOf(handler);
            if (!REVIEWED_UNSCANNABLE.contains(name)) {
                unreviewed.add(name + " answers with " + handler.getGenericReturnType());
            }
        }

        assertThat(unreviewed).as("""
                        A response type reflection cannot read is classified as clean, and
                        its body is then stored in plain text for a day. Answer with a
                        record so the classification is decided for you, or add the handler
                        to REVIEWED_UNSCANNABLE with the reason it carries nothing personal.""").isEmpty();
    }

    @Test
    @DisplayName("no reviewed exception outlives the handler it was granted for")
    void theReviewedListHasNoStaleEntries() {
        // An exception list nobody prunes is how a handler that has since started
        // answering with a record -- or stopped existing -- keeps a standing
        // permission nobody re-read. Both directions, or the list only ever grows.
        List<String> unscannable = new ArrayList<>();
        for (Method handler : idempotentHandlers()) {
            if (!ResponseBodyProtection.isScannable(handler)) {
                unscannable.add(nameOf(handler));
            }
        }

        assertThat(REVIEWED_UNSCANNABLE)
                .as("this handler is scannable now, or is gone; drop its reviewed exception")
                .allSatisfy(reviewed -> assertThat(unscannable).contains(reviewed));
    }

    @Test
    @DisplayName("a classified response is only ever answered under a tenant")
    void everyClassifiedResponseHasATenantToEncryptUnder() {
        // Envelope keys are per tenant, and the idempotency table legitimately
        // holds rows with no tenant at all -- V0006 gives platform-scoped
        // operations their own unique index. An endpoint answering with personal
        // data from outside a tenant path would therefore have no key, and the
        // interceptor would fail closed and drop the body. Better to refuse it
        // here, where the answer is a compile-time path, than to discover it as a
        // silently empty replay.
        List<String> keyless = new ArrayList<>();

        for (Method handler : idempotentHandlers()) {
            if (ResponseBodyProtection.classify(handler).isEmpty()) {
                continue;
            }
            if (!pathOf(handler).contains("{tenantId}")) {
                keyless.add(nameOf(handler));
            }
        }

        assertThat(keyless)
                .as("an endpoint answering with personal data must sit under {tenantId}, "
                        + "or there is no per-tenant key to protect its stored response with")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan still finds the endpoints that carried the defect")
    void theScanFindsTheResponsesItIsAbout() {
        List<String> classified = new ArrayList<>();

        for (Method handler : idempotentHandlers()) {
            ResponseBodyProtection.classify(handler).ifPresent(dataClass -> classified.add(nameOf(handler)));
        }

        assertThat(idempotentHandlers())
                .as("a scan that silently found no endpoints would pass forever")
                .hasSizeGreaterThan(100);
        assertThat(classified)
                .as("""
                        These are the three responses built from a decrypt. If this list ever
                        shrinks, either an endpoint stopped returning personal data or the
                        classifier stopped seeing it, and only one of those is good news.""")
                .contains(
                        "StorefrontCustomerController#addAddress",
                        "StorefrontCustomerController#updateAddress",
                        "StorefrontCustomerController#updateProfile");
        assertThat(classified)
                .as("""
                        And the two the scan found that the report did not start with. A QR
                        token is a bearer credential for a table, and a branch's address and
                        contact phone are the fields ADR 0029 classifies wherever they sit --
                        both were being stored in clear for the same reason the address was.""")
                .contains("FloorPlanController#rotate", "TenantControlPlaneController#describeLocation");
    }

    // ------------------------------------------------------- the classifier itself

    @Test
    @DisplayName("the classifier reads through the containers a handler returns")
    void theClassifierUnwrapsResponseEntityAndList() {
        assertThat(ResponseBodyProtection.responseTypeOf(signature("wrapped").getGenericReturnType()))
                .as("a check stopping at ResponseEntity would find nothing classified anywhere " + "and pass forever")
                .isEqualTo(SampleAddress.class);
        assertThat(ResponseBodyProtection.responseTypeOf(signature("listed").getGenericReturnType()))
                .as("an address book is a list of addresses")
                .isEqualTo(SampleAddress.class);
    }

    @Test
    @DisplayName("the classifier actually fires, and does not fire on a clean response")
    void theClassifierCanFailAndCanPass() {
        assertThat(ResponseBodyProtection.classify(signature("wrapped")))
                .as("a name-based check that never fires would be worse than no check")
                .contains(DataClass.PERSONAL);
        assertThat(ResponseBodyProtection.classify(signature("clean")))
                .as("an order id and a status are not personal data, and encrypting every "
                        + "body on the platform would leave the tenant-less ones with no key")
                .isEmpty();
    }

    @Test
    @DisplayName("the strongest class reachable from a response is the one that wins")
    void theStrongestClassificationWins() {
        assertThat(ResponseBodyProtection.classify(signature("mixed")))
                .as("filing a passport number under the ordinary-personal key would put it "
                        + "below its classification, and the key is per class so it need not be")
                .contains(DataClass.PERSONAL_SENSITIVE);
    }

    @Test
    @DisplayName("a body-less response is scannable and carries nothing")
    void aVoidResponseIsUnderstood() {
        assertThat(ResponseBodyProtection.isScannable(signature("nothing"))).isTrue();
        assertThat(ResponseBodyProtection.classify(signature("nothing"))).isEmpty();
    }

    @Test
    @DisplayName("a map response is refused by the scanner rather than assumed clean")
    void aMapResponseIsNotScannable() {
        assertThat(ResponseBodyProtection.isScannable(signature("opaque")))
                .as("nothing in Map<String, Object> says what a handler will put in it, so "
                        + "this must reach the reviewed list rather than pass as clean")
                .isFalse();
        assertThat(ResponseBodyProtection.classify(signature("opaque"))).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    // "unused": every method here exists only to be reflected on by signature()
    // below (ResponseBodyProtection.classify/isScannable read the generic return
    // type, never the value), so none is ever called.
    // "NullAway": for the same reason, "return null" here is a stub body that is
    // provably never executed rather than a real nullable contract; the six
    // ResponseEntity<...> return types stay honestly non-null for reflection.
    @SuppressWarnings({"unused", "NullAway"})
    private static final class Samples {

        ResponseEntity<SampleAddress> wrapped() {
            return null;
        }

        ResponseEntity<List<SampleAddress>> listed() {
            return null;
        }

        ResponseEntity<SampleOrder> clean() {
            return null;
        }

        ResponseEntity<SampleMixed> mixed() {
            return null;
        }

        ResponseEntity<Void> nothing() {
            return null;
        }

        ResponseEntity<java.util.Map<String, Object>> opaque() {
            return null;
        }
    }

    private record SampleAddress(UUID addressId, String line1) {}

    private record SampleOrder(UUID orderId, String status, long totalMinor) {}

    private record SampleMixed(
            String phone,

            @Classified(value = DataClass.PERSONAL_SENSITIVE, reason = "an identity document")
            String documentNumber) {}

    private static Method signature(String name) {
        for (Method method : Samples.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException("No sample named " + name);
    }

    // ------------------------------------------------------------------ the scan

    /**
     * The same set the interceptor acts on: {@code @Idempotent}, plus
     * {@code @RequiresCapability(mutating = true)} which still implies it.
     */
    private static List<Method> idempotentHandlers() {
        List<Method> handlers = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (triggersIdempotency(method)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private static boolean triggersIdempotency(Method handler) {
        if (handler.getAnnotation(Idempotent.class) != null) {
            return true;
        }
        RequiresCapability declaration = handler.getAnnotation(RequiresCapability.class);
        return declaration != null && declaration.mutating();
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                controllers.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException unreachable) {
                throw new IllegalStateException(unreachable);
            }
        }
        return controllers;
    }

    private static String nameOf(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    private static String pathOf(Method handler) {
        RequestMapping onClass = handler.getDeclaringClass().getAnnotation(RequestMapping.class);
        String base = onClass == null || onClass.value().length == 0 ? "" : onClass.value()[0];
        return base + methodPath(handler).orElse("");
    }

    private static Optional<String> methodPath(Method handler) {
        PostMapping post = handler.getAnnotation(PostMapping.class);
        if (post != null) {
            return first(post.value());
        }
        PutMapping put = handler.getAnnotation(PutMapping.class);
        if (put != null) {
            return first(put.value());
        }
        PatchMapping patch = handler.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return first(patch.value());
        }
        DeleteMapping delete = handler.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            return first(delete.value());
        }
        GetMapping get = handler.getAnnotation(GetMapping.class);
        if (get != null) {
            return first(get.value());
        }
        RequestMapping mapping = handler.getAnnotation(RequestMapping.class);
        return mapping == null ? Optional.empty() : first(mapping.value());
    }

    private static Optional<String> first(String[] values) {
        return values.length == 0 ? Optional.empty() : Optional.of(values[0]);
    }
}
