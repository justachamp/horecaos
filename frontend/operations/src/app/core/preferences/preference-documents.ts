/**
 * The documents a person's own interface preferences are stored as (ADR 0160) -- the console's side of
 * the closed vocabulary `StaffPreferenceSchemas` validates on the server.
 *
 * Every list below exists twice, in Java and here, and the two are held together by a spec that reads
 * the OpenAPI client the platform generates (`preference-vocabulary.spec.ts`): a column the server
 * accepts that the console does not know, or the other way round, fails the build instead of reaching a
 * person as an empty cell or a refused save.
 *
 * A document stores a **change from the default** and nothing else. `columns.show` lists the optional
 * columns a person turned on, `columns.hide` the default ones they turned off; a column a later release
 * adds to the defaults is therefore not hidden by an old choice, and one it adds to the optional set is
 * not shown by one.
 *
 * What a saved view may hold is deliberately narrow: never a search term, a customer, an order, a
 * courier, a marketplace binding or an absolute date. Its label is the one thing a person types; it is
 * stored protected, and {@link validSavedViewLabel} holds it to the shape the server holds it to.
 */

/** The closed set of screen families a document is stored for (`StaffPreferenceSurface`). */
export const PREFERENCE_SURFACES = ['SHELL', 'ORDER_BOARD'] as const;
export type PreferenceSurface = (typeof PREFERENCE_SURFACES)[number];

/** `OrderBoardColumn`: the order board's columns a document may name, in the server's order. */
export const ORDER_BOARD_COLUMN_KEYS = [
  'branch',
  'channel',
  'customer',
  'items',
  'payment',
  'courier',
  'createdBy',
  'acceptedBy',
  'courierType',
  'discount',
] as const;
export type OrderBoardColumnKey = (typeof ORDER_BOARD_COLUMN_KEYS)[number];

/** The board's seven tabs (`OrderBoardTab`), as `/orders?tab=` spells them. */
export const SAVED_VIEW_TABS = [
  'attention',
  'new',
  'preparing',
  'delivering',
  'completed',
  'cancelled',
  'all',
] as const;
export type SavedViewTab = (typeof SAVED_VIEW_TABS)[number];

/** `SavedViewDateRange`: a period relative to today, never an absolute date. */
export const SAVED_VIEW_DATE_RANGES = ['TODAY', 'YESTERDAY', 'LAST_7_DAYS', 'THIS_MONTH'] as const;
export type SavedViewDateRange = (typeof SAVED_VIEW_DATE_RANGES)[number];

/** The filters a saved view may carry. A false switch is the same as an absent one and is not stored. */
export interface SavedViewFilters {
  readonly allBranches?: boolean;
  readonly channelCode?: string;
  readonly origin?: 'HORECAOS' | 'MARKETPLACE';
  readonly fulfillmentMode?: 'DELIVERY' | 'PICKUP' | 'DINE_IN';
  readonly paymentMethodCode?: string;
  readonly paymentStatus?: string;
  readonly fiscalStatus?: string;
  readonly lateOnly?: boolean;
  readonly problemOnly?: boolean;
  readonly callbackRequested?: boolean;
  readonly mineOnly?: boolean;
  readonly dateRange?: SavedViewDateRange;
}

export interface SavedView {
  readonly id: string;
  readonly label: string;
  readonly tab: SavedViewTab;
  readonly filters: SavedViewFilters;
}

/** `ORDER_BOARD`: optional columns, as a change from the default, and the person's saved views. */
export interface OrderBoardDocument {
  readonly columns?: {
    readonly show?: readonly OrderBoardColumnKey[];
    readonly hide?: readonly OrderBoardColumnKey[];
  };
  readonly savedViews?: readonly SavedView[];
}

/** `SHELL`: the person's default branch. */
export interface ShellDocument {
  readonly defaultLocationId?: string | null;
}

export interface PreferenceDocuments {
  readonly SHELL: ShellDocument;
  readonly ORDER_BOARD: OrderBoardDocument;
}

/** One surface as `GET .../staff/me/preferences` and `PUT .../{surface}` answer it. */
export interface StoredPreference<S extends PreferenceSurface = PreferenceSurface> {
  readonly document: PreferenceDocuments[S];
  readonly documentSchema: number;
  readonly version: number;
}

export interface PreferencesResponse {
  readonly surfaces: { readonly [S in PreferenceSurface]?: StoredPreference<S> };
}

/** The body of `PUT .../{surface}`. */
export interface SavePreferenceRequest<S extends PreferenceSurface> {
  readonly document: PreferenceDocuments[S];
}

/** The most saved views one board document may hold (the platform's cap). */
export const MAX_SAVED_VIEWS = 20;

export const EMPTY_ORDER_BOARD_DOCUMENT: OrderBoardDocument = {};
export const EMPTY_SHELL_DOCUMENT: ShellDocument = {};

// ---------------------------------------------------------------------------------------------
// Reading what came from outside this release: the server's answer and the browser's mirror.
// ---------------------------------------------------------------------------------------------

const COLUMN_KEYS: ReadonlySet<string> = new Set(ORDER_BOARD_COLUMN_KEYS);
const TAB_IDS: ReadonlySet<string> = new Set(SAVED_VIEW_TABS);
const DATE_RANGES: ReadonlySet<string> = new Set(SAVED_VIEW_DATE_RANGES);
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function columnList(raw: unknown): readonly OrderBoardColumnKey[] | undefined {
  if (!Array.isArray(raw)) {
    return undefined;
  }
  const keys = raw.filter(
    (entry): entry is OrderBoardColumnKey => typeof entry === 'string' && COLUMN_KEYS.has(entry),
  );
  return keys.length > 0 ? [...new Set(keys)] : undefined;
}

function stringOf(raw: unknown): string | undefined {
  return typeof raw === 'string' && raw.length > 0 ? raw : undefined;
}

function filtersOf(raw: unknown): SavedViewFilters {
  if (!isRecord(raw)) {
    return {};
  }
  const filters: { -readonly [K in keyof SavedViewFilters]: SavedViewFilters[K] } = {};
  for (const name of [
    'allBranches',
    'lateOnly',
    'problemOnly',
    'callbackRequested',
    'mineOnly',
  ] as const) {
    if (raw[name] === true) {
      filters[name] = true;
    }
  }
  const channelCode = stringOf(raw['channelCode']);
  if (channelCode !== undefined) {
    filters.channelCode = channelCode;
  }
  const paymentMethodCode = stringOf(raw['paymentMethodCode']);
  if (paymentMethodCode !== undefined) {
    filters.paymentMethodCode = paymentMethodCode;
  }
  const paymentStatus = stringOf(raw['paymentStatus']);
  if (paymentStatus !== undefined) {
    filters.paymentStatus = paymentStatus;
  }
  const fiscalStatus = stringOf(raw['fiscalStatus']);
  if (fiscalStatus !== undefined) {
    filters.fiscalStatus = fiscalStatus;
  }
  if (raw['origin'] === 'HORECAOS' || raw['origin'] === 'MARKETPLACE') {
    filters.origin = raw['origin'];
  }
  if (
    raw['fulfillmentMode'] === 'DELIVERY' ||
    raw['fulfillmentMode'] === 'PICKUP' ||
    raw['fulfillmentMode'] === 'DINE_IN'
  ) {
    filters.fulfillmentMode = raw['fulfillmentMode'];
  }
  if (typeof raw['dateRange'] === 'string' && DATE_RANGES.has(raw['dateRange'])) {
    filters.dateRange = raw['dateRange'] as SavedViewDateRange;
  }
  return filters;
}

/**
 * A document from the server or from the browser's mirror, read down to what this release knows. A
 * column key or a tab it does not know (a newer release wrote it) is dropped rather than trusted, the same
 * defensive read `CurrentLocation` and `I18n` give a stored value; what remains is always valid to send
 * back.
 */
export function sanitizeOrderBoardDocument(raw: unknown): OrderBoardDocument {
  if (!isRecord(raw)) {
    return EMPTY_ORDER_BOARD_DOCUMENT;
  }
  let columns: OrderBoardDocument['columns'];
  if (isRecord(raw['columns'])) {
    const show = columnList(raw['columns']['show']);
    const hide = columnList(raw['columns']['hide']);
    if (show !== undefined || hide !== undefined) {
      columns = { ...(show ? { show } : {}), ...(hide ? { hide } : {}) };
    }
  }
  const views: SavedView[] = [];
  if (Array.isArray(raw['savedViews'])) {
    for (const entry of raw['savedViews']) {
      if (
        isRecord(entry) &&
        typeof entry['id'] === 'string' &&
        UUID_SHAPE.test(entry['id']) &&
        typeof entry['label'] === 'string' &&
        typeof entry['tab'] === 'string' &&
        TAB_IDS.has(entry['tab']) &&
        views.length < MAX_SAVED_VIEWS &&
        !views.some((view) => view.id === entry['id'])
      ) {
        views.push({
          id: entry['id'],
          label: entry['label'],
          tab: entry['tab'] as SavedViewTab,
          filters: filtersOf(entry['filters']),
        });
      }
    }
  }
  return {
    ...(columns ? { columns } : {}),
    ...(views.length > 0 ? { savedViews: views } : {}),
  };
}

export function sanitizeShellDocument(raw: unknown): ShellDocument {
  if (
    isRecord(raw) &&
    typeof raw['defaultLocationId'] === 'string' &&
    UUID_SHAPE.test(raw['defaultLocationId'])
  ) {
    return { defaultLocationId: raw['defaultLocationId'] };
  }
  return EMPTY_SHELL_DOCUMENT;
}
