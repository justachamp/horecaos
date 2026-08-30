package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import uz.horecaos.platform.iam.api.organizations.OrganizationDirectory;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;

/**
 * ADR 0009 against a real Keycloak.
 *
 * <p>Not a mock, and deliberately so: the whole risk this adapter carries is
 * that Keycloak's Admin API behaves differently from what the code assumes, and
 * a stub written from the same assumption proves nothing. It runs against the
 * realm in {@code infra/keycloak/realm}, using the two ADR 0009 service accounts
 * with their real credentials, and it exercises the wiring in
 * {@link KeycloakConfiguration} rather than a copy of it.
 *
 * <p>It skips — loudly, naming the reason — when Keycloak is absent or when its
 * realm does not grant the service accounts the roles ADR 0009 specifies. As of
 * writing, the checked-in realm export declares both clients and maps no
 * {@code realm-management} roles onto either service account, so every Admin API
 * call returns 403 and this class skips. That is a finding about the realm, not
 * a reason to weaken the test: the moment the export carries the role mappings
 * the ADR already records as verified, every assertion below runs for real.
 */
class KeycloakOrganizationIntegrationTests {

    private static final String BASE_URL =
            System.getenv().getOrDefault("HORECAOS_KEYCLOAK_BASE_URL", "http://localhost:8081");
    private static final String REALM =
            System.getenv().getOrDefault("HORECAOS_KEYCLOAK_REALM", "horecaos");

    /**
     * The realm import's development placeholders. Overridable, because a
     * deployment rotates them immediately after import and this test has to be
     * runnable against such a realm.
     */
    private static final String PROVISIONING_SECRET = System.getenv().getOrDefault(
            "HORECAOS_KEYCLOAK_PROVISIONING_SECRET", "development-only-not-a-secret-provisioning");
    private static final String READER_SECRET = System.getenv().getOrDefault(
            "HORECAOS_KEYCLOAK_READER_SECRET", "development-only-not-a-secret-reader");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() { };

    private static RestClient admin;

    private final List<String> organizationsToRemove = new ArrayList<>();
    private final List<String> usersToRemove = new ArrayList<>();

    private OrganizationProvisioner provisioner;
    private OrganizationDirectory directory;
    private String alias;

    @BeforeAll
    static void requireAUsableRealm() {
        RestClient probe;
        try {
            probe = clientFor("horecaos-provisioning", PROVISIONING_SECRET);
        } catch (RuntimeException unreachable) {
            Assumptions.abort(
                    "Keycloak at " + BASE_URL + " is not answering, or the service-account "
                            + "credentials do not match the realm: " + unreachable.getMessage());
            return;
        }

        // Authenticating is not the same as being able to do anything, and the
        // difference is exactly the current state of the checked-in realm.
        try {
            probe.get().uri("/admin/realms/{realm}/organizations", REALM).retrieve().body(LIST);
        } catch (RuntimeException forbidden) {
            Assumptions.abort(
                    "The " + REALM + " realm grants horecaos-provisioning no realm-management roles, so "
                            + "the ADR 0009 Admin API calls are all forbidden: " + forbidden.getMessage());
        }
        admin = probe;
    }

    @BeforeEach
    void buildAdapters() {
        Clock clock = Clock.systemUTC();
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of(
                        "horecaos.secrets.identity_admin.keycloak.provisioning-secret", PROVISIONING_SECRET,
                        "horecaos.secrets.identity_admin.keycloak.reader-secret", READER_SECRET)::get,
                clock);

        // The production wiring, not a rebuild of it. The ADR 0009 note about
        // OnboardingServiceTests is the reason: a test that assembles its own
        // graph proves less than it appears to.
        KeycloakConfiguration configuration = new KeycloakConfiguration();
        provisioner = configuration.organizationProvisioner(
                secrets, clock, BASE_URL, REALM, "horecaos-provisioning", "local");
        directory = configuration.organizationDirectory(
                secrets, clock, BASE_URL, REALM, "horecaos-identity-reader", "local");

        alias = "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void removeWhatThisTestCreated() {
        if (admin == null) {
            return;
        }
        // The test cleans up after itself. ADR 0009 forbids the *platform* from
        // deleting Keycloak objects; it says nothing about a test's own fixtures,
        // and leaving them would make the next run's alias search ambiguous.
        //
        // The email sweep is not belt-and-braces. A test that fails *inside*
        // ensureMembership never gets to record the subject it created, so
        // relying on the recorded list alone leaves a user behind on exactly the
        // runs that matter — which is how the first run of this class left nine.
        usersToRemove.forEach(id -> delete("/admin/realms/{realm}/users/" + id));
        usersWithEmail(alias + "@example.test")
                .forEach(id -> delete("/admin/realms/{realm}/users/" + id));
        organizationsToRemove.forEach(id -> delete("/admin/realms/{realm}/organizations/" + id));
        usersToRemove.clear();
        organizationsToRemove.clear();
    }

    @Test
    void ensuringTheSameOrganizationTwiceProducesOneExternalObject() {
        UUID tenantId = UUID.randomUUID();

        var first = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(tenantId, alias, "Acme", null));
        organizationsToRemove.add(first.organizationId());

        var second = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(
                        tenantId, alias, "Acme", first.organizationId()));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.organizationId()).isEqualTo(first.organizationId());
        assertThat(organizationsNamed(alias))
                .as("a retried onboarding step must never produce a second identity for one tenant")
                .hasSize(1);
    }

    @Test
    void anUncertainCreateIsRecoveredByReadbackRatherThanByCreatingAgain() {
        UUID tenantId = UUID.randomUUID();

        // The state a timed-out create leaves behind: the object exists, and
        // HorecaOS never learned its id.
        String orphanId = createOrganizationDirectly(alias);
        organizationsToRemove.add(orphanId);

        var reconciled = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(tenantId, alias, "Acme", null));

        assertThat(reconciled.created()).isFalse();
        assertThat(reconciled.organizationId()).isEqualTo(orphanId);
        assertThat(organizationsNamed(alias)).hasSize(1);
    }

    @Test
    void aStoredIdThatNoLongerResolvesStopsRatherThanCreatingAReplacement() {
        UUID tenantId = UUID.randomUUID();
        String vanished = UUID.randomUUID().toString();

        assertThatThrownBy(() -> provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(tenantId, alias, "Acme", vanished)))
                .isInstanceOf(OrganizationProvisioner.OrganizationDriftException.class);

        assertThat(organizationsNamed(alias))
                .as("a replacement organization would orphan every membership on the first")
                .isEmpty();
    }

    @Test
    void linkingTheSameOwnerTwicePreservesOneMembership() {
        UUID tenantId = UUID.randomUUID();
        var organization = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(tenantId, alias, "Acme", null));
        organizationsToRemove.add(organization.organizationId());
        String email = alias + "@example.test";

        var first = provisioner.ensureMembership(new OrganizationProvisioner.EnsureMembership(
                organization.organizationId(), email, null));
        usersToRemove.add(first.subjectId());
        var second = provisioner.ensureMembership(new OrganizationProvisioner.EnsureMembership(
                organization.organizationId(), email, null));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.subjectId()).isEqualTo(first.subjectId());
        assertThat(membersOf(organization.organizationId())).containsExactly(first.subjectId());
    }

    /**
     * The property the whole tenant boundary rests on.
     *
     * <p>Organization membership is how this platform decides which tenant a
     * person belongs to. One person may legitimately hold two tenants, and the
     * failure mode is not a rejected login — it is a member of one tenant being
     * silently accepted as a member of another. Asserted against a real realm,
     * because a stub that returns a list of members is a stub of the very
     * behaviour in question.
     */
    @Test
    void aMemberOfOneTenantIsNotAMemberOfAnother() {
        String aliasA = alias + "-a";
        String aliasB = alias + "-b";
        var organizationA = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(UUID.randomUUID(), aliasA, "A", null));
        var organizationB = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(UUID.randomUUID(), aliasB, "B", null));
        organizationsToRemove.add(organizationA.organizationId());
        organizationsToRemove.add(organizationB.organizationId());

        var membership = provisioner.ensureMembership(new OrganizationProvisioner.EnsureMembership(
                organizationA.organizationId(), alias + "@example.test", null));
        usersToRemove.add(membership.subjectId());

        assertThat(membersOf(organizationA.organizationId())).containsExactly(membership.subjectId());
        assertThat(membersOf(organizationB.organizationId()))
                .as("membership in one tenant's organization must not appear in another's")
                .isEmpty();

        // And the same subject added to B is a second membership, not a shared
        // one: the two organizations stay independent facts about one person.
        var second = provisioner.ensureMembership(new OrganizationProvisioner.EnsureMembership(
                organizationB.organizationId(), alias + "@example.test", membership.subjectId()));

        assertThat(second.created())
                .as("the existing subject is linked, never created a second time")
                .isFalse();
        assertThat(membersOf(organizationA.organizationId())).containsExactly(membership.subjectId());
        assertThat(membersOf(organizationB.organizationId())).containsExactly(membership.subjectId());
    }

    @Test
    void theReadOnlyCredentialCanReadTheOrganizationAndCannotCreateOne() {
        var organization = provisioner.ensureOrganization(
                new OrganizationProvisioner.EnsureOrganization(UUID.randomUUID(), alias, "Acme", null));
        organizationsToRemove.add(organization.organizationId());

        assertThat(directory.getOrganization(organization.organizationId()))
                .as("the drift report must be able to see what provisioning wrote")
                .isPresent();

        assertThat(statusOfCreateAttempt(clientFor("horecaos-identity-reader", READER_SECRET)))
                .as("the drift report runs unattended; a credential that could write "
                        + "could quietly alter the memberships it exists to report on")
                .isEqualTo(403);
    }

    @Test
    void theProvisioningCredentialCannotChangeTheRealm() {
        assertThat(statusOfRealmUpdate(clientFor("horecaos-provisioning", PROVISIONING_SECRET)))
                .as("a provisioning credential able to change the realm could create "
                        + "administrators and rewrite authentication flows")
                .isEqualTo(403);
    }

    @Test
    void aVanishedOrganizationIsAbsentRatherThanAnError() {
        assertThat(directory.getOrganization(UUID.randomUUID().toString())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Raw Admin API, used only to set up and inspect. The adapter under test is
    // never asked to prove itself with its own reads.
    // -----------------------------------------------------------------------

    private String createOrganizationDirectly(String organizationAlias) {
        admin.post()
                .uri("/admin/realms/{realm}/organizations", REALM)
                .body(Map.of("name", organizationAlias, "alias", organizationAlias, "enabled", true))
                .retrieve()
                .toBodilessEntity();
        return organizationsNamed(organizationAlias).getFirst();
    }

    private List<String> organizationsNamed(String organizationAlias) {
        List<Map<String, Object>> all = admin.get()
                .uri("/admin/realms/{realm}/organizations", REALM).retrieve().body(LIST);
        return all == null ? List.of() : all.stream()
                .filter(organization -> organizationAlias.equals(String.valueOf(organization.get("alias"))))
                .map(organization -> String.valueOf(organization.get("id")))
                .toList();
    }

    private List<String> usersWithEmail(String email) {
        try {
            List<Map<String, Object>> users = admin.get()
                    .uri(builder -> builder.path("/admin/realms/{realm}/users")
                            .queryParam("email", email).queryParam("exact", true).build(REALM))
                    .retrieve().body(LIST);
            return users == null ? List.of()
                    : users.stream().map(user -> String.valueOf(user.get("id"))).toList();
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private List<String> membersOf(String organizationId) {
        List<Map<String, Object>> members = admin.get()
                .uri("/admin/realms/{realm}/organizations/{org}/members", REALM, organizationId)
                .retrieve().body(LIST);
        return members == null ? List.of()
                : members.stream().map(member -> String.valueOf(member.get("id"))).toList();
    }

    private void delete(String path) {
        try {
            admin.delete().uri(path, REALM).retrieve().toBodilessEntity();
        } catch (RuntimeException alreadyGone) {
            // Cleanup is best effort; a failure here must not mask a test result.
        }
    }

    private int statusOfCreateAttempt(RestClient client) {
        return statusOf(() -> client.post()
                .uri("/admin/realms/{realm}/organizations", REALM)
                .body(Map.of("name", alias + "-denied", "alias", alias + "-denied", "enabled", true)));
    }

    private int statusOfRealmUpdate(RestClient client) {
        return statusOf(() -> client.put()
                .uri("/admin/realms/{realm}", REALM)
                .body(Map.of("realm", REALM, "displayName", "tampered")));
    }

    private static int statusOf(java.util.function.Supplier<RestClient.RequestBodySpec> request) {
        int[] observed = {0};
        try {
            request.get()
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (ignored, response) -> {
                        observed[0] = response.getStatusCode().value();
                    })
                    .toBodilessEntity();
        } catch (RuntimeException refused) {
            if (refused instanceof org.springframework.web.client.HttpStatusCodeException status) {
                return status.getStatusCode().value();
            }
            throw refused;
        }
        return observed[0];
    }

    private static RestClient clientFor(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        Map<String, Object> token = RestClient.create(BASE_URL).post()
                .uri("/realms/{realm}/protocol/openid-connect/token", REALM)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(MAP);

        if (token == null || token.get("access_token") == null) {
            throw new IllegalStateException("Keycloak did not return an access token for " + clientId);
        }
        String bearer = String.valueOf(token.get("access_token"));
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .requestInitializer(request -> request.getHeaders().setBearerAuth(bearer))
                .build();
    }
}
