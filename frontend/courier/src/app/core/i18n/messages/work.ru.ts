import type { workEn } from './work.en';

export const workRu = {
  'work.refusal.GPS_POSITION_REQUIRED':
    'Для этого шага нужно ваше местоположение. Включите геолокацию для приложения и повторите.',
  'work.refusal.GPS_ACCURACY_INSUFFICIENT':
    'Положение определено слишком неточно. Выйдите на открытое место или подождите и повторите.',
  'work.refusal.GPS_REFERENCE_UNAVAILABLE':
    'У этого места пока нет точки на карте, поэтому шаг нельзя проверить. Сообщите диспетчеру.',
  'work.refusal.TOO_FAR_FROM_PICKUP':
    'Вы слишком далеко от точки выдачи. Подойдите ближе и повторите.',
  'work.refusal.TOO_FAR_FROM_DROPOFF':
    'Вы слишком далеко от двери клиента. Подойдите ближе и повторите.',
  'work.refusal.KITCHEN_NOT_READY': 'Кухня ещё не закончила этот заказ.',
  'work.refusal.PAYMENT_CONFIRMATION_REQUIRED':
    'Подтвердите полученные наличные, прежде чем отметить доставку.',
  'work.refusal.PAYMENT_AMOUNT_MISMATCH':
    'Это не та сумма, которая причитается. Пересчитайте наличные и введите точную сумму.',
  'work.refusal.LOCATION_NOT_YET_REVEALED':
    'Адрес клиента показывается только после принятия заказа.',
  'work.refusal.COURIER_NOT_ELIGIBLE':
    'Сейчас вы не можете брать новые заказы. Проверьте свой статус на экране «Сегодня».',
  'work.refusal.STEP_NOT_ALLOWED':
    'Из текущего состояния этот шаг сделать нельзя. Обновите экран и проверьте.',
  'work.refusal.PAYMENT_NOT_CONFIRMABLE':
    'Наличные можно подтвердить только после того, как заказ забран.',
  'work.refusal.unknown': 'Платформа не приняла этот шаг. Обновите экран и повторите.',
  'work.stale': 'Это изменилось, пока вы смотрели. Показано актуальное.',
  'work.gate.kind.GEO': 'местоположение',
  'work.gate.kind.PHOTO': 'фото',
  'work.gate.kind.PAYMENT': 'оплата',
  'work.gate.failed': 'Шаг отклонён. Не удалось подтвердить: {kinds}.',
  'work.gate.failed.unnamed': 'Шаг отклонён: одну из проверок подтвердить не удалось.',
  'work.gate.unconfirmed': 'Готово. Платформа не смогла подтвердить: {kinds}. Диспетчер это видит.',
  'work.gate.passed': 'Все проверки пройдены.',
  'work.customer.show': 'Показать адрес клиента',
  'work.customer.hide': 'Скрыть адрес',
  'work.customer.title': 'Адрес клиента',
  'work.customer.entrance': 'Подъезд {value}',
  'work.customer.floor': 'Этаж {value}',
  'work.customer.apartment': 'Квартира {value}',
  'work.customer.instructions': 'Комментарий для курьера: {value}',
  'work.customer.navigate': 'Открыть на карте',
  'work.customer.audited': 'Каждое открытие адреса фиксируется.',
  'work.pickup.title': 'Точка выдачи',
  'work.landmark': 'Ориентир: {value}',
  'work.cash.collect': 'Получите наличными {amount}',
  'work.cash.prepaid': 'Оплачено онлайн. Ничего не получайте.',
  'work.kitchen.ready': 'Кухня: готово',
  'work.kitchen.preparing': 'Кухня: ещё готовится',
  'work.route': 'Маршрут: {distance}',
  'work.destination': 'Куда: {label}',
  'work.promised': 'Обещано к {time}',
  'work.window': 'Забрать с {start} до {end}',
  'work.order': 'Заказ {reference}',
} as const satisfies Record<keyof typeof workEn, string>;
