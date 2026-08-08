(ns clj-infisical.credentials-test
  "Tests for clj-infisical.credentials, written directly from
   doc/SPEC.md §8.1/§8.2. src/clj_infisical/credentials.clj doesn't exist
   yet — that's expected, this is the red step."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-infisical.credentials :as creds]
            [clj-infisical.test-util :as tu]))

;; ---------------------------------------------------------------------
;; §8.1 calculations
;; ---------------------------------------------------------------------

(deftest bits-clear?-file-mask-test
  (testing "8r077 (files): only owner bits allowed"
    (is (true? (creds/bits-clear? 8r700 8r077)))
    (is (true? (creds/bits-clear? 8r600 8r077)))
    (is (false? (creds/bits-clear? 8r750 8r077)) "group bits set")
    (is (false? (creds/bits-clear? 8r704 8r077)) "other-read bit set")
    (is (true? (creds/bits-clear? 8r000 8r077)))))

(deftest bits-clear?-dir-mask-test
  (testing "8r022 (directory): only write bits checked"
    (is (true? (creds/bits-clear? 8r755 8r022))
        "group/other read+execute is fine under the write-only mask")
    (is (false? (creds/bits-clear? 8r775 8r022)) "group-write bit set")
    (is (true? (creds/bits-clear? 8r000 8r022)))))

;; -- select-credential-source fixtures ---------------------------------

(defn- file-entry
  [& {:keys [exists? symlink? no-group-or-other-bits? owned-by-process-user? content]
      :or {exists? true
           symlink? false
           no-group-or-other-bits? true
           owned-by-process-user? true
           content "secret-value\n"}}]
  {:exists? exists?
   :symlink? symlink?
   :no-group-or-other-bits? no-group-or-other-bits?
   :owned-by-process-user? owned-by-process-user?
   :content content})

(defn- secure-inputs
  "A baseline `inputs` map (SPEC §5.2): no env vars set, /etc/infisical and
   both credential files fully compliant."
  []
  {:env {:client-id nil :client-secret nil}
   :file {:dir-exists? true
          :dir-symlink? false
          :dir-not-group-or-other-writable? true
          :dir-owned-by-root? true
          :client-id-file (file-entry :content "cid-value\n")
          :client-secret-file (file-entry :content "csecret-value\n")}})

(deftest select-credential-source-env-wins-test
  (testing "both env vars set wins outright, insecure file inputs are ignored"
    (let [inputs {:env {:client-id "env-id" :client-secret "env-secret"}
                  :file {:dir-exists? true :dir-symlink? false
                         :dir-not-group-or-other-writable? false
                         :dir-owned-by-root? false
                         :client-id-file (file-entry :no-group-or-other-bits? false)
                         :client-secret-file (file-entry :no-group-or-other-bits? false)}}]
      (is (= {:client-id "env-id" :client-secret "env-secret" :source :env}
             (creds/select-credential-source inputs))))))

(deftest select-credential-source-ambiguous-env-test
  (testing "only client-id set"
    (is (= :clj-infisical/ambiguous-credentials
           (:type (creds/select-credential-source
                   {:env {:client-id "env-id" :client-secret nil}
                    :file (:file (secure-inputs))})))))
  (testing "only client-secret set (symmetry)"
    (is (= :clj-infisical/ambiguous-credentials
           (:type (creds/select-credential-source
                   {:env {:client-id nil :client-secret "env-secret"}
                    :file (:file (secure-inputs))}))))))

(deftest select-credential-source-files-secure-test
  (testing "no env vars, dir + both files secure -> file-sourced credentials, trimmed"
    (is (= {:client-id "cid-value" :client-secret "csecret-value" :source :file}
           (creds/select-credential-source (secure-inputs))))))

(deftest select-credential-source-dir-insecure-test
  (testing "dir owned by root but group-writable"
    (let [inputs (assoc-in (secure-inputs) [:file :dir-not-group-or-other-writable?] false)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs))))))
  (testing "dir not owned by root, even though not group/other-writable"
    (let [inputs (assoc-in (secure-inputs) [:file :dir-owned-by-root?] false)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs))))))
  (testing "dir is a symlink"
    (let [inputs (assoc-in (secure-inputs) [:file :dir-symlink?] true)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs)))))))

(deftest select-credential-source-dir-readable-is-fine-test
  (testing "dir readable/listable by others but not writable is NOT an error by itself"
    ;; secure-inputs already models this: only write bits are checked.
    (is (= :file (:source (creds/select-credential-source (secure-inputs)))))))

(deftest select-credential-source-file-insecure-test
  (testing "client_id file world-readable"
    (let [inputs (assoc-in (secure-inputs) [:file :client-id-file :no-group-or-other-bits?] false)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs))))))
  (testing "file mode-secure but owned by root instead of the process user"
    (let [inputs (assoc-in (secure-inputs) [:file :client-secret-file :owned-by-process-user?] false)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs))))))
  (testing "file is a symlink"
    (let [inputs (assoc-in (secure-inputs) [:file :client-id-file :symlink?] true)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs)))))))

(deftest select-credential-source-not-found-test
  (testing "both files absent -> not-found, checked before any security predicate"
    (let [inputs (-> (secure-inputs)
                      (assoc-in [:file :dir-owned-by-root?] false) ; would otherwise be "insecure"
                      (assoc-in [:file :client-id-file :exists?] false)
                      (assoc-in [:file :client-secret-file :exists?] false))]
      (is (= :clj-infisical/credentials-not-found
             (:type (creds/select-credential-source inputs))))))
  (testing "directory doesn't exist at all -> not-found, not insecure"
    (let [inputs {:env {:client-id nil :client-secret nil}
                  :file {:dir-exists? false :dir-symlink? false
                         :dir-not-group-or-other-writable? false
                         :dir-owned-by-root? false
                         :client-id-file (file-entry :exists? false :content nil)
                         :client-secret-file (file-entry :exists? false :content nil)}}]
      (is (= :clj-infisical/credentials-not-found
             (:type (creds/select-credential-source inputs)))))))

(deftest select-credential-source-partial-setup-test
  (testing "only one of the two files exists -> insecure, not not-found"
    (let [inputs (assoc-in (secure-inputs) [:file :client-secret-file :exists?] false)]
      (is (= :clj-infisical/insecure-credential-files
             (:type (creds/select-credential-source inputs)))))))

(deftest select-credential-source-trims-content-test
  (testing "trailing newline in file content is trimmed from the returned Credentials"
    (let [inputs (secure-inputs)
          result (creds/select-credential-source inputs)]
      (is (= "cid-value" (:client-id result)))
      (is (= "csecret-value" (:client-secret result))))))

;; -- read-env ------------------------------------------------------------

(deftest read-env-test
  (testing "pulls exactly the two keys it cares about"
    (is (= {:client-id "cid" :client-secret "csecret"}
           (creds/read-env {"INFISICAL_CLIENT_ID" "cid" "INFISICAL_CLIENT_SECRET" "csecret"}))))
  (testing "neither key present"
    (is (= {:client-id nil :client-secret nil} (creds/read-env {}))))
  (testing "works against a java.util.Map, not just a Clojure map"
    (let [env (doto (java.util.HashMap.)
                (.put "INFISICAL_CLIENT_ID" "cid")
                (.put "INFISICAL_CLIENT_SECRET" "csecret"))]
      (is (= {:client-id "cid" :client-secret "csecret"} (creds/read-env env))))))

;; ---------------------------------------------------------------------
;; §8.2 actions
;; ---------------------------------------------------------------------

(deftest read-env!-delegates-test
  (testing "delegates to read-env rather than reimplementing"
    (with-redefs [creds/read-env (fn [_] {:client-id "x" :client-secret "y"})]
      (is (= {:client-id "x" :client-secret "y"} (creds/read-env!))))))

(deftest stat-credential-files!-real-fixture-test
  (testing "reports real filesystem state, decides nothing"
    (let [dir (tu/create-temp-dir! 8r755)]
      (try
        (tu/write-file! dir "client_id" 8r600 "abc\n")
        (tu/write-file! dir "client_secret" 8r600 "xyz\n")
        (let [result (creds/stat-credential-files! dir)]
          (is (true? (:dir-exists? result)))
          (is (false? (:dir-symlink? result)))
          (is (true? (:dir-not-group-or-other-writable? result)))
          (is (false? (:dir-owned-by-root? result))
              "test process isn't root; the root-owned branch is covered at the calculation level (§8.1) instead")
          (is (= {:exists? true :symlink? false :no-group-or-other-bits? true
                  :owned-by-process-user? true :content "abc\n"}
                 (:client-id-file result)))
          (is (= {:exists? true :symlink? false :no-group-or-other-bits? true
                  :owned-by-process-user? true :content "xyz\n"}
                 (:client-secret-file result))))
        (finally (tu/delete-recursively! dir))))))

(deftest stat-credential-files!-missing-dir-test
  (testing "absence is data, not an I/O error"
    (let [parent (tu/create-temp-dir! 8r755)
          missing-dir (str parent "/does-not-exist")]
      (try
        (let [result (creds/stat-credential-files! missing-dir)]
          (is (false? (:dir-exists? result)))
          (is (false? (:exists? (:client-id-file result))))
          (is (false? (:exists? (:client-secret-file result)))))
        (finally (tu/delete-recursively! parent))))))

(deftest stat-credential-files!-symlink-test
  (testing "symlink detected regardless of the target's own permissions"
    (let [dir (tu/create-temp-dir! 8r755)]
      (try
        (let [target (tu/write-file! dir "real-secret" 8r600 "shh\n")]
          (tu/symlink! dir "client_id" target)
          (tu/write-file! dir "client_secret" 8r600 "xyz\n")
          (is (true? (:symlink? (:client-id-file (creds/stat-credential-files! dir))))))
        (finally (tu/delete-recursively! dir))))))

(deftest resolve-credentials!-short-circuits-on-env-test
  (testing "does not touch the filesystem when env vars are already valid"
    (with-redefs [creds/read-env! (constantly (select-keys tu/env-credentials-fixture
                                                             [:client-id :client-secret]))
                  creds/stat-credential-files! tu/unreachable!]
      (is (= tu/env-credentials-fixture (creds/resolve-credentials!))))))

(deftest resolve-credentials!-throws-on-rejection-test
  (testing "select-credential-source's ErrorData becomes a thrown ex-info"
    (with-redefs [creds/read-env! (fn [] {:client-id nil :client-secret nil})
                  creds/stat-credential-files!
                  (fn [_]
                    {:dir-exists? false :dir-symlink? false
                     :dir-not-group-or-other-writable? false
                     :dir-owned-by-root? false
                     :client-id-file {:exists? false :symlink? false
                                       :no-group-or-other-bits? false
                                       :owned-by-process-user? false :content nil}
                     :client-secret-file {:exists? false :symlink? false
                                           :no-group-or-other-bits? false
                                           :owned-by-process-user? false :content nil}})]
      (let [{:keys [error]} (tu/try-invoke creds/resolve-credentials!)]
        (is (= :clj-infisical/credentials-not-found (:type error)))))))
