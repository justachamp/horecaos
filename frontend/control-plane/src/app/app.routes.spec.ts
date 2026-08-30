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

  it('guards the whole console behind authentication', () => {
    const shell = routes.find((route) => route.path === '');
    expect(shell?.canActivate).toHaveLength(1);
  });

  it('leaves the unavailable state outside the guard', () => {
    // Guarding it would send a visitor into a redirect loop against a realm
    // that is exactly the thing that is not answering.
    const unavailable = routes.find((route) => route.path === 'unavailable');
    expect(unavailable).toBeDefined();
    expect(unavailable?.canActivate).toBeUndefined();
  });

  it('declares a capability for every section that is not the overview', () => {
    for (const section of SECTIONS) {
      if (section.id !== 'overview') {
        expect(section.capability, section.id).toBeDefined();
      }
    }
  });
});
