(ns clj-infisical.auth
  "Universal Auth login. See doc/SPEC.md §5.4."
  (:require [clj-infisical.errors :as errors]
            [clj-infisical.http :as http]))

(defn login-request
  [site-url client-id client-secret]
  {:url (str site-url "/api/v1/auth/universal-auth/login")
   :json-body-map {"clientId" client-id "clientSecret" client-secret}})

(defn parse-login-response
  [{:keys [status body]}]
  (if (= status 200)
    (let [parsed (errors/parse-json body)]
      (if (map? parsed)
        {:token (get parsed "accessToken")
         :expires-in (get parsed "expiresIn")
         :token-type (get parsed "tokenType")}
        (errors/error-data :clj-infisical/invalid-response status body parsed)))
    (errors/error-data :clj-infisical/auth-failed status body (errors/parse-json body))))

(defn login!
  [site-url creds]
  (let [{:keys [url json-body-map]} (login-request site-url (:client-id creds) (:client-secret creds))
        response (http/post-json! url json-body-map)]
    (errors/unwrap! "Infisical login failed" (parse-login-response response))))
