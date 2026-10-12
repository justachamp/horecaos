import type { AreaMessages } from '../message-areas';
import type { deviceEn } from './device.en';

/**
 * Russian messages of the `device` area (namespaces `device`).
 *
 * Typed against `deviceEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const deviceRu: AreaMessages<typeof deviceEn> = {
  // Экран устройства — строки X/X.2 и X.35 (ADR 0079, ADR 0119, волна P17).
  // Недоступен из основной оболочки консоли: `/device`, отдельный маршрут.
  'device.offlineBanner': 'Нет связи — ждём восстановления соединения',
  'device.setup.title': 'Настройка устройства',
  'device.setup.hint': 'Один раз — укажите точку этого устройства перед привязкой.',
  'device.setup.tenantId': 'ID арендатора',
  'device.setup.brandId': 'ID бренда',
  'device.setup.locationId': 'ID точки',
  'device.setup.incomplete': 'Заполните все три поля.',
  'device.setup.save': 'Сохранить',
  'device.enrol.title': 'Привязка устройства',
  'device.enrol.hint': 'Менеджер считывает код ниже и одобряет его в разделе Кухня → Устройства.',
  'device.enrol.kindTitle': 'Что это за экран?',
  'device.enrol.begin': 'Сенсорная доска повара',
  'device.enrol.beginWall': 'Настенный экран (только просмотр)',
  'device.enrol.manual': 'Задать филиал вручную',
  'device.enrol.beginning': 'Запрашиваем код…',
  'device.enrol.userCodeLabel': 'Код для менеджера',
  'device.enrol.waiting': 'Ожидаем, пока менеджер одобрит это устройство…',
  'device.enrol.qrLabel': 'Код привязки этого устройства',
  'device.enrol.denied': 'В привязке отказано. Попробуйте снова.',
  'device.enrol.expired': 'Срок действия кода истёк. Попробуйте снова.',
  'device.enrol.error': 'Не удалось связаться с платформой. Попробуйте снова.',
  'device.enrol.retry': 'Попробовать снова',
  'device.profile.loading': 'Читаем запись этого устройства…',
  'device.profile.failed':
    'Не удалось прочитать запись этого устройства. Проверьте связь или задайте филиал вручную.',
  'device.board.loading': 'Загрузка очереди',
  'device.board.empty': 'Сейчас нет заказов на линии',
  'device.board.error': 'Не удалось загрузить очередь',
  'device.board.start': 'Начать',
  'device.board.ready': 'Готово',
  'device.resetDevice': 'Сбросить это устройство',

  // ------------------------------------------------------------------- terminal unlock and the kiosk mode (ADR 0155, ADR 0162)
  'device.enrol.beginKiosk': 'Это киоск самообслуживания',
  'device.enrol.waitingKiosk':
    'Руководитель сканирует QR телефоном или вводит этот код в консоли и подтверждает, что он совпадает с экраном.',
  'device.enrol.countdown': 'Код действует {time}. Новый появится сам.',
  'device.kiosk.title': 'Киоск самообслуживания',
  'device.kiosk.channel': 'Канал продаж',
  'device.kiosk.branch': 'Филиал',
  'device.kiosk.channelDown':
    'Канал отключён в этом филиале. Киоск ничего не продаёт, пока руководитель не исправит это.',
  'device.kiosk.ready': 'Подключён и готов. На этом экране ничего делать не нужно.',
  'device.kiosk.unbound': 'У киоска нет канала продаж. Руководитель должен выбрать его в консоли.',
  'device.terminal.keypad.clear': 'Сброс',
  'device.terminal.keypad.ok': 'ОК',
  'device.terminal.who': 'Кто работает?',
  'device.terminal.rosterEmpty':
    'Никто не может разблокировать этот экран. Руководитель добавит людей в консоли.',
  'device.terminal.state.NOT_SET': 'Задать PIN',
  'device.terminal.state.LOCKED': 'Заблокирован',
  'device.terminal.enterPin': '{who}, введите PIN',
  'device.terminal.haveCode': 'У меня есть код активации',
  'device.terminal.refused': 'Этот PIN не подошёл.',
  'device.terminal.lockedMinutes': 'Слишком много попыток. Повторите через {minutes} мин.',
  'device.terminal.lockedUntilReset': 'PIN заблокирован. Попросите руководителя сбросить его.',
  'device.terminal.noPin':
    'PIN пока нет. Попросите у руководителя код активации или получите его в своём профиле.',
  'device.terminal.setPin': 'Задать PIN по коду',
  'device.terminal.back': 'Назад',
  'device.terminal.close': 'Закрыть',
  'device.terminal.enterCode': 'Введите код активации',
  'device.terminal.codeRefused': 'Код не принят. Возможно, он уже использован или истёк.',
  'device.terminal.chooseNewPin': 'Выберите PIN из {digits} цифр',
  'device.terminal.confirmNewPin': 'Введите PIN ещё раз',
  'device.terminal.mismatch': 'PIN не совпали. Начните заново.',
  'device.terminal.enterCurrentPin': 'Введите текущий PIN',
  'device.terminal.pinChanged': 'PIN изменён.',
  'device.terminal.error': 'Нет связи с сервером. Повторите.',
  'device.terminal.rule.NOT_DIGITS': 'PIN состоит только из цифр.',
  'device.terminal.rule.WRONG_LENGTH': 'В PIN должно быть цифр: {digits}.',
  'device.terminal.rule.REPEATED_DIGIT': 'PIN из одной повторяющейся цифры легко угадать.',
  'device.terminal.rule.SEQUENCE': 'Ряд вроде 1234 или 4321 легко угадать.',
  'device.terminal.rule.COMMON': 'Этот PIN слишком распространён.',
  'device.terminal.rule.PERSONAL_NUMBER':
    'PIN не должен совпадать с концом вашего телефона или табельного номера.',
  'device.terminal.rule.OTHER': 'Такой PIN нельзя.',
  'device.terminal.changePin': 'Сменить PIN',
  'device.terminal.lock': 'Заблокировать',
  'device.terminal.unlock': 'Разблокировать',
  'device.terminal.nobody': 'Никто не разблокировал',
  'device.terminal.refusal.TERMINAL_UNLOCK_REQUIRED': 'Сначала разблокируйте планшет.',
  'device.terminal.refusal.TERMINAL_SESSION_ENDED':
    'Разблокировка закончилась. Разблокируйте снова.',
  'device.terminal.refusal.TERMINAL_PERSON_NOT_AUTHORIZED':
    'Ваша должность не позволяет это здесь.',
};
