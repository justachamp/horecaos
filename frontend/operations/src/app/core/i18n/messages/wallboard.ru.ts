import type { AreaMessages } from '../message-areas';
import type { wallboardEn } from './wallboard.en';

/**
 * Russian messages of the `wallboard` area (namespaces `wallboard`, `wallboardKitchen`,
 * `wallboardVdu`).
 *
 * Typed against `wallboardEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const wallboardRu: AreaMessages<typeof wallboardEn> = {
  // ---- IA 0.1e / X/X.3 Информационная панель (wallboard-shell.ts) — волна T23 ----
  'wallboard.title': 'Живая доска',
  'wallboard.denied': 'Нет доступа к живой доске',
  'wallboard.counters.inProgress': 'В процессе',
  'wallboard.counters.cancelled': 'Отменено',
  'wallboard.mix.source.title': 'По источнику',
  'wallboard.mix.type.title': 'По типу',
  'wallboard.mix.empty': 'Нет заказов в процессе',
  'wallboard.branches.title': 'Загрузка по филиалам',
  'wallboard.branches.empty': 'Нет данных по филиалам',
  'wallboard.branches.unavailable': 'Не удалось загрузить список филиалов',
  'wallboard.operators.deferred':
    'Операторы на общем экране не показываются: пока не решено, можно ли выводить на нём имена сотрудников.',
  'wallboard.fullscreen.enter': 'Развернуть на весь экран',
  'wallboard.freshness.loading': 'Подключение…',
  'wallboard.freshness.seconds': 'Обновлено {seconds} с назад',
  'wallboard.freshness.minutes': 'Обновлено {minutes} мин назад',

  // Кухонный wallboard (2.1/2.4)
  'wallboardKitchen.title': 'Кухня',
  'wallboardKitchen.denied': 'Нет доступа к кухонной доске этой точки',
  'wallboardKitchen.loading': 'Загрузка кухонной доски',
  'wallboardKitchen.empty': 'Сейчас ничего не готовится',
  'wallboardKitchen.offline': 'Нет связи — показана последняя загруженная доска',
  'wallboardKitchen.actionError': 'Не удалось выполнить действие. Попробуйте ещё раз.',
  'wallboardVdu.title': 'Экран выдачи',
  'wallboardVdu.denied': 'Нет доступа к кухонной доске этой точки',
  'wallboardVdu.loading': 'Загрузка экрана выдачи',
  'wallboardVdu.empty': 'Сейчас ничего не готовится',
  'wallboardVdu.offline': 'Нет связи — показан последний загруженный экран',
  'wallboardVdu.stationFilter.label': 'Станция',
  'wallboardVdu.stationFilter.all': 'Все станции',
};
