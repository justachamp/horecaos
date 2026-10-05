import { localMessages } from '../core/i18n/local-messages';

/**
 * The shell's own sentences for the keyboard cheat-sheet and the brand picker (row `X.1`).
 *
 * They ship with the shell's lazy chunk and not in the shared catalogues: the Russian catalogue is
 * in the initial bundle, which has no room left -- see `LocalMessages`.
 */
export const shellMessages = localMessages({
  ru: {
    'keys.title': 'Горячие клавиши',
    'keys.close': 'Закрыть',
    'keys.open': 'Показать горячие клавиши',
    'keys.scope.global': 'Везде',
    'keys.closeDialog': 'Закрыть окно или подсказку',
    'brand.label': 'Бренд',
    'location.label': 'Филиал',
  },
  'uz-Latn': {
    'keys.title': 'Tezkor tugmalar',
    'keys.close': 'Yopish',
    'keys.open': 'Tezkor tugmalarni koʻrsatish',
    'keys.scope.global': 'Hamma joyda',
    'keys.closeDialog': 'Oynani yoki maslahatni yopish',
    'brand.label': 'Brend',
    'location.label': 'Filial',
  },
  en: {
    'keys.title': 'Keyboard shortcuts',
    'keys.close': 'Close',
    'keys.open': 'Show keyboard shortcuts',
    'keys.scope.global': 'Everywhere',
    'keys.closeDialog': 'Close a dialog or popover',
    'brand.label': 'Brand',
    'location.label': 'Location',
  },
});

export type ShellMessageKey = Parameters<typeof shellMessages.text>[1];
