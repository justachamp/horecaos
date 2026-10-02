import {
  catchweightEstimateGrams,
  formatQuantity,
  formatVolume,
  formatWeight,
  initialQuantity,
  lineAmountMinor,
  nutritionPerUnit,
  portionStep,
  unitPriceMinor,
  type PhysicalFacts,
} from './physical';

const NBSP = ' ';

const CAKE: PhysicalFacts = {
  catchweight: true,
  catchweightQuantumGrams: 100,
  catchweightNominalGrams: 1_200,
  splittable: false,
};

describe('formatQuantity', () => {
  it('writes a whole quantity without decimals and a portion with the language’s decimal mark', () => {
    expect(formatQuantity(2, 'ru')).toBe('2');
    expect(formatQuantity(0.5, 'ru')).toBe('0,5');
    expect(formatQuantity(0.5, 'uz')).toBe('0,5');
    expect(formatQuantity(0.5, 'en')).toBe('0.5');
    expect(formatQuantity(1.25, 'ru')).toBe('1,25');
  });

  it('shows no floating-point noise', () => {
    expect(formatQuantity(0.1 + 0.2, 'en')).toBe('0.3');
    expect(formatQuantity(2.9996, 'en')).toBe('3');
  });
});

describe('formatWeight and formatVolume', () => {
  it('writes grams below a kilogram and kilograms from there', () => {
    expect(formatWeight(350, 'ru')).toBe(`350${NBSP}г`);
    expect(formatWeight(350, 'uz')).toBe(`350${NBSP}g`);
    expect(formatWeight(1340, 'ru')).toBe(`1,34${NBSP}кг`);
    expect(formatWeight(1340, 'en')).toBe(`1.34${NBSP}kg`);
    expect(formatWeight(1000, 'en')).toBe(`1${NBSP}kg`);
  });

  it('writes millilitres below a litre and litres from there', () => {
    expect(formatVolume(330, 'ru')).toBe(`330${NBSP}мл`);
    expect(formatVolume(1500, 'ru')).toBe(`1,5${NBSP}л`);
    expect(formatVolume(1500, 'uz')).toBe(`1,5${NBSP}l`);
  });
});

describe('portionStep and initialQuantity', () => {
  it('orders in whole units unless the variant is splittable with a portion size', () => {
    expect(portionStep(null)).toBe(1);
    expect(portionStep(undefined)).toBe(1);
    expect(portionStep({ catchweight: false, splittable: true, portionSize: 0.5 })).toBe(0.5);
    expect(portionStep({ catchweight: false, splittable: true, portionSize: null })).toBe(1);
    expect(portionStep({ catchweight: false, splittable: false, portionSize: 0.5 })).toBe(1);
  });

  it('starts at one whole portion, or at the first whole multiple of a step that does not divide it', () => {
    expect(initialQuantity(1)).toBe(1);
    expect(initialQuantity(0.5)).toBe(1);
    expect(initialQuantity(0.3)).toBe(1.2);
    expect(initialQuantity(1.5)).toBe(1.5);
  });
});

describe('pricing a portion or a weighed item', () => {
  it('is the price row times the quantity for a plain variant, exact for whole quantities', () => {
    expect(lineAmountMinor(38_000, 3, null)).toBe(114_000);
    expect(lineAmountMinor(38_000, 0.5, null)).toBe(19_000);
    expect(lineAmountMinor(18_001, 0.5, null)).toBe(9_001); // 9000.5 rounds half up
    expect(lineAmountMinor(15_000, 1.2, null)).toBe(18_000);
  });

  it('prices a weighed item per quantum at its nominal weight until it is weighed', () => {
    expect(unitPriceMinor(15_000, CAKE)).toBe(180_000);
    expect(lineAmountMinor(15_000, 1, CAKE)).toBe(180_000);
    expect(lineAmountMinor(15_000, 2, CAKE)).toBe(360_000);
  });

  it('uses the net weight when no estimate was authored', () => {
    const noNominal: PhysicalFacts = {
      catchweight: true,
      catchweightQuantumGrams: 100,
      netWeightGrams: 800,
      splittable: false,
    };

    expect(catchweightEstimateGrams(noNominal)).toBe(800);
    expect(unitPriceMinor(15_000, noNominal)).toBe(120_000);
  });

  it('a variant that is not sold by weight has no weighed estimate', () => {
    expect(catchweightEstimateGrams(null)).toBeNull();
    expect(
      catchweightEstimateGrams({ catchweight: false, splittable: false, netWeightGrams: 300 }),
    ).toBeNull();
  });

  it('rounds once, half up, and not per gram', () => {
    expect(lineAmountMinor(15_001, 1, { ...CAKE, catchweightNominalGrams: 1_250 })).toBe(187_513);
  });
});

describe('nutritionPerUnit', () => {
  it('scales per-100 figures to the variant’s own weight', () => {
    const plov: PhysicalFacts = {
      catchweight: false,
      splittable: false,
      netWeightGrams: 350,
      nutrition: {
        caloriesKcalPer100: 215,
        proteinGramsPer100: 8,
        fatGramsPer100: 10,
        carbohydratesGramsPer100: 24,
      },
    };

    expect(nutritionPerUnit(plov)).toEqual({
      basisLabel: 'WEIGHT',
      amount: 350,
      caloriesKcal: 753,
      proteinGrams: 28,
      fatGrams: 35,
      carbohydratesGrams: 84,
    });
  });

  it('scales by volume for a volume-measured variant', () => {
    const cola: PhysicalFacts = {
      catchweight: false,
      splittable: false,
      netVolumeMillilitres: 500,
      nutrition: { caloriesKcalPer100: 42 },
    };

    const result = nutritionPerUnit(cola);

    expect(result?.basisLabel).toBe('VOLUME');
    expect(result?.caloriesKcal).toBe(210);
    expect(result?.proteinGrams).toBeNull();
  });

  it('is absent without a figure, or without a weight to scale by', () => {
    expect(nutritionPerUnit(null)).toBeNull();
    expect(
      nutritionPerUnit({ catchweight: false, splittable: false, netWeightGrams: 300 }),
    ).toBeNull();
    expect(
      nutritionPerUnit({
        catchweight: false,
        splittable: false,
        nutrition: { caloriesKcalPer100: 100 },
      }),
    ).toBeNull();
  });
});
