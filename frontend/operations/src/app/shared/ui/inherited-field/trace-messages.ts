import { localMessages } from '../../../core/i18n/local-messages';

/**
 * The per-level line in the resolution-trace popover (row `X.1`): which version is in force at a
 * level, who changed it and when; and the words of the fifth state, «Locked by plan». Local to the control rather than in the shared catalogues --
 * see `LocalMessages` for why the initial bundle cannot take more Russian keys.
 */
export const traceMessages = localMessages({
  ru: {
    changed: 'Версия {version} · {who} · {when}',
    unknownActor: 'человек без записи в сотрудниках компании',
    lockedChip: 'Закрыто тарифом',
    lockedMore: 'Подробнее',
    lockedMoreAria: 'Подробнее о тарифе: {feature}',
  },
  'uz-Latn': {
    changed: 'Versiya {version} · {who} · {when}',
    unknownActor: 'bu kompaniyada xodim yozuvi yoʻq shaxs',
    lockedChip: 'Tarif yopgan',
    lockedMore: 'Batafsil',
    lockedMoreAria: 'Tarif haqida batafsil: {feature}',
  },
  en: {
    changed: 'Version {version} · {who} · {when}',
    unknownActor: 'a person with no staff record here',
    lockedChip: 'Locked by plan',
    lockedMore: 'Learn more',
    lockedMoreAria: 'Learn more about the plan: {feature}',
  },
});
