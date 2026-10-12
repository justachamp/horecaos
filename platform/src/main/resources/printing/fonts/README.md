# Receipt face (ADR 0154, Decision 3)

`FiraCode-Regular.ttf` and `FiraCode-Bold.ttf`, version 5.002, under the SIL Open Font License 1.1
(`OFL.txt`). A monospaced face: 600/1000 em advance, so 20 px is exactly the 12-dot cell the
renderer lays text on (24-dot rows).

Where the files came from: the copies bundled with the JetBrains Runtime of a local IntelliJ
installation (`lib/fonts`), byte for byte, because the record asks for a vendored OFL face and the
build must not fetch one. The name table of both files carries the copyright and the licence.

What the glyph test proves (`PrintingGlyphCoverageTests`): every string the three locale catalogues
put on a document, the Latin Extended letters Uzbek uses, and the Cyrillic Uzbek letters have a real
glyph, so no document draws a missing-glyph box.

**One known gap, handled in `GlyphFace`:** this face has no U+02BB (modifier letter turned comma, the
`oʻ` and `gʻ` of Uzbek Latin) or U+02BC. The renderer draws them with the face's U+2018 and U+2019,
which are the same shapes at this size. A face with a native U+02BB (IBM Plex Mono is the first
candidate; it is the UI face's sibling) replaces these files and `GlyphFace.SUBSTITUTES` becomes
empty; the glyph test is the acceptance for the swap. Recorded in ADR 0154's open input «The bundled
font» as an engineering follow-up.
