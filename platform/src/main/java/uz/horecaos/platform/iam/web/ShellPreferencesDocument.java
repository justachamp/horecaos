package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The {@code SHELL} surface's document (ADR 0160): the person's default branch.
 *
 * <p>Published so the generated client types it; the route itself reads and writes the document as a
 * tree and validates it against {@code StaffPreferenceSchemas}, which is the authority for what may be
 * stored.
 *
 * @param defaultLocationId a branch the person holds an active grant at (checked on write); the console
 *                          ignores it when the grant has since gone, and when this device has chosen
 *                          another branch of its own
 */
@Schema(description = "The SHELL surface: the person's default branch")
public record ShellPreferencesDocument(@Nullable UUID defaultLocationId) {}
