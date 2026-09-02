package uz.horecaos.platform.iam.api.secrets;

/**
 * Writes the value behind a secret reference (ADR 0028, ADR 0065).
 *
 * <p>The counterpart to {@link SecretResolver} that ADR 0028 left unbuilt: "no
 * write path — nothing writes a secret, so every rotation is a manual {@code bao
 * kv put}". ADR 0065 opens exactly one caller of this port, the write-only
 * ingress door, because a tenant has no other way to reach the manager at all.
 *
 * <p>Deliberately the narrowest possible seam: one method, no read, no list, no
 * delete. A caller that already holds a {@link SecretReference} legitimately —
 * freshly minted by {@link SecretIngressGateway}, never tenant-supplied — may
 * write the value behind it. Nothing here returns the value, and nothing here
 * lets a caller choose where it lands.
 */
public interface SecretWriter {

    /**
     * Writes {@code value} under {@code reference}, creating it if absent and
     * overwriting it otherwise. Rotation is exactly this: the same operation,
     * called again.
     *
     * <p>Implementations must never log, trace, or otherwise echo {@code value}.
     * The only permitted output of a call to this method is success or a thrown
     * exception naming the reference — never the value.
     */
    void write(SecretReference reference, SecretValue value);
}
