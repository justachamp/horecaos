import { localMessages } from '../../core/i18n/local-messages';
import { ScreenKeys, registerScreenKeys } from '../../shared/keyboard/screen-keys';
import { Shortcut, ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The category tree's keys (catalog.md §4.3, gap map row `X.1`). They are the shared tree's own: `q-tree-view`
 * handles them on the focused row (the arrows, `←`/`→` to collapse and expand, `Alt+↑`/`Alt+↓` to reorder
 * within the parent, `Alt+←` to promote a level, `Enter` to rename inline) and the unit specs of the tree prove
 * each. This scope exists so the cheat-sheet lists them while the tree is on screen, which is what the registry
 * is for: its shortcuts carry no `run`, so the registry never consumes the key and the tree is the only one
 * that acts on it.
 */
const messages = localMessages({
  ru: {
    title: 'Категории',
    move: 'Следующая / предыдущая категория',
    fold: 'Развернуть / свернуть ветку',
    reorder: 'Переставить внутри родителя',
    promote: 'Поднять на уровень выше',
    rename: 'Переименовать на месте',
  },
  'uz-Latn': {
    title: 'Kategoriyalar',
    move: 'Keyingi / oldingi kategoriya',
    fold: 'Shoxni ochish / yopish',
    reorder: 'Ota ichida joyini almashtirish',
    promote: 'Bir daraja yuqoriga koʻtarish',
    rename: 'Joyida nomini oʻzgartirish',
  },
  en: {
    title: 'Categories',
    move: 'Next / previous category',
    fold: 'Expand / collapse the branch',
    reorder: 'Reorder within the parent',
    promote: 'Promote one level',
    rename: 'Rename in place',
  },
});

export function categoriesScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const listed = (shortcut: Shortcut): Shortcut => shortcut;

  return {
    id: 'catalog-categories',
    title: () => text('title'),
    shortcuts: [
      listed({ keys: ['ArrowDown', 'ArrowUp'], caps: ['↑', '↓'], label: () => text('move') }),
      listed({ keys: ['ArrowRight', 'ArrowLeft'], caps: ['←', '→'], label: () => text('fold') }),
      listed({
        keys: ['ArrowUp', 'ArrowDown'],
        modifier: 'alt',
        caps: ['Alt+↑', 'Alt+↓'],
        label: () => text('reorder'),
      }),
      listed({
        keys: ['ArrowLeft'],
        modifier: 'alt',
        caps: ['Alt+←'],
        label: () => text('promote'),
      }),
      listed({ keys: ['Enter'], caps: ['Enter'], label: () => text('rename') }),
    ],
  };
}

/** The page's one line: `private readonly keys = registerCategoriesKeys();`. */
export function registerCategoriesKeys(): void {
  registerScreenKeys(categoriesScope);
}
