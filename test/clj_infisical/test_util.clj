(ns clj-infisical.test-util
  "Shared helpers for exercising ex-info-throwing actions and for building
   real POSIX-permissioned temp-file fixtures (used by the
   clj-infisical.credentials action tests, per doc/SPEC.md §8.2)."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn try-invoke
  "Calls (f), returning {:ok result} or {:error (ex-data ex)} for any
   ExceptionInfo it throws. Keeps try/catch out of every test body."
  [f]
  (try
    {:ok (f)}
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-data e)})))

(defn- octal->posix-permission-string [octal]
  (let [table ["---" "--x" "-w-" "-wx" "r--" "r-x" "rw-" "rwx"]
        digit (fn [shift] (nth table (bit-and (bit-shift-right octal shift) 8r7)))]
    (str (digit 6) (digit 3) (digit 0))))

(defn ^Path path [s]
  (Paths/get s (make-array String 0)))

(defn chmod! [file-path octal-mode]
  (Files/setPosixFilePermissions (path (str file-path))
                                  (PosixFilePermissions/fromString
                                   (octal->posix-permission-string octal-mode))))

(defn create-temp-dir!
  "Creates a real temp directory with mode `octal-mode`, returns its path
   as a string. Not cleaned up automatically — pair with delete-recursively!
   in a fixture."
  [octal-mode]
  (let [dir (Files/createTempDirectory "clj-infisical-test" (make-array FileAttribute 0))]
    (chmod! dir octal-mode)
    (str dir)))

(defn write-file!
  "Writes `content` to `dir`/`filename`, chmods it to `octal-mode`, returns
   the file's path as a string."
  [dir filename octal-mode content]
  (let [file-path (str dir "/" filename)]
    (spit file-path content)
    (chmod! file-path octal-mode)
    file-path))

(defn symlink!
  "Creates a symlink at `dir`/`filename` pointing at `target`, returns the
   symlink's path as a string."
  [dir filename target]
  (let [link-path (str dir "/" filename)]
    (Files/createSymbolicLink (path link-path) (path target) (make-array FileAttribute 0))
    link-path))

(defn delete-recursively! [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (io/delete-file child true)))))
