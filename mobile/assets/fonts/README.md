# IBM Plex Sans

The design system's typeface. It is **not** in this repository, and the
application currently renders in the platform default as a result.

## Why it has to be bundled rather than fetched

ADR 0035 requires the faces bundled as application assets. Two reasons, both
concrete:

- **ru and uz-Latn need Cyrillic and Latin Extended present offline.** A
  customer on a metro platform with no signal still has to be able to read a
  price.
- **A font that arrives over the network arrives after first paint.** Every cold
  start would reflow.

## Why the pubspec declaration is commented out

Declaring an asset that is not on disk fails the build. The declaration and the
files land in the same commit, not before it.

## What to add

Download IBM Plex Sans (SIL Open Font License 1.1) from the IBM Plex releases
and place these four faces here:

```text
assets/fonts/IBMPlexSans-Light.ttf        w300
assets/fonts/IBMPlexSans-Regular.ttf      w400
assets/fonts/IBMPlexSans-SemiBold.ttf     w600
assets/fonts/IBMPlexSans-Italic.ttf       w400 italic
```

Four, not the whole family: the closed type scale in
`lib/src/design/qoida_typography.dart` uses w300, w400 and w600 and nothing
else. Shipping weights nothing references is megabytes of download for nothing.

Check the subsets before committing. The default IBM Plex Sans release is Latin
only; ru needs the Cyrillic subset, and it ships as a separate family
(`IBMPlexSans-*` versus the Cyrillic-bearing release). A face without Cyrillic
renders every Russian string in the fallback font, which looks like a bug on
one locale only and is easy to miss in review.

## Then

Add this block to `pubspec.yaml` under `flutter:`:

```yaml
  fonts:
    - family: IBMPlexSans
      fonts:
        - asset: assets/fonts/IBMPlexSans-Light.ttf
          weight: 300
        - asset: assets/fonts/IBMPlexSans-Regular.ttf
          weight: 400
        - asset: assets/fonts/IBMPlexSans-Italic.ttf
          weight: 400
          style: italic
        - asset: assets/fonts/IBMPlexSans-SemiBold.ttf
          weight: 600
```

And set the family in `lib/src/design/qoida_typography.dart`:

```dart
static const String? fontFamily = 'IBMPlexSans';
```

`test/design/token_drift_test.dart` asserts that these two changes happen
together: it fails if `.ttf` files appear here while `fontFamily` is still null.

The licence file goes beside the faces. It is an OFL requirement, not a courtesy.
