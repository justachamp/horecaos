import { inject } from '@angular/core';
import type { CanActivateFn } from '@angular/router';

import { I18n } from './i18n';
import type { MessageArea } from './message-areas';

/**
 * `canActivate: [messagesGuard('orders', 'customers')]` -- a route's promise that the messages it shows
 * are in memory before it draws.
 *
 * Messages are split by feature area and fetched when needed (`message-areas.ts`, ADR 0035's loading
 * model); the route table is where "when needed" is written down. Declare the areas on the route that
 * brings the screen in -- a section's shell route covers its children, and a child that needs more
 * than its parent adds its own guard. `core` is always there and is never declared.
 *
 * It resolves `true` whatever happens: a chunk that cannot be fetched is logged by {@link I18n.require}
 * and the screen opens with raw keys in place of that area, which is better than a route nobody can
 * reach. `tools/i18n/areas.mjs --check` (run by `npm run i18n:areas:test`) fails when a route's code names
 * a key of an area that no guard on its way declares.
 */
export function messagesGuard(...areas: readonly MessageArea[]): CanActivateFn {
  return async () => {
    await inject(I18n).require(areas);
    return true;
  };
}
