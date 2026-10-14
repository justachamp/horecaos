import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { MapPin } from '../../../shared/ui/map/map-pin';
import { LatLng } from '../../../shared/ui/map/map-provider';
import { FALLBACK_MAP_CENTRE, MapRegionService } from '../../delivery/map-region';

/**
 * Places or moves the pin of a saved address and nothing else (ADR 0177, row `1.3b`).
 *
 * **Why it is not `q-customer-address-editor`.** That editor changes an address's text, and the
 * New order screen's own persona cannot read that text: it holds `customer.address.capture`, not
 * `customer.pii.reveal`, so the server sends it the label, the street without its house number,
 * the district and the point, and nothing a courier would knock on. An editor over that would
 * invite typing a house number into a field that cannot save one. What this operator can honestly
 * do to a saved address is put its point where the caller says it is, so this is a map, the two
 * coordinate fields beside it, and the way to say "this is the point" — `q-map-pin`, without the
 * option to take the pin away, because the pin is what makes the address deliverable.
 *
 * A pin the person placed is `OPERATOR_PIN`, which the server writes; this component only reports
 * where the pin is. There is no way here to produce a geocoder's point (ADR 0145 decision 5).
 */
@Component({
  selector: 'q-address-pin-editor',
  imports: [MapPin, TPipe],
  templateUrl: './address-pin-editor.html',
  styleUrl: './address-pin-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddressPinEditor {
  private readonly regions = inject(MapRegionService);

  /** The address's saved point, or `null` when it has none; the pin starts here. */
  readonly point = input<LatLng | null>(null);
  /** The address's label, shown on the map. */
  readonly label = input<string | null>(null);

  /** The pin as the person has it now; `null` while none is placed. */
  readonly pointChange = output<LatLng | null>();

  protected readonly pin = signal<LatLng | null>(null);

  protected readonly region = computed(() => this.regions.primary());
  protected readonly center = computed<LatLng>(
    () => this.point() ?? this.region()?.centre ?? FALLBACK_MAP_CENTRE,
  );

  constructor() {
    void this.regions.ensureLoaded();

    // A different address (or its re-read after a save) starts the pin from its own point.
    effect(() => {
      const saved = this.point();
      untracked(() => {
        this.pin.set(saved);
        this.pointChange.emit(saved);
      });
    });
  }

  protected onMoved(next: LatLng | null): void {
    this.pin.set(next);
    this.pointChange.emit(next);
  }
}
