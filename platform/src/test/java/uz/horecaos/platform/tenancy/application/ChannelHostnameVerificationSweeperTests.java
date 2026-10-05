package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.FakeDnsTxtResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenge;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelSetupStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Row 10.5's DNS-TXT ownership challenge: {@code ChannelSetupService}'s own
 * {@code verifyCustomHostname}/{@code rotateChallenge}, and {@code
 * ChannelHostnameVerificationSweeper}'s periodic re-check.
 *
 * <p>Against a real PostgreSQL for {@code JdbcChannelSetupStore}'s own SQL,
 * with DNS itself always a {@link FakeDnsTxtResolver} -- this suite never
 * touches the real network, the same rule every test in this wave follows.
 */
class ChannelHostnameVerificationSweeperTests {

    private static final Instant NOW = Instant.parse("2026-09-28T09:00:00Z");
    private static final String BASE_DOMAIN = "stores.horecaos.uz";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcChannelSetupStore setupStore;
    private ChannelSetupService setup;
    private ChannelHostnameVerificationSweeper sweeper;
    private FakeDnsTxtResolver dns;
    private UUID tenantId;
    private UUID channelId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JdbcSalesChannelStore channelStore = new JdbcSalesChannelStore(jdbc);
        SalesChannelService channels = new SalesChannelService(
                channelStore, clock, AuditTrail.recorder(jdbc), AuditTrail.actor("channel-author"));
        setupStore = new JdbcChannelSetupStore(jdbc);
        dns = new FakeDnsTxtResolver();
        setup = new ChannelSetupService(
                setupStore,
                channels,
                clock,
                AuditTrail.recorder(jdbc),
                AuditTrail.actor("channel-author"),
                dns,
                BASE_DOMAIN);
        sweeper = new ChannelHostnameVerificationSweeper(setupStore, dns, clock);

        tenantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'hostname-verify', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        channelId = channels.create(
                        tenantId,
                        new SalesChannelService.CreateChannelCommand("WEB1", "WEB", "Website", null, false, true, null))
                .id();
    }

    // ------------------------------------------------------- setCustomHostname

    @Test
    @DisplayName("claiming a custom hostname issues a fresh DNS-TXT challenge under its own record name")
    void claimingACustomHostnameIssuesAChallenge() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);

        HostnameChallenge challenge = setup.challenge(tenantId, channelId).orElseThrow();
        assertThat(challenge.hostname()).isEqualTo("orders.tandir-house.uz");
        assertThat(challenge.recordName()).isEqualTo("_horecaos-challenge.orders.tandir-house.uz");
        assertThat(challenge.token()).startsWith("horecaos-verify-");
        assertThat(challenge.issuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a platform-issued subdomain never gets a challenge -- it is verified immediately")
    void aSubdomainHasNoChallenge() {
        setup.setSubdomain(tenantId, channelId, "tandir-house", 1);

        assertThat(setup.challenge(tenantId, channelId)).isEmpty();
    }

    // --------------------------------------------------------------- verify

    @Test
    @DisplayName("verify refuses with UNPROCESSABLE_STATE while the TXT record does not carry the token")
    void verifyRefusesUntilTheRecordMatches() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);

        assertThatThrownBy(() -> setup.verifyCustomHostname(tenantId, channelId, 2))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));

        // A record that exists but carries the wrong value is exactly as
        // unverified as no record at all -- an operator's own typo must not
        // accidentally prove ownership of something else.
        dns.publish("_horecaos-challenge.orders.tandir-house.uz", "not-the-token");
        assertThatThrownBy(() -> setup.verifyCustomHostname(tenantId, channelId, 2))
                .isInstanceOf(ApiException.class);

        assertThat(setup.hostname(tenantId, channelId).orElseThrow().verified())
                .as("a refused verify must never have flipped the flag")
                .isFalse();
    }

    @Test
    @DisplayName("verify flips the hostname verified once the TXT record carries the issued token")
    void verifySucceedsOnAMatchingRecord() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);
        HostnameChallenge challenge = setup.challenge(tenantId, channelId).orElseThrow();
        dns.publish(challenge.recordName(), challenge.token());

        ChannelHostname verified = setup.verifyCustomHostname(tenantId, channelId, 2);

        assertThat(verified.verified()).isTrue();
    }

    @Test
    @DisplayName("verify refuses with RESOURCE_NOT_FOUND when this channel has no hostname claimed at all")
    void verifyRefusesWithNoHostnameClaimed() {
        assertThatThrownBy(() -> setup.verifyCustomHostname(tenantId, channelId, 1))
                .isInstanceOf(TenantResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- rotate

    @Test
    @DisplayName("rotating the challenge issues a new token and un-verifies an already-verified hostname")
    void rotateReplacesTheTokenAndUnverifies() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);
        HostnameChallenge first = setup.challenge(tenantId, channelId).orElseThrow();
        dns.publish(first.recordName(), first.token());
        setup.verifyCustomHostname(tenantId, channelId, 2);

        HostnameChallenge rotated = setup.rotateChallenge(tenantId, channelId, 3);

        assertThat(rotated.token()).isNotEqualTo(first.token());
        assertThat(setup.hostname(tenantId, channelId).orElseThrow().verified())
                .as("the DNS record still carries the pre-rotation token, which is no longer the live challenge")
                .isFalse();

        // The old token no longer verifies -- the record has to be updated
        // to the new one, exactly as rotating a leaked credential should work.
        assertThatThrownBy(() -> setup.verifyCustomHostname(tenantId, channelId, 4))
                .isInstanceOf(ApiException.class);

        dns.publish(rotated.recordName(), rotated.token());
        assertThat(setup.verifyCustomHostname(tenantId, channelId, 4).verified())
                .isTrue();
    }

    @Test
    @DisplayName("rotate is refused for a platform-issued subdomain -- there is no challenge to replace")
    void rotateRefusedForASubdomain() {
        setup.setSubdomain(tenantId, channelId, "tandir-house", 1);

        assertThatThrownBy(() -> setup.rotateChallenge(tenantId, channelId, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------- sweeper

    @Test
    @DisplayName("the sweep leaves a verified hostname alone while its TXT record still matches")
    void sweepLeavesAMatchingHostnameVerified() {
        verifyOrdersHostname();

        assertThat(sweeper.runOnce()).isZero();
        assertThat(setup.hostname(tenantId, channelId).orElseThrow().verified()).isTrue();
    }

    @Test
    @DisplayName("the sweep un-verifies a hostname whose TXT record has since disappeared")
    void sweepUnverifiesAWithdrawnRecord() {
        String recordName = verifyOrdersHostname();
        dns.withdraw(recordName);

        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(setup.hostname(tenantId, channelId).orElseThrow().verified())
                .as("the record is gone, so the platform can no longer stand behind this hostname")
                .isFalse();
    }

    @Test
    @DisplayName("the sweep un-verifies a hostname whose TXT record now carries a different value")
    void sweepUnverifiesAChangedRecord() {
        String recordName = verifyOrdersHostname();
        dns.publish(recordName, "someone-elses-value");

        assertThat(sweeper.runOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("the sweep never touches a platform-issued subdomain -- it was never given a challenge to check")
    void sweepIgnoresPlatformIssuedSubdomains() {
        setup.setSubdomain(tenantId, channelId, "tandir-house", 1);

        assertThat(sweeper.runOnce()).isZero();
        assertThat(setup.hostname(tenantId, channelId).orElseThrow().verified()).isTrue();
    }

    @Test
    @DisplayName("the sweep leaves an unverified custom hostname alone -- nothing to un-verify")
    void sweepIgnoresAnUnverifiedCustomHostname() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);

        assertThat(sweeper.runOnce()).isZero();
    }

    private String verifyOrdersHostname() {
        setup.setCustomHostname(tenantId, channelId, "orders.tandir-house.uz", 1);
        HostnameChallenge challenge = setup.challenge(tenantId, channelId).orElseThrow();
        dns.publish(challenge.recordName(), challenge.token());
        setup.verifyCustomHostname(tenantId, channelId, 2);
        return challenge.recordName();
    }
}
