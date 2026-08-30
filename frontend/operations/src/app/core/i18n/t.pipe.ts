import { Pipe, PipeTransform, inject } from '@angular/core';

import { I18n } from './i18n';
import { MessageKey } from './messages.en';

/**
 * `{{ 'shell.nav.orders' | t }}` in a template.
 *
 * Impure, because the message depends on the locale signal and not only on the
 * key. In a zoneless application with signals that costs a re-evaluation per
 * change detection pass over the template, which for a console of this size is
 * not measurable — and the alternative, a pure pipe, silently keeps showing the
 * old language after a switch.
 *
 * The `MessageKey` parameter type is what makes a typo in a template a build
 * error: Angular's strict template checking resolves the literal against the
 * union.
 */
@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    return this.i18n.t(key, values);
  }
}
