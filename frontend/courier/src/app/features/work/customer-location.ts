import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

import type { CustomerLocation } from '../../core/courier/courier-work';
import { TPipe } from '../../core/i18n/t.pipe';
import { geoUri } from './geo-link';

/**
 * The customer's door, after the audited reveal.
 *
 * Personal data throughout: it is drawn from the location it is given, held by no store, and gone
 * when the screen that asked drops it (`hidden` is that screen's cue). The only thing it does with
 * the coordinate is hand it to the phone's own maps app through a `geo:` link.
 */
@Component({
  selector: 'app-customer-location',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-location.html',
  styleUrl: './customer-location.css',
})
export class CustomerLocationCard {
  readonly location = input.required<CustomerLocation>();
  readonly hidden = output<void>();

  private readonly sanitizer = inject(DomSanitizer);

  protected readonly link = computed(() =>
    geoUri(this.sanitizer, this.location().latitude, this.location().longitude),
  );
}
