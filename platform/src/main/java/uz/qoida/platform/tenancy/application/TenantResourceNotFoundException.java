package uz.qoida.platform.tenancy.application;

public final class TenantResourceNotFoundException extends RuntimeException {

    public TenantResourceNotFoundException(String message) {
        super(message);
    }
}
