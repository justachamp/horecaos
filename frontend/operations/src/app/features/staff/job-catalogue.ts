import { CapabilityEntry } from './staff-api';
import { CAPABILITY_AREAS, CAPABILITY_SENTENCES, SentenceLocale } from './capability-sentences';

/** One capability as the grid and the job editor show it. */
export interface CatalogueRow {
  readonly code: string;
  /** The plain sentence in the reader's language, else the dotted code - visibly wrong rather than blank. */
  readonly sentence: string;
  /** The area name in the reader's language, from the sentence table's area, else the server's resource type. */
  readonly area: string;
  /** Held by the reader company-wide, so she may put it in a job. */
  readonly held: boolean;
  /** A job a company defines may carry it at all. */
  readonly composable: boolean;
}

/** An area and its rows, in the order the areas are first met in a sorted catalogue. */
export interface CatalogueArea {
  readonly area: string;
  readonly rows: readonly CatalogueRow[];
}

export function catalogueRow(entry: CapabilityEntry, locale: SentenceLocale): CatalogueRow {
  const sentence = CAPABILITY_SENTENCES[entry.code];
  const areaKey = sentence?.area ?? entry.area;
  return {
    code: entry.code,
    sentence: sentence?.[locale] ?? entry.code,
    area: CAPABILITY_AREAS[areaKey]?.[locale] ?? areaKey,
    held: entry.held,
    composable: entry.composable,
  };
}

/**
 * Whether a row has any business matching `needle` (already trimmed and lower-cased): its sentence in the
 * reader's language, its dotted code - an administrator who knows the code from a refusal can paste it in -
 * or its area's name. This is the search over the catalogue itself, not over which jobs carry what
 * (that is the library's, and it stays there).
 */
export function matchesCatalogueRow(row: CatalogueRow, needle: string): boolean {
  if (needle === '') {
    return true;
  }
  return (
    row.code.toLowerCase().includes(needle) ||
    row.sentence.toLowerCase().includes(needle) ||
    row.area.toLowerCase().includes(needle)
  );
}

/** Groups rows by area, areas and rows alphabetical in the reader's language, empty areas dropped. */
export function groupByArea(
  rows: readonly CatalogueRow[],
  locale: string,
): readonly CatalogueArea[] {
  const byArea = new Map<string, CatalogueRow[]>();
  for (const row of rows) {
    const list = byArea.get(row.area) ?? [];
    list.push(row);
    byArea.set(row.area, list);
  }
  return [...byArea.entries()]
    .sort(([a], [b]) => a.localeCompare(b, locale))
    .map(([area, list]) => ({
      area,
      rows: [...list].sort((a, b) => a.sentence.localeCompare(b.sentence, locale)),
    }));
}
