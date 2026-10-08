package uz.horecaos.platform.content.domain;

/**
 * The part an asset plays in a revision (ADR 0157, {@code content.revision_media.role}).
 *
 * <p>Recorded at publish for every asset a revision uses, because the retention sweeper and the
 * media usage probe answer "is this asset still in use" from this relation and not by reading
 * documents.
 */
public enum MediaRole {
    PRIMARY,
    POSTER,
    SLIDE,
    AVATAR,
    BODY_IMAGE,
    ALBUM_IMAGE
}
