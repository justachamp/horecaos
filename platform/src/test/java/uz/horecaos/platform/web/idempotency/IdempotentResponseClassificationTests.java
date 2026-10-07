package uz.horecaos.platform.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
            // Onboarding: a run id, a count of reopened steps, and cancel's own
            // fixed status literal -- none of the three is a fact about a person.
            "OnboardingController#start",
            "OnboardingController#resume",
            "OnboardingController#cancel",
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
            // Wave 53: the same four operations, unmodified, reachable at the
            // operations-prefixed mirror of the path above. Every method here
            // forwards to ProviderInstallationController's own handler, so the
            // response carries exactly what its reviewed entry already says --
            // this is one call site, not a new hazard.
            "OperationsProviderInstallationController#install",
            "OperationsProviderInstallationController#bind",
            "OperationsProviderInstallationController#activateBinding",
            "OperationsProviderInstallationController#suspendBinding",
            // Commercial administration: one newly created identifier each.
            "CommercialAdminController#createPlan",
            "CommercialAdminController#draftVersion",
            "CommercialAdminController#startSubscription",
            "CommercialAdminController#override",
            "CommercialAdminController#adjust",
            // A courier invoice id.
            "OperationsCourierController#importInvoice",
            // A rate card id (IA 3.4) -- nothing about a person.
            "OperationsCourierController#authorRateCard",
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
            "PosSyncRunController#start",
            // Same reasoning, wave 94's three additions: a run id, a difference
            // id, and per-status item counts, all assembled from identifiers and
            // enum names the handler already holds. review-decisions' note field
            // is free text an operator wrote about a menu item, never echoed back
            // in the response -- the request carries it, the response does not.
            "PosSyncRunController#recordReviewDecision",
            "PosSyncRunController#applyRun",
            "PosSyncRunController#resumeRun",
            // ADR 0058: the short-lived /link handshake code itself. Not
            // customer data — this is what ADR 0029 classifies, and there is no
            // data subject here — but it is a single-use, fifteen-minute
            // credential, so replaying it from the idempotent-response cache is
            // read as intentional (the same client retrying the same request
            // needs to see the same code) rather than as a gap: the code
            // expires and is spent on first use regardless of how many times
            // this response is replayed.
            "TelegramLinkCodeController#issue",
            // ADR 0106, gap-map row 10.8c: whether a merchant's own stuck inbox
            // message was replayed — same "changed"/"outcome" shape as
            // GrantController#revoke above, never the message's own payload.
            "OperationsIntegrationFailureController#replay",
            // ADR 0106, gap-map row 10.8d: whether a partner API client was
            // revoked. The client's secret never reaches this response —
            // revoke only ever answers changed/outcome, matching issue's and
            // rotate's own records for the secret-bearing calls beside it.
            "PartnerApiClientController#revoke",
            // ADR 0116, wave S01: whether a staff invitation was revoked — the
            // same "changed" shape as GrantController#revoke above (here with
            // no "outcome" at all, since revoke has exactly one outcome). No
            // invitee name, phone, email or token reaches this response; those
            // stay behind the audited reveal StaffInvitationService itself
            // gates.
            "StaffInvitationController#revoke");

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
            if (handler.getAnnotation(OneTimeResponse.class) != null) {
                // Kept nowhere at all, so there is nothing to encrypt; reviewed one by one below.
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

    /**
     * The handlers allowed to answer with a secret shown once and keep no copy (ADR 0070).
     *
     * <p>{@link OneTimeResponse} exempts a classified response from the rule above, so it is a way
     * round that rule and is held to a list a person edits: each is a platform-scoped call that
     * mints a credential, and neither can be encrypted under a tenant because it has none.
     */
    private static final Set<String> REVIEWED_ONE_TIME = Set.of(
            "StorefrontAppRegistryController#registerStorefrontApp",
            "StorefrontAppRegistryController#rotateStorefrontAppSecret");

    @Test
    @DisplayName("a response kept nowhere is a reviewed exception, one handler at a time")
    void oneTimeResponsesAreReviewedOneByOne() {
        Set<String> declared = new LinkedHashSet<>();
        for (Method handler : idempotentHandlers()) {
            if (handler.getAnnotation(OneTimeResponse.class) != null) {
                declared.add(nameOf(handler));
                assertThat(ResponseBodyProtection.classify(handler))
                        .as("%s is marked one-time but its response carries nothing classified", nameOf(handler))
                        .isPresent();
            }
        }

        assertThat(declared).as("""
                        OneTimeResponse keeps a classified response from being stored at all, which
                        exempts it from being encrypted under a tenant. It is a way round the rule
                        above and so is a reviewed list: add the handler here, with the reason it
                        mints a credential, or take the annotation off.""").isEqualTo(REVIEWED_ONE_TIME);
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
        assertThat(classified).as("""
                        And the one a staff member's name reached: the lateness editor names who
                        approved each rung of its ladder, from StaffDirectory, in the reply to the
                        POST that publishes a version. The name heuristic does not know the word,
                        so only the declaration on LevelResponse.approvedByName keeps it encrypted.""").contains("OrderLatenessPolicyEditorController#authorLatenessPolicy");
    }

    // ------------------------------------------------- a staff member's name (ADR 0029, ADR 0139)

    /**
     * A record component that names the person who did something -- {@code approvedByName},
     * {@code changedByName}, {@code createdByDisplayName}, {@code operatorName}. The name heuristic
     * of {@link uz.horecaos.platform.iam.api.protection.ClassificationScanner} is a list of fields
     * about <em>customers</em> ({@code phone}, {@code firstName}, ...) and does not contain the bare
     * word {@code name}, because a product, a table and a brand all have one. A staff member's name
     * reaches a response under a role-and-name component the list never anticipated, which is how
     * {@code approvedByName} was stored in clear for a day. It is found by the shape of its name
     * and then <em>declared</em>, because a declaration is what survives the next rename.
     */
    private static final java.util.regex.Pattern PERSON_BEHIND_AN_ACTION = java.util.regex.Pattern.compile(
            "(?i)(by|approver|operator|actor|author|assignee|staff|employee|member)(display)?name$");

    @Test
    @DisplayName(
            "a component named for the person behind an action is classified wherever an idempotent reply holds it")
    void aPersonBehindAnActionIsDeclaredNotGuessed() {
        List<String> undeclared = new ArrayList<>();

        for (Method handler : idempotentHandlers()) {
            Type scanType = ResponseBodyProtection.scanTypeOf(handler.getGenericReturnType());
            if (scanType == null) {
                continue;
            }
            Set<String> found = new LinkedHashSet<>();
            undeclaredPersonComponents(scanType, found, new HashSet<>());
            found.forEach(component -> undeclared.add(nameOf(handler) + " -> " + component));
        }

        assertThat(undeclared).as("""
                        A response component named like approvedByName holds a person's name whoever
                        fills it. Annotate it @Classified(DataClass.PERSONAL, reason = ...) -- the
                        name heuristic does not know the word, and the reply is stored in clear
                        until it is declared.""").isEmpty();
    }

    @Test
    @DisplayName("the staff-name checks can fail: an undeclared approver name is found, a declared one is not")
    void theStaffNameChecksCanFailAndCanPass() {
        Set<String> found = new LinkedHashSet<>();
        undeclaredPersonComponents(SampleApproval.class, found, new HashSet<>());
        assertThat(found).containsExactly("approvedByName(SampleApproval)");

        Set<String> declared = new LinkedHashSet<>();
        undeclaredPersonComponents(SampleDeclaredApproval.class, declared, new HashSet<>());
        assertThat(declared).isEmpty();
    }

    /** Record components reachable from {@code type} that name a person behind an action and are not declared. */
    private static void undeclaredPersonComponents(Type type, Set<String> found, Set<Type> ancestors) {
        Class<?> raw = type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType()
                : type instanceof Class<?> candidate ? candidate : null;
        if (raw == null) {
            return;
        }
        if (raw.isArray()) {
            undeclaredPersonComponents(raw.getComponentType(), found, ancestors);
            return;
        }
        if (type instanceof ParameterizedType parameterized && !raw.isRecord()) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                undeclaredPersonComponents(argument, found, ancestors);
            }
            return;
        }
        if (!raw.isRecord() || !ancestors.add(type)) {
            return;
        }
        for (RecordComponent component : raw.getRecordComponents()) {
            Classified declared = component.getAnnotation(Classified.class);
            if (declared == null
                    && PERSON_BEHIND_AN_ACTION.matcher(component.getName()).find()) {
                found.add(component.getName() + "(" + raw.getSimpleName() + ")");
            }
            if (declared == null) {
                undeclaredPersonComponents(component.getGenericType(), found, ancestors);
            }
        }
        ancestors.remove(type);
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
    @DisplayName("a list inside a record is read: a response holding a hundred addresses is not cleaner than one")
    void theClassifierReadsThroughAListInsideARecord() {
        assertThat(ResponseBodyProtection.classify(signature("batch"))).as("""
                        Every list the scan met inside a record used to read as clean, because
                        the component's erased class is List and not a record. A response
                        assembled from rows is exactly what an operator screen is made of.""").contains(DataClass.PERSONAL);
        assertThat(ResponseBodyProtection.classify(signature("tally")))
                .as("a list of counts per status is still not personal data")
                .isEmpty();
    }

    @Test
    @DisplayName("a generic page is classified by what it holds, not by being a page")
    void aGenericPageIsReadWithItsArgument() {
        assertThat(ResponseBodyProtection.classify(signature("pagedAddresses")))
                .as("Page<SampleAddress> is a page of addresses; read as the bare Page it is clean")
                .contains(DataClass.PERSONAL);
        assertThat(ResponseBodyProtection.classify(signature("pagedOrders")))
                .as("and a page of orders stays unclassified, or every list endpoint would need a key")
                .isEmpty();
    }

    @Test
    @DisplayName("the three fields the stronger scan wrongly flagged are declared, and their responses stay clean")
    void aVersionNumberIsNotATaxNumber() {
        // «resulTINg» holds "tin", and "commentPresetCodes" / "hasCustomerNote" hold "comment" and
        // "note": the name heuristic reads each as a person's data, and the stronger scan now sees them
        // inside the lists that carry them. Left alone, every cart edit and every bulk order action
        // would have stored its idempotent reply encrypted and paid a key lookup and a decrypt on
        // each replay, for a number, a code and a flag.
        List<String> misclassified = new ArrayList<>();
        int inspected = 0;
        for (Method handler : idempotentHandlers()) {
            String name = nameOf(handler);
            boolean aCartAnswer = name.startsWith("StorefrontOrderingController#")
                    && String.valueOf(handler.getGenericReturnType()).contains("CartResponse");
            if (aCartAnswer || name.equals("OperationsOrderController#bulkAction")) {
                inspected++;
                if (ResponseBodyProtection.classify(handler).isPresent()) {
                    misclassified.add(name);
                }
            }
        }

        assertThat(inspected)
                .as("the cart answers and the bulk action: if this reads zero the test is no longer looking")
                .isGreaterThanOrEqualTo(10);
        assertThat(misclassified).isEmpty();
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

        ResponseEntity<SampleBatch> batch() {
            return null;
        }

        ResponseEntity<SampleTally> tally() {
            return null;
        }

        ResponseEntity<SamplePage<SampleAddress>> pagedAddresses() {
            return null;
        }

        ResponseEntity<SamplePage<SampleOrder>> pagedOrders() {
            return null;
        }
    }

    private record SampleAddress(UUID addressId, String line1) {}

    private record SampleOrder(UUID orderId, String status, long totalMinor) {}

    private record SampleMixed(
            String phone,

            @Classified(value = DataClass.PERSONAL_SENSITIVE, reason = "an identity document")
            String documentNumber) {}

    private record SampleBatch(UUID batchId, List<SampleAddress> entries) {}

    private record SampleTally(UUID batchId, List<SampleOrder> orders) {}

    private record SamplePage<T>(List<T> items, @Nullable String cursor) {}

    private record SampleApproval(UUID approvalId, String approvedByName) {}

    private record SampleDeclaredApproval(
            UUID approvalId,

            @Classified(value = DataClass.PERSONAL, reason = "a staff member's name")
            String approvedByName) {}

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
