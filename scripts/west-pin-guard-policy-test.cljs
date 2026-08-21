#!/usr/bin/env nbb
(require '[scripts.west-pin-guard-policy :as policy]
         '[scripts.nbb-compat :as compat])

(def cases
  [{:name "child repo has no west blobs"
    :head "" :main "" :expected false}
   {:name "unrelated superproject push"
    :head "same" :main "same" :expected false}
   {:name "west-changing superproject push"
    :head "candidate" :main "baseline" :expected true}])

(doseq [{:keys [name head main expected]} cases]
  (let [actual (boolean (policy/requires-verification? head main))]
    (when-not (= expected actual)
      (println (str "FAIL " name " expected=" expected " actual=" actual))
      (compat/exit 1))
    (println (str "OK " name))))

(def payload-cases
  [{:name "payload file read -> verify it"
    :named? true :read? true :expected :verify}
   {:name "payload path named but unreadable -> cannot verify, do not fail open"
    :named? true :read? false :expected :unreadable}
   {:name "no payload path (inline / $VAR / wrapper) -> nothing to read"
    :named? false :read? false :expected :unknown}])

(doseq [{:keys [name named? read? expected]} payload-cases]
  (let [actual (policy/payload-decision named? read?)]
    (when-not (= expected actual)
      (println (str "FAIL " name " expected=" expected " actual=" actual))
      (compat/exit 1))
    (println (str "OK " name))))

(def deletion-cases
  [;; deletions — nothing to verify
   {:name "--delete flag" :cmd "git push origin --delete agent/x" :expected true}
   {:name "-d flag" :cmd "git push -d origin agent/x" :expected true}
   {:name "--delete before remote" :cmd "git push --delete origin agent/x" :expected true}
   {:name "empty-source refspec" :cmd "git push origin :agent/x" :expected true}
   {:name "empty-source full ref" :cmd "git push origin :refs/heads/agent/x" :expected true}
   {:name "git -C <dir> deletion" :cmd "git -C /tmp/wt push origin --delete agent/x" :expected true}
   ;; ordinary pushes — must still be verified
   {:name "plain push" :cmd "git push origin main" :expected false}
   {:name "src:dst refspec" :cmd "git push origin HEAD:main" :expected false}
   {:name "push with -u" :cmd "git push -u origin agent/x" :expected false}
   {:name "force-with-lease is not a deletion"
    :cmd "git push --force-with-lease origin agent/x" :expected false}
   ;; the separators the tail is cut at, so an unrelated later command cannot
   ;; make a real push look like a deletion
   {:name "deletion word after && does not count"
    :cmd "git push origin main && echo --delete" :expected false}
   {:name "colon after ; does not count"
    :cmd "git push origin main ; echo :done" :expected false}
   ;; and the inverse: text BEFORE push must not count either
   {:name "colon before push does not count"
    :cmd "echo :note ; git push origin main" :expected false}])

(doseq [{:keys [name cmd expected]} deletion-cases]
  (let [actual (policy/deletion-push? cmd)]
    (when-not (= expected actual)
      (println (str "FAIL " name " expected=" expected " actual=" actual
                    " cmd=" (pr-str cmd)))
      (compat/exit 1))
    (println (str "OK " name))))

(println "west-pin-guard-policy: 19 cases OK")

