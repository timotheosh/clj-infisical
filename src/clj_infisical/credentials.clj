(ns clj-infisical.credentials
  "Resolving the Universal Auth client id/secret from environment variables
   or /etc/infisical files, with strict permission checks. See
   doc/SPEC.md §5.2."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

;; ---------------------------------------------------------------------
;; Calculations
;; ---------------------------------------------------------------------

(defn bits-clear?
  [mode-bits mask]
  (zero? (bit-and mode-bits mask)))

(defn read-env
  [env-map]
  {:client-id (get env-map "INFISICAL_CLIENT_ID")
   :client-secret (get env-map "INFISICAL_CLIENT_SECRET")})

(defn- file-failure-reason [f]
  (cond
    (not (:exists? f)) {:reason :file-missing}
    (:symlink? f) {:reason :file-is-symlink}
    (not (:owned-by-process-user? f)) {:reason :file-not-owned-by-process-user}
    (not (:no-group-or-other-bits? f)) {:reason :file-group-or-other-permissions}))

(defn- first-failing-check [file]
  (or
   (when-not (:dir-exists? file) {:path "/etc/infisical" :reason :dir-missing})
   (when (:dir-symlink? file) {:path "/etc/infisical" :reason :dir-is-symlink})
   (when-not (:dir-owned-by-root? file) {:path "/etc/infisical" :reason :dir-not-owned-by-root})
   (when-not (:dir-not-group-or-other-writable? file)
     {:path "/etc/infisical" :reason :dir-group-or-other-writable})
   (when-let [r (file-failure-reason (:client-id-file file))] (assoc r :path "client_id"))
   (when-let [r (file-failure-reason (:client-secret-file file))] (assoc r :path "client_secret"))))

(defn select-credential-source
  [{:keys [env file]}]
  (let [{:keys [client-id client-secret]} env
        env-id? (not (str/blank? client-id))
        env-secret? (not (str/blank? client-secret))]
    (cond
      (and env-id? env-secret?)
      {:client-id client-id :client-secret client-secret :source :env}

      (or env-id? env-secret?)
      {:type :clj-infisical/ambiguous-credentials}

      (and (not (get-in file [:client-id-file :exists?]))
           (not (get-in file [:client-secret-file :exists?])))
      {:type :clj-infisical/credentials-not-found}

      :else
      (if-let [failure (first-failing-check file)]
        (merge {:type :clj-infisical/insecure-credential-files} failure)
        {:client-id (str/trim (get-in file [:client-id-file :content]))
         :client-secret (str/trim (get-in file [:client-secret-file :content]))
         :source :file}))))

;; ---------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------

(defn read-env!
  []
  (read-env (System/getenv)))

(defn- permission-string->mode [s]
  (->> (partition 3 s)
       (map (fn [[r w x]]
              (+ (if (= r \r) 8r4 0) (if (= w \w) 8r2 0) (if (= x \x) 8r1 0))))
       (reduce (fn [acc d] (+ (* acc 8) d)) 0)))

(defn- path-exists? [^java.io.File f]
  (Files/exists (.toPath f) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- symlink? [^java.io.File f]
  (Files/isSymbolicLink (.toPath f)))

(defn- mode-of [^java.io.File f]
  (-> (Files/getPosixFilePermissions (.toPath f) (make-array LinkOption 0))
      PosixFilePermissions/toString
      permission-string->mode))

(defn- owner-name [^java.io.File f]
  (.getName (Files/getOwner (.toPath f) (make-array LinkOption 0))))

(defn- owned-by-root? [f]
  (= "root" (owner-name f)))

(defn- owned-by-process-user? [f]
  (= (System/getProperty "user.name") (owner-name f)))

(defn- stat-dir [^java.io.File d]
  (if-not (path-exists? d)
    {:dir-exists? false :dir-symlink? false
     :dir-not-group-or-other-writable? false :dir-owned-by-root? false}
    (if (symlink? d)
      {:dir-exists? true :dir-symlink? true
       :dir-not-group-or-other-writable? false :dir-owned-by-root? false}
      {:dir-exists? true :dir-symlink? false
       :dir-not-group-or-other-writable? (bits-clear? (mode-of d) 8r022)
       :dir-owned-by-root? (owned-by-root? d)})))

(defn- stat-file [^java.io.File f]
  (if-not (path-exists? f)
    {:exists? false :symlink? false :no-group-or-other-bits? false
     :owned-by-process-user? false :content nil}
    (if (symlink? f)
      {:exists? true :symlink? true :no-group-or-other-bits? false
       :owned-by-process-user? false :content nil}
      {:exists? true :symlink? false
       :no-group-or-other-bits? (bits-clear? (mode-of f) 8r077)
       :owned-by-process-user? (owned-by-process-user? f)
       :content (slurp f)})))

(defn stat-credential-files!
  [dir]
  (merge (stat-dir (io/file dir))
         {:client-id-file (stat-file (io/file dir "client_id"))
          :client-secret-file (stat-file (io/file dir "client_secret"))}))

(defn resolve-credentials!
  []
  (let [env (read-env!)
        neither-env-var-set? (and (str/blank? (:client-id env)) (str/blank? (:client-secret env)))
        file (when neither-env-var-set? (stat-credential-files! "/etc/infisical"))
        result (select-credential-source {:env env :file file})]
    (if (:type result)
      (throw (ex-info (str "Infisical credential resolution failed: " (name (:type result)))
                       result))
      result)))
