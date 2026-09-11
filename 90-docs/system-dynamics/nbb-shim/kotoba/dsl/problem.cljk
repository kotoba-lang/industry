(ns kotoba.dsl.problem
  "nbb shim for kotoba-lang/dsl-core's problem.kotoba (a .kotoba source file
  that nbb cannot load). Same observable contract: domain-namespaced keys,
  :severity/:code/:id/:msg, and the errors/warnings/valid? selectors.")

(defn domain-key [domain k] (keyword (str (name domain) "/" (name k))))

(defn problem [domain severity code subject msg]
  {(domain-key domain :severity) severity
   (domain-key domain :code) code
   (domain-key domain :id) subject
   (domain-key domain :msg) msg})

(defn has-severity? [domain v wanted]
  (= wanted (get v (domain-key domain :severity))))

(defn error? [domain v] (has-severity? domain v :error))
(defn warning? [domain v] (has-severity? domain v :warn))

(defn errors   [domain problems] (vec (filter #(has-severity? domain % :error) problems)))
(defn warnings [domain problems] (vec (filter #(has-severity? domain % :warn) problems)))
(defn valid?   [domain problems] (zero? (count (errors domain problems))))
