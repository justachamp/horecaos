import { Locale, interpolate } from './i18n';

/**
 * A screen's own sentences in all three languages. Declared as
 * `Record<Locale, Record<K, string>>` so the compiler, not a spec, refuses a catalogue
 * in which one language lacks a key another has.
 */
export type LocalCatalogue<K extends string> = Readonly<
  Record<Locale, Readonly<Record<K, string>>>
>;

/**
 * Sentences that ship inside the lazy chunk of the one screen that uses them, instead of
 * the shared `messages.*.ts` catalogues.
 *
 * **Why this exists.** `messages.ru.ts` is the default language and loads eagerly, so every
 * Russian key counts against the operations app's initial-bundle budget (`angular.json`,
 * 832 kB, ADR 0101's gate) -- and that budget has no room left: it has been raised by a
 * kilobyte at a time for a handful of keys. A sentence used only by a screen that is itself
 * lazy-loaded (settings, orders, the shell) costs the first paint nothing if it travels with
 * that screen, and there is nothing to gain by making an operator download it before they
 * can see the sign-in page.
 *
 * **What it does not change.** The operator still sees the language they chose, switched at
 * runtime: callers pass `I18n.locale()`, a signal, so a template that calls `text(...)`
 * re-renders on a language switch like one that uses the `t` pipe. Placeholders are the same
 * `{name}` form. Strings shared by several screens, or by anything on the first paint, belong
 * in the catalogues.
 */
export class LocalMessages<K extends string> {
  constructor(private readonly catalogue: LocalCatalogue<K>) {}

  text(locale: Locale, key: K, values?: Readonly<Record<string, string | number>>): string {
    return interpolate(this.catalogue[locale][key], values);
  }
}

export function localMessages<K extends string>(catalogue: LocalCatalogue<K>): LocalMessages<K> {
  return new LocalMessages(catalogue);
}
