import { describe, expect, it } from 'vitest';

import {
  EMPTY_PHYSICAL_FORM,
  PhysicalAttributesView,
  PhysicalForm,
  conflictsWithMarking,
  fieldOfReason,
  formFromView,
  isEmptyRequest,
  parsePhysicalForm,
} from './physical-attributes';

function form(overrides: Partial<PhysicalForm> = {}): PhysicalForm {
  return { ...EMPTY_PHYSICAL_FORM, ...overrides };
}

function view(overrides: Partial<PhysicalAttributesView> = {}): PhysicalAttributesView {
  return { catchweight: false, splittable: false, version: 0, ...overrides };
}

describe('parsePhysicalForm', () => {
  it('an empty form is a valid, empty request: the row is cleared, never stored full of nulls', () => {
    const { request, errors } = parsePhysicalForm(form());

    expect(errors).toEqual({});
    expect(isEmptyRequest(request)).toBe(true);
    expect(request).toEqual({
      netWeightGrams: null,
      netVolumeMillilitres: null,
      catchweight: false,
      catchweightQuantumGrams: null,
      catchweightNominalGrams: null,
      splittable: false,
      portionSize: null,
      caloriesKcalPer100: null,
      proteinGramsPer100: null,
      fatGramsPer100: null,
      carbohydratesGramsPer100: null,
    });
  });

  it('a weight and a volume are exclusive: the measure chosen decides which is sent', () => {
    expect(
      parsePhysicalForm(form({ measure: 'WEIGHT', measureValue: '350' })).request,
    ).toMatchObject({ netWeightGrams: 350, netVolumeMillilitres: null });
    expect(
      parsePhysicalForm(form({ measure: 'VOLUME', measureValue: '330' })).request,
    ).toMatchObject({ netWeightGrams: null, netVolumeMillilitres: 330 });
    expect(parsePhysicalForm(form({ measure: 'NONE', measureValue: '999' })).request).toMatchObject(
      { netWeightGrams: null, netVolumeMillilitres: null },
    );
  });

  it('a measure must be a whole number above zero', () => {
    for (const bad of ['0', '-5', '1.5', 'abc', '3000000000']) {
      expect(parsePhysicalForm(form({ measure: 'WEIGHT', measureValue: bad })).errors, bad).toEqual(
        { measure: 'NET_WEIGHT_NOT_POSITIVE' },
      );
    }
    expect(parsePhysicalForm(form({ measure: 'VOLUME', measureValue: '0' })).errors).toEqual({
      measure: 'NET_VOLUME_NOT_POSITIVE',
    });
  });

  it('a catchweight variant needs the quantum its price is quoted per, and something to quote against', () => {
    expect(parsePhysicalForm(form({ catchweight: true })).errors).toEqual({
      catchweightQuantum: 'CATCHWEIGHT_NEEDS_QUANTUM',
      catchweightNominal: 'CATCHWEIGHT_NEEDS_WEIGHT',
    });
    expect(parsePhysicalForm(form({ catchweight: true, quantum: '100' })).errors).toEqual({
      catchweightNominal: 'CATCHWEIGHT_NEEDS_WEIGHT',
    });
  });

  it('a net weight stands in for the nominal weight, as the server accepts it', () => {
    const result = parsePhysicalForm(
      form({ catchweight: true, quantum: '100', measure: 'WEIGHT', measureValue: '1200' }),
    );

    expect(result.errors).toEqual({});
    expect(result.request).toMatchObject({
      catchweight: true,
      catchweightQuantumGrams: 100,
      catchweightNominalGrams: null,
      netWeightGrams: 1200,
    });
  });

  it('a volume does not stand in for a weight: a catchweight drink needs its nominal weight', () => {
    expect(
      parsePhysicalForm(
        form({ catchweight: true, quantum: '100', measure: 'VOLUME', measureValue: '500' }),
      ).errors,
    ).toEqual({ catchweightNominal: 'CATCHWEIGHT_NEEDS_WEIGHT' });
  });

  it('a quantum and a nominal weight are dropped when the variant is not catchweight', () => {
    const { request, errors } = parsePhysicalForm(
      form({ catchweight: false, quantum: '100', nominal: '1200' }),
    );

    expect(errors).toEqual({});
    expect(request.catchweightQuantumGrams).toBeNull();
    expect(request.catchweightNominalGrams).toBeNull();
  });

  it('a portion size is read with a comma or a point, and is a plain number on the wire', () => {
    expect(parsePhysicalForm(form({ splittable: true, portion: '0,5' })).request.portionSize).toBe(
      0.5,
    );
    expect(
      parsePhysicalForm(form({ splittable: true, portion: '0.250' })).request.portionSize,
    ).toBe(0.25);
  });

  it('a portion size is positive, at most three decimals and at most 999', () => {
    const errorOf = (portion: string) =>
      parsePhysicalForm(form({ splittable: true, portion })).errors;

    expect(errorOf('0')).toEqual({ portion: 'PORTION_SIZE_NOT_POSITIVE' });
    expect(errorOf('-1')).toEqual({ portion: 'PORTION_SIZE_NOT_POSITIVE' });
    expect(errorOf('0.0001')).toEqual({ portion: 'PORTION_SIZE_TOO_PRECISE' });
    expect(errorOf('1000')).toEqual({ portion: 'PORTION_SIZE_TOO_LARGE' });
    expect(errorOf('999')).toEqual({});
    expect(errorOf('x')).toEqual({ portion: 'NOT_A_NUMBER' });
  });

  it('a portion size is dropped when the variant is not splittable', () => {
    const { request, errors } = parsePhysicalForm(form({ splittable: false, portion: '0.5' }));

    expect(errors).toEqual({});
    expect(request.portionSize).toBeNull();
  });

  it('КБЖУ is range-checked the way the server does: calories to 99999.9, grams to 100', () => {
    expect(parsePhysicalForm(form({ calories: '-1' })).errors).toEqual({
      calories: 'CALORIES_NEGATIVE',
    });
    expect(parsePhysicalForm(form({ calories: '100000' })).errors).toEqual({
      calories: 'CALORIES_TOO_LARGE',
    });
    expect(parsePhysicalForm(form({ protein: '100.1' })).errors).toEqual({
      protein: 'PROTEIN_OUT_OF_RANGE',
    });
    expect(parsePhysicalForm(form({ fat: '-0.1' })).errors).toEqual({ fat: 'FAT_OUT_OF_RANGE' });
    expect(parsePhysicalForm(form({ carbs: '101' })).errors).toEqual({
      carbs: 'CARBOHYDRATES_OUT_OF_RANGE',
    });
    const ok = parsePhysicalForm(
      form({ calories: '215,5', protein: '8.2', fat: '10', carbs: '100' }),
    );
    expect(ok.errors).toEqual({});
    expect(ok.request).toMatchObject({
      caloriesKcalPer100: 215.5,
      proteinGramsPer100: 8.2,
      fatGramsPer100: 10,
      carbohydratesGramsPer100: 100,
    });
  });

  it('a zero КБЖУ figure is a real figure, not an absent one', () => {
    const { request } = parsePhysicalForm(form({ calories: '0' }));

    expect(request.caloriesKcalPer100).toBe(0);
    expect(isEmptyRequest(request)).toBe(false);
  });
});

describe('formFromView', () => {
  it('a variant with no row reads back as the empty form', () => {
    expect(formFromView(view())).toEqual(EMPTY_PHYSICAL_FORM);
  });

  it('round-trips a full set through the form and back to the same request', () => {
    const full = view({
      netWeightGrams: 1200,
      catchweight: true,
      catchweightQuantumGrams: 100,
      catchweightNominalGrams: 1250,
      splittable: true,
      portionSize: 0.5,
      caloriesKcalPer100: 215.5,
      proteinGramsPer100: 8.2,
      fatGramsPer100: 10,
      carbohydratesGramsPer100: 31,
      version: 3,
    });

    const { request, errors } = parsePhysicalForm(formFromView(full));

    expect(errors).toEqual({});
    expect(request).toEqual({
      netWeightGrams: 1200,
      netVolumeMillilitres: null,
      catchweight: true,
      catchweightQuantumGrams: 100,
      catchweightNominalGrams: 1250,
      splittable: true,
      portionSize: 0.5,
      caloriesKcalPer100: 215.5,
      proteinGramsPer100: 8.2,
      fatGramsPer100: 10,
      carbohydratesGramsPer100: 31,
    });
  });

  it('a volume-measured variant opens on the volume measure', () => {
    expect(formFromView(view({ netVolumeMillilitres: 330 }))).toMatchObject({
      measure: 'VOLUME',
      measureValue: '330',
    });
  });
});

describe('conflictsWithMarking', () => {
  it('a marked good may be neither catchweight nor splittable (ADR 0038)', () => {
    const plain = parsePhysicalForm(form()).request;
    const catchweight = parsePhysicalForm(
      form({ catchweight: true, quantum: '100', nominal: '500' }),
    ).request;
    const splittable = parsePhysicalForm(form({ splittable: true })).request;

    expect(conflictsWithMarking(plain, true)).toBe(false);
    expect(conflictsWithMarking(catchweight, true)).toBe(true);
    expect(conflictsWithMarking(splittable, true)).toBe(true);
    expect(conflictsWithMarking(catchweight, false)).toBe(false);
  });
});

describe('fieldOfReason', () => {
  it('puts a server refusal on the field it names', () => {
    expect(fieldOfReason('PROTEIN_OUT_OF_RANGE')).toBe('protein');
    expect(fieldOfReason('CATCHWEIGHT_NEEDS_QUANTUM')).toBe('catchweightQuantum');
    expect(fieldOfReason('PORTION_SIZE_NEEDS_SPLITTABLE')).toBe('portion');
    expect(fieldOfReason('WEIGHT_AND_VOLUME_EXCLUSIVE')).toBe('measure');
    expect(fieldOfReason('SOMETHING_NEW')).toBeNull();
  });
});
