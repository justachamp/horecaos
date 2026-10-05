import type { AreaMessages } from '../message-areas';
import type { todayEn } from './today.en';

/**
 * Russian messages of the `today` area (namespaces `today`, `myWork`).
 *
 * Typed against `todayEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const todayRu: AreaMessages<typeof todayEn> = {
  // ---- IA 0.1 Живая доска (today-page.ts, live-board.ts) ----
  'today.myWork.link': 'Моя работа',
  'today.loading': 'Загрузка живой доски',
  'today.denied': 'Нет доступа к живой доске',
  'today.error.retry': 'Повторить',
  'today.updated': 'обновлено {time}',
  'today.counters.inProgress': 'В процессе',
  'today.counters.cancelled': 'Отменено',
  'today.period.businessDay': 'Отменено и завершено — за текущий рабочий день, с {from}',
  'today.mix.source.title': 'По источнику',
  'today.mix.type.title': 'По типу',
  'today.mix.empty': 'Нет заказов в процессе',
  'today.branches.title': 'Загрузка по филиалам',
  'today.branches.column.branch': 'Филиал',
  'today.branches.column.inProgress': 'В процессе',
  'today.branches.empty': 'Нет данных по филиалам',
  'today.branches.unavailable': 'Не удалось загрузить список филиалов',
  'today.branches.partial': 'Показано филиалов: {shown} из {total}',
  'today.operators.title': 'Операторы',
  'today.operators.column.operator': 'Оператор',
  'today.operators.column.accepted': 'Принято',
  'today.operators.column.created': 'Создано',
  'today.operators.unnamed': 'Сотрудник без имени',
  'today.operators.empty': 'Сегодня заказов ещё никто не принял',
  'today.operators.unavailable': 'Не удалось загрузить операторов',
  'today.operators.overlap':
    'Один заказ может попасть в оба столбца: один человек может и создать, и принять заказ.',

  // ---- IA 0.2 Моя работа (my-work-page.ts) — волна T01 ----
  'myWork.title': 'Моя работа',
  'myWork.subtitle': 'Ваши собственные заказы и выручка по этому филиалу — только за сегодня.',
  'myWork.loading': 'Загрузка данных о вашей работе',
  'myWork.error': 'Не удалось загрузить',
  'myWork.channel.title': 'Мои заказы по каналам',
  'myWork.channel.period': 'С {from}, текущий торговый день',
  'myWork.channel.empty': 'Сегодня у вас пока нет заказов',
  'myWork.payment.title': 'Выручка по способу оплаты',
  'myWork.payment.empty': 'Пока нет поступлений за сегодня',
  'myWork.locked.title': 'Пока недоступно',
  'myWork.locked.ask':
    'Персонализация интерфейса (сохранённые фильтры, раскладка) пока не построена.',
  'myWork.profile.body': 'Имя, телефон, фото и языки меняются в вашем профиле.',
};
