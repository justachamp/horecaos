import {
  OrderBoardColumnKey,
  OrderBoardDocument,
  ORDER_BOARD_COLUMN_KEYS,
  SavedView,
} from './preference-documents';

/**
 * Changing a person's documents, and holding what they type to the platform's shape (ADR 0160).
 *
 * Kept apart from `preference-documents.ts` on purpose: that module is what the store needs to read a
 * document (the vocabulary and the sanitizers) and ships with the shell; this one is what the order board,
 * the column picker and «Интерфейс» need to edit one, and loads with them.
 */

export const MAX_SAVED_VIEW_LABEL = 40;

/**
 * A run of this many digits (a space, a dot, a bracket or a hyphen between two does not break it) reads
 * as a phone number. The server's rule, mirrored so a person is told before the save, not after it.
 */
const PHONE_LIKE = /\+?\(?\d(?:[\s().-]{0,2}\d){8,}/u;

export type SavedViewLabelProblem = 'empty' | 'tooLong' | 'control' | 'number';

/** Why a label would be refused, or `null` when it is fine. The server is the authority; this is the early answer. */
export function savedViewLabelProblem(label: string): SavedViewLabelProblem | null {
  const stripped = label.trim();
  if (stripped.length === 0) {
    return 'empty';
  }
  if (Array.from(stripped).length > MAX_SAVED_VIEW_LABEL) {
    return 'tooLong';
  }
  // Control characters and the invisible format characters (zero-width joiners, bidi marks, the
  // line and paragraph separators).
  if (/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(stripped)) {
    return 'control';
  }
  if (PHONE_LIKE.test(stripped)) {
    return 'number';
  }
  return null;
}

/** The label as it is stored: its ends stripped. */
export function normalisedSavedViewLabel(label: string): string {
  return label.trim();
}

/** The default-shown, hideable columns and the addable ones, in the server's order (`OrderBoardColumn#shownByDefault`). */
export const DEFAULT_SHOWN_COLUMNS: readonly OrderBoardColumnKey[] = [
  'branch',
  'channel',
  'customer',
  'items',
  'payment',
  'courier',
];
export const ADDABLE_COLUMNS: readonly OrderBoardColumnKey[] = [
  'createdBy',
  'acceptedBy',
  'courierType',
  'discount',
  'courierEta',
  'source',
  'fiscal',
  'distance',
  'externalNumber',
];

// ---------------------------------------------------------------------------------------------
// The column choice, as a change from the default.
// ---------------------------------------------------------------------------------------------

/** Whether the board draws `key` for a person whose document is `document` (before the screen's own limits, e.g. «Филиал» outside «Все филиалы»). */
export function isColumnShown(document: OrderBoardDocument, key: OrderBoardColumnKey): boolean {
  if (DEFAULT_SHOWN_COLUMNS.includes(key)) {
    return !(document.columns?.hide ?? []).includes(key);
  }
  return (document.columns?.show ?? []).includes(key);
}

/**
 * The document with one column turned on or off, kept a change from the default: turning a default
 * column back on removes it from `hide`, turning an optional one off removes it from `show`, so a
 * document never says what the defaults already say (the server refuses one that does).
 */
export function withColumnShown(
  document: OrderBoardDocument,
  key: OrderBoardColumnKey,
  shown: boolean,
): OrderBoardDocument {
  const show = new Set(document.columns?.show ?? []);
  const hide = new Set(document.columns?.hide ?? []);
  if (DEFAULT_SHOWN_COLUMNS.includes(key)) {
    if (shown) {
      hide.delete(key);
    } else {
      hide.add(key);
    }
  } else if (shown) {
    show.add(key);
  } else {
    show.delete(key);
  }
  return withColumns(document, show, hide);
}

/** The document with its column choice cleared and everything else (the saved views) kept. */
export function withoutColumnChoice(document: OrderBoardDocument): OrderBoardDocument {
  return withColumns(document, new Set(), new Set());
}

function withColumns(
  document: OrderBoardDocument,
  show: ReadonlySet<OrderBoardColumnKey>,
  hide: ReadonlySet<OrderBoardColumnKey>,
): OrderBoardDocument {
  const inOrder = (keys: ReadonlySet<OrderBoardColumnKey>): OrderBoardColumnKey[] =>
    ORDER_BOARD_COLUMN_KEYS.filter((key) => keys.has(key));
  const shown = inOrder(show);
  const hidden = inOrder(hide);
  const { columns: _previous, ...rest } = document;
  void _previous;
  return shown.length === 0 && hidden.length === 0
    ? rest
    : {
        ...rest,
        columns: {
          ...(shown.length > 0 ? { show: shown } : {}),
          ...(hidden.length > 0 ? { hide: hidden } : {}),
        },
      };
}

/** The document with its saved views replaced (an empty list removes the key). */
export function withSavedViews(
  document: OrderBoardDocument,
  views: readonly SavedView[],
): OrderBoardDocument {
  const { savedViews: _previous, ...rest } = document;
  void _previous;
  return views.length === 0 ? rest : { ...rest, savedViews: views };
}
