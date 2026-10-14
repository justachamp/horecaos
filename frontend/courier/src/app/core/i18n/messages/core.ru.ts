import type { coreEn } from './core.en';

export const coreRu = {
  'app.title': 'HorecaOS Курьер',
  'shell.skip': 'Перейти к содержимому',
  'shell.nav.label': 'Основная навигация',
  'shell.nav.home': 'Сегодня',
  'shell.nav.profile': 'Профиль',
  'shell.language': 'Язык',
  'shell.update.available': 'Доступна новая версия приложения.',
  'shell.update.reload': 'Обновить',
  'common.retry': 'Повторить',
  'common.loading': 'Загрузка…',
  'common.signOut': 'Выйти',
  'common.signIn': 'Войти',
  'error.generic': 'Что-то пошло не так. Попробуйте ещё раз.',
  'error.network': 'Нет связи с сервером. Проверьте интернет и повторите.',
  'error.forbidden': 'Этому аккаунту это действие недоступно.',
  'auth.callback.working': 'Выполняем вход…',
  'auth.error.title': 'Не удалось войти',
  'auth.error.access_denied': 'Вход отменён.',
  'auth.error.expired':
    'Вход занял слишком много времени или был начат в другой вкладке. Начните заново.',
  'auth.error.storage_unavailable':
    'Браузер не сохраняет данные, нужные для входа. Отключите приватный режим и повторите.',
  'auth.error.unavailable': 'Сервис входа сейчас недоступен. Повторите чуть позже.',
  'auth.error.default': 'Вход не завершён. Повторите попытку.',
  'auth.signedOut.title': 'Вы вышли из приложения',
  'auth.signedOut.body': 'Войдите снова, чтобы продолжить работу.',
} as const satisfies Record<keyof typeof coreEn, string>;
