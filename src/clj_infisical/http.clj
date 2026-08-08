(ns clj-infisical.http
  "Thin transport actions. This is the only namespace allowed to import
   clj-http-lite -- see doc/SPEC.md §5.3."
  (:require [clj-http.lite.client :as client]
            [clojure.data.json :as json]))

(defn post-json!
  "POSTs json-body-map as a JSON body. Never throws on a non-2xx status --
   callers decide what counts as an error, not clj-http-lite."
  [url json-body-map]
  (let [{:keys [status body]} (client/post url {:body (json/write-str json-body-map)
                                                 :content-type "application/json"
                                                 :throw-exceptions false})]
    {:status status :body body}))

(defn get-json!
  "GETs url with query-params/headers. Never throws on a non-2xx status."
  [url query-params headers]
  (let [{:keys [status body]} (client/get url {:query-params query-params
                                                :headers headers
                                                :throw-exceptions false})]
    {:status status :body body}))
