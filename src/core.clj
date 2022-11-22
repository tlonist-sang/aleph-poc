(ns core
  (:require [aleph.http :as http]
            [route :as route]))

(comment
  (http/start-server (route/app {}) {:port 8080}))
