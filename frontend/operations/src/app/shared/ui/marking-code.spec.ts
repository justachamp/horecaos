import { describe, expect, it } from 'vitest';

import vectors from '../../../testing/marking-normalisation-vectors.json';
import {
  GROUP_SEPARATOR,
  checkGs1Structure,
  diagnoseScan,
  normaliseMarkingCode,
} from './marking-code';

interface NormalisationVector {
  readonly name: string;
  readonly input: string;
  readonly canonical: string | null;
}

interface StructureVector {
  readonly name: string;
  readonly canonical: string;
  readonly gs1Valid: boolean;
  readonly gtin: string | null;
}

/**
 * The shared vector file is the contract with `ordering.domain.MarkingCodes` (ADR 0161): the same
 * file, byte for byte, drives the Java suite, and `repo_hygiene.py` fails when the two copies differ.
 */
describe('marking code normalisation (shared vectors)', () => {
  const normalisation = vectors.normalisation as readonly NormalisationVector[];
  const structure = vectors.structure as readonly StructureVector[];

  it('reads a vector file with both accepted and refused inputs', () => {
    expect(normalisation.filter((v) => v.canonical !== null).length).toBeGreaterThan(8);
    expect(normalisation.filter((v) => v.canonical === null).length).toBeGreaterThan(8);
    expect(structure.some((v) => v.gs1Valid)).toBe(true);
    expect(structure.some((v) => !v.gs1Valid)).toBe(true);
  });

  for (const vector of normalisation) {
    it(`normalises: ${vector.name}`, () => {
      expect(normaliseMarkingCode(vector.input)).toBe(vector.canonical);
    });
  }

  for (const vector of structure) {
    it(`checks the GS1 structure: ${vector.name}`, () => {
      const result = checkGs1Structure(vector.canonical);
      expect(result.valid).toBe(vector.gs1Valid);
      expect(result.gtin).toBe(vector.gtin);
    });
  }
});

describe('scan diagnostics', () => {
  const code = '010460000000001521Zq8mK2pXr4Wd';

  it('reports lengths and counts, never the content', () => {
    const diagnostic = diagnoseScan(`]d2${code}${GROUP_SEPARATOR}91EE10`);
    expect(diagnostic).toEqual({
      length: code.length + 3 + 1 + 6,
      groupSeparators: 1,
      aimIdentifier: true,
      gs1Shaped: true,
    });
    expect(JSON.stringify(diagnostic)).not.toContain('Zq8m');
  });

  it('says a scan is not GS1-shaped when a scanner dropped the separator and ran the elements together', () => {
    // A serial that should have ended at a GS runs on into the next element and past 20 characters.
    const merged = `01${'04600000000015'}21${'Zq8mK2pXr4Wd'}91EE10${'x'.repeat(10)}`;
    expect(diagnoseScan(merged).gs1Shaped).toBe(false);
    expect(diagnoseScan(merged).groupSeparators).toBe(0);
  });
});
