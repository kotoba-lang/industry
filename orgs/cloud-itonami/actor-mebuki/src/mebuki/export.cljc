(ns mebuki.export
  "Serialising the model as an OASIS XMILE 1.0 document, so that the same
  structure this repository simulates can be opened in any tool that reads
  XMILE rather than only by this repository.

  That interchange is the reason for choosing XMILE over a private model
  format: the recovery structure is an argument about how a place works, and
  an argument nobody outside the authoring tool can open is not reviewable."
  (:require [mebuki.model :as model]
            [xmile.xml :as xml]
            [xmile.validate :as v]))

(defn xmile-string
  "XMILE 1.0 XML text for `params`. Throws if the document does not validate,
  because emitting a file that says <xmile version=\"1.0\"> and is not one is
  worse than emitting nothing."
  ([params] (xmile-string params nil))
  ([params opts]
   (let [doc (model/document params opts)
         problems (v/validate-doc doc)]
     (when (seq (v/errors problems))
       (throw (ex-info "mebuki.export: refusing to emit an invalid XMILE document"
                       {:errors (v/errors problems)})))
     (xml/emit-string doc))))

#?(:clj
   (defn write-xmile!
     [path params opts]
     (spit path (xmile-string params opts))
     path))
