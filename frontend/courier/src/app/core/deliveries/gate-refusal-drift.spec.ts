// This app's tsconfig carries no "node" types (a browser app has no business importing Node built-ins
// at runtime) -- this runs only under Vitest's Node test runner, never shipped to a browser, the same
// exception the operations console's `preference-vocabulary.spec.ts` carries for the identical reason.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';

import { GATE_REFUSAL_REASONS } from './step-outcome';

/**
 * ADR 0179, "The gates": the reasons a rule can give for refusing a step exist twice, as the platform's
 * `GateRefusal` enum and as `GATE_REFUSAL_REASONS` here, and nothing generated carries one to the other --
 * the `reason` is an extension of a problem document and is absent from the OpenAPI baseline.
 *
 * The client list is typed against its own sentences (`REFUSAL_KEYS` in `step-message.ts` is a
 * `Record<GateRefusalReason, MessageKey>`, so a reason added *here* without a sentence does not compile),
 * but a member added to the *enum* is invisible to the compiler: the platform's tests stay green, the client
 * builds, and every courier who meets it reads the generic "unknown" sentence. This reads the enum's source
 * and fails the build when the two lists differ in either direction; the platform's own test
 * (`DeliveryGateTests.refusalNamesAreDistinctAndStable`) pins the enum to the exact set, so a rename or an
 * addition there also fails first.
 */

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../../../../..');

/** The constants of `GateRefusal`, in declaration order, read from the Java source with its comments taken out. */
function platformReasons(): string[] {
  const source = readFileSync(
    resolve(repo, 'platform/src/main/java/uz/horecaos/platform/courier/domain/GateRefusal.java'),
    'utf8',
  ) as string;
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const open = withoutComments.indexOf('enum GateRefusal');
  expect(open, 'GateRefusal.java declares the enum').toBeGreaterThanOrEqual(0);
  const body = withoutComments.slice(
    withoutComments.indexOf('{', open) + 1,
    withoutComments.lastIndexOf('}'),
  );
  expect(
    body,
    'this reads a plain enum of names; if GateRefusal gains arguments or members, teach this reader the new shape',
  ).not.toMatch(/[(;"]/);
  return [...body.matchAll(/\b([A-Z][A-Z0-9_]*)\b/g)].map((match) => match[1]);
}

describe('the refusal reasons, client against platform (ADR 0179)', () => {
  it('reads the enum it is meant to read', () => {
    const reasons = platformReasons();

    expect(reasons.length).toBeGreaterThan(0);
    expect(new Set(reasons).size, 'no reason is declared twice').toBe(reasons.length);
  });

  it('has a sentence-bearing entry for every reason the platform can give', () => {
    const missingHere = platformReasons().filter(
      (reason) => !(GATE_REFUSAL_REASONS as readonly string[]).includes(reason),
    );

    expect(
      missingHere,
      'a GateRefusal member with no entry in GATE_REFUSAL_REASONS (step-outcome.ts), REFUSAL_KEYS (step-message.ts) and the work catalogues reaches a courier as the generic sentence',
    ).toEqual([]);
  });

  it('lists no reason the platform no longer gives', () => {
    const known = platformReasons();
    const gone = GATE_REFUSAL_REASONS.filter((reason) => !known.includes(reason));

    expect(
      gone,
      'an entry in GATE_REFUSAL_REASONS with no GateRefusal member is a sentence for a refusal that cannot happen',
    ).toEqual([]);
  });
});
