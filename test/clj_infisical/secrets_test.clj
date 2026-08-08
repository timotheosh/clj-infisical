(ns clj-infisical.secrets-test
  "Tests for clj-infisical.secrets, written directly from doc/SPEC.md §8.5/§8.6."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.secrets :as secrets]
            [clj-infisical.http :as http]
            [clj-infisical.test-util :as tu]))

(def ^:private config-fixture
  {:project-id "ws1" :environment "dev" :secret-path "/" :site-url "https://kms.example.lan"})

;; ---------------------------------------------------------------------
;; §8.5 calculations
;; ---------------------------------------------------------------------

(deftest secret-request-test
  (testing "builds url/query-params/headers per SPEC §3.2 (workspaceId, no viewSecretValue)"
    (let [config (assoc config-fixture :secret-path "/stepca-cockroachdb")
          req (secrets/secret-request config tu/access-token-fixture "password")]
      (is (= "https://kms.example.lan/api/v3/secrets/raw/password" (:url req)))
      (is (= {"workspaceId" "ws1" "environment" "dev" "secretPath" "/stepca-cockroachdb"}
             (:query-params req)))
      (is (= {"Authorization" (str "Bearer " (:token tu/access-token-fixture))} (:headers req))))))

(deftest parse-secret-response-success-test
  (testing "200 with only secretValue"
    (is (= {:secret-value "shh"}
           (secrets/parse-secret-response
            {:status 200 :body (tu/json-body {"secret" {"secretValue" "shh"}})})))))

(deftest parse-secret-response-passthrough-test
  (testing "extra keys survive, keywordized -- the 'raw' contract"
    (let [result (secrets/parse-secret-response
                  {:status 200
                   :body (tu/json-body {"secret" {"secretValue" "shh" "secretKey" "password" "version" 3}})})]
      (is (= "shh" (:secret-value result)))
      (is (= "password" (:secret-key result)))
      (is (= 3 (:version result))))))

(deftest parse-secret-response-type-field-passthrough-test
  (testing "regression: a real Infisical secret's own \"type\" field
            (shared/personal) must not be mistaken for ErrorData's :type"
    (let [result (secrets/parse-secret-response
                  {:status 200
                   :body (tu/json-body {"secret" {"secretValue" "shh" "type" "shared"}})})]
      (is (= "shh" (:secret-value result)))
      (is (= "shared" (:type result)))
      (is (nil? (:status result)) "not an ErrorData -- has no :status/:body/:parsed"))))

(deftest parse-secret-response-not-found-test
  (testing "404 with a JSON body -> secret-not-found, real message preserved in :parsed"
    (let [result (secrets/parse-secret-response
                  {:status 404 :body (tu/json-body {"message" "secret not found"})})]
      (is (= :clj-infisical/secret-not-found (:type result)))
      (is (= 404 (:status result)))
      (is (= {"message" "secret not found"} (:parsed result))))))

(deftest parse-secret-response-http-error-test
  (testing "500 with a JSON body -> http-error, :parsed populated"
    (let [result (secrets/parse-secret-response
                  {:status 500 :body (tu/json-body {"message" "internal error"})})]
      (is (= :clj-infisical/http-error (:type result)))
      (is (= {"message" "internal error"} (:parsed result))))))

(deftest parse-secret-response-missing-secret-key-test
  (testing "200 body missing the secret key entirely -> invalid-response"
    (is (= :clj-infisical/invalid-response
           (:type (secrets/parse-secret-response {:status 200 :body (tu/json-body {})}))))))

(deftest parse-secret-response-missing-secret-value-test
  (testing "200 body has a secret object but no secretValue -> invalid-response"
    (is (= :clj-infisical/invalid-response
           (:type (secrets/parse-secret-response
                   {:status 200 :body (tu/json-body {"secret" {"secretKey" "password"}})}))))))

;; ---------------------------------------------------------------------
;; §8.6 actions
;; ---------------------------------------------------------------------

(deftest fetch-secret!-delegates-test
  (testing "delegates to get-json! + parse-secret-response, doesn't reimplement"
    (with-redefs [http/get-json!
                  (constantly {:status 200 :body (tu/json-body {"secret" {"secretValue" "shh" "version" 3}})})]
      (is (= {:secret-value "shh" :version 3}
             (secrets/fetch-secret! config-fixture tu/access-token-fixture "password"))))))

(deftest fetch-secret!-succeeds-with-type-field-test
  (testing "regression: end-to-end through fetch-secret!/errors/unwrap!, a
            shared secret (Infisical's own \"type\" field) does not get
            thrown as an error"
    (with-redefs [http/get-json!
                  (constantly {:status 200
                               :body (tu/json-body {"secret" {"secretValue" "shh" "type" "shared"}})})]
      (is (= {:secret-value "shh" :type "shared"}
             (secrets/fetch-secret! config-fixture tu/access-token-fixture "password"))))))

(deftest fetch-secret!-throws-on-404-test
  (testing "secret-not-found ErrorData becomes a thrown ex-info"
    (with-redefs [http/get-json! (constantly {:status 404 :body (tu/json-body {})})]
      (let [{:keys [error]} (tu/try-invoke
                              #(secrets/fetch-secret! config-fixture tu/access-token-fixture "missing"))]
        (is (= :clj-infisical/secret-not-found (:type error)))))))
