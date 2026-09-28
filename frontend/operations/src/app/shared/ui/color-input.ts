import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

const HEX_PATTERN = /^#[0-9a-fA-F]{6}$/;

/**
 * A hex colour, picked or typed (ADR 0101, row `X.32`).
 *
 * The gap this closes: a tenant has no way today to set its late-highlight
 * colour or a per-channel brand colour, so a native `<input type="color">`
 * alone — no hex readout, no typed entry, no validation — is not a control a
 * settings screen can ship. This pairs the native swatch with a typed hex
 * field so a brand guideline's exact value can be entered, not eyeballed.
 *
 * {@link value} only ever changes to a syntactically valid six-digit hex
 * colour (`#rrggbb`); a partial or malformed typed value is held locally
 * until it resolves to one or the field blurs back to the last valid value.
 *
 * {@link warnings} (gap map row `X.39`) renders whatever legibility warnings
 * a caller already computed for the current value, inline below the swatch —
 * see that input's own doc.
 */
@Component({
  selector: 'q-color-input',
  templateUrl: './color-input.html',
  styleUrl: './color-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ColorInput {
  readonly value = input<string>('#000000');
  readonly ariaLabel = input<string | null>(null);

  /**
   * Already-formatted legibility warnings for the current {@link value}
   * (gap map row `X.39`) — a caller computes these itself, typically from
   * `evaluateHighlightColourContrast` (`shared/ui/color/highlight-contrast.ts`),
   * since only the caller knows which reference surfaces matter for its own
   * screen. This component stays deliberately ignorant of WCAG maths; it
   * only renders whatever strings it is handed, inline, below the swatch.
   */
  readonly warnings = input<readonly string[]>([]);

  readonly valueChange = output<string>();

  /** The typed text, which may be mid-edit and not yet a valid hex value. */
  protected readonly draft = signal(this.value());

  protected readonly isValidDraft = computed(() => HEX_PATTERN.test(this.draft()));

  constructor() {
    // The external value only ever moves to something this component already
    // considers final, so re-sync the draft whenever it changes — including
    // the echo of our own emission, which is simply a no-op re-render.
    effect(() => this.draft.set(this.value()));
  }

  protected onSwatchInput(hex: string): void {
    this.draft.set(hex);
    this.valueChange.emit(hex);
  }

  protected onTextInput(text: string): void {
    this.draft.set(text);
    if (HEX_PATTERN.test(text)) {
      this.valueChange.emit(text);
    }
  }

  protected onTextBlur(): void {
    if (!HEX_PATTERN.test(this.draft())) {
      // Nothing valid was ever typed — revert to the last good value rather
      // than leave a malformed string sitting in the field.
      this.draft.set(this.value());
    }
  }
}
