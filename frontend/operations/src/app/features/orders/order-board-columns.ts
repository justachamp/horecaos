import { MessageKey } from '../../core/i18n/messages.en';
import {
  OrderBoardColumnKey,
  OrderBoardDocument,
  ORDER_BOARD_COLUMN_KEYS,
} from '../../core/preferences/preference-documents';
import { isColumnShown } from '../../core/preferences/preference-edits';
import { OrderSummaryResponse } from './order-summary';

/**
 * Where the board's optional columns come from (ADR 0160, gap map row `1.1`; orders.md §2.5's
 * «column picker, persisted per user»).
 *
 * **One registry, typed twice.** `satisfies Record<OrderBoardColumnKey, …>` makes a key the platform's
 * vocabulary has and this table lacks a compile error, and `reads` is a `keyof OrderSummaryResponse`, so a
 * column whose field the response lacks does not build either. `preference-vocabulary.spec.ts` closes the
 * loop on the other side by reading the platform's generated client.
 *
 * **A column is offered only when the board read gives its cell something to draw**, so the picker cannot
 * hand a person a column that renders empty:
 *
 *  - «Создал» and «Принял» read `createdByDisplayName` and `acceptedByDisplayName`, which the board read
 *    now resolves with one `StaffDirectory#namesOf` batch per page. The raw subject is never drawn: a
 *    subject the tenant has no name for, and an actor that is not a person, draw «—».
 *  - «Тип курьера» reads `courierId` against the courier roster the board already fetches for its
 *    «Курьер» column (`courierTypeName`). That roster is `COURIER_READ`'s, so the column is offered only to a
 *    person who holds it: without the roster every cell would be a dash.
 *  - «Скидка» reads `discountMinor`, with the order's currency.
 *  - `items` is in the platform's vocabulary and **not offered**: the board read carries no per-order item
 *    summary, so there is nothing to draw. It joins the picker when the read carries one.
 *  - `courierEta` and `source` are not in the vocabulary yet either (they need a read of their own and a
 *    product answer); row `1.1` keeps them as its residue.
 *
 * `channel` is the channel part of the «Тип / канал» cell: hiding it leaves the fulfilment mode alone.
 * «Филиал» exists only while the board reads «Все филиалы», so it is offered only there.
 *
 * What a column needs is stated once, here (`needs`), and answered by the screen through
 * {@link OrderBoardColumnContext}: a usability prediction, as every capability check in this console is --
 * the platform still decides what the roster call returns.
 */
export interface OrderBoardColumnDefinition {
  readonly key: OrderBoardColumnKey;
  readonly labelKey: MessageKey;
  readonly defaultShown: boolean;
  /** The field of a board row the cell draws, or `null` for a column the read gives nothing to draw. */
  readonly reads: keyof OrderSummaryResponse | null;
  /**
   * `allBranches` for a column that exists only on the brand-wide board; `courierRoster` for one whose cell
   * reads the courier roster, which only a person who may read couriers can fetch.
   */
  readonly needs: 'always' | 'allBranches' | 'courierRoster';
}

export const ORDER_BOARD_COLUMNS = {
  branch: {
    key: 'branch',
    labelKey: 'orders.column.branch',
    defaultShown: true,
    reads: 'locationId',
    needs: 'allBranches',
  },
  channel: {
    key: 'channel',
    labelKey: 'orders.column.channel',
    defaultShown: true,
    reads: 'channelCode',
    needs: 'always',
  },
  customer: {
    key: 'customer',
    labelKey: 'orders.column.customer',
    defaultShown: true,
    // Resolved for the page by one batch keyed by the order, not carried on the row.
    reads: 'orderId',
    needs: 'always',
  },
  items: {
    key: 'items',
    labelKey: 'orders.column.items',
    defaultShown: true,
    reads: null,
    needs: 'always',
  },
  payment: {
    key: 'payment',
    labelKey: 'orders.column.payment',
    defaultShown: true,
    reads: 'paymentStatusProjection',
    needs: 'always',
  },
  courier: {
    key: 'courier',
    labelKey: 'orders.column.courier',
    defaultShown: true,
    reads: 'courierId',
    needs: 'always',
  },
  createdBy: {
    key: 'createdBy',
    labelKey: 'orders.column.createdBy',
    defaultShown: false,
    reads: 'createdByDisplayName',
    needs: 'always',
  },
  acceptedBy: {
    key: 'acceptedBy',
    labelKey: 'orders.column.acceptedBy',
    defaultShown: false,
    reads: 'acceptedByDisplayName',
    needs: 'always',
  },
  courierType: {
    key: 'courierType',
    labelKey: 'orders.column.courierType',
    defaultShown: false,
    reads: 'courierId',
    needs: 'courierRoster',
  },
  discount: {
    key: 'discount',
    labelKey: 'orders.column.discount',
    defaultShown: false,
    reads: 'discountMinor',
    needs: 'always',
  },
} as const satisfies Record<OrderBoardColumnKey, OrderBoardColumnDefinition>;

/** What the screen knows that decides whether a column can be drawn at all. */
export interface OrderBoardColumnContext {
  /** The board reads every branch of the brand. */
  readonly allBranches: boolean;
  /** The person holds `COURIER_READ`, so the roster that names a courier's type can be fetched for them. */
  readonly courierRoster: boolean;
}

function canDraw(
  definition: OrderBoardColumnDefinition,
  context: OrderBoardColumnContext,
): boolean {
  switch (definition.needs) {
    case 'always':
      return true;
    case 'allBranches':
      return context.allBranches;
    case 'courierRoster':
      return context.courierRoster;
  }
}

/** The columns the picker lists, in the platform's order: those whose cell has something to draw here. */
export function offeredColumns(context: OrderBoardColumnContext): readonly OrderBoardColumnKey[] {
  return ORDER_BOARD_COLUMN_KEYS.filter((key) => {
    const definition: OrderBoardColumnDefinition = ORDER_BOARD_COLUMNS[key];
    return definition.reads !== null && canDraw(definition, context);
  });
}

/** Which columns the table draws: the person's choice, held to what this screen can draw. */
export function columnVisibility(
  document: OrderBoardDocument,
  context: OrderBoardColumnContext,
): Readonly<Record<OrderBoardColumnKey, boolean>> {
  const offered = new Set(offeredColumns(context));
  return Object.fromEntries(
    ORDER_BOARD_COLUMN_KEYS.map((key) => [key, offered.has(key) && isColumnShown(document, key)]),
  ) as Record<OrderBoardColumnKey, boolean>;
}
