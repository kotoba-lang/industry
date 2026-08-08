(ns isekai.games.kingdom-cascade.rng
  "Deterministic, portable pseudo-random source for Kingdom Cascade.

  Lehmer / Park-Miller: `s' = (s * 48271) mod (2^31 - 1)`.

  The multiplier is chosen so every intermediate product stays under 2^53
  (48271 * 2^31 is about 1.04e14), which means the sequence is bit-identical
  on the JVM (longs) and in ClojureScript (doubles). That parity is the whole
  point: a level's replay digest has to match across headless nbb, the browser
  and the packaged mobile shell, and an RNG that overflows differently per
  runtime silently breaks that.

  Every function is pure and threads the state explicitly — there is no
  ambient generator anywhere in this game."
  #?(:clj (:refer-clojure :exclude [shuffle])))

(def modulus 2147483647)
(def multiplier 48271)

(defn seed
  "Normalises `n` into a usable state. State 0 is a fixed point of the
  recurrence, so it is mapped away."
  [n]
  (let [s (mod (long n) modulus)]
    (if (zero? s) 1 s)))

(defn step
  "Advances the generator one position."
  [s]
  (mod (* s multiplier) modulus))

(defn draw
  "Returns `[v s']` with `v` uniform-ish in `[0, n)`.

  Takes the high bits of the advanced state; the low bits of a Lehmer
  generator are the weak ones and `n` here is typically 4-6 (the colour
  count), which is exactly where that would show."
  [s n]
  (let [s' (step s)]
    [(mod (quot s' 64) n) s']))

(defn pick
  "Returns `[element s']` chosen from the indexed collection `coll`."
  [s coll]
  (let [v (vec coll)
        [i s'] (draw s (count v))]
    [(nth v i) s']))

(defn shuffle
  "Fisher-Yates. Returns `[shuffled-vector s']`."
  [s coll]
  (loop [v (vec coll)
         i (dec (count coll))
         s s]
    (if (pos? i)
      (let [[j s'] (draw s (inc i))]
        (recur (assoc v i (nth v j) j (nth v i)) (dec i) s'))
      [v s])))
