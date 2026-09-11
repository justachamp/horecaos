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
   */
  it('declares every literal path before the parameterised path that would swallow it', () => {
    const paths = consoleRoutes.map((route) => route.path ?? '');
    const swallows = (pattern: string, literal: string): boolean => {
      const parts = pattern.split('/');
      const segments = literal.split('/');
      return (
        parts.length === segments.length &&
        parts.some((part) => part.startsWith(':')) &&
        parts.every((part, index) => part.startsWith(':') || part === segments[index])
      );
    };

    for (const [index, literal] of paths.entries()) {
      if (literal.includes(':') || literal === '**' || literal === '') {
        continue;
      }
      const shadow = paths.findIndex(
        (other, other_index) => other_index < index && swallows(other, literal),
      );
      expect(shadow, `'${literal}' is unreachable behind '${paths[shadow]}'`).toBe(-1);
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
