import { inject } from '@angular/core';
import type { CanActivateFn } from '@angular/router';

import { I18n } from './i18n';
import type { MessageArea } from './message-areas';

/**
 * `canActivate: [messagesGuard('home')]` -- a route's promise that the messages it shows are in
 * memory before it draws. `core` is always there and is never declared. Resolves `true` whatever
 * happens: a chunk that cannot be fetched is logged and the screen opens with raw keys, which is
 * better than a route nobody can reach.
 */
export function messagesGuard(...areas: readonly MessageArea[]): CanActivateFn {
  return async () => {
    await inject(I18n).require(areas);
    return true;
  };
}
