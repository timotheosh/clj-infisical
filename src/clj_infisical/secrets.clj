(ns clj-infisical.secrets
  "Fetching a secret's raw value via /api/v3/secrets/raw. See
   doc/SPEC.md §5.5."
  (:require [clj-infisical.errors :as errors]
            [clj-infisical.http :as http]
            [clojure.string :as str]))

(defn secret-request
  [config token secret-name]
  {:url (str (:site-url config) "/api/v3/secrets/raw/" secret-name)
   :query-params {"workspaceId" (:workspace-id config)
                  "environment" (:environment config)
                  "secretPath" (:secret-path config)}
   :headers {"Authorization" (str "Bearer " (:token token))}})

(defn- camel->kebab-keyword [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case
      keyword))

(defn- keywordize-camel [m]
  (into {} (map (fn [[k v]] [(camel->kebab-keyword k) v])) m))

(defn parse-secret-response
  [{:keys [status body]}]
  (let [parsed (errors/parse-json body)]
    (cond
      (= status 200)
      (let [secret (get parsed "secret")]
        (if (and (map? secret) (contains? secret "secretValue"))
          (keywordize-camel secret)
          (errors/error-data :clj-infisical/invalid-response status body parsed)))

      (= status 404)
      (errors/error-data :clj-infisical/secret-not-found status body parsed)

      :else
      (errors/error-data :clj-infisical/http-error status body parsed))))

(defn fetch-secret!
  [config token secret-name]
  (let [{:keys [url query-params headers]} (secret-request config token secret-name)
        response (http/get-json! url query-params headers)]
    (errors/unwrap! "Infisical secret fetch failed" (parse-secret-response response))))
