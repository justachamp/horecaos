import { FoundSetting } from '../../features/settings/settings-find';

/** What choosing an item does: go somewhere, with the query that presets the screen. */
export interface PaletteTarget {
  readonly commands: readonly string[];
  readonly queryParams?: Readonly<Record<string, string>>;
}

export interface PaletteItem {
  readonly id: string;
  readonly group: 'go' | 'setting' | 'order';
  readonly label: string;
  /** The second column: where it is, or what is set. */
  readonly hint: string;
  readonly target: PaletteTarget;
}

/** A place the operator may go: its name already in their language, and where it is. */
export interface Destination {
  readonly id: string;
  readonly label: string;
  /** The group it sits under (a rail section, a Settings screen), already in their language. */
  readonly hint: string;
  readonly target: PaletteTarget;
}

export const MAX_PALETTE_ITEMS = 12;

/**
 * An order number or a partner's reference, as typed: letters, digits and dashes, with at least one digit. It is
 * never a person's name or phone: the board's search is the non-PII reference search (orders.md §2.8), and a
 * query that looks like anything else is not offered as an order lookup.
 */
const ORDER_REFERENCE = /^(?=.*\d)[A-Za-z0-9][A-Za-z0-9-]{0,31}$/;

/** How well a name matches what was typed: 0 for starting with it, 1 for containing it, null for neither. */
function rank(label: string, needle: string): number | null {
  const lowered = label.toLowerCase();
  if (lowered.startsWith(needle)) {
    return 0;
  }
  return lowered.includes(needle) ? 1 : null;
}

/**
 * The command palette's items for what was typed (settings.md §1.6, `⌘K`): the sections and Settings screens the
 * operator may open, the settings «Find a setting» would find, and, for something that reads as an order number,
 * a lookup on the board. Places whose name starts with the query come before those that merely contain it; the
 * settings keep the order «Find a setting» gave them; the order lookup comes last, so Enter on a number that is
 * also a section's name goes to the section.
 *
 * Nothing is offered for an empty query: a palette that lists everything is a menu, and the rail is one already.
 */
export function paletteItems(
  query: string,
  destinations: readonly Destination[],
  settings: readonly FoundSetting[],
  orderLabel: (query: string) => string,
  orderHint: string,
): readonly PaletteItem[] {
  const text = query.trim();
  const needle = text.toLowerCase();
  if (needle === '') {
    return [];
  }

  const places = destinations
    .map((destination) => ({ destination, score: rank(destination.label, needle) }))
    .filter((entry): entry is { destination: Destination; score: number } => entry.score !== null)
    .sort((a, b) => a.score - b.score)
    .map(({ destination }): PaletteItem => ({
      id: destination.id,
      group: 'go',
      label: destination.label,
      hint: destination.hint,
      target: destination.target,
    }));

  const found = settings.map((setting): PaletteItem => ({
    id: setting.id,
    group: 'setting',
    label: setting.label,
    hint: `${setting.breadcrumb} · ${setting.detail}`,
    target: {
      commands: setting.route.startsWith('/') ? [setting.route] : ['/settings', setting.route],
      ...(Object.keys(setting.queryParams).length > 0 ? { queryParams: setting.queryParams } : {}),
    },
  }));

  const order: PaletteItem[] = ORDER_REFERENCE.test(text)
    ? [
        {
          id: `order:${text}`,
          group: 'order',
          label: orderLabel(text),
          hint: orderHint,
          target: { commands: ['/orders'], queryParams: { tab: 'all', q: text } },
        },
      ]
    : [];

  return [...places, ...found, ...order].slice(0, MAX_PALETTE_ITEMS);
}
