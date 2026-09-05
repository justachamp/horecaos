const STORAGE_KEY = 'app_tg_entry_reload';
const QUERY_KEY = '_r';

/** Telegram Mini App keeps a stale document; home and login are the usual entry URLs. */
export function hardReloadTelegramEntryPage(): void {
  if (typeof window === 'undefined') {
    return;
  }
  if (!isTelegramWebView()) {
    return;
  }
  if (!isHomeOrLoginPath(window.location.pathname)) {
    return;
  }

  const url = new URL(window.location.href);
  if (url.searchParams.has(QUERY_KEY)) {
    markReloaded();
    return;
  }
  if (alreadyReloadedThisSession()) {
    return;
  }

  markReloaded();
  url.searchParams.set(QUERY_KEY, String(Date.now()));
  window.location.replace(url.toString());
}

function isHomeOrLoginPath(pathname: string): boolean {
  return (
    pathname === '/' ||
    pathname === '/home' ||
    pathname.startsWith('/home/') ||
    pathname === '/auth' ||
    pathname === '/auth/login' ||
    pathname.startsWith('/auth/login/')
  );
}

function isTelegramWebView(): boolean {
  const win = window as unknown as {
    Telegram?: { WebApp?: { initData?: string } };
    TelegramWebviewProxy?: { postEvent: (event: string, data: string) => void };
  };
  if (win.TelegramWebviewProxy?.postEvent) {
    return true;
  }
  const initData = win.Telegram?.WebApp?.initData;
  return !!(initData && initData.length > 0);
}

function alreadyReloadedThisSession(): boolean {
  try {
    return sessionStorage.getItem(STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

function markReloaded(): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, '1');
  } catch {
    // sessionStorage can be blocked in some WebViews
  }
}
