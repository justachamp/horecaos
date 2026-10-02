import { TestBed } from '@angular/core/testing';

import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import type { PhysicalFacts } from '../../utils/physical';
import { PhysicalFactsComponent } from './physical-facts.component';

const NBSP = ' ';

class FakeTranslateService {
  get(key: string): string {
    return key === 'common.currency' ? "so'm" : key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeLangService {
  langId = () => 'en';
}

function render(inputs: {
  physical: PhysicalFacts | null;
  priceMinor?: number | null;
  compact?: boolean;
}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: TranslateService, useValue: new FakeTranslateService() },
      { provide: LangService, useValue: new FakeLangService() },
    ],
  });
  const fixture = TestBed.createComponent(PhysicalFactsComponent);
  fixture.componentRef.setInput('physical', inputs.physical);
  fixture.componentRef.setInput('priceMinor', inputs.priceMinor ?? null);
  fixture.componentRef.setInput('compact', inputs.compact ?? false);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const text = (id: string) => host.querySelector(`[data-testid="${id}"]`)?.textContent?.trim();
  return { fixture, host, text };
}

describe('PhysicalFactsComponent', () => {
  it('draws nothing for a fixed unit sold whole', () => {
    const { host } = render({ physical: null });

    expect(host.querySelector('[data-testid="physical-facts"]')).toBeNull();
  });

  it('shows a weight or a volume as the customer reads it', () => {
    expect(
      render({ physical: { catchweight: false, splittable: false, netWeightGrams: 350 } }).text(
        'physical-measure',
      ),
    ).toBe(`350${NBSP}g`);
    TestBed.resetTestingModule();
    expect(
      render({
        physical: { catchweight: false, splittable: false, netVolumeMillilitres: 1500 },
      }).text('physical-measure'),
    ).toBe(`1.5${NBSP}l`);
  });

  it('shows nutrition per 100 g and, when the weight is known, per serving', () => {
    const { host, text } = render({
      physical: {
        catchweight: false,
        splittable: false,
        netWeightGrams: 350,
        nutrition: {
          caloriesKcalPer100: 215,
          proteinGramsPer100: 8,
          fatGramsPer100: 10,
          carbohydratesGramsPer100: 24,
        },
      },
    });

    expect(host.querySelector('[data-testid="physical-nutrition"]')).not.toBeNull();
    expect(text('physical-nutrition-calories')).toContain('215');
    expect(text('physical-nutrition-calories')).toContain('753');
    expect(text('physical-nutrition-protein')).toContain('8');
    expect(text('physical-nutrition-protein')).toContain('28');
    expect(host.textContent).toContain('physical.nutritionPer100g');
  });

  it('shows per 100 g alone when there is no weight to scale by', () => {
    const { host, text } = render({
      physical: {
        catchweight: false,
        splittable: false,
        nutrition: { caloriesKcalPer100: 215 },
      },
    });

    expect(text('physical-nutrition-calories')).toContain('215');
    expect(host.textContent).not.toContain('physical.nutritionPerUnit');
  });

  it('labels the figures per 100 ml for a volume-measured variant', () => {
    const { host } = render({
      physical: {
        catchweight: false,
        splittable: false,
        netVolumeMillilitres: 500,
        nutrition: { caloriesKcalPer100: 42 },
      },
    });

    expect(host.textContent).toContain('physical.nutritionPer100ml');
    expect(host.textContent).not.toContain('physical.nutritionPer100g');
  });

  it('shows no nutrition block when the author entered none', () => {
    const { host } = render({
      physical: { catchweight: false, splittable: false, netWeightGrams: 350 },
    });

    expect(host.querySelector('[data-testid="physical-nutrition"]')).toBeNull();
  });

  it('prices a weighed variant per quantum, estimates it at the nominal weight and says the final weight is set at handover', () => {
    const { text } = render({
      priceMinor: 15_000,
      physical: {
        catchweight: true,
        catchweightQuantumGrams: 100,
        catchweightNominalGrams: 1_200,
        splittable: false,
      },
    });

    expect(text('physical-price-per-quantum')).toContain('physical.pricePerQuantum');
    expect(text('physical-price-per-quantum')).toContain(`100${NBSP}g`);
    expect(text('physical-estimate')).toContain(`1.2${NBSP}kg`);
    expect(text('physical-final-weight-notice')).toBe('physical.finalWeightNotice');
  });

  it('says nothing about a quantum or a final weight for a variant that is not weighed', () => {
    const { host } = render({
      priceMinor: 15_000,
      physical: { catchweight: false, splittable: false, netWeightGrams: 350 },
    });

    expect(host.querySelector('[data-testid="physical-price-per-quantum"]')).toBeNull();
    expect(host.querySelector('[data-testid="physical-final-weight-notice"]')).toBeNull();
  });

  it('says a splittable variant is ordered in portions', () => {
    const { text } = render({
      physical: { catchweight: false, splittable: true, portionSize: 0.5 },
    });

    expect(text('physical-portion-step')).toContain('0.5');
  });

  it('in compact form draws only the weight or volume — the card has its own price and no room for the rest', () => {
    const { host, text } = render({
      compact: true,
      priceMinor: 15_000,
      physical: {
        catchweight: true,
        catchweightQuantumGrams: 100,
        catchweightNominalGrams: 1_200,
        splittable: false,
        netWeightGrams: 1_200,
        nutrition: { caloriesKcalPer100: 300 },
      },
    });

    expect(text('physical-measure')).toBe(`1.2${NBSP}kg`);
    expect(host.querySelector('[data-testid="physical-nutrition"]')).toBeNull();
    expect(host.querySelector('[data-testid="physical-estimate"]')).toBeNull();
    expect(host.querySelector('[data-testid="physical-final-weight-notice"]')).toBeNull();
  });
});
