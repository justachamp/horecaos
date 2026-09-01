package uz.horecaos.platform.customers;

import java.util.UUID;
import uz.horecaos.platform.customers.api.CustomerTelegramSignIn;

/**
 * A {@link CustomerTelegramSignIn} for a {@code TelegramUpdateHandler} test that
 * is not itself about the ADR 0063 share-contact flow — every other handler test
 * that constructs {@code TelegramUpdateHandler} by hand needs some
 * implementation of this port to compile against, and none of them ever drives
 * a {@code contact} message, so throwing is the honest answer: reaching either
 * method here is a bug in a *different* test, not a legitimate call this class
 * should quietly swallow.
 */
public final class NoOpCustomerTelegramSignIn implements CustomerTelegramSignIn {

    @Override
    public Resolved resolveAccount(UUID tenantId, UUID brandId, String rawPhone) {
        throw new UnsupportedOperationException("This test does not exercise ADR 0063's share-contact sign-in");
    }

    @Override
    public Session establishSession(UUID tenantId, UUID brandId, UUID accountId, boolean accountCreated) {
        throw new UnsupportedOperationException("This test does not exercise ADR 0063's share-contact sign-in");
    }
}
