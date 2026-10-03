/**
 * ADR 0137 — what a variant physically is, and the one form that authors it.
 *
 * Mirrors `CatalogAuthoringController.PhysicalAttributesRequest/Response` and
 * `catalog.domain.PhysicalAttributes`, whose constructor states the same rules
 * this file checks before a request leaves, so an operator is told which field
 * is wrong instead of receiving a refusal for the whole set. The server stays
 * the authority: a reason it names that this file did not anticipate still
 * reaches the screen ({@link fieldOfReason} returns `null` for it).
 */

/** `PhysicalAttributesResponse`. A variant with no row reads as version 0 with everything empty. */
export interface PhysicalAttributesView {
  readonly netWeightGrams?: number | null;
  readonly netVolumeMillilitres?: number | null;
  readonly catchweight: boolean;
  readonly catchweightQuantumGrams?: number | null;
  readonly catchweightNominalGrams?: number | null;
  readonly splittable: boolean;
  readonly portionSize?: number | null;
  readonly caloriesKcalPer100?: number | null;
  readonly proteinGramsPer100?: number | null;
  readonly fatGramsPer100?: number | null;
  readonly carbohydratesGramsPer100?: number | null;
  readonly version: number;
}

/**
 * `PhysicalAttributesRequest`: the whole set, written under `If-Match`. Every
 * optional number is sent explicitly (`null` clears it) and both booleans are
 * always sent — Jackson 3 refuses a primitive that is missing, and the server
 * reads a missing boxed one as false, which is how an unchecked box would
 * otherwise be lost.
 */
export interface PhysicalAttributesRequest {
  readonly netWeightGrams: number | null;
  readonly netVolumeMillilitres: number | null;
  readonly catchweight: boolean;
  readonly catchweightQuantumGrams: number | null;
  readonly catchweightNominalGrams: number | null;
  readonly splittable: boolean;
  readonly portionSize: number | null;
  readonly caloriesKcalPer100: number | null;
  readonly proteinGramsPer100: number | null;
  readonly fatGramsPer100: number | null;
  readonly carbohydratesGramsPer100: number | null;
}

/** A variant is weighed or measured by volume, never both — so the form asks which, once. */
export type PhysicalMeasure = 'NONE' | 'WEIGHT' | 'VOLUME';

/** What the operator typed, as text: a half-typed number is not yet a number. */
export interface PhysicalForm {
  readonly measure: PhysicalMeasure;
  readonly measureValue: string;
  readonly catchweight: boolean;
  readonly quantum: string;
  readonly nominal: string;
  readonly splittable: boolean;
  readonly portion: string;
  readonly calories: string;
  readonly protein: string;
  readonly fat: string;
  readonly carbs: string;
}

export const EMPTY_PHYSICAL_FORM: PhysicalForm = {
  measure: 'NONE',
  measureValue: '',
  catchweight: false,
  quantum: '',
  nominal: '',
  splittable: false,
  portion: '',
  calories: '',
  protein: '',
  fat: '',
  carbs: '',
};

/** The form's fields a refusal attaches to. */
export type PhysicalField =
  | 'measure'
  | 'catchweightQuantum'
  | 'catchweightNominal'
  | 'portion'
  | 'calories'
  | 'protein'
  | 'fat'
  | 'carbs';

/**
 * The stable codes `PhysicalAttributes.InvalidPhysicalAttributesException`
 * carries as `reason`, plus the one only a form can produce.
 */
export type PhysicalReason =
  | 'NOT_A_NUMBER'
  | 'NET_WEIGHT_NOT_POSITIVE'
  | 'NET_VOLUME_NOT_POSITIVE'
  | 'WEIGHT_AND_VOLUME_EXCLUSIVE'
  | 'CATCHWEIGHT_QUANTUM_NOT_POSITIVE'
  | 'CATCHWEIGHT_NOMINAL_NOT_POSITIVE'
  | 'CATCHWEIGHT_NEEDS_QUANTUM'
  | 'CATCHWEIGHT_NEEDS_WEIGHT'
  | 'CATCHWEIGHT_FIELDS_WITHOUT_CATCHWEIGHT'
  | 'PORTION_SIZE_NOT_POSITIVE'
  | 'PORTION_SIZE_TOO_PRECISE'
  | 'PORTION_SIZE_TOO_LARGE'
  | 'PORTION_SIZE_NEEDS_SPLITTABLE'
  | 'CALORIES_NEGATIVE'
  | 'CALORIES_TOO_LARGE'
  | 'PROTEIN_OUT_OF_RANGE'
  | 'FAT_OUT_OF_RANGE'
  | 'CARBOHYDRATES_OUT_OF_RANGE';

export type PhysicalErrors = Readonly<Partial<Record<PhysicalField, PhysicalReason>>>;

const FIELD_OF_REASON: Readonly<Record<PhysicalReason, PhysicalField>> = {
  NOT_A_NUMBER: 'measure',
  NET_WEIGHT_NOT_POSITIVE: 'measure',
  NET_VOLUME_NOT_POSITIVE: 'measure',
  WEIGHT_AND_VOLUME_EXCLUSIVE: 'measure',
  CATCHWEIGHT_QUANTUM_NOT_POSITIVE: 'catchweightQuantum',
  CATCHWEIGHT_NEEDS_QUANTUM: 'catchweightQuantum',
  CATCHWEIGHT_NOMINAL_NOT_POSITIVE: 'catchweightNominal',
  CATCHWEIGHT_NEEDS_WEIGHT: 'catchweightNominal',
  CATCHWEIGHT_FIELDS_WITHOUT_CATCHWEIGHT: 'catchweightQuantum',
  PORTION_SIZE_NOT_POSITIVE: 'portion',
  PORTION_SIZE_TOO_PRECISE: 'portion',
  PORTION_SIZE_TOO_LARGE: 'portion',
  PORTION_SIZE_NEEDS_SPLITTABLE: 'portion',
  CALORIES_NEGATIVE: 'calories',
  CALORIES_TOO_LARGE: 'calories',
  PROTEIN_OUT_OF_RANGE: 'protein',
  FAT_OUT_OF_RANGE: 'fat',
  CARBOHYDRATES_OUT_OF_RANGE: 'carbs',
};

/** The field a server `reason` belongs to, or `null` for a reason this build has not heard of. */
export function fieldOfReason(reason: string): PhysicalField | null {
  return Object.hasOwn(FIELD_OF_REASON, reason) ? FIELD_OF_REASON[reason as PhysicalReason] : null;
}

/** Whether a string from the server's `reason` is one this build can word. */
export function isPhysicalReason(reason: unknown): reason is PhysicalReason {
  return typeof reason === 'string' && Object.hasOwn(FIELD_OF_REASON, reason);
}

const INT32_MAX = 2_147_483_647;

/** The form a stored set opens on. */
export function formFromView(view: PhysicalAttributesView): PhysicalForm {
  const weight = view.netWeightGrams ?? null;
  const volume = view.netVolumeMillilitres ?? null;
  return {
    measure: weight !== null ? 'WEIGHT' : volume !== null ? 'VOLUME' : 'NONE',
    measureValue: weight !== null ? String(weight) : volume !== null ? String(volume) : '',
    catchweight: view.catchweight,
    quantum: text(view.catchweightQuantumGrams),
    nominal: text(view.catchweightNominalGrams),
    splittable: view.splittable,
    portion: text(view.portionSize),
    calories: text(view.caloriesKcalPer100),
    protein: text(view.proteinGramsPer100),
    fat: text(view.fatGramsPer100),
    carbs: text(view.carbohydratesGramsPer100),
  };
}

function text(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value);
}

/** What {@link parsePhysicalForm} makes of the form: the request it would send, and what is wrong with it. */
export interface ParsedPhysicalForm {
  readonly request: PhysicalAttributesRequest;
  readonly errors: PhysicalErrors;
}

/**
 * Reads the form the way the server will: a point or a comma for a decimal
 * (an operator on a Russian keyboard types `0,5`), a quantum or a nominal
 * weight only on a catchweight variant, a portion step only on a splittable
 * one. What does not apply is not sent, so unticking a box cannot leave a
 * stale figure behind it for the server to refuse.
 */
export function parsePhysicalForm(form: PhysicalForm): ParsedPhysicalForm {
  const errors: { -readonly [F in PhysicalField]?: PhysicalReason } = {};

  let netWeightGrams: number | null = null;
  let netVolumeMillilitres: number | null = null;
  if (form.measure !== 'NONE' && form.measureValue.trim() !== '') {
    const grams = wholePositive(form.measureValue);
    if (grams === null) {
      errors.measure =
        form.measure === 'WEIGHT' ? 'NET_WEIGHT_NOT_POSITIVE' : 'NET_VOLUME_NOT_POSITIVE';
    } else if (form.measure === 'WEIGHT') {
      netWeightGrams = grams;
    } else {
      netVolumeMillilitres = grams;
    }
  }

  let catchweightQuantumGrams: number | null = null;
  let catchweightNominalGrams: number | null = null;
  if (form.catchweight) {
    if (form.quantum.trim() === '') {
      errors.catchweightQuantum = 'CATCHWEIGHT_NEEDS_QUANTUM';
    } else {
      catchweightQuantumGrams = wholePositive(form.quantum);
      if (catchweightQuantumGrams === null) {
        errors.catchweightQuantum = 'CATCHWEIGHT_QUANTUM_NOT_POSITIVE';
      }
    }
    if (form.nominal.trim() !== '') {
      catchweightNominalGrams = wholePositive(form.nominal);
      if (catchweightNominalGrams === null) {
        errors.catchweightNominal = 'CATCHWEIGHT_NOMINAL_NOT_POSITIVE';
      }
    } else if (form.measure !== 'WEIGHT' || form.measureValue.trim() === '') {
      // The net weight stands in for the menu estimate; a volume cannot.
      errors.catchweightNominal = 'CATCHWEIGHT_NEEDS_WEIGHT';
    }
  }

  let portionSize: number | null = null;
  if (form.splittable && form.portion.trim() !== '') {
    const portion = decimal(form.portion);
    if (portion === null) {
      errors.portion = 'NOT_A_NUMBER';
    } else if (portion <= 0) {
      errors.portion = 'PORTION_SIZE_NOT_POSITIVE';
    } else if (fractionDigits(form.portion) > 3) {
      errors.portion = 'PORTION_SIZE_TOO_PRECISE';
    } else if (portion > 999) {
      errors.portion = 'PORTION_SIZE_TOO_LARGE';
    } else {
      portionSize = portion;
    }
  }

  const caloriesKcalPer100 = nutrient(form.calories, 'calories', errors);
  const proteinGramsPer100 = nutrient(form.protein, 'protein', errors);
  const fatGramsPer100 = nutrient(form.fat, 'fat', errors);
  const carbohydratesGramsPer100 = nutrient(form.carbs, 'carbs', errors);

  return {
    request: {
      netWeightGrams,
      netVolumeMillilitres,
      catchweight: form.catchweight,
      catchweightQuantumGrams,
      catchweightNominalGrams,
      splittable: form.splittable,
      portionSize,
      caloriesKcalPer100,
      proteinGramsPer100,
      fatGramsPer100,
      carbohydratesGramsPer100,
    },
    errors,
  };
}

function nutrient(
  raw: string,
  field: 'calories' | 'protein' | 'fat' | 'carbs',
  errors: { -readonly [F in PhysicalField]?: PhysicalReason },
): number | null {
  if (raw.trim() === '') {
    return null;
  }
  const value = decimal(raw);
  if (value === null) {
    errors[field] = 'NOT_A_NUMBER';
    return null;
  }
  if (field === 'calories') {
    if (value < 0) {
      errors.calories = 'CALORIES_NEGATIVE';
      return null;
    }
    if (value > 99999.9) {
      errors.calories = 'CALORIES_TOO_LARGE';
      return null;
    }
    return value;
  }
  // Grams per 100 g cannot exceed 100: a larger figure is a per-portion figure typed into the per-100 box.
  if (value < 0 || value > 100) {
    errors[field] =
      field === 'protein'
        ? 'PROTEIN_OUT_OF_RANGE'
        : field === 'fat'
          ? 'FAT_OUT_OF_RANGE'
          : 'CARBOHYDRATES_OUT_OF_RANGE';
    return null;
  }
  return value;
}

/** `350` → 350; anything else, including `1.5`, a sign and an int32 overflow → `null`. */
function wholePositive(raw: string): number | null {
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }
  const value = Number.parseInt(trimmed, 10);
  return value > 0 && value <= INT32_MAX ? value : null;
}

/** A point or a comma; `null` when it is not a number at all. */
function decimal(raw: string): number | null {
  const trimmed = raw.trim().replace(',', '.');
  if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return null;
  }
  return Number.parseFloat(trimmed);
}

/** Significant fraction digits: `0.250` has two, as the server strips trailing zeros before it counts. */
function fractionDigits(raw: string): number {
  const fraction = raw.trim().replace(',', '.').split('.')[1] ?? '';
  return fraction.replace(/0+$/, '').length;
}

/** No attribute is set, so the server stores no row — the same test `PhysicalAttributes.isEmpty` makes. */
export function isEmptyRequest(request: PhysicalAttributesRequest): boolean {
  return (
    request.netWeightGrams === null &&
    request.netVolumeMillilitres === null &&
    !request.catchweight &&
    request.catchweightQuantumGrams === null &&
    request.catchweightNominalGrams === null &&
    !request.splittable &&
    request.portionSize === null &&
    request.caloriesKcalPer100 === null &&
    request.proteinGramsPer100 === null &&
    request.fatGramsPer100 === null &&
    request.carbohydratesGramsPer100 === null
  );
}

/**
 * ADR 0038's sentence, restated: a marked good may be neither catchweight nor
 * splittable. The server checks it at publication
 * (`PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING`), not on this write, because
 * the fiscal classification it is checked against is authored on another tab —
 * so the form says so as soon as the two meet on screen.
 */
export function conflictsWithMarking(
  request: Pick<PhysicalAttributesRequest, 'catchweight' | 'splittable'>,
  markingRequired: boolean,
): boolean {
  return markingRequired && (request.catchweight || request.splittable);
}
