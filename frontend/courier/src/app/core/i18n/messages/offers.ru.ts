import type { offersEn } from './offers.en';

export const offersRu = {
  'offers.title': 'Предложения',
  'offers.empty': 'Сейчас предложений нет. Новые появятся здесь сами.',
  'offers.noShift.title': 'Смена не открыта',
  'offers.noShift.body': 'Откройте смену, чтобы получать предложения.',
  'offers.noShift.link': 'Перейти к смене',
  'offers.polling': 'Новые предложения проверяются каждые {seconds} с. Обновлено в {time}.',
  'offers.lapses': 'Истекает через {seconds} с',
  'offers.lapsed': 'Истекло',
  'offers.earning': 'Вы заработаете {amount}',
  'offers.earning.increment': 'Дополнительно за заказ к вашему текущему рейсу',
  'offers.earning.none': 'Для этого предложения нет расчёта оплаты',
  'offers.accept': 'Принять',
  'offers.decline': 'Отклонить',
  'offers.gone': 'Это предложение уже недоступно.',
  'offers.declined': 'Предложение отклонено.',
} as const satisfies Record<keyof typeof offersEn, string>;
