package uz.horecaos.platform.iam.infrastructure.secrets;

import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.api.secrets.SecretWriter;

/**
 * Writes to the {@link MutableSecretStore} backing the {@code environment}
 * secrets provider (ADR 0028, ADR 0065). Local and CI only; see {@link
 * MutableSecretStore}'s own doc comment for why a full application context
 * needs this bean to exist even where nothing exercises it.
 */
public class EnvironmentSecretWriter implements SecretWriter {

    private final MutableSecretStore store;

    public EnvironmentSecretWriter(MutableSecretStore store) {
        this.store = store;
    }

    @Override
    public void write(SecretReference reference, SecretValue value) {
        store.put(EnvironmentSecretResolver.propertyNameFor(reference), value.reveal());
    }
}
