(ns clj-infisical.core-test
  "Tests for clj-infisical.core, written directly from doc/SPEC.md §8.7.
   get-secret!/get-secret-raw! share one private orchestration action
   (-fetch-secret!, per SPEC §5.6), so most scenarios below are exercised
   against both public entry points off the same rebound fixtures."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.core :as core]
            [clj-infisical.credentials :as creds]
            [clj-infisical.auth :as auth]
            [clj-infisical.secrets :as secrets]
            [clj-infisical.test-util :as tu]))

(def base-args {:workspace-id "ws1" :secret-name "password"})

(deftest invalid-arguments-test
  (testing "missing workspace-id -> invalid-arguments before any I/O, for both entry points"
    (with-redefs [creds/resolve-credentials! (fn [] (throw (ex-info "should not be called" {})))
                  auth/login! (fn [& _] (throw (ex-info "should not be called" {})))
                  secrets/fetch-secret! (fn [& _] (throw (ex-info "should not be called" {})))]
      (doseq [f [core/get-secret! core/get-secret-raw!]]
        (let [{:keys [error]} (tu/try-invoke #(f {:secret-name "password"}))]
          (is (= :clj-infisical/invalid-arguments (:type error)))))))
  (testing "missing secret-name"
    (with-redefs [creds/resolve-credentials! (fn [] (throw (ex-info "should not be called" {})))]
      (let [{:keys [error]} (tu/try-invoke #(core/get-secret! {:workspace-id "ws1"}))]
        (is (= :clj-infisical/invalid-arguments (:type error)))))))

(deftest explicit-credentials-bypass-resolution-test
  (testing "resolve-credentials! is never invoked when client-id/client-secret are supplied"
    (with-redefs [creds/resolve-credentials! (fn [] (throw (ex-info "should not be called" {})))
                  auth/login! (fn [_site-url given-creds]
                                (is (= "cid" (:client-id given-creds)))
                                (is (= "csecret" (:client-secret given-creds)))
                                {:token "t" :expires-in 7200 :token-type "Bearer"})
                  secrets/fetch-secret! (fn [& _] {:secret-value "shh"})]
      (is (= "shh" (core/get-secret! (assoc base-args :client-id "cid" :client-secret "csecret")))))))

(deftest get-secret-raw!-returns-full-map-test
  (testing "returns the full Secret map untouched -- the 'raw' half"
    (with-redefs [creds/resolve-credentials! (fn [] {:client-id "eid" :client-secret "esecret" :source :env})
                  auth/login! (fn [_site-url _creds] {:token "t" :expires-in 7200 :token-type "Bearer"})
                  secrets/fetch-secret! (fn [& _] {:secret-value "s" :secret-key "k" :version 3})]
      (is (= {:secret-value "s" :secret-key "k" :version 3}
             (core/get-secret-raw! base-args))))))

(deftest get-secret!-returns-bare-string-test
  (testing "returns just the plaintext value -- the convenience half"
    (with-redefs [creds/resolve-credentials! (fn [] {:client-id "eid" :client-secret "esecret" :source :env})
                  auth/login! (fn [_site-url _creds] {:token "t" :expires-in 7200 :token-type "Bearer"})
                  secrets/fetch-secret! (fn [& _] {:secret-value "s" :secret-key "k" :version 3})]
      (is (= "s" (core/get-secret! base-args))))))

(deftest resolve-credentials!-exception-propagates-test
  (testing "no wrapping/swallowing, for both entry points"
    (with-redefs [creds/resolve-credentials!
                  (fn [] (throw (ex-info "no creds" {:type :clj-infisical/credentials-not-found})))]
      (doseq [f [core/get-secret! core/get-secret-raw!]]
        (let [{:keys [error]} (tu/try-invoke #(f base-args))]
          (is (= :clj-infisical/credentials-not-found (:type error))))))))

(deftest login!-exception-propagates-and-short-circuits-test
  (testing "fetch-secret! is never invoked after a login failure"
    (with-redefs [creds/resolve-credentials! (fn [] {:client-id "eid" :client-secret "esecret" :source :env})
                  auth/login! (fn [_site-url _creds]
                                (throw (ex-info "bad creds" {:type :clj-infisical/auth-failed})))
                  secrets/fetch-secret! (fn [& _] (throw (ex-info "should not be called" {})))]
      (let [{:keys [error]} (tu/try-invoke #(core/get-secret! base-args))]
        (is (= :clj-infisical/auth-failed (:type error)))))))

(deftest defaults-test
  (testing "environment/secret-path/site-url default when omitted"
    (with-redefs [creds/resolve-credentials! (fn [] {:client-id "eid" :client-secret "esecret" :source :env})
                  auth/login! (fn [site-url _creds]
                                (is (= "https://app.infisical.com" site-url))
                                {:token "t" :expires-in 7200 :token-type "Bearer"})
                  secrets/fetch-secret! (fn [config _token _secret-name]
                                          (is (= "dev" (:environment config)))
                                          (is (= "/" (:secret-path config)))
                                          (is (= "https://app.infisical.com" (:site-url config)))
                                          {:secret-value "s"})]
      (is (= "s" (core/get-secret! base-args))))))
