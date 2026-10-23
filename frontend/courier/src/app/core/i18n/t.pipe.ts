import { Pipe, type PipeTransform, inject } from '@angular/core';

import { I18n } from './i18n';
import type { MessageKey } from './message-keys';

/**
 * `{{ 'shell.nav.home' | t }}` in a template.
 *
 * Impure, because the message depends on the locale signal and not only on the key: a pure pipe
 * would keep showing the old language after a switch. The `MessageKey` parameter type is what
 * makes a typo in a template a build error under strict templates.
 */
@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    return this.i18n.t(key, values);
  }
}
