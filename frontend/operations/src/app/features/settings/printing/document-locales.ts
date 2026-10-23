import { LOCALES } from '../../../core/i18n/i18n';
import type { PlatformLocales } from '../../../core/i18n/platform-locales';

/**
 * The languages a printed document can be worded in for a brand: the registry's messages tier (the
 * languages the platform writes documents in, which is what the backend accepts for a footer line or a
 * brand's document language — `PrintingConfigurationKeys.DOCUMENT_LOCALES`), narrowed to the brand's own
 * supported set, in the brand's order (default first).
 *
 * A brand that serves no language the platform writes documents in is offered the whole messages tier
 * rather than nothing: the cashier's paper must come out in some language, and the platform's own set is
 * the honest answer. Before the registry has been read (or when it cannot be) the languages this build has
 * a catalogue for are offered, as everywhere else in the console (ADR 0149, Decision 5).
 */
export function documentLocales(
  registry: PlatformLocales,
  brandLocales: readonly string[],
): readonly string[] {
  const messages = registry.active('MESSAGES');
  if (messages.length === 0) {
    return LOCALES;
  }
  const own = brandLocales.filter((tag) => messages.includes(tag));
  return own.length > 0 ? own : messages;
}
