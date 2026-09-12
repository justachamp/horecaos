import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The language-tab strip roughly forty forms in this console need, and none
 * of them has (ADR 0101, row `X/X.5`).
 *
 * On every one of them today a tenant edits whatever locale they happen to be
 * in with no indication of which locale the entity actually falls back to and
 * no sense of which locales are still empty before publishing. This renders
 * the tabs, the default-language marker and a per-locale completeness dot;
 * it does **not** render the fields themselves — like `product-editor-page`'s
 * own locale strip that it replaces, the caller keeps switching its own
 * `@switch`/`@if` on {@link activeLocale} underneath.
 *
 * **The default marker follows the entity, never the UI locale.** The
 * catalog's own default locale is `uz` (`CatalogSnapshotLoader`'s
 * `default-locale`) while the console's own default UI locale is `ru`
 * (`i18n.ts`'s `DEFAULT_LOCALE`) — two different defaults for two different
 * things, and {@link defaultLocale} is an explicit input precisely so this
 * component never conflates them by reading the viewer's own locale instead.
 *
 * The IA also asks for an optional auto-translate button here; it does not
 * exist in this build — no translation provider is installed anywhere on the
 * platform (`ProviderCategory` has no `TRANSLATION` value), so the button
 * would have nothing to call. See the gap map's own `X.5 auto-translate` row.
 */
@Component({
  selector: 'q-localized-field-group',
  imports: [TPipe],
  templateUrl: './localized-field-group.html',
  styleUrl: './localized-field-group.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocalizedFieldGroup {
  readonly locales = input.required<readonly string[]>();
  readonly activeLocale = input.required<string>();
  /** The entity's own default locale — not the viewer's UI locale. `null` shows no marker. */
  readonly defaultLocale = input<string | null>(null);
  /**
   * Per-locale completeness. A locale absent from this map renders no dot at
   * all — "unknown" is not the same as "incomplete", and this component
   * never guesses one from the other.
   */
  readonly completeness = input<Readonly<Record<string, boolean>>>({});
  /** Optional human label per locale code — defaults to the bare code, upper-cased. */
  readonly localeLabels = input<Readonly<Record<string, string>>>({});

  readonly activeLocaleChange = output<string>();

  protected labelFor(locale: string): string {
    return this.localeLabels()[locale] ?? locale.toUpperCase();
  }
}
