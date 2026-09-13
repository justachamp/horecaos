import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { StatusPill } from '../status-pill';

/**
 * The one credential control every settings row renders a provider secret
 * through (ADR 0028/0065, ADR 0101, gap-map row `X.14`).
 *
 * Three bare `type="password"` inputs did this job today —
 * `connect-provider-panel`, `register-merchant-binding-panel` and
 * `rotate-secret-dialog` — each with its own masking, none with a reveal
 * toggle, a copy action, or a place to show when a credential was last
 * rotated or used. This wave (P35) replaces all three and adds the two
 * fields the console had nowhere to put: last-rotated and last-used.
 *
 * **`entry` mode** is a form field: the operator is typing a value that will
 * travel through the write-only secret door. `revealed` toggles whether the
 * characters *currently in the DOM* are legible — never a value the server
 * returned, because no surface this platform exposes ever returns one
 * (ADR 0028). A spec that promised "reveal" for a *stored* credential would
 * contradict the security model; this is reveal-once **at entry**, the
 * qualifier `platform/docs/operations-spec/settings.md:900` already carries
 * and this wave's own ADR copies into the two documents that were missing
 * it.
 *
 * **`configured` mode** is a read-out: masked presence, the ADR 0028
 * *reference* string (never the value — a reference is a pointer, not a
 * secret, and copying it is how a support conversation confirms which
 * credential a "configured" badge refers to without anyone ever seeing what
 * is behind it), last-rotated and last-used (both already-formatted text
 * from the caller, the same "pre-translated or a key" split `q-inline-alert`
 * uses for the parts that vary by page), and a {@link rotate} trigger the
 * parent wires to its own rotate flow.
 */
@Component({
  selector: 'q-secret-input',
  imports: [TPipe, StatusPill],
  templateUrl: './secret-input.html',
  styleUrl: './secret-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecretInput {
  readonly mode = input<'entry' | 'configured'>('entry');
  readonly label = input.required<string>();
  /**
   * Named `fieldId`, never `id`: `id` is a global HTML attribute, and Angular
   * reflects a global attribute onto the host element in addition to binding
   * it to a same-named `@Input` — so a plain `id="..."` on `<q-secret-input>`
   * would leave two elements sharing one id (the host tag and the inner
   * `<input>` this value is actually meant for), breaking `label[for]`
   * association and any `#id`-based query, in the DOM this renders and not
   * only in a test.
   */
  readonly fieldId = input.required<string>();
  readonly disabled = input(false, { transform: booleanAttribute });

  // -------------------------------------------------------------- entry mode
  readonly value = input('');
  readonly valueChange = output<string>();

  // ---------------------------------------------------------- configured mode
  /** Whether a credential is on file at all — distinct from `reference`, which may be withheld. */
  readonly configured = input(false, { transform: booleanAttribute });
  /** The ADR 0028 reference string, copyable. Never the secret value. */
  readonly reference = input<string | null>(null);
  /** Already-formatted text, e.g. a locale date string or "Never". */
  readonly lastRotatedLabel = input<string | null>(null);
  readonly lastUsedLabel = input<string | null>(null);
  readonly rotate = output<void>();

  protected readonly revealed = signal(false);
  protected readonly copiedJustNow = signal(false);

  protected toggleReveal(): void {
    this.revealed.update((current) => !current);
  }

  protected onInput(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement).value);
  }

  protected async copyReference(): Promise<void> {
    const value = this.reference();
    if (value === null) {
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
      this.copiedJustNow.set(true);
      setTimeout(() => this.copiedJustNow.set(false), 2000);
    } catch {
      // Clipboard access denied or unavailable (an insecure context, an
      // unsupported browser) — the reference stays visible in the DOM as
      // plain text either way, so a copy failure loses convenience, not
      // information.
    }
  }
}
