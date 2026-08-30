export interface OrderItem {
  id: string;
  title: string;
  subtitle: string;
  status: 'yetkazildi' | 'tayyorlanmoqda' | 'bekor' | 'tasdiqlandi' | 'yetkazilmoqda';
  date: string;
  price: string;
  image: string;
  /** Order number shown as "Order N: X" (active orders) */
  orderNumber?: number;
  /** e.g. "4 ta" */
  itemCount?: string;
  /** e.g. "8.8" for "8.8 km" */
  distanceKm?: string;
  /** Available actions from API, e.g. ['cancel'] */
  actions?: string[];
}

/** Map API status id to UI display label */
export const API_TO_UI_STATUS: Record<string, string> = {
  new: 'YANGI',
  accepted: 'TASDIQLANDI',
  cooking: 'TAYYORLANMOQDA',
  ready: 'TAYYOR',
  delivering: 'YETKAZILMOQDA',
  completed: 'TUGATILDI',
  cancelled: 'BEKOR QILINDI',
};

/** Map UI status back to API status for i18n keys (orders.statusXxx) */
export const UI_TO_API_STATUS: Record<string, string> = {
  YANGI: 'new',
  TASDIQLANDI: 'accepted',
  TAYYORLANMOQDA: 'cooking',
  TAYYOR: 'ready',
  YETKAZILMOQDA: 'delivering',
  TUGATILDI: 'completed',
  'BEKOR QILINDI': 'cancelled',
};

/** API status -> i18n key suffix (delivering uses statusDeliveringCode) */
export const API_STATUS_TO_I18N_KEY: Record<string, string> = {
  new: 'statusNew',
  accepted: 'statusAccepted',
  cooking: 'statusCooking',
  ready: 'statusReady',
  delivering: 'statusDeliveringCode',
  completed: 'statusCompleted',
  cancelled: 'statusCancelled',
};

/** Active order status display labels */
export const ACTIVE_STATUS_LABELS: Record<string, string> = {
  tasdiqlandi: 'Tasdiqlandi',
  tayyorlanmoqda: 'Tayyorlanmoqda',
  yetkazilmoqda: 'Yetkazilmoqda',
};

/** Line item for order detail view */
export interface OrderLineItem {
  name: string;
  image: string;
  quantity: number;
  unitPrice: string;
  /** For @for track when items can share the same name */
  variantId?: string;
}

/** Full order detail for /orders/detail/:id */
export interface OrderDetail {
  id: string;
  orderNumber: number;
  lineItems: OrderLineItem[];
  subtotal: string;
  deliveryFee: string;
  total: string;
  /** Packaging fee when > 0 */
  packaging?: string;
  /** Available actions from API, e.g. ['cancel'] */
  actions?: string[];
}