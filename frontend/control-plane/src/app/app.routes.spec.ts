import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from './app.routes';
import { ROUTED_SECTIONS, SECTIONS } from './layout/sections';

/**
 * Keeps the rail and the router from disagreeing.
 *
 * A section with a route and no router entry is a link to a redirect loop; a
 * router entry with no section is a screen nobody can find. Both are easy to
 * produce and neither shows up in a build.
 */
const consoleRoutes = routes.find((route) => route.path === '')?.children ?? [];

function pathsOf(children: readonly Route[]): string[] {
  return children.map((child) => `/${child.path}`.replace(/^\/$/, '/'));
}

/**
 * Every array the router matches within: the top level, the shell's children,
 * and any `children` a future route grows of its own.
 *
 * The router matches one level at a time, so each array is its own ordering
 * problem and a path in one is never in competition with a path in another —
 * which is why this collects arrays rather than flattening the paths together.
 * The rules below then run once per array, and the top-level array comes under
 * them for the first time.
 */
function routeArrays(declared: readonly Route[]): (readonly Route[])[] {
  return [
    declared,
    ...declared.flatMap((route) => (route.children ? routeArrays(route.children) : [])),
  ];
}

describe('routes', () => {
  it('gives every routed section a router entry', () => {
    const routed = pathsOf(consoleRoutes);
    for (const section of ROUTED_SECTIONS) {
      expect(routed, section.id).toContain(section.route);
    }
  });

  /**
   * The router matches in declaration order and never backtracks, so a literal
   * path declared below a parameterised sibling of the same shape is simply
   * unreachable: `/tenants/configuration` spent its whole life resolving to the
   * tenant detail screen with `tenantId = 'configuration'`, and the rail
   * contract above passed the entire time, because it only asks whether the
   * path is somewhere in the array.
   *
   * Asserted as a rule rather than for today's two literals, so the next screen
   * added under a parameterised prefix cannot vanish the same way.
   *
   * A partly parameterised path is a victim like any other: `tenants/:tenantId`
   * declared above `tenants/:tenantId/onboarding` is harmless — different
   * lengths — but `tenants/:tenantId/:section` above it swallows five sibling
   * screens, and while this loop skipped every candidate containing a `:` it
   * said nothing about that. The whole `tenants/:tenantId` family, which is
   * where this wave's screens live, was checked zero times.
   */
  it('declares every path before the parameterised one that would swallow it', () => {
    const swallows = (pattern: string, candidate: string): boolean => {
      const parts = pattern.split('/');
      const segments = candidate.split('/');
      return (
        parts.length === segments.length &&
        parts.some((part) => part.startsWith(':')) &&
        // A `:param` in the earlier pattern matches whatever sits in the same
        // position of the candidate, literal or `:param` alike: the router does
        // not care which, it simply stops at the first pattern that matches.
        parts.every((part, index) => part.startsWith(':') || part === segments[index])
      );
    };

    for (const siblings of routeArrays(routes)) {
      const paths = siblings.map((route) => route.path ?? '');
      for (const [index, candidate] of paths.entries()) {
        if (candidate === '**' || candidate === '') {
          continue;
        }
        const shadow = paths.findIndex(
          (other, other_index) => other_index < index && swallows(other, candidate),
        );
        expect(shadow, `'${candidate}' is unreachable behind '${paths[shadow]}'`).toBe(-1);
      }
    }
  });

  /**
   * The sibling hole the same traversal exposes. `**` has no `:` in it, so the
   * check above cannot see it as a swallower — and it swallows everything.
   * Anything declared after it is unreachable no matter what it is called.
   */
  it('declares the catch-all last in its own array, where it swallows nothing', () => {
    for (const siblings of routeArrays(routes)) {
      const paths = siblings.map((route) => route.path ?? '');
      const catchAll = paths.indexOf('**');
      if (catchAll >= 0) {
        expect(
          catchAll,
          `routes are declared after '**' and cannot be reached: ${paths.slice(catchAll + 1).join(', ')}`,
        ).toBe(paths.length - 1);
      }
    }
  });

  it('guards the whole console behind authentication', () => {
    const shell = routes.find((route) => route.path === '');
    expect(shell?.canActivate).toHaveLength(1);
  });

  it('leaves the login page outside the guard', () => {
    // Guarding the page that signs somebody in would refuse to render it to
    // exactly the visitor it exists for (ADR 0062).
    const login = routes.find((route) => route.path === 'login');
    expect(login).toBeDefined();
    expect(login?.canActivate).toBeUndefined();
  });

  it('declares a capability for every section that is not the overview', () => {
    for (const section of SECTIONS) {
      if (section.id !== 'overview') {
        expect(section.capability, section.id).toBeDefined();
      }
    }
  });
});
