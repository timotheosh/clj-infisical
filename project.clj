(defproject io.github.timotheosh/clj-infisical "0.1.0"
  :description "A Clojure client for fetching secrets from Infisical via Universal Auth."
  :url "https://github.com/timotheosh/clj-infisical"
  :license {:name "MIT License"
            :url "https://opensource.org/license/mit"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [org.clj-commons/clj-http-lite "1.0.13"]
                 [org.clojure/data.json "2.5.1"]]
  :deploy-repositories [["releases" {:url "https://repo.clojars.org/"
                                      :username :env/clojars_username
                                      :password :env/clojars_password}]
                        ["snapshots" {:url "https://repo.clojars.org/"
                                      :username :env/clojars_username
                                      :password :env/clojars_password}]
                        ["clojars" {:url "https://repo.clojars.org/"
                                    :username :env/clojars_username
                                    :password :env/clojars_password}]]
  :repl-options {:init-ns clj-infisical.core})
