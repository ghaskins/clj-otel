(ns example.puzzle-service-explicit-async
  "Example application demonstrating using `clj-otel` to add telemetry to an
   asynchronous Ring HTTP service that is run without the OpenTelemetry
   instrumentation agent. In this example, the context is explicitly passed in
   as a parameter to `clj-otel` functions."
  (:require [aero.core :as aero]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn]
            [clj-http.core :as http-core]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [example.common.core-async.utils :as async']
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]
            [steffan-westcott.clj-otel.api.metrics.http.server :as metrics-http-server]
            [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]
            [steffan-westcott.clj-otel.api.trace.http :as trace-http]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [steffan-westcott.clj-otel.context :as context]
            [steffan-westcott.clj-otel.instrumentation.runtime-telemetry-java17 :as
             runtime-telemetry]
            [steffan-westcott.clj-otel.sdk.autoconfigure :as autoconfig])
  (:import (clojure.lang PersistentQueue)))


(defonce
  ^{:doc "Delay containing histogram that records the number of letters in each generated puzzle."}
  puzzle-size
  (delay (instrument/instrument {:name        "service.puzzle.puzzle-size"
                                 :instrument-type :histogram
                                 :unit        "{letters}"
                                 :description "The number of letters in each generated puzzle"})))



(def ^:private config
  {})

(def ^:private async-conn-mgr
  (delay (conn/make-reusable-async-conn-manager {})))

(def ^:private async-client
  (delay (http-core/build-async-http-client {} @async-conn-mgr)))



(defn client-request
  "Make an asynchronous HTTP request using `clj-http`."
  [context request respond raise]

  (let [request (conj request
                      {:async true
                       :throw-exceptions false
                       :connection-manager @async-conn-mgr
                       :http-client @async-client})]

    ;; Manually create a client span with `context` as the parent context.
    ;; Context containing client span is assigned to `context*`. Client span is
    ;; ended when either a response or exception is returned.
    (span/async-span
     (trace-http/client-span-opts request {:parent context})
     (fn [context* respond* raise*]

       (let [;; Propagate context containing client span to remote
             ;; server by injecting headers. This enables span
             ;; correlation to make distributed traces.
             request' (update request :headers merge (context/->headers {:context context*}))]

         (client/request request'
                         (fn [response]

                           ;; Add HTTP response data to the client span.
                           (trace-http/add-client-span-response-data! response {:context context*})

                           (respond* response))
                         (fn [e]

                           ;; Add error information to the client span.
                           (trace-http/add-client-span-response-data!
                            {:io.opentelemetry.api.trace.span.attrs/error-type e}
                            {:context context*})
                           (raise* e)))))
     respond
     raise)))



(defn <client-request
  "Make an asynchronous HTTP request and return a channel of the response."
  [context request]
  (let [<ch    (async/chan)
        put-ch #(async/put! <ch %)]
    (client-request context request put-ch put-ch)
    <ch))



(defn <get-random-word
  "Get a random word string of the requested type and return a channel of the
   word."
  [context word-type]
  (let [endpoint  (get-in config [:endpoints :random-word-service])
        <response (<client-request context
                                   {:method       :get
                                    :url          (str endpoint "/random-word")
                                    :query-params {"type" (name word-type)}})]
    (async'/go-try
      (let [response (async'/<? <response)
            status   (:status response)]
        (if (= 200 status)
          (:body response)
          (throw (ex-info "Unexpected HTTP response"
                          {:type          ::ring/response
                           :response      {:status status
                                           :body   "Unexpected HTTP response"}
                           :service/error :service.errors/unexpected-http-response})))))))



(defn <random-words
  "Get random words of the requested types and return a channel containing
   a value for each word."
  [context word-types]

  ;; Start a new internal span that ends when the source channel (returned by
  ;; the body) closes or 5000 milliseconds have elapsed. Returns a dest channel
  ;; with buffer size 2. Values are piped from source to dest irrespective of
  ;; timeout. Context containing internal span is assigned to `context*`.
  (async'/<with-span-binding [context* {:parent     context
                                        :name       "Getting random words"
                                        :attributes {:system/word-types word-types}}]
    5000
    2

    (let [<words* (map #(<get-random-word context* %) word-types)]
      (async'/<concat <words*))))



(defn scramble
  "Scrambles a given word."
  [context word]

  ;; Wrap synchronous function body with an internal span. Context containing
  ;; internal span is assigned to `context*`.
  (span/with-span-binding [context* {:parent     context
                                     :name       "Scrambling word"
                                     :attributes {:system/word word}}]

    (Thread/sleep 5)
    (let [scrambled-word (->> word
                              seq
                              shuffle
                              (apply str))]

      ;; Add more attributes to internal span
      (span/add-span-data! {:context    context*
                            :attributes {:service.puzzle/scrambled-word scrambled-word}})

      scrambled-word)))



(defn <generate-puzzle
  "Constructs a puzzle string containing scrambled random words of the
   requested word types and returns a channel of the puzzle string."
  [context word-types]
  (let [<words (<random-words context word-types)]
    (async'/go-try
      (try
        (loop [scrambled-words (PersistentQueue/EMPTY)]
          (if-let [word (async'/<? <words)]
            (recur (conj scrambled-words (scramble context word)))
            (do

              ;; Add event to span
              (span/add-span-data! {:context context
                                    :event   {:name       "Completed setting puzzle"
                                              :attributes {:system/puzzle scrambled-words}}})

              ;; Update puzzle-size metric
              (instrument/record! @puzzle-size
                                  {:context context
                                   :value   (reduce + (map count scrambled-words))})

              (str/join " " scrambled-words))))
        (finally
          (async'/close-and-drain!! <words))))))



(defn get-puzzle-handler
  "Asynchronous Ring handler for `GET /puzzle` request. Returns an HTTP
   response containing a puzzle of the requested word types."
  [{:keys [query-params io.opentelemetry/server-span-context]} respond raise]
  (let [word-types (map keyword (str/split (get query-params "types") #","))
        <puzzle    (<generate-puzzle server-span-context word-types)]
    (async'/ch->respond-raise <puzzle
                              (fn [puzzle]
                                (respond (response/response puzzle)))
                              raise)))



(defn ping-handler
  "Ring handler for ping health check."
  [_ respond _]
  (respond (response/response nil)))



(def handler
  "Ring handler for all requests."
  (ring/ring-handler (ring/router [["/ping"
                                    {:name ::ping
                                     :get  ping-handler}]
                                   ["/puzzle"
                                    {:name ::puzzle
                                     :get  get-puzzle-handler}]]
                                  {:data {:muuntaja   m/instance
                                          :middleware [;; Add route data
                                                       trace-http/wrap-reitit-route

                                                       ;; Add metrics that include http.route
                                                       ;; attribute
                                                       metrics-http-server/wrap-metrics-by-route

                                                       parameters/parameters-middleware
                                                       muuntaja/format-middleware
                                                       exception/exception-middleware

                                                       ;; Add exception event before
                                                       ;; exception-middleware runs
                                                       trace-http/wrap-exception-event]}})
                     (ring/create-default-handler)

                     ;; Wrap handling of all requests, including those which have no matching
                     ;; route. As this application is not run with the OpenTelemetry
                     ;; instrumentation agent, create a server span for each request.
                     {:middleware [[trace-http/wrap-server-span {:create-span? true}]
                                   [metrics-http-server/wrap-active-requests]]}))



(defn server
  "Starts puzzle-service server instance."
  ([]
   (server {}))
  ([jetty-opts]

   ;; Initialise OpenTelemetry SDK instance and set as default used by `clj-otel`
   (autoconfig/init-otel-sdk!)

   ;; Register measurements that report metrics about the JVM runtime. These measurements cover
   ;; buffer pools, classes, CPU, garbage collector, memory pools and threads.
   (runtime-telemetry/register!)

   (alter-var-root #'config (constantly (aero/read-config (io/resource "config.edn"))))
   (jetty/run-jetty #'handler (merge {:async? true} (:jetty-opts config) jetty-opts))))



(comment
  (server {:join? false})
  ;
)
