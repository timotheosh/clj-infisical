(ns clj-infisical.secrets
  "Fetching a secret's raw value via /api/v3/secrets/raw. See
   doc/SPEC.md §5.5."
  (:require [clj-infisical.http :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn secret-request
  [config token secret-name]
  {:url (str (:site-url config) "/api/v3/secrets/raw/" secret-name)
   :query-params {"workspaceId" (:workspace-id config)
                  "environment" (:environment config)
                  "secretPath" (:secret-path config)}
   :headers {"Authorization" (str "Bearer " (:token token))}})

(defn- try-parse-json [body]
  (try (json/read-str body) (catch Exception _ nil)))

(defn- camel->kebab-keyword [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case
      keyword))

(defn- keywordize-camel [m]
  (into {} (map (fn [[k v]] [(camel->kebab-keyword k) v])) m))

(defn parse-secret-response
  [{:keys [status body]}]
  (let [parsed (try-parse-json body)]
    (cond
      (= status 200)
      (let [secret (get parsed "secret")]
        (if (and (map? secret) (contains? secret "secretValue"))
          (keywordize-camel secret)
          {:type :clj-infisical/invalid-response :status status :body body :parsed parsed}))

      (= status 404)
      {:type :clj-infisical/secret-not-found :status status :body body :parsed parsed}

      :else
      {:type :clj-infisical/http-error :status status :body body :parsed parsed})))

(defn fetch-secret!
  [config token secret-name]
  (let [{:keys [url query-params headers]} (secret-request config token secret-name)
        response (http/get-json! url query-params headers)
        result (parse-secret-response response)]
    (if (:type result)
      (throw (ex-info (str "Infisical secret fetch failed: " (name (:type result))) result))
      result)))
