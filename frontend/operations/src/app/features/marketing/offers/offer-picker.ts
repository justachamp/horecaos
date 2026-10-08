import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { formatMoment } from '../moment';
import { offerEligible } from '../scenarios/scenario-draft';
import { ScenarioChannel } from '../scenarios/scenarios-api';
import { OfferView } from './offers-api';

/** One choice in the list: an offer, and whether it is still one a step may name. */
interface PickerOption {
  readonly offerId: string;
  readonly label: string;
  readonly inForce: boolean;
}

/**
 * Chooses one of a brand's versioned offers for a scenario step (ADR 0112).
 *
 * **It selects; it never authors.** An offer already says which pricing promotion or loyalty
 * accrual rule it points at, and what that is worth is pricing's and loyalty's. So this control
 * offers no field for an amount, a percentage or a number of points, and shows an offer's
 * reference as a kind (a promotion, an accrual rule) rather than as terms it would then seem to
 * own.
 *
 * Only an offer a step may name right now is listed: published, not over, and allowed in the
 * step's channel. The one exception is the offer the step already names: it is listed with a
 * warning when it has since been retired or has ended, because hiding it would show an empty
 * select over a step that still points at something, and the author could not see what to fix.
 *
 * The eligibility is read at the moment the inputs change, not on a timer: a picker lives for one
 * editing session, and the server checks the same rule again when the scenario is saved and again
 * at every guest's step.
 */
@Component({
  selector: 'q-offer-picker',
  imports: [TPipe],
  templateUrl: './offer-picker.html',
  styleUrl: './offer-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferPicker {
  readonly offers = input.required<readonly OfferView[]>();
  readonly channel = input.required<ScenarioChannel>();
  /** The chosen offer's id, or null for none. */
  readonly value = input<string | null>(null);
  /** An in-app step shows an offer and nothing else, so it cannot be left empty. */
  readonly required = input(false, { transform: booleanAttribute });

  readonly valueChange = output<string | null>();

  protected readonly i18n = inject(I18n);

  protected readonly options = computed<readonly PickerOption[]>(() => {
    const now = Date.now();
    const channel = this.channel();
    const chosen = this.value();
    const listed: PickerOption[] = this.offers()
      .filter((offer) => offerEligible(offer, channel, now))
      .map((offer) => ({
        offerId: offer.offerId,
        label: `${offer.displayName} · v${offer.versionNumber}`,
        inForce: true,
      }));
    if (chosen !== null && !listed.some((option) => option.offerId === chosen)) {
      const known = this.offers().find((offer) => offer.offerId === chosen);
      listed.push({
        offerId: chosen,
        label: known ? `${known.displayName} · v${known.versionNumber}` : chosen,
        inForce: false,
      });
    }
    return listed.sort((a, b) => a.label.localeCompare(b.label));
  });

  protected readonly selected = computed(
    () => this.offers().find((offer) => offer.offerId === this.value()) ?? null,
  );

  protected readonly selectedIsStale = computed(() => {
    const chosen = this.value();
    return chosen !== null && this.options().some((o) => o.offerId === chosen && !o.inForce);
  });

  protected pick(offerId: string): void {
    this.valueChange.emit(offerId === '' ? null : offerId);
  }

  protected window(offer: OfferView): string {
    const from = formatMoment(offer.validFrom);
    return offer.validUntil === null
      ? this.i18n.t('marketing.offerPicker.window.open', { from })
      : this.i18n.t('marketing.offerPicker.window.closed', {
          from,
          until: formatMoment(offer.validUntil),
        });
  }
}
