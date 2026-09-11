(ns minimax-m2-modal.host-config)

(defn from-environment
  "Acquire process configuration at the CLJ host boundary. Portable `.cljc`
   namespaces receive only the resulting closed value."
  []
  {:url (or (System/getenv "LLM_URL") (System/getenv "MINIMAX_URL"))
   :model (System/getenv "LLM_MODEL")
   :api-key (System/getenv "LLM_KEY")})
