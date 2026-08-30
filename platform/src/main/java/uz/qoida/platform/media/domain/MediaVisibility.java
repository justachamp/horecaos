package uz.qoida.platform.media.domain;

/**
 * Who may see an asset (ADR 0010).
 *
 * <p>Neither value means a public bucket. Buckets stay private with public
 * access blocked; {@link #PUBLIC} means "servable through the CDN origin",
 * and {@link #PRIVATE} means "only through a short-lived signed URL".
 */
public enum MediaVisibility {
    PUBLIC,
    PRIVATE
}
