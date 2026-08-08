// Two functions squint-cljs 0.8.147 does not implement, supplied to the bundle through
// esbuild's `--inject`, which replaces exactly the identifiers a module leaves undefined.
//
// This is not a convenience. **Squint compiles an unresolved symbol to a bare JavaScript
// identifier and says nothing about it** — no warning at compile time, no error, no entry in
// the output. A core function it happens not to have becomes a `ReferenceError` the first
// time that line runs, and if the line sits on a branch the smoke test does not take, it
// becomes a wrong number instead. The engine's `.cljc` is written against Clojure, so it is
// entitled to assume both of these exist.
//
// Found the only way they can be found: by running the engine and reading the stack trace.
// `pos-int?` surfaced from `kotoba.render.texture`, `double` from
// `kotoba.render.instance/normalize-uv-transform`, which `pack-instances` calls for every
// instance in the street.
//
// What keeps this file honest is `test/parity.cljs`: the same render-IR is packed by the
// JVM engine (`clojure -M:parity-dump`) and by this bundle, and every float is compared. A
// shim with the wrong semantics changes numbers, and changed numbers fail that test. Adding
// anything here without extending the parity dump to cover it would put us back to trusting
// a guess.

// (pos-int? x) — a positive fixed-precision integer. Clojure's admits any integer type;
// JavaScript has one number type, so integrality is the whole of the test.
export function pos_int_QMARK_(x) {
  return typeof x === 'number' && Number.isInteger(x) && x > 0;
}

// (double x) — coerce to a double. Every JavaScript number already is one, so this is a
// numeric coercion of whatever was passed. Clojure throws on a non-number; `Number` yields
// NaN, which propagates into the packed floats rather than stopping. The parity test is what
// catches the difference if it ever matters.
export function double$(x) {
  return Number(x);
}

// (keyword s) — squint has no `keyword` at all. It does not need one internally: a keyword
// in squint IS its name string, so `:isic-9601` compiles to `"isic-9601"` and a map keyed by
// `(keyword id)` must be keyed by `id`. That equivalence is the whole shim.
//
// It cannot simply be deleted from the source instead. `world.cljc` is .cljc, and on the JVM
// and under nbb a keyword is not a string: `kotoba.sprite2d` and `test/world_ir_test.clj`
// both look these sprites up as `:isic-9601`. Removing the call was tried and broke that
// suite in eleven places.
export function keyword(a, b) {
  return b === undefined ? String(a) : String(a) + "/" + String(b);
}
