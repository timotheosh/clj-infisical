(ns clj-infisical.secrets-test
  "Tests for clj-infisical.secrets, written directly from doc/SPEC.md §8.5/§8.6."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.secrets :as secrets]
            [clj-infisical.http :as http]
            [clj-infisical.test-util :as tu]))

;; ---------------------------------------------------------------------
;; §8.5 calculations
;; ---------------------------------------------------------------------

(deftest secret-request-test
  (testing "builds url/query-params/headers per SPEC §3.2 (workspaceId, no viewSecretValue)"
    (let [config {:workspace-id "ws1" :environment "dev"
                   :secret-path "/stepca-cockroachdb" :site-url "https://kms.example.lan"}
          token {:token "abc123" :expires-in 7200 :token-type "Bearer"}
          req (secrets/secret-request config token "password")]
      (is (= "https://kms.example.lan/api/v3/secrets/raw/password" (:url req)))
      (is (= {"workspaceId" "ws1" "environment" "dev" "secretPath" "/stepca-cockroachdb"}
             (:query-params req)))
      (is (= {"Authorization" "Bearer abc123"} (:headers req))))))

(deftest parse-secret-response-success-test
  (testing "200 with only secretValue"
    (is (= {:secret-value "shh"}
           (secrets/parse-secret-response
            {:status 200 :body "{\"secret\":{\"secretValue\":\"shh\"}}"})))))

(deftest parse-secret-response-passthrough-test
  (testing "extra keys survive, keywordized -- the 'raw' contract"
    (let [result (secrets/parse-secret-response
                  {:status 200
                   :body "{\"secret\":{\"secretValue\":\"shh\",\"secretKey\":\"password\",\"version\":3}}"})]
      (is (= "shh" (:secret-value result)))
      (is (= "password" (:secret-key result)))
      (is (= 3 (:version result))))))

(deftest parse-secret-response-not-found-test
  (testing "404 with a JSON body -> secret-not-found, real message preserved in :parsed"
    (let [result (secrets/parse-secret-response
                  {:status 404 :body "{\"message\":\"secret not found\"}"})]
      (is (= :clj-infisical/secret-not-found (:type result)))
      (is (= 404 (:status result)))
      (is (= {"message" "secret not found"} (:parsed result))))))

(deftest parse-secret-response-http-error-test
  (testing "500 with a JSON body -> http-error, :parsed populated"
    (let [result (secrets/parse-secret-response
                  {:status 500 :body "{\"message\":\"internal error\"}"})]
      (is (= :clj-infisical/http-error (:type result)))
      (is (= {"message" "internal error"} (:parsed result))))))

(deftest parse-secret-response-missing-secret-key-test
  (testing "200 body missing the secret key entirely -> invalid-response"
    (is (= :clj-infisical/invalid-response
           (:type (secrets/parse-secret-response {:status 200 :body "{}"}))))))

(deftest parse-secret-response-missing-secret-value-test
  (testing "200 body has a secret object but no secretValue -> invalid-response"
    (is (= :clj-infisical/invalid-response
           (:type (secrets/parse-secret-response
                   {:status 200 :body "{\"secret\":{\"secretKey\":\"password\"}}"}))))))

;; ---------------------------------------------------------------------
;; §8.6 actions
;; ---------------------------------------------------------------------

(deftest fetch-secret!-delegates-test
  (testing "delegates to get-json! + parse-secret-response, doesn't reimplement"
    (with-redefs [http/get-json!
                  (fn [_url _query-params _headers]
                    {:status 200 :body "{\"secret\":{\"secretValue\":\"shh\",\"version\":3}}"})]
      (is (= {:secret-value "shh" :version 3}
             (secrets/fetch-secret! {:workspace-id "ws1" :environment "dev" :secret-path "/"
                                      :site-url "https://kms.example.lan"}
                                     {:token "abc" :expires-in 7200 :token-type "Bearer"}
                                     "password"))))))

(deftest fetch-secret!-throws-on-404-test
  (testing "secret-not-found ErrorData becomes a thrown ex-info"
    (with-redefs [http/get-json! (fn [_url _query-params _headers] {:status 404 :body "{}"})]
      (let [{:keys [error]} (tu/try-invoke
                              #(secrets/fetch-secret!
                                {:workspace-id "ws1" :environment "dev" :secret-path "/"
                                 :site-url "https://kms.example.lan"}
                                {:token "abc" :expires-in 7200 :token-type "Bearer"}
                                "missing"))]
        (is (= :clj-infisical/secret-not-found (:type error)))))))
