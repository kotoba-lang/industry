# 260629: ongakuka.gftd.ai cljc BGM catalog

## Decision

Implement `ongakuka.gftd.ai` as a portable `.cljc` actor, not as Python scripts.

The first runtime surface is deterministic background-music selection:

- `resources/catalog.edn` is the source of truth for candidate BGM assets.
- `ongakuka.policy` enforces license constraints before a track is returned.
- `ongakuka.compose/compose` returns a render plan, not a public audio URL.
- Actual audio files are stored in the yukkuri DataLad/git-annex asset dataset.

## Initial source

The first imported set is a 30-track pack:

- 20 DOVA-SYNDROME tracks commonly heard in Japanese YouTube/yukkuri-style videos.
- 10 Incompetech/Kevin MacLeod tracks commonly heard in US/global YouTube videos,
  including region-tagged selection hints for Arabia, India, China/East Asia, and EU/global use.

The catalog records each source license URL and marks every imported file:

- render-only
- raw public access forbidden
- AI training forbidden
- Content ID registration forbidden

Incompetech tracks additionally carry `:license/attribution-required? true` and
their generated `:credit/text` must be included in video descriptions or
equivalent credits.

## Runtime rule

`yukkuri.gftd.ai` and `animeka.gftd.ai` may use the selected track only by
mixing it into a produced video.  The app must not place the original MP3 in a
public static path or expose it via raw blob URLs.
