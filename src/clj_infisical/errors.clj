(ns clj-infisical.errors
  "Shared machinery for building this library's ErrorData (SPEC §5.1/§7)
   and converting it into a thrown ex-info at each action boundary. Used by
   clj-infisical.{credentials,auth,secrets} -- extracted because all three
   independently reimplemented the same 'is this an error, and if so throw
   it' check, and auth/secrets independently reimplemented the same
   safe-JSON-parse-for-a-response-body helper."
  (:require [clojure.data.json :as json]))

(defn parse-json
  "clojure.data.json/read-str, returning nil instead of throwing when body
   isn't valid JSON -- an HTTP response body is never guaranteed to be
   JSON (proxies, gateways, etc. can return anything)."
  [body]
  (try
    (json/read-str body)
    (catch Exception _ nil)))

(defn error-data
  "Builds an ErrorData map (SPEC §5.1): :type plus the :status/:body/:parsed
   fields every HTTP-originated error branch in auth/secrets carries."
  [type status body parsed]
  {:type type :status status :body body :parsed parsed})

(defn error?
  [result]
  (boolean (:type result)))

(defn unwrap!
  "Given a calculation's result (either good Data, or ErrorData per SPEC
   §5.1), returns it unchanged, or throws an ex-info carrying it if it's an
   error. The one place resolve-credentials!/login!/fetch-secret! turn a
   returned ErrorData into a thrown one."
  [message-prefix result]
  (if (error? result)
    (throw (ex-info (str message-prefix ": " (name (:type result))) result))
    result))
