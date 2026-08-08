(ns clj-infisical.errors-test
  "Tests for clj-infisical.errors. Not part of the original SPEC §8 test
   list (errors.clj was extracted later, per doc/SPEC.md §5.7) -- added
   after a real production bug: error? used a bare truthiness check on
   :type, which misidentified a successful secret fetch as an ErrorData
   because Infisical's own secret objects carry a field literally named
   \"type\" (e.g. \"shared\"/\"personal\")."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.errors :as errors]))

(deftest parse-json-test
  (testing "valid JSON"
    (is (= {"a" 1} (errors/parse-json "{\"a\":1}"))))
  (testing "invalid JSON returns nil, doesn't throw"
    (is (nil? (errors/parse-json "not json")))))

(deftest error-data-test
  (testing "builds the ErrorData shape"
    (is (= {:type :clj-infisical/http-error :status 500 :body "b" :parsed {"a" 1}}
           (errors/error-data :clj-infisical/http-error 500 "b" {"a" 1})))))

(deftest error?-test
  (testing "true for a real ErrorData, whose :type is always our own keyword"
    (is (true? (errors/error? {:type :clj-infisical/secret-not-found}))))
  (testing "false when :type is absent"
    (is (false? (errors/error? {:secret-value "s"}))))
  (testing "false when :type is nil"
    (is (false? (errors/error? {:type nil}))))
  (testing "regression: false when :type is a *string*, not a keyword -- this
            is exactly what a passed-through Secret looks like when Infisical's
            own response includes a field literally named \"type\" (e.g. a
            shared vs. personal secret), keywordized to `:type \"shared\"`"
    (is (false? (errors/error? {:secret-value "s" :type "shared"})))
    (is (false? (errors/error? {:secret-value "s" :type "personal"})))))

(deftest unwrap!-test
  (testing "returns non-error results unchanged"
    (is (= {:secret-value "s"} (errors/unwrap! "prefix" {:secret-value "s"}))))
  (testing "returns a real Secret unchanged even though it carries its own :type field"
    (is (= {:secret-value "s" :type "shared"}
           (errors/unwrap! "prefix" {:secret-value "s" :type "shared"}))))
  (testing "throws on real ErrorData, message includes the prefix and error type"
    (try
      (errors/unwrap! "fetch failed" {:type :clj-infisical/secret-not-found :status 404})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= "fetch failed: secret-not-found" (ex-message e)))
        (is (= :clj-infisical/secret-not-found (:type (ex-data e))))))))
