export interface CartItem {
  id: string;
  name: string;
  image: string;
  quantity: number;
  currentPrice: string;
  originalPrice: string;
  discountPercent: number;
}

export const DELIVERY_FEE = "20 000 so'm";
export const FREE_DELIVERY_THRESHOLD = 199_000;

export const MOCK_CART_ITEMS: CartItem[] = [
  {
    id: '1',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "32 000 so'm",
    originalPrice: "44 000 so'm",
    discountPercent: 20
  },
  {
    id: '2',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "32 000 so'm",
    originalPrice: "44 000 so'm",
    discountPercent: 30
  },
  {
    id: '3',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '4',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '5',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '6',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '7',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '8',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '9',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '10',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '11',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  },
  {
    id: '12',
    name: "Qovurilgan lag'mon",
    image: '/assets/orders/placeholder-order.png',
    quantity: 2,
    currentPrice: "25 000 so'm",
    originalPrice: "35 000 so'm",
    discountPercent: 20
  }
];
