/**
 * English messages of the `map` area (namespaces `ui.map`).
 *
 * This file defines the key set of its area: `mapRu` and `mapUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const mapEn = {
  'ui.map.label': 'Map',
  'ui.map.loading': 'Loading the map…',
  'ui.map.unavailable.notConfigured':
    'No map provider is set up in this environment, so there is no map to show. You can still enter the coordinates by hand.',
  'ui.map.unavailable.noTiles':
    'This environment can search for addresses but has no map to draw. You can still enter the coordinates by hand.',
  'ui.map.unavailable.loadFailed':
    'The map could not be loaded. You can still enter the coordinates by hand.',
  'ui.map.retry': 'Try again',
  'ui.map.latitude': 'Latitude',
  'ui.map.longitude': 'Longitude',
  'ui.map.coordinate.invalid':
    'Enter a latitude between -90 and 90 and a longitude between -180 and 180, as decimal numbers.',
  'ui.map.pin.hint': 'Click the map or drag the pin to place it, or type the coordinates.',
  'ui.map.pin.remove': 'Remove the pin',
  'ui.map.pin.outsideRegion':
    'This point is outside the region. Check that latitude and longitude are not swapped.',
  'ui.map.polygon.hint':
    'Draw the zone corner by corner, drag a corner to move it, or edit the coordinates below.',
  'ui.map.polygon.draw': 'Draw on the map',
  'ui.map.polygon.stopDrawing': 'Stop drawing',
  'ui.map.polygon.addCorner': 'Add a corner',
  'ui.map.polygon.clear': 'Clear the outline',
  'ui.map.polygon.corners': 'Corners of the outline',
  'ui.map.polygon.corner': 'Corner {n}',
  'ui.map.polygon.removeCorner': 'Remove corner {n}',
  'ui.map.polygon.problem.tooFew': 'An outline needs at least three corners.',
  'ui.map.polygon.problem.duplicate': 'Two neighbouring corners are the same point.',
  'ui.map.polygon.problem.crossing': 'The outline crosses itself.',
  'ui.map.polygon.problem.outsideRegion': 'Some corners are outside the region.',
  'ui.map.bbox.hint': 'Drag the rectangle to change the box, or edit the four numbers.',
  'ui.map.bbox.hintEmpty': 'Click two opposite corners on the map, or type the four numbers.',
  'ui.map.bbox.southWest': 'South-west corner',
  'ui.map.bbox.northEast': 'North-east corner',
  'ui.map.bbox.problem.outOfRange':
    'A latitude must be between -90 and 90 and a longitude between -180 and 180.',
  'ui.map.bbox.problem.inverted':
    'The north-east corner must be north and east of the south-west corner.',
  'ui.map.address.search': 'Search for an address',
  'ui.map.address.placeholder': 'Start typing an address',
  'ui.map.address.useAsTyped': 'Use “{query}” as typed',
  'ui.map.address.nothingResolved':
    'The address could not be placed on the map. Place the pin by hand.',
  'ui.map.address.lowConfidence':
    'The provider is not sure of this one. Check the pin on the map before you use it.',
  'ui.map.address.confirm': 'Use this point',
  'ui.map.address.street': 'Street',
  'ui.map.address.house': 'House',
  'ui.map.address.entrance': 'Entrance',
  'ui.map.address.floor': 'Floor',
  'ui.map.address.flat': 'Flat',
  'ui.map.address.landmark': 'Landmark',
  'ui.map.address.unavailable.notConfigured':
    'Address search is not set up in this environment. Type the address and place the pin by hand.',
  'ui.map.address.unavailable.refused':
    'Address search is refusing requests. Type the address and place the pin by hand, and tell an administrator.',
  'ui.map.address.unavailable.unavailable':
    'Address search is not answering right now. Type the address and place the pin by hand.',
  'ui.map.address.unavailable.rateLimited':
    'Too many searches in a short time. Wait a moment and keep typing.',
  'ui.map.address.unavailable.noRegion':
    'No region is registered, so addresses cannot be searched. Add a region in the delivery settings.',
  'ui.map.address.unavailable.failed':
    'The search did not get through. Type the address and place the pin by hand.',
} as const;
