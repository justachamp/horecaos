import type { AreaMessages } from '../message-areas';
import type { mapEn } from './map.en';

/**
 * Russian messages of the `map` area (namespaces `ui.map`).
 *
 * Typed against `mapEn`: a key missing here, or one that does not exist there, is a
 * compile error. `../messages.en.ts` documents the layout.
 */
export const mapRu: AreaMessages<typeof mapEn> = {
  'ui.map.label': 'Карта',
  'ui.map.loading': 'Загружаем карту…',
  'ui.map.unavailable.notConfigured':
    'В этой среде не подключён поставщик карт, поэтому карты нет. Координаты по-прежнему можно ввести вручную.',
  'ui.map.unavailable.noTiles':
    'В этой среде работает поиск адресов, но карты для показа нет. Координаты по-прежнему можно ввести вручную.',
  'ui.map.unavailable.loadFailed':
    'Не удалось загрузить карту. Координаты по-прежнему можно ввести вручную.',
  'ui.map.retry': 'Повторить',
  'ui.map.latitude': 'Широта',
  'ui.map.longitude': 'Долгота',
  'ui.map.coordinate.invalid':
    'Введите широту от -90 до 90 и долготу от -180 до 180 в виде десятичных чисел.',
  'ui.map.pin.hint': 'Нажмите на карту или перетащите метку, либо введите координаты.',
  'ui.map.pin.remove': 'Убрать метку',
  'ui.map.pin.outsideRegion':
    'Эта точка вне региона. Проверьте, что широта и долгота не перепутаны.',
  'ui.map.polygon.hint':
    'Рисуйте зону по углам, перетаскивайте угол, чтобы сдвинуть его, или правьте координаты ниже.',
  'ui.map.polygon.draw': 'Рисовать на карте',
  'ui.map.polygon.stopDrawing': 'Закончить рисование',
  'ui.map.polygon.addCorner': 'Добавить угол',
  'ui.map.polygon.clear': 'Очистить контур',
  'ui.map.polygon.corners': 'Углы контура',
  'ui.map.polygon.corner': 'Угол {n}',
  'ui.map.polygon.removeCorner': 'Удалить угол {n}',
  'ui.map.polygon.problem.tooFew': 'В контуре должно быть не меньше трёх углов.',
  'ui.map.polygon.problem.duplicate': 'Два соседних угла совпадают.',
  'ui.map.polygon.problem.crossing': 'Контур пересекает сам себя.',
  'ui.map.polygon.problem.outsideRegion': 'Часть углов находится вне региона.',
  'ui.map.bbox.hint': 'Перетащите прямоугольник, чтобы изменить границы, или правьте четыре числа.',
  'ui.map.bbox.hintEmpty': 'Нажмите на карте два противоположных угла или введите четыре числа.',
  'ui.map.bbox.southWest': 'Юго-западный угол',
  'ui.map.bbox.northEast': 'Северо-восточный угол',
  'ui.map.bbox.problem.outOfRange': 'Широта должна быть от -90 до 90, долгота — от -180 до 180.',
  'ui.map.bbox.problem.inverted':
    'Северо-восточный угол должен быть севернее и восточнее юго-западного.',
  'ui.map.address.search': 'Поиск адреса',
  'ui.map.address.placeholder': 'Начните вводить адрес',
  'ui.map.address.useAsTyped': 'Взять «{query}» как введено',
  'ui.map.address.nothingResolved': 'Адрес не удалось найти на карте. Поставьте метку вручную.',
  'ui.map.address.lowConfidence':
    'Поставщик не уверен в этом результате. Проверьте метку на карте, прежде чем использовать её.',
  'ui.map.address.confirm': 'Использовать эту точку',
  'ui.map.address.street': 'Улица',
  'ui.map.address.house': 'Дом',
  'ui.map.address.entrance': 'Подъезд',
  'ui.map.address.floor': 'Этаж',
  'ui.map.address.flat': 'Квартира',
  'ui.map.address.landmark': 'Ориентир',
  'ui.map.address.unavailable.notConfigured':
    'Поиск адресов в этой среде не подключён. Введите адрес и поставьте метку вручную.',
  'ui.map.address.unavailable.refused':
    'Поиск адресов отклоняет запросы. Введите адрес и поставьте метку вручную, и сообщите администратору.',
  'ui.map.address.unavailable.unavailable':
    'Поиск адресов сейчас не отвечает. Введите адрес и поставьте метку вручную.',
  'ui.map.address.unavailable.rateLimited':
    'Слишком много поисков за короткое время. Подождите немного и продолжайте ввод.',
  'ui.map.address.unavailable.noRegion':
    'Не зарегистрирован ни один регион, поэтому искать адреса нельзя. Добавьте регион в настройках доставки.',
  'ui.map.address.unavailable.failed':
    'Запрос поиска не дошёл. Введите адрес и поставьте метку вручную.',
};
