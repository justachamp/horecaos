import type { coreEn } from './messages/core.en';
import type { homeEn } from './messages/home.en';
import type { profileEn } from './messages/profile.en';
import type { shiftEn } from './messages/shift.en';
import type { noticeEn } from './messages/notice.en';

/**
 * Every key the application may ask for, derived from the English area modules.
 *
 * Type-only imports: no catalogue enters the bundle through here. The other two locales' modules
 * are typed against the English ones (`satisfies Record<keyof typeof coreEn, string>`), so a key
 * added in English and not translated fails `tsc` -- which is `ng build` and `ng test` -- instead
 * of reaching a courier as English text in a Russian screen.
 */
export type MessageKey =
  | keyof typeof coreEn
  | keyof typeof homeEn
  | keyof typeof profileEn
  | keyof typeof shiftEn
  | keyof typeof noticeEn;
