import type { homeEn } from './home.en';

export const homeRu = {
  'home.title': 'Сегодня',
  'home.greeting': 'Здравствуйте, {name}',
  'home.greeting.anonymous': 'Здравствуйте',
  'home.refresh': 'Обновить',
  'home.notRegistered.title': 'Этот аккаунт не курьер этой компании',
  'home.notRegistered.body':
    'Вход выполнен, но аккаунт не привязан к курьеру этой компании. Попросите диспетчера проверить.',
  'home.engagement.title': 'Статус работы',
  'home.engagement.PENDING_VERIFICATION': 'Ждём проверки вашей регистрации',
  'home.engagement.ACTIVE': 'Допущены к работе',
  'home.engagement.SUSPENDED_COMPLIANCE': 'Приостановлено: нужно обновить регистрацию',
  'home.engagement.SUSPENDED_OPERATIONAL': 'Приостановлено диспетчером',
  'home.engagement.ENDED': 'Сотрудничество завершено',
  'home.engagement.validUntil': 'Регистрация действительна до {date}',
  'home.engagement.EXPIRING': 'Срок регистрации скоро истекает. Продлите её вместе с диспетчером.',
  'home.engagement.LAPSED':
    'Срок регистрации истёк. Новые заказы недоступны, пока она не будет продлена.',
  'home.branches.title': 'Ваши точки',
  'home.branches.none': 'За вами пока не закреплена ни одна точка. Обратитесь к диспетчеру.',
  'home.branches.primary': 'основная',
  'home.shift.title': 'Смена',
  'home.shift.manage': 'Управление сменой',
  'home.shift.none': 'Смена не открыта.',
  'home.shift.since': 'Открыта с {time}',
  'home.shift.at': 'Точка: {branch}',
  'home.shift.duty.AVAILABLE': 'На линии',
  'home.shift.duty.ON_BREAK': 'Перерыв',
  'home.shift.duty.AT_CAPACITY': 'Загружен',
  'home.shift.duty.UNREACHABLE': 'Нет связи',
} as const satisfies Record<keyof typeof homeEn, string>;
