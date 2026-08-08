(ns clj-infisical.auth
  "Universal Auth login. See doc/SPEC.md §5.4."
  (:require [clj-infisical.http :as http]
            [clojure.data.json :as json]))

(defn login-request
  [site-url client-id client-secret]
  {:url (str site-url "/api/v1/auth/universal-auth/login")
   :json-body-map {"clientId" client-id "clientSecret" client-secret}})

(defn- try-parse-json [body]
  (try (json/read-str body) (catch Exception _ nil)))

(defn parse-login-response
  [{:keys [status body]}]
  (if (= status 200)
    (let [parsed (try-parse-json body)]
      (if (map? parsed)
        {:token (get parsed "accessToken")
         :expires-in (get parsed "expiresIn")
         :token-type (get parsed "tokenType")}
        {:type :clj-infisical/invalid-response :status status :body body :parsed parsed}))
    {:type :clj-infisical/auth-failed :status status :body body :parsed (try-parse-json body)}))

(defn login!
  [site-url creds]
  (let [{:keys [url json-body-map]} (login-request site-url (:client-id creds) (:client-secret creds))
        response (http/post-json! url json-body-map)
        result (parse-login-response response)]
    (if (:type result)
      (throw (ex-info (str "Infisical login failed: " (name (:type result))) result))
      result)))
