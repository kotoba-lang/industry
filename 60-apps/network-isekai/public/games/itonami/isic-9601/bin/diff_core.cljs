(ns diff-core
  "Pixel diff for ADR-2608108000 / #1754 — compare two RGBA frames.

  Lives under `bin/` (not `src/`) so squint's page compile never sees it — this
  module is nbb/Node only. Pure on buffers: `compare-rgba` takes W/H and two
  Uint8(Clamped)Array-shaped byte sequences (length W*H*4) and returns
  `{:changed-pixels n :max-delta d :regions [[x y w h] …]}`.

  A pixel counts as changed when the max absolute channel delta across R,G,B
  (alpha ignored) is **strictly greater** than `threshold`. Identical inputs
  with threshold 0 therefore yield zero changed pixels.

  Regions are 4-connected component bounding boxes of changed pixels, sorted by
  area descending. A one-instance colour change should produce a region that
  covers that instance and nothing else (modulo coverage fringe — measured in
  goldens/THRESHOLD.edn)."
  (:require ["pngjs" :refer [PNG]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- max-ch-delta [a b i]
  (js/Math.max
   (js/Math.abs (- (aget a i) (aget b i)))
   (js/Math.abs (- (aget a (+ i 1)) (aget b (+ i 1))))
   (js/Math.abs (- (aget a (+ i 2)) (aget b (+ i 2))))))

(defn- flood-bbox!
  "4-connected flood fill; returns [minx miny maxx maxy] or nil.
  Neighbours are pushed one-at-a-time — nbb/SCI `.push` with multiple args is unreliable."
  [changed W H sx sy visited]
  (let [qx #js [sx]
        qy #js [sy]
        minx (atom sx) miny (atom sy) maxx (atom sx) maxy (atom sy)
        found? (atom false)
        push! (fn [x y] (.push qx x) (.push qy y))]
    (loop [qi 0]
      (when (< qi (.-length qx))
        (let [x (aget qx qi)
              y (aget qy qi)]
          (when (and (>= x 0) (< x W) (>= y 0) (< y H))
            (let [idx (+ x (* y W))]
              (when (and (pos? (aget changed idx)) (zero? (aget visited idx)))
                (aset visited idx 1)
                (reset! found? true)
                (push! (dec x) y)
                (push! (inc x) y)
                (push! x (dec y))
                (push! x (inc y))
                (when (< x @minx) (reset! minx x))
                (when (> x @maxx) (reset! maxx x))
                (when (< y @miny) (reset! miny y))
                (when (> y @maxy) (reset! maxy y)))))
          (recur (inc qi)))))
    (when @found?
      [@minx @miny @maxx @maxy])))

(defn regions-from-mask [changed W H]
  (let [visited (.fill (js/Uint8Array. (* W H)) 0)
        out (atom [])]
    (dotimes [y H]
      (dotimes [x W]
        (let [idx (+ x (* y W))]
          (when (and (pos? (aget changed idx)) (zero? (aget visited idx)))
            (when-let [[x0 y0 x1 y1] (flood-bbox! changed W H x y visited)]
              (swap! out conj [x0 y0 (inc (- x1 x0)) (inc (- y1 y0))]))))))
    (->> @out
         (sort-by (fn [[_ _ w h]] (- (* w h))))
         vec)))

(defn compare-rgba
  "Diff two RGBA buffers. `threshold` is inclusive quiet-band (0 = exact)."
  ([W H a b] (compare-rgba W H a b 0))
  ([W H a b threshold]
   (let [n (* W H)
         expect (* n 4)]
     (when (not= (.-length a) expect)
       (throw (js/Error. (str "buffer a length " (.-length a) " ≠ " expect))))
     (when (not= (.-length b) expect)
       (throw (js/Error. (str "buffer b length " (.-length b) " ≠ " expect))))
     (let [changed (.fill (js/Uint8Array. n) 0)
           max-d (atom 0)
           count* (atom 0)]
       (dotimes [p n]
         (let [i (* p 4)
               d (max-ch-delta a b i)]
           (when (> d @max-d) (reset! max-d d))
           (when (> d threshold)
             (aset changed p 1)
             (swap! count* inc))))
       {:changed-pixels @count*
        :max-delta @max-d
        :regions (if (pos? @count*)
                   (regions-from-mask changed W H)
                   [])}))))

(defn paint-diff-rgba
  "RGBA visualisation: dimmed baseline + magenta changed pixels."
  ([W H a b] (paint-diff-rgba W H a b 0))
  ([W H a b threshold]
   (let [out (js/Uint8ClampedArray. (* W H 4))]
     (dotimes [p (* W H)]
       (let [i (* p 4)
             d (max-ch-delta a b i)]
         (if (> d threshold)
           (do (aset out i 255)
               (aset out (+ i 1) 0)
               (aset out (+ i 2) 255)
               (aset out (+ i 3) 255))
           (do (aset out i (js/Math.floor (* 0.35 (aget a i))))
               (aset out (+ i 1) (js/Math.floor (* 0.35 (aget a (+ i 1)))))
               (aset out (+ i 2) (js/Math.floor (* 0.35 (aget a (+ i 2)))))
               (aset out (+ i 3) 255)))))
     out)))

(defn read-png
  "Decode a PNG file → `{:width :height :data}` (RGBA Uint8Array).

  nbb cannot call `.sync.read` as a dotted instance method — use `(.-sync PNG)`."
  [file]
  (let [buf (fs/readFileSync file)
        png (.read (.-sync PNG) buf)]
    {:width (.-width png)
     :height (.-height png)
     :data (.-data png)}))

(defn write-png!
  "Write RGBA Uint8(Clamped)Array `data` (W*H*4) to `file`."
  [file W H data]
  (let [png (PNG. #js {:width W :height H})
        dst (.-data png)]
    (.set dst data)
    (fs/mkdirSync (path/dirname file) #js {:recursive true})
    (fs/writeFileSync file (.write (.-sync PNG) png))
    file))

(defn compare-files
  "Load two PNGs, require matching size, return stats + optional `:diff-data`."
  ([a-path b-path] (compare-files a-path b-path 0 true))
  ([a-path b-path threshold] (compare-files a-path b-path threshold true))
  ([a-path b-path threshold with-diff?]
   (let [A (read-png a-path)
         B (read-png b-path)]
     (when (or (not= (:width A) (:width B))
               (not= (:height A) (:height B)))
       (throw (js/Error. (str "size mismatch "
                              (:width A) "x" (:height A) " vs "
                              (:width B) "x" (:height B)))))
     (let [W (:width A) H (:height A)
           stats (compare-rgba W H (:data A) (:data B) threshold)]
       (cond-> (assoc stats :width W :height H)
         with-diff? (assoc :diff-data (paint-diff-rgba W H (:data A) (:data B) threshold)))))))

(defn region-intersects?
  [[ax ay aw ah] [bx by bw bh]]
  (and (< ax (+ bx bw)) (< bx (+ ax aw))
       (< ay (+ by bh)) (< by (+ ay ah))))

(defn summarize [stats]
  (select-keys stats [:changed-pixels :max-delta :regions]))
