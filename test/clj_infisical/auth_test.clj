(ns clj-infisical.auth-test
  "Tests for clj-infisical.auth, written directly from doc/SPEC.md §8.3/§8.4."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.auth :as auth]
            [clj-infisical.http :as http]
            [clj-infisical.test-util :as tu]))

;; ---------------------------------------------------------------------
;; §8.3 calculations
;; ---------------------------------------------------------------------

(deftest login-request-test
  (testing "builds the request map, doesn't touch the network"
    (let [req (auth/login-request "https://kms.example.lan" "cid" "csecret")]
      (is (= "https://kms.example.lan/api/v1/auth/universal-auth/login" (:url req)))
      (is (= {"clientId" "cid" "clientSecret" "csecret"} (:json-body-map req))))))

(deftest parse-login-response-success-test
  (testing "200 with a valid body -> AccessToken"
    (is (= {:token "t" :expires-in 7200 :token-type "Bearer"}
           (auth/parse-login-response
            {:status 200
             :body "{\"accessToken\":\"t\",\"expiresIn\":7200,\"tokenType\":\"Bearer\"}"})))))

(deftest parse-login-response-auth-failed-test
  (testing "non-200 with a JSON body -> auth-failed, real message preserved in :parsed"
    (let [result (auth/parse-login-response
                  {:status 401 :body "{\"message\":\"bad creds\"}"})]
      (is (= :clj-infisical/auth-failed (:type result)))
      (is (= 401 (:status result)))
      (is (= "{\"message\":\"bad creds\"}" (:body result)))
      (is (= {"message" "bad creds"} (:parsed result))))))

(deftest parse-login-response-invalid-json-test
  (testing "200 but unparsable body -> invalid-response, not an uncaught exception"
    (let [result (auth/parse-login-response {:status 200 :body "not json"})]
      (is (= :clj-infisical/invalid-response (:type result))))))

(deftest parse-login-response-non-json-error-body-test
  (testing "non-200 with a non-JSON body (e.g. a proxy's HTML error page) degrades gracefully"
    (let [result (auth/parse-login-response
                  {:status 500 :body "<html>Bad Gateway</html>"})]
      (is (= :clj-infisical/auth-failed (:type result)))
      (is (nil? (:parsed result)))
      (is (= "<html>Bad Gateway</html>" (:body result))))))

;; ---------------------------------------------------------------------
;; §8.4 actions
;; ---------------------------------------------------------------------

(deftest login!-delegates-test
  (testing "delegates to post-json! + parse-login-response, doesn't reimplement"
    (with-redefs [http/post-json!
                  (fn [_url _body]
                    {:status 200
                     :body "{\"accessToken\":\"t\",\"expiresIn\":7200,\"tokenType\":\"Bearer\"}"})]
      (is (= {:token "t" :expires-in 7200 :token-type "Bearer"}
             (auth/login! "https://kms.example.lan"
                           {:client-id "cid" :client-secret "csecret" :source :env}))))))

(deftest login!-throws-on-failure-test
  (testing "auth-failed ErrorData becomes a thrown ex-info"
    (with-redefs [http/post-json!
                  (fn [_url _body] {:status 401 :body "{\"message\":\"bad creds\"}"})]
      (let [{:keys [error]} (tu/try-invoke
                              #(auth/login! "https://kms.example.lan"
                                            {:client-id "cid" :client-secret "wrong" :source :env}))]
        (is (= :clj-infisical/auth-failed (:type error)))))))
