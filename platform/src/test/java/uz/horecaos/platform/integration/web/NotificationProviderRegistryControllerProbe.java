package uz.horecaos.platform.integration.web;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Lets a test in another package call the registry read, which is package-private on the controller. */
public final class NotificationProviderRegistryControllerProbe {

    private NotificationProviderRegistryControllerProbe() {}

    public static NotificationProviderRegistryController.NotificationProviders registry(JdbcClient jdbc) {
        return new NotificationProviderRegistryController(jdbc).registry();
    }
}
