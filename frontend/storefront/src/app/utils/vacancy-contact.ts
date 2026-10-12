import type { VacancyApply } from '../types/editorial.types';
import { httpsUrlOrNull } from './safe-url';

/**
 * How a vacancy's one outbound contact is shown (ADR 0157, Decision 7).
 *
 * The platform stores no candidate data and offers no application form, so a vacancy is a way to
 * reach the employer and nothing more: a phone to call, a Telegram handle, an https address or an
 * e-mail address. Each is turned into a link only when it passes its own rule; a value that does
 * not is shown as the plain text it is, never as a link.
 */
export interface VacancyContact {
  /** What the visitor reads. */
  readonly label: string;
  /** Where it goes, or `null` for plain text. */
  readonly href: string | null;
  /** Opens in a new tab. */
  readonly external: boolean;
}

const PHONE = /^\+?\d{5,15}$/;
const EMAIL = /^[^\s@?#]+@[^\s@?#]+\.[^\s@?#]+$/;
/** Telegram usernames are 5 to 32 characters: a letter, then letters, digits and underscores. */
const HANDLE = /^[A-Za-z][A-Za-z0-9_]{4,31}$/;
const TELEGRAM_PREFIX = /^(?:https?:\/\/)?t\.me\//i;

export function vacancyContact(apply: VacancyApply): VacancyContact {
  const value = apply.value.trim();
  switch (apply.type) {
    case 'PHONE': {
      const digits = value.replace(/[\s()-]/g, '');
      return PHONE.test(digits)
        ? { label: value, href: `tel:${digits}`, external: false }
        : plain(value);
    }
    case 'EMAIL':
      return EMAIL.test(value)
        ? { label: value, href: `mailto:${value}`, external: false }
        : plain(value);
    case 'URL': {
      const href = httpsUrlOrNull(value);
      return href === null ? plain(value) : { label: value, href, external: true };
    }
    case 'TELEGRAM': {
      const handle = value.replace(TELEGRAM_PREFIX, '').replace(/^@/, '');
      return HANDLE.test(handle)
        ? { label: `@${handle}`, href: `https://t.me/${handle}`, external: true }
        : plain(value);
    }
  }
}

function plain(label: string): VacancyContact {
  return { label, href: null, external: false };
}
