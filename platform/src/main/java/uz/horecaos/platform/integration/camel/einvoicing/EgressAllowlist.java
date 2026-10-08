package uz.horecaos.platform.integration.camel.einvoicing;

import java.util.Optional;
import java.util.Set;

/**
 * The hosts an approved provider environment may be called at (ADR 0026): the one
 * place a base URL is checked against, so an adapter, however it was written or
 * configured, cannot be made to reach a host nobody approved.
 */
@FunctionalInterface
public interface EgressAllowlist {

    /** The lower-case host names the environment approves; empty when there is no such environment. */
    Optional<Set<String>> hostsOf(String environmentCode);
}
