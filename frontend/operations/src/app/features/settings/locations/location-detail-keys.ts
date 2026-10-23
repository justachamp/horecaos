import { localMessages } from '../../../core/i18n/local-messages';
import { ScreenKeys, registerScreenKeys } from '../../../shared/keyboard/screen-keys';
import { ShortcutScope, modifierCap } from '../../../shared/keyboard/shortcut-registry';

/**
 * A branch record's keys (brands-and-locations.md §4.7, gap map row `X.1`): `1`..`7` switch the tab, `Cmd/Ctrl+S`
 * saves the form the cursor is in, and the hours editor's own two (`Alt+↓` copies the day to the next, and `1000`
 * means `10:00`) are listed so the sheet is the whole scheme -- those two are the editor's, in the shared schedule
 * grid and time field, where the widget is used.
 *
 * `Cmd/Ctrl+S` presses the primary button of the form that holds the cursor -- «Сохранить» in the hours editor,
 * in the bands editor, in the place form -- so it saves exactly what that button saves, under the same
 * disabled-while-saving rule, and the browser's own «save page» never opens.
 */
const messages = localMessages({
  ru: {
    title: 'Филиал',
    tab: 'Переключить вкладку: от «Основное» до «План зала»',
    save: 'Сохранить форму, в которой стоит курсор',
    copyDay: 'Часы: скопировать окна выбранного дня на следующий',
    shorthand: 'Часы: «1000» в поле времени значит 10:00',
  },
  'uz-Latn': {
    title: 'Filial',
    tab: 'Yorliqni almashtirish: «Asosiy»dan «Zal rejasi»gacha',
    save: 'Kursor turgan shaklni saqlash',
    copyDay: 'Soatlar: tanlangan kun oynalarini keyingi kunga nusxalash',
    shorthand: 'Soatlar: vaqt maydonida «1000» — bu 10:00',
  },
  en: {
    title: 'Branch',
    tab: 'Switch tab: Basics through Floor plan',
    save: 'Save the form the cursor is in',
    copyDay: "Hours: copy the focused day's windows to the next day",
    shorthand: 'Hours: «1000» in a time field means 10:00',
  },
});

const TABS = '.tabs > button.tab';
const PRIMARY = 'button.primary:not([disabled])';

/** The primary button of the closest form around the cursor, so Cmd/Ctrl+S saves what it saves. */
export function primaryButtonAround(host: HTMLElement): HTMLElement | null {
  let node: HTMLElement | null =
    document.activeElement instanceof HTMLElement ? document.activeElement : null;
  while (node !== null && host.contains(node)) {
    const button = node.querySelector<HTMLElement>(PRIMARY);
    if (button !== null) {
      return button;
    }
    node = node.parentElement;
  }
  return null;
}

export function locationDetailScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const tabs = (): HTMLElement[] => [...host.querySelectorAll<HTMLElement>(TABS)];

  return {
    id: 'location-detail',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['1', '2', '3', '4', '5', '6', '7'],
        caps: ['1–7'],
        label: () => text('tab'),
        enabled: () => tabs().length > 0,
        run: (event) => tabs()[Number(event.key) - 1]?.click(),
      },
      {
        keys: ['s'],
        modifier: 'ctrlOrCmd',
        caps: [`${modifierCap()}+S`],
        label: () => text('save'),
        inField: true,
        enabled: () => primaryButtonAround(host) !== null,
        run: () => primaryButtonAround(host)?.click(),
      },
      // The editor's own keys (shared/ui/schedule-grid.ts, time-input.ts), listed here so the sheet is complete.
      { keys: ['ArrowDown'], modifier: 'alt', caps: ['Alt+↓'], label: () => text('copyDay') },
      { keys: [], caps: ['1000 → 10:00'], label: () => text('shorthand') },
    ],
  };
}

/** The pane's one line: `private readonly keys = registerLocationDetailKeys();`. */
export function registerLocationDetailKeys(): void {
  registerScreenKeys(locationDetailScope);
}
