(ns server.core
  (:require [aleph.http :as http]
            [server.route :as route])
  (:gen-class))

(defn -main [& args]
  (http/start-server (route/app {}) {:port 8080}))
