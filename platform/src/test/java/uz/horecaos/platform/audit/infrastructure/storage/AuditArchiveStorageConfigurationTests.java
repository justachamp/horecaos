package uz.horecaos.platform.audit.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uz.horecaos.platform.audit.api.AuditArchiveStore;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * Switching audit archival off has to mean the platform no longer needs its
 * credentials to start.
 *
 * <p>{@code horecaos.audit.archive.enabled} guarded the archiver but not the
 * store it uses, and the store resolves both object-storage secrets the moment
 * it is built. So a deployment that turned archival off — the correct setting
 * wherever the object store has no S3 Object Lock, which this store requires —
 * still refused to boot, with "No secret is configured for ...
 * audit-archive-access-key". The first fresh deployment, onto GCS, crash-looped
 * on exactly that. The store has no other consumer than the archiver, so it
 * follows the same switch.
 */
class AuditArchiveStorageConfigurationTests {

    /**
     * A resolver that fails the test outright if anything asks it for a value.
     * With archival off, nothing should.
     */
    private static final SecretResolver REFUSES = new SecretResolver() {
        @Override
        public SecretValue resolve(SecretReference reference) {
            throw new AssertionError("archival is off, yet something resolved " + reference);
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            return resolve(reference);
        }
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuditArchiveStorageConfiguration.class)
            .withBean(SecretResolver.class, () -> REFUSES);

    @Test
    @DisplayName("with archival switched off, no store is built and no secret is asked for")
    void switchedOffNeedsNoCredentials() {
        runner.withPropertyValues("horecaos.audit.archive.enabled=false").run(context -> {
            assertThat(context)
                    .as("the context must start: a disabled feature cannot hold the platform's boot "
                            + "hostage to credentials it will never use")
                    .hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditArchiveStore.class);
        });
    }

    @Test
    @DisplayName("left on, the store is still built — the switch does not quietly disable archival")
    void switchedOnStillBuildsTheStore() {
        runner.withPropertyValues(
                        "horecaos.audit.archive.enabled=true",
                        "horecaos.audit.archive.access-key-reference=horecaos:production:object_storage:platform:audit-archive-access-key",
                        "horecaos.audit.archive.secret-key-reference=horecaos:production:object_storage:platform:audit-archive-secret-key")
                .run(context -> assertThat(context)
                        .as("with archival on, the store resolves its credentials — which this refusing "
                                + "resolver turns into a startup failure, proving the store was attempted")
                        .hasFailed());
    }
}
