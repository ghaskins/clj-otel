(ns example.auto-instrument-agent.middleware.sentence-summary-service-async
  "Example application demonstrating using `clj-otel` to add telemetry to an
  asynchronous Ring HTTP service that is run with the OpenTelemetry
  instrumentation agent."
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [example.common-utils.core-async :as async']
            [example.common-utils.middleware :as middleware]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as response]
            [steffan-westcott.clj-otel.api.trace.http :as trace-http]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [steffan-westcott.clj-otel.context :as context]))


(defn client-request
  "Make an asynchronous HTTP request using `clj-http`."
  [context request respond raise]

  ;; Set the current context just while the client request is created. This
  ;; ensures the client span created by the agent will have the correct parent
  ;; context.
  (context/with-context! context

    ;; Apache HttpClient request is automatically wrapped in a client span
    ;; created by the OpenTelemetry instrumentation agent. The agent also
    ;; propagates the context containing the client span to the remote HTTP
    ;; server by injecting headers into the request.
    (client/request request respond raise)))



(defn <client-request
  "Make an asynchronous HTTP request and return a channel of the response."
  [context request]
  (let [<ch (async/chan)
        put-ch #(async/put! <ch %)]
    (client-request context request put-ch put-ch)
    <ch))



(defn <get-word-length
  "Get the length of `word` and return a channel of the length value."
  [context word]
  (let [<response (<client-request context
                                   {:method           :get
                                    :url              "http://localhost:8081/length"
                                    :query-params     {"word" word}
                                    :async            true
                                    :throw-exceptions false})]
    (async'/go-try
      (let [response (async'/<? <response)
            status (:status response)]
        (if (= 200 status)
          (Integer/parseInt (:body response))
          (throw (ex-info (str status " HTTP response")
                          {:status status
                           :error  :unexpected-http-response})))))))



(defn <word-lengths
  "Get the word lengths and return a channel containing a value for each word
  length."
  [context words]

  ;; Start a new internal span that ends when the source channel (returned by
  ;; the body) closes or 6000 milliseconds have elapsed. Returns a dest channel
  ;; with buffer size 3. Values are piped from source to dest irrespective of
  ;; timeout. Context containing internal span is assigned to `context*`.
  (async'/<with-span-binding [context* {:parent     context
                                        :name       "Getting word lengths"
                                        :attributes {:words words}}]
    6000 3

    (let [chs (map #(<get-word-length context* %) words)]
      (async/merge chs))))



(defn summary
  "Returns a summary of the given word lengths."
  [context lengths]

  ;; Wrap synchronous function body with an internal span. Context containing
  ;; internal span is assigned to `context*`.
  (span/with-span-binding [context* {:parent     context
                                     :name       "Building sentence summary"
                                     :attributes {:input-data lengths}}]

    (Thread/sleep 25)
    (let [result {:word-count      (count lengths)
                  :shortest-length (apply min lengths)
                  :longest-length  (apply max lengths)}]

      ;; Add more attributes to internal span
      (span/add-span-data! {:context    context*
                            :attributes (select-keys result [:word-count])})

      result)))



(defn <build-summary
  "Builds a summary of the words in the sentence and returns a channel of the
  summary value."
  [context sentence]
  (let [words (str/split sentence #"\s+")
        <all-lengths (<word-lengths context words)
        <lengths (async'/<into?? [] <all-lengths)]
    (async'/go-try
      (try
        (let [lengths (async'/<? <lengths)]
         (summary context lengths))
        (finally
          (async'/close-and-drain!! <all-lengths))))))



(defn get-summary-handler
  "Asynchronous Ring handler for `GET /summary` request. Returns an HTTP
  response containing a summary of the words in the given sentence."
  [{:keys [query-params io.opentelemetry/server-span-context]} respond raise]

  ;; Add attributes describing matched route to server span.
  (trace-http/add-route-data! "/summary" {:context server-span-context})

  (let [sentence (get query-params "sentence")
        <summary (<build-summary server-span-context sentence)]
    (async'/ch->respond-raise <summary
                              (fn [summary]
                                (respond (response/response (str summary))))
                              raise)))



(defn handler
  "Asynchronous Ring handler for all requests."
  [{:keys [request-method uri] :as request} respond raise]
  (case [request-method uri]
    [:get "/summary"] (get-summary-handler request respond raise)
    (response/not-found "Not found")))



(def service
  "Ring handler with middleware applied."
  (-> handler
      params/wrap-params
      middleware/wrap-exception

      ;; Wrap request handling of all routes. As this application is run with
      ;; the OpenTelemetry instrumentation agent, a server span will be
      ;; provided by the agent and there is no need to create another one.
      (trace-http/wrap-server-span {:create-span? false
                                    :server-name  "sentence-summary"})))



(defonce ^{:doc "sentence-summary-service server instance"} server
         (jetty/run-jetty #'service {:port 8080 :async? true :join? false}))
