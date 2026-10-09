import type { VacancyApply } from '../types/editorial.types';
import { safeHttpsUrl } from './content-link';

/** A link in a body or a contact, once its scheme has been checked. */
export interface SafeLink {
  readonly href: string;
  /** True for an `https:` link, which opens in a new tab that cannot reach back. */
  readonly external: boolean;
}

/** How a vacancy's one contact is drawn. `text` has nothing to click. */
export interface ContactLink {
  readonly kind: 'tel' | 'mailto' | 'link' | 'text';
  readonly label: string;
  readonly href?: string;
  readonly external?: boolean;
}

const TEL = /^tel:(\+?[0-9 ()\-.]{3,30})$/i;
const MAILTO = /^mailto:([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})$/i;

/**
 * A link in editorial content, or null when it must not be one.
 *
 * The rule (ADR 0157, Decision 6): the scheme is exactly `https:` (parsed, with a host and no
 * embedded credentials), `tel:` (digits with a leading `+`, separators dropped) or `mailto:` (one
 * plain address, no query or list). The server already refused what it could not vouch for; this is
 * the client applying the rule again, because it is what stands between a brand's typed text and a
 * click that could run code. Whatever fails is shown as plain text by the caller.
 */
export function safeLink(href: unknown): SafeLink | null {
  if (typeof href !== 'string') {
    return null;
  }
  const text = href.trim();
  const https = safeHttpsUrl(text);
  if (https !== null) {
    return { href: https, external: true };
  }
  const tel = TEL.exec(text);
  if (tel) {
    const number = tel[1].replace(/[\s().-]/g, '');
    return /[0-9]/.test(number) ? { href: `tel:${number}`, external: false } : null;
  }
  const mail = MAILTO.exec(text);
  return mail ? { href: `mailto:${mail[1]}`, external: false } : null;
}

/** An address shown as a label: no scheme, no trailing slash on a bare host. */
function displayUrl(url: string): string {
  const parsed = new URL(url);
  const path = parsed.pathname === '/' ? '' : parsed.pathname;
  return `${parsed.host}${path}${parsed.search}`;
}

/**
 * The one outbound contact a vacancy publishes (there is no application form and no applicant
 * data): a phone to call, an address to write to, or an `https` address to open. A value that does
 * not pass the scheme rule for its type is shown as plain text with nothing to click.
 */
export function vacancyContact(apply: VacancyApply | undefined): ContactLink | null {
  const value = apply?.value.trim();
  if (!apply || !value) {
    return null;
  }
  const plain: ContactLink = { kind: 'text', label: value };
  switch (apply.type) {
    case 'PHONE': {
      const link = safeLink(`tel:${value}`);
      return link ? { kind: 'tel', href: link.href, label: value, external: false } : plain;
    }
    case 'EMAIL': {
      const link = safeLink(`mailto:${value}`);
      return link ? { kind: 'mailto', href: link.href, label: value, external: false } : plain;
    }
    case 'URL':
    case 'TELEGRAM': {
      const url = safeHttpsUrl(value);
      return url ? { kind: 'link', href: url, label: displayUrl(url), external: true } : plain;
    }
    default:
      return plain;
  }
}
