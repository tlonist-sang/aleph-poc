(ns route
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
            [medley.core :refer [dissoc-in]]
            [muuntaja.core :as m]
            [reitit.coercion.spec]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.multipart-params.temp-file :as temp-file]
            [ring.middleware.not-modified :as not-modified]
            [ring.middleware.resource :as resource]
            [ring.util.http-response :as hr]
            [spec-tools.data-spec :as ds]))

(defn swagger-docs []
  ["/swagger.json"
   {:get {:no-doc  true
          :swagger {:basePath            "/"
                    :info                {:title       "팜모닝 API Reference"
                                          :description ""
                                          :version     "0.0.1"}
                    :securityDefinitions {:apiAuth {:type        "apiKey"
                                                    :name        "Authorization"
                                                    :description ""
                                                    :in          "header"}}}
          :handler (swagger/create-swagger-handler)}}])

(defn router-config [context]
  {:exception pretty/exception
   :data      {:coercion   reitit.coercion.spec/coercion
               :muuntaja   m/instance
               :middleware [swagger/swagger-feature
                            parameters/parameters-middleware
                            muuntaja/format-middleware
                            muuntaja/format-negotiate-middleware
                            muuntaja/format-request-middleware
                            muuntaja/format-response-middleware
                            coercion/coerce-request-middleware
                            coercion/coerce-response-middleware
                            (multipart/create-multipart-middleware {:store (temp-file/temp-file-store {:expires-in 60})})
                            cookies/wrap-cookies]}})

(defn health []
  ["/health" {:get {:no-doc    true
                    :summary   "health check"
                    :responses {200 {:body {:message string?}}}
                    :handler   (fn [_] (hr/ok {:message "I'm alive!"}))}}])

(defn default []
  ["/" {:get {:no-doc    true
              :summary   "health check"
              :responses {200 {:body {:message string?}}}
              :handler   (fn [_] (hr/ok {:message "Hello Farmmy"}))}}])

(defn hello
  [_ {:keys [name]} _]
  (str "Hello, " name "!"))

(def hello-schema
  (-> "resources/sample.edn"
      slurp
      edn/read-string
      (attach-resolvers {:say-hello hello})
      schema/compile))

(defn parse-json [s]
  (try
    (json/read-str s)
    (catch Exception _ nil)))

(defn ->try-int [s]
  (try
    (Integer/parseInt s)
    (catch Exception _ s)))

(defn- assoc-multipart-variable
  ""
  [operations multipart]
  (reduce-kv (fn [operations name path]
               (when (seq path)
                 ;; vector 에 호환을 맞추기 위해 int로 변환 했음. hashmap(object)인 경우는 고려하지 않음
                 (let [path (map ->try-int (str/split (first path) #"\."))]
                   (assoc-in operations path (multipart (str name))))))
             operations
             (parse-json (multipart "map"))))

(defn variables
  [req]
  (get-in req [:body-params :variables]))

(defn- build-multipart-query-variables [multipart]
  (let [operations (-> (parse-json (multipart "operations"))
                       (assoc-multipart-variable multipart))]
    {:query     (get operations "query")
     ;; variable key 가 keyword 여야 인식함
     :variables (walk/keywordize-keys (get operations "variables"))}))

(defn- build-plain-query-variables [request]
  {:query     (get-in request [:body-params :query])
   :variables (variables request)})

(defn- build-options [request]
  (let [operation-name (get-in request [:body-params :operationName])]
    (cond-> {}
            operation-name (assoc :operation-name operation-name))))

(defn- executes
  [schema query variables ctx options]
  (try
    (let [response (lacinia/execute schema query variables ctx options)]
      (if (:tracing variables)
        response
        (dissoc-in response [:extensions :tracing])))
    (catch Exception e
      (throw e))))

(defn graphql-handler [ctx]
  (fn [request]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (let [options   (build-options request)
                    multipart (:multipart-params request)
                    {:keys [query variables]} (if-not (empty? multipart)
                                                (build-multipart-query-variables multipart)
                                                (build-plain-query-variables request))
                    result    (executes hello-schema query variables ctx options)]
                (json/write-str result))}))

(defn g-route [handler ctx]
  ["/graphql" {:post {:summary    "graphql handler"
                      :responses  {200 {:body any?}}
                      :parameters {:multipart {(ds/opt :operations) string?
                                               (ds/opt :map)        string?}}
                      :handler    (handler ctx)}}])

(defn app
  [context]
  (-> (ring/ring-handler
        (ring/router
          [(default)
           (swagger-docs)
           (health)
           (g-route graphql-handler context)]
          (router-config context))
        (ring/routes
          (swagger-ui/create-swagger-ui-handler {:path "/swagger-ui"})
          (ring/create-default-handler)))
      (resource/wrap-resource "static")
      (wrap-cors
        :access-control-allow-origin [#".*"]
        :access-control-allow-methods [:options :head :get :post :delete :put])
      content-type/wrap-content-type
      not-modified/wrap-not-modified))