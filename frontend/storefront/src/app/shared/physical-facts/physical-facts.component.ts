import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import {
  catchweightEstimateGrams,
  formatQuantity,
  formatVolume,
  formatWeight,
  hasNutrition,
  nutritionPerUnit,
  unitPriceMinor,
  type PhysicalFacts,
} from '../../utils/physical';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * What a customer is told about a variant's physical nature (ADR 0137): how much it weighs or
 * holds, whether it is sold by the portion or by a weight only known at handover — and then its
 * price per quantum, what a unit is estimated at, and that the final weight and price are set at
 * handover — and its КБЖУ.
 *
 * Presentation only. The figures come from the published menu; a portion's КБЖУ is this
 * component's own computation from the per-100 figures and the variant's weight, never a second
 * stored value that could drift from the first. In its compact form (a menu card) it draws the
 * weight or volume alone.
 */
@Component({
  selector: 'app-physical-facts',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './physical-facts.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhysicalFactsComponent {
  private readonly translate = inject(TranslateService);
  private readonly lang = inject(LangService);

  readonly physical = input<PhysicalFacts | null>(null);
  /** The variant's price row: per unit, or per quantum for a weighed variant. */
  readonly priceMinor = input<number | null>(null);
  readonly compact = input(false);

  protected readonly measure = computed(() => {
    const p = this.physical();
    if (!p) {
      return null;
    }
    const lang = this.lang.langId();
    if (p.netWeightGrams) {
      return formatWeight(p.netWeightGrams, lang);
    }
    if (p.netVolumeMillilitres) {
      return formatVolume(p.netVolumeMillilitres, lang);
    }
    return null;
  });

  protected readonly portionStep = computed(() => {
    const p = this.physical();
    return p?.splittable && p.portionSize
      ? this.translate.getWithParams('physical.portionStep', {
          step: formatQuantity(p.portionSize, this.lang.langId()),
        })
      : null;
  });

  /** Present only for a weighed variant that has a price and something to estimate against. */
  protected readonly catchweight = computed(() => {
    this.translate.current();
    const p = this.physical();
    const price = this.priceMinor();
    const estimate = catchweightEstimateGrams(p);
    if (!p || price === null || estimate === null || !p.catchweightQuantumGrams) {
      return null;
    }
    const lang = this.lang.langId();
    return {
      perQuantum: this.translate.getWithParams('physical.pricePerQuantum', {
        price: this.formatPrice(price),
        quantum: formatWeight(p.catchweightQuantumGrams, lang),
      }),
      estimate: this.translate.getWithParams('physical.estimatedFor', {
        price: this.formatPrice(unitPriceMinor(price, p)),
        weight: formatWeight(estimate, lang),
      }),
    };
  });

  protected readonly nutrition = computed(() => {
    this.translate.current();
    const p = this.physical();
    if (!p || !hasNutrition(p)) {
      return null;
    }
    const per100 = p.nutrition ?? {};
    const unit = nutritionPerUnit(p);
    const lang = this.lang.langId();
    const volume = !p.netWeightGrams && !!p.netVolumeMillilitres;
    return {
      per100Label: this.translate.get(
        volume ? 'physical.nutritionPer100ml' : 'physical.nutritionPer100g',
      ),
      unitLabel: unit
        ? this.translate.getWithParams('physical.nutritionPerUnit', {
            amount:
              unit.basisLabel === 'WEIGHT'
                ? formatWeight(unit.amount, lang)
                : formatVolume(unit.amount, lang),
          })
        : null,
      rows: [
        {
          id: 'calories',
          label: this.translate.get('physical.calories'),
          unitSuffix: this.translate.get('physical.kcal'),
          per100: per100.caloriesKcalPer100 ?? null,
          perUnit: unit?.caloriesKcal ?? null,
        },
        {
          id: 'protein',
          label: this.translate.get('physical.proteins'),
          unitSuffix: '',
          per100: per100.proteinGramsPer100 ?? null,
          perUnit: unit?.proteinGrams ?? null,
        },
        {
          id: 'fat',
          label: this.translate.get('physical.fats'),
          unitSuffix: '',
          per100: per100.fatGramsPer100 ?? null,
          perUnit: unit?.fatGrams ?? null,
        },
        {
          id: 'carbs',
          label: this.translate.get('physical.carbs'),
          unitSuffix: '',
          per100: per100.carbohydratesGramsPer100 ?? null,
          perUnit: unit?.carbohydratesGrams ?? null,
        },
      ].filter((row) => row.per100 !== null),
    };
  });

  protected figure(value: number | null, suffix: string): string {
    if (value === null) {
      return '—';
    }
    const text = formatQuantity(value, this.lang.langId());
    return suffix ? `${text} ${suffix}` : text;
  }

  private formatPrice(minor: number): string {
    const currency = this.translate.get('common.currency') || "so'm";
    return `${minor.toLocaleString('uz-UZ')} ${currency}`;
  }
}
