import { Injectable } from '@angular/core';

export type TelegramMiniAppMode = 'fullscreen' | 'compact' | null;

/** Telegram Web/Mini App API - methods via postMessage (web) or TelegramWebviewProxy (desktop/mobile) */
@Injectable({ providedIn: 'root' })
export class TelegramWebappService {
  private readonly webTargetOrigin = 'https://web.telegram.org';

  /** Cached mode from URL/params - computed once on first access */
  private _mode: TelegramMiniAppMode | undefined;

  /** Pending clipboard read callbacks by req_id (see web_app_read_text_from_clipboard) */
  private readonly clipboardCallbacks = new Map<string, (text: string | null) => void>();

  constructor() {
    if (typeof window !== 'undefined') {
      this.setupClipboardEventListeners();
      this.setPlatformHeaderHeight();
    }
  }

  /** Set --tg-header-min-height: 100px for iPhone, 60px for Android */
  private setPlatformHeaderHeight(): void {
    const ua = navigator.userAgent || navigator.vendor || '';
    const isIOS = /iPhone|iPad|iPod/i.test(ua);
    const height = isIOS ? '100px' : '60px';
    document.documentElement.style.setProperty('--tg-header-min-height', height);
  }

  /** Set up listeners for clipboard_text_received (web + desktop/mobile) */
  private setupClipboardEventListeners(): void {
    const handleClipboardEvent = (eventData: { req_id?: string; data?: string | null }): void => {
      const reqId = eventData?.req_id;
      if (!reqId) return;
      const cb = this.clipboardCallbacks.get(reqId);
      if (cb) {
        cb(eventData?.data ?? null);
        this.clipboardCallbacks.delete(reqId);
      }
    };

    // Web (iframe): parent sends postMessage
    window.addEventListener('message', (e) => {
      if (typeof e.data !== 'string') return;
      try {
        const { eventType, eventData } = JSON.parse(e.data) as { eventType?: string; eventData?: unknown };
        if (eventType === 'clipboard_text_received' && eventData && typeof eventData === 'object') {
          handleClipboardEvent(eventData as { req_id?: string; data?: string | null });
        }
      } catch {}
    });

    // Desktop/Mobile: Telegram calls receiveEvent (docs.telegram-mini-apps.com/platform/events)
    const receiveHandler = (eventType: string, eventData: unknown): void => {
      if (eventType === 'clipboard_text_received' && eventData && typeof eventData === 'object') {
        handleClipboardEvent(eventData as { req_id?: string; data?: string | null });
      }
    };

    const win = window as unknown as Record<string, unknown>;
    // iOS/Android
    if (!win['Telegram']) win['Telegram'] = {};
    const tg = (win['Telegram'] as Record<string, unknown>) || {};
    if (!tg['WebView']) tg['WebView'] = {};
    (tg['WebView'] as Record<string, unknown>)['receiveEvent'] = receiveHandler;
    // Desktop
    win['TelegramGameProxy'] = win['TelegramGameProxy'] || {};
    (win['TelegramGameProxy'] as Record<string, unknown>)['receiveEvent'] = receiveHandler;
    // Windows Phone
    win['TelegramGameProxy_receiveEvent'] = receiveHandler;
  }

  /**
   * Reads text from clipboard via Telegram API (v6.4+).
   * When running in Telegram, calls web_app_read_text_from_clipboard and invokes
   * onResult when clipboard_text_received fires.
   * @see https://docs.telegram-mini-apps.com/platform/methods#web-app-read-text-from-clipboard
   */
  readTextFromClipboard(reqId: string, onResult: (text: string | null) => void): void {
    this.clipboardCallbacks.set(reqId, onResult);
    this.postEvent('web_app_read_text_from_clipboard', { req_id: reqId });
  }

  private postEvent(eventType: string, eventData: Record<string, unknown>): void {
    if (typeof window === 'undefined') return;
    const win = window as unknown as {
      TelegramWebviewProxy?: { postEvent: (event: string, data: string) => void };
      parent?: Window;
      external?: { notify: (data: string) => void };
    };

    const dataStr = JSON.stringify(eventData);

    if (win.TelegramWebviewProxy?.postEvent) {
      win.TelegramWebviewProxy.postEvent(eventType, dataStr);
      return;
    }
    if (win.external?.notify) {
      win.external.notify(JSON.stringify({ eventType, eventData }));
      return;
    }
    try {
      const parent = window.parent as Window;
      if (parent?.postMessage && parent !== window.self) {
        parent.postMessage(JSON.stringify({ eventType, eventData }), this.webTargetOrigin);
      }
    } catch {}
  }

  /** Whether the app is running inside Telegram (not when opened directly in browser) */
  get isTelegram(): boolean {
    if (typeof window === 'undefined') return false;
    const win = window as unknown as {
      Telegram?: { WebApp?: { initData?: string } };
      TelegramWebviewProxy?: { postEvent: (event: string, data: string) => void };
    };
    // TelegramWebviewProxy = native Telegram app (desktop/mobile)
    if (win.TelegramWebviewProxy?.postEvent) return true;
    // Telegram.WebApp with non-empty initData = opened from Telegram (web or in-app webview)
    const initData = win.Telegram?.WebApp?.initData;
    return !!(initData && initData.length > 0);
  }

  /**
   * Detects how the Mini App was opened: fullscreen or compact.
   * Sources: URL param ?mode=compact|fullscreen, tgWebAppStartParam, or Telegram.WebApp.isFullscreen/isExpanded.
   */
  get mode(): TelegramMiniAppMode {
    if (this._mode !== undefined) return this._mode;
    if (typeof window === 'undefined') return null;

    const params = new URLSearchParams(window.location.search);

    // 1. Explicit URL param
    const urlMode = params.get('mode');
    if (urlMode === 'compact' || urlMode === 'fullscreen') {
      this._mode = urlMode;
      return this._mode;
    }

    // 2. From tgWebAppStartParam (e.g. startapp=cmd&mode=compact)
    const startParam = params.get('tgWebAppStartParam');
    if (startParam?.toLowerCase().includes('mode=compact')) {
      this._mode = 'compact';
      return this._mode;
    }
    if (startParam?.toLowerCase().includes('mode=fullscreen')) {
      this._mode = 'fullscreen';
      return this._mode;
    }

    // 3. From Telegram WebApp API (Bot API 8.0+)
    const tg = (window as unknown as { Telegram?: { WebApp?: { isFullscreen?: boolean; isExpanded?: boolean } } })
      .Telegram?.WebApp;
    if (tg) {
      if (typeof tg.isFullscreen === 'boolean') {
        this._mode = tg.isFullscreen ? 'fullscreen' : 'compact';
        return this._mode;
      }
      if (typeof tg.isExpanded === 'boolean') {
        this._mode = tg.isExpanded ? 'fullscreen' : 'compact';
        return this._mode;
      }
    }

    this._mode = null;
    return this._mode;
  }

  /** Whether the Mini App is in fullscreen mode (convenience getter) */
  get isFullscreen(): boolean {
    return this.mode === 'fullscreen';
  }

  /** Whether the Mini App is in compact (half-screen) mode */
  get isCompact(): boolean {
    return this.mode === 'compact';
  }

  /** Start param from tgWebAppStartParam (e.g. from ?startapp=command) */
  get startParam(): string | null {
    if (typeof window === 'undefined') return null;
    return new URLSearchParams(window.location.search).get('tgWebAppStartParam');
  }

  /**
   * Requests fullscreen mode (Bot API 8.0+).
   * Call when ?mode=fullscreen is in the URL to open the Mini App in fullscreen.
   */
  requestFullscreen(): void {
    if (typeof window === 'undefined') return;
    const tg = (window as unknown as { Telegram?: { WebApp?: { requestFullscreen?: () => void } } })
      .Telegram?.WebApp;
    if (typeof tg?.requestFullscreen === 'function') {
      tg.requestFullscreen();
    }
  }

  /**
   * Sets swipe behavior (available since v7.7).
   * @param allowVerticalSwipe - When false, prevents closing the app via vertical swipe.
   */
  setupSwipeBehavior(allowVerticalSwipe = false): void {
    if (typeof window === 'undefined') return;

    const win = window as unknown as {
      TelegramWebviewProxy?: { postEvent: (event: string, data: string) => void };
      parent?: Window;
      external?: { notify: (data: string) => void };
    };

    const eventData = { allow_vertical_swipe: allowVerticalSwipe };

    // Desktop and Mobile: use TelegramWebviewProxy
    if (win.TelegramWebviewProxy?.postEvent) {
      win.TelegramWebviewProxy.postEvent(
        'web_app_setup_swipe_behavior',
        JSON.stringify(eventData)
      );
      return;
    }

    // Windows Phone: use external.notify
    if (win.external?.notify) {
      const data = JSON.stringify({
        eventType: 'web_app_setup_swipe_behavior',
        eventData,
      });
      win.external.notify(data);
      return;
    }

    // Web (iframe): use postMessage to parent
    try {
      const parent = window.parent as Window;
      if (parent?.postMessage && parent !== window.self) {
        const data = JSON.stringify({
          eventType: 'web_app_setup_swipe_behavior',
          eventData,
        });
        parent.postMessage(data, this.webTargetOrigin);
      }
    } catch {
      // Cross-origin or no parent
    }
  }
}
