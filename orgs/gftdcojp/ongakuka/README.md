# ongakuka.gftd.ai

`ongakuka` is the cljc music/BGM actor used by `yukkuri.gftd.ai` and
`animeka.gftd.ai`.

The service does not treat downloaded music files as public static assets.
They are internal production materials for embedding as background music into
videos.  The catalog records license constraints, required credit, and a local
DataLad/git-annex locator.  Runtime selection returns an asset plan; renderers
must mux the chosen file into a video and must not expose the raw file to end
users.

Python placeholder generation is retired.  New composition/selection logic lives
in portable Clojure/ClojureScript (`src/ongakuka/*.cljc`) with EDN as the source
of truth.

## API shape

```clojure
(ongakuka.compose/compose
 {:channel/id "cyber"
  :duration/sec 180
  :mood :calm-tech
  :usage/context :youtube-background})
```

Returns a map containing `:asset/id`, `:asset/path`, `:credit/text`, and
`:policy/render-only?`.

## Policy

- Use as background music in videos only.
- Do not publish raw files from app static folders/CDNs.
- Do not use source files for AI training.
- Do not register outputs in Content ID or any equivalent fingerprinting system.
- Prefer generated `ongakuka` music when license constraints are incompatible
  with a target channel.

## Operations

- BGM import + B2 annex copy: `docs/b2-annex-runbook.md`
- Catalog and policy decision record: `docs/260629-cljc-ongakuka-bgm-catalog.md`
