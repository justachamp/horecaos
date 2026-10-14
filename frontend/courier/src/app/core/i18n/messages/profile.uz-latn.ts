import type { profileEn } from './profile.en';

export const profileUzLatn = {
  'profile.title': 'Profil',
  'profile.account': 'Akkaunt',
  'profile.name': 'Ism',
  'profile.username': 'Login',
  'profile.reference': 'Kuryer kodi',
  'profile.language': 'Til',
  'profile.app': 'Ilova',
  'profile.version': 'Versiya {version}',
} as const satisfies Record<keyof typeof profileEn, string>;
