import type { profileEn } from './profile.en';

export const profileRu = {
  'profile.title': 'Профиль',
  'profile.account': 'Аккаунт',
  'profile.name': 'Имя',
  'profile.username': 'Логин',
  'profile.reference': 'Код курьера',
  'profile.language': 'Язык',
  'profile.app': 'Приложение',
  'profile.version': 'Версия {version}',
} as const satisfies Record<keyof typeof profileEn, string>;
