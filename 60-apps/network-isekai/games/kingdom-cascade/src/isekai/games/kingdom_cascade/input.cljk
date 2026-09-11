(ns isekai.games.kingdom-cascade.input
  "Pointer gestures -> game actions.

  Two gestures, which is the whole vocabulary the genre needs: drag a piece
  toward a neighbour to swap, tap a special to fire it. Both work identically
  under a mouse and a finger, so there is no separate touch path to keep in
  sync.

  Renderer-independent on purpose. It takes screen points and returns an
  action; it never touches the board and never calls the game. The host does:

      (let [[ui' action] (input/pointer-up ui board [x y])]
        (case (first action)
          :swap (core/swap state (second action) (nth action 2))
          :tap  (core/tap state (second action))
          state))

  A drag commits on the *first* axis to cross the threshold rather than on
  release. Waiting for release makes a fast flick feel dropped, which is the
  single most common complaint about a match-3 control scheme."
  (:require [isekai.games.kingdom-cascade.render-ir :as ir]))

(def drag-threshold
  "Screen distance before a drag counts as a direction. Just under half a
  cell: far enough that a tap with a shaky finger is still a tap, near enough
  that a deliberate drag registers before it leaves the tile."
  (quot ir/cell-size 3))

(defn- direction
  "Dominant axis of a drag, or nil below the threshold. The larger component
  wins outright — a diagonal drag picks one neighbour rather than none."
  [[dx dy]]
  (let [ax (if (neg? dx) (- dx) dx)
        ay (if (neg? dy) (- dy) dy)]
    (cond
      (and (< ax drag-threshold) (< ay drag-threshold)) nil
      (>= ax ay) (if (pos? dx) [1 0] [-1 0])
      :else (if (pos? dy) [0 1] [0 -1]))))

(def idle
  "Initial UI state. Kept separate from the game state: a half-finished drag
  is not a fact about the board."
  {:drag nil})

(defn pointer-down
  "Begins a gesture. Returns `[ui' action]`; the action is always nil — a
  press alone commits to nothing."
  [ui board screen]
  (if-let [pos (ir/hit-test board screen)]
    [{:drag {:from pos :origin screen :moved? false}} nil]
    [idle nil]))

(defn pointer-move
  "Continues a gesture. Emits `[:swap a b]` as soon as the drag crosses the
  threshold, and clears the drag so the same press cannot fire twice."
  [ui board [x y]]
  (if-let [{:keys [from origin]} (:drag ui)]
    (let [[ox oy] origin]
      (if-let [[dx dy] (direction [(- x ox) (- y oy)])]
        (let [target [(+ (first from) dx) (+ (second from) dy)]]
          [idle [:swap from target]])
        [(assoc-in ui [:drag :moved?] false) nil]))
    [ui nil]))

(defn pointer-up
  "Ends a gesture. A press that never crossed the threshold is a tap."
  [ui _board _screen]
  (if-let [{:keys [from]} (:drag ui)]
    [idle [:tap from]]
    [idle nil]))

(defn pointer-cancel
  "Drops the gesture — the pointer left the surface, or a modal opened."
  [_ui]
  [idle nil])

(defn apply-action
  "Runs an action against a game state. Unknown or nil actions are the
  identity, so a host may pass the result of any gesture straight through."
  [state action swap-fn tap-fn]
  (case (first action)
    :swap (swap-fn state (nth action 1) (nth action 2))
    :tap (tap-fn state (nth action 1))
    state))
