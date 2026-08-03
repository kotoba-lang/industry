(ns scripts.west-pin-guard-policy)

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
