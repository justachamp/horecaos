package uz.qoida.platform.tenancy.application;

public final class TenantResourceConflictException extends RuntimeException {

    public TenantResourceConflictException(String message) {
        super(message);
    }
}
