(ns scripts.west-pin-guard-policy
  (:require [clojure.string :as str]))

(defn- push-tail
  "The arguments of the `git push`, up to the next shell separator.

  Everything this predicate looks for is an ARGUMENT of the push, so anything
  before `push` or after `&&` must not be able to trigger it."
  [cmd]
  (some-> (re-find #"git\s+(?:-C\s+\S+\s+)?push\b([\s\S]*)$" cmd)
          second
          (str/split #"&&|;|\|")
          first))

(defn deletion-push?
  "True when this `git push` only DELETES refs, so it carries no content.

  A deletion has nothing to verify — there is no candidate `west.yml` in it —
  but the guard used to run the ordinary HEAD-vs-origin/main comparison anyway,
  against whatever the current checkout happened to be. Measured 2026-08-08:
  deleting a merged feature branch from a checkout that was behind `origin/main`
  reported SEVEN pins as regressions and denied the deletion. None of them had
  anything to do with the command; the checkout was simply stale.

  Two spellings, both of which mean deletion:
  - the `--delete` / `-d` flag
  - a refspec with an empty SOURCE side, `:branch` or `:refs/heads/branch`

  A colon INSIDE a refspec (`HEAD:main`, `src:dst`) is an ordinary push and has
  no whitespace before the colon, so it does not match."
  [cmd]
  (if-let [tail (push-tail cmd)]
    (boolean (or (re-find #"(?:^|\s)--delete(?:\s|=|$)" tail)
                 (re-find #"(?:^|\s)-d(?:\s|$)" tail)
                 (re-find #"\s:[^\s:]+" tail)))
    false))

(defn requires-verification?
  "Only a superproject push that changes manifest/west.yml needs pin
  verification. Child repositories have no blobs; unrelated pushes have equal
  blobs. This predicate is deliberately tiny so G-O cannot regress unnoticed."
  [head-blob main-blob]
  (and (not (clojure.string/blank? head-blob))
       (not (clojure.string/blank? main-blob))
       (not= head-blob main-blob)))

(defn payload-decision
  "What the guard can conclude about a `gh api PUT` whose body lives in a file.

  A PreToolUse hook runs *before* the command, so it sees the payload file as
  it is now. Three states, and only one of them was handled:

  - `:verify`     — the file exists and was read; check that content
  - `:unreadable` — the command names a payload path that is missing or
                    unparseable. This is the hole: it used to fail open, so a
                    single command that both writes the payload and PUTs it was
                    never checked at all. Measured 2026-08-03 — the guard read
                    a *previous* run's file and reported pins that were in
                    neither the tip nor the new payload.
  - `:unknown`    — no payload path in the command (inline content, `$VAR`,
                    a wrapper script). Nothing to read; fail open as before.

  `named?` is whether the command names a payload path at all; `read?` is
  whether content was actually obtained from it."
  [named? read?]
  (cond read?  :verify
        named? :unreadable
        :else  :unknown))
