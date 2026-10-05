import { localMessages } from '../../../core/i18n/local-messages';

/**
 * The per-level line in the resolution-trace popover (row `X.1`): which version is in force at a
 * level, who changed it and when. Local to the control rather than in the shared catalogues --
 * see `LocalMessages` for why the initial bundle cannot take more Russian keys.
 */
export const traceMessages = localMessages({
  ru: {
    changed: 'Версия {version} · {who} · {when}',
    unknownActor: 'человек без записи в сотрудниках компании',
  },
  'uz-Latn': {
    changed: 'Versiya {version} · {who} · {when}',
    unknownActor: 'bu kompaniyada xodim yozuvi yoʻq shaxs',
  },
  en: {
    changed: 'Version {version} · {who} · {when}',
    unknownActor: 'a person with no staff record here',
  },
});
