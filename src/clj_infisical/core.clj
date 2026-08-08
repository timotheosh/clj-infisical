(ns clj-infisical.core
  "Public facade: get-secret!/get-secret-raw!. See doc/SPEC.md §5.6."
  (:require [clj-infisical.auth :as auth]
            [clj-infisical.credentials :as creds]
            [clj-infisical.secrets :as secrets]))

(defn- -fetch-secret!
  [{:keys [workspace-id environment secret-path secret-name
           site-url client-id client-secret]
    :or {environment "dev"
         secret-path "/"
         site-url "https://app.infisical.com"}}]
  (when (or (nil? workspace-id) (nil? secret-name))
    (throw (ex-info "workspace-id and secret-name are required"
                     {:type :clj-infisical/invalid-arguments
                      :missing-keys (cond-> []
                                      (nil? workspace-id) (conj :workspace-id)
                                      (nil? secret-name) (conj :secret-name))})))
  (let [resolved-creds (if (and client-id client-secret)
                          {:client-id client-id :client-secret client-secret :source :explicit}
                          (creds/resolve-credentials!))
        token (auth/login! site-url resolved-creds)
        config {:workspace-id workspace-id :environment environment
                :secret-path secret-path :site-url site-url}]
    (secrets/fetch-secret! config token secret-name)))

(defn get-secret-raw!
  "Fetches a secret from Infisical, returning the full decoded response
   object -- every field Infisical returns, not just the value."
  [args]
  (-fetch-secret! args))

(defn get-secret!
  "Fetches a secret from Infisical, returning its plaintext value."
  [args]
  (:secret-value (-fetch-secret! args)))
