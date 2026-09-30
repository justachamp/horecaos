import { Pipe, PipeTransform } from '@angular/core';

import { formatPhone } from './phone';

/**
 * `{{ customer.phone | phone }}` in a template — the number in the brand's own pattern
 * (Settings 10.12).
 *
 * Impure for the reason `TPipe` is: the result depends on the brand's pattern, a signal,
 * and not only on the number. A pure pipe would keep showing the old pattern after an
 * operator saves a new one, until the number itself changed.
 */
@Pipe({ name: 'phone', pure: false })
export class PhonePipe implements PipeTransform {
  transform(raw: string | null | undefined): string {
    return formatPhone(raw);
  }
}
