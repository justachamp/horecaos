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
  'device.enrol.begin': 'Показать код привязки',
  'device.enrol.beginning': 'Запрашиваем код…',
  'device.enrol.userCodeLabel': 'Код для менеджера',
  'device.enrol.waiting': 'Ожидаем, пока менеджер одобрит это устройство…',
  'device.enrol.qrLabel': 'Код привязки этого устройства',
  'device.enrol.denied': 'В привязке отказано. Попробуйте снова.',
  'device.enrol.expired': 'Срок действия кода истёк. Попробуйте снова.',
  'device.enrol.error': 'Не удалось связаться с платформой. Попробуйте снова.',
  'device.enrol.retry': 'Попробовать снова',
  'device.board.loading': 'Загрузка очереди',
  'device.board.empty': 'Сейчас нет заказов на линии',
  'device.board.error': 'Не удалось загрузить очередь',
  'device.board.start': 'Начать',
  'device.board.ready': 'Готово',
  'device.resetDevice': 'Сбросить это устройство',
};
