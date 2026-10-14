/**
 * The feature areas the message catalogue is split into (`messages/<area>.<locale>.ts`).
 *
 * `core` is the shell, sign-in and shared errors, and is always loaded. Every other area is
 * fetched by the route that shows it (`messagesGuard`), so the initial bundle carries the words of
 * the screens a courier has not opened yet only when they open them. A new feature adds its area
 * here, its three catalogue modules, and a line in `catalogue-loaders.ts`.
 */
export const MESSAGE_AREAS = ['core', 'home', 'profile'] as const;

export type MessageArea = (typeof MESSAGE_AREAS)[number];
