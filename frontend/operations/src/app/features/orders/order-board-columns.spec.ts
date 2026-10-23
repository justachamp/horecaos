import { describe, expect, it } from 'vitest';

import { ORDER_BOARD_COLUMN_KEYS } from '../../core/preferences/preference-documents';
import { ADDABLE_COLUMNS, DEFAULT_SHOWN_COLUMNS } from '../../core/preferences/preference-edits';
import {
  BOARD_FACT_COLUMNS,
  ORDER_BOARD_COLUMNS,
  boardFactParams,
  columnVisibility,
  offeredColumns,
  requestedBoardFacts,
} from './order-board-columns';

describe('the order board’s column registry (ADR 0160)', () => {
  it('has one definition for every column the platform’s vocabulary names, under its own key', () => {
    expect(Object.keys(ORDER_BOARD_COLUMNS).sort()).toEqual([...ORDER_BOARD_COLUMN_KEYS].sort());
    for (const key of ORDER_BOARD_COLUMN_KEYS) {
      expect(ORDER_BOARD_COLUMNS[key].key).toBe(key);
    }
  });

  it('agrees with the document helpers about which columns are shown by default', () => {
    const shown = ORDER_BOARD_COLUMN_KEYS.filter((key) => ORDER_BOARD_COLUMNS[key].defaultShown);

    expect(shown).toEqual([...DEFAULT_SHOWN_COLUMNS]);
    expect(ORDER_BOARD_COLUMN_KEYS.filter((key) => !ORDER_BOARD_COLUMNS[key].defaultShown)).toEqual(
      [...ADDABLE_COLUMNS],
    );
  });

  it('offers every column whose cell has something to draw, «Филиал» only on «Все филиалы»', () => {
    expect(offeredColumns({ allBranches: false, courierRoster: true })).toEqual([
      'channel',
      'customer',
      'items',
      'payment',
      'courier',
      'createdBy',
      'acceptedBy',
      'courierType',
      'discount',
      'courierEta',
      'source',
      'fiscal',
      'distance',
      'externalNumber',
    ]);
    expect(offeredColumns({ allBranches: true, courierRoster: true })).toEqual([
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
      'courierEta',
      'source',
      'fiscal',
      'distance',
      'externalNumber',
    ]);
  });

  it('gives every column a field of the board row to draw from, now that the read carries one for each', () => {
    for (const key of ORDER_BOARD_COLUMN_KEYS) {
      expect(ORDER_BOARD_COLUMNS[key].reads, key).not.toBeNull();
    }
    expect(ORDER_BOARD_COLUMNS.items.reads).toBe('itemCount');
    expect(ORDER_BOARD_COLUMNS.courierEta.reads).toBe('courierEtaAt');
    expect(ORDER_BOARD_COLUMNS.source.reads).toBe('channelSystemType');
  });

  it('offers «Тип курьера» only to a person who can read the courier roster that names it', () => {
    const without = offeredColumns({ allBranches: false, courierRoster: false });

    expect(without).not.toContain('courierType');
    expect(without).toEqual([
      'channel',
      'customer',
      'items',
      'payment',
      'courier',
      'createdBy',
      'acceptedBy',
      'discount',
      'courierEta',
      'source',
      'fiscal',
      'distance',
      'externalNumber',
    ]);
    expect(offeredColumns({ allBranches: false, courierRoster: true })).toContain('courierType');
    expect(ORDER_BOARD_COLUMNS.courierType.needs).toBe('courierRoster');
  });

  it('never draws «Тип курьера» for a person without the roster, whatever another device of theirs stored', () => {
    const stored = { columns: { show: ['courierType', 'discount'] } } as const;

    const without = columnVisibility(stored, { allBranches: false, courierRoster: false });
    const withRoster = columnVisibility(stored, { allBranches: false, courierRoster: true });

    expect(without.courierType).toBe(false);
    expect(without.discount).toBe(true);
    expect(withRoster.courierType).toBe(true);
  });

  it('draws the defaults for a person who has chosen nothing, and holds «Филиал» to the screen that can draw it', () => {
    const plain = columnVisibility({}, { allBranches: false, courierRoster: true });

    expect(plain).toMatchObject({
      branch: false,
      channel: true,
      customer: true,
      items: true,
      payment: true,
      courier: true,
      createdBy: false,
      acceptedBy: false,
      courierType: false,
      discount: false,
      courierEta: false,
      source: false,
      fiscal: false,
      distance: false,
      externalNumber: false,
    });
    expect(columnVisibility({}, { allBranches: true, courierRoster: true }).branch).toBe(true);
  });

  it('applies the person’s change from the default, and shows a column added later to a person whose document predates it', () => {
    const visibility = columnVisibility(
      { columns: { show: ['createdBy', 'discount'], hide: ['courier'] } },
      { allBranches: false, courierRoster: true },
    );

    expect(visibility.createdBy).toBe(true);
    expect(visibility.discount).toBe(true);
    expect(visibility.courier).toBe(false);
    // A stored document names only what the person changed: everything else follows the defaults, so a
    // default column a later release adds is shown to this person without a migration of their document.
    expect(visibility.customer).toBe(true);
    expect(visibility.payment).toBe(true);
    expect(visibility.acceptedBy).toBe(false);
  });

  it('never draws a column the screen cannot, however the document says', () => {
    expect(
      columnVisibility(
        { columns: { show: ['createdBy'] } },
        { allBranches: false, courierRoster: true },
      ).branch,
    ).toBe(false);
    expect(
      columnVisibility(
        { columns: { hide: ['branch'] } },
        { allBranches: true, courierRoster: true },
      ).branch,
    ).toBe(false);
  });

  it('asks the board for the facts of the optional columns the person has on, and for nothing when none is', () => {
    const none = columnVisibility({}, { allBranches: false, courierRoster: true });
    expect(requestedBoardFacts(none)).toEqual([]);
    expect(boardFactParams(none)).toEqual({});

    const some = columnVisibility(
      { columns: { show: ['courierEta', 'fiscal', 'discount'] } },
      { allBranches: false, courierRoster: true },
    );
    expect(requestedBoardFacts(some)).toEqual(['courierEta', 'fiscal']);
    expect(boardFactParams(some)).toEqual({ include: ['courierEta', 'fiscal'] });
  });

  it('names as facts exactly the five columns the platform reads only when asked', () => {
    expect([...BOARD_FACT_COLUMNS]).toEqual([
      'courierEta',
      'source',
      'fiscal',
      'distance',
      'externalNumber',
    ]);
  });
});
