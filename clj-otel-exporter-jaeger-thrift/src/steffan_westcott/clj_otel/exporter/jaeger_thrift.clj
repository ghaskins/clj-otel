(ns ^{:deprecated "0.2.4.1"} steffan-westcott.clj-otel.exporter.jaeger-thrift
  "Span data exporter to Jaeger via Thrift. Deprecated - Use
   `steffan-westcott.clj-otel.exporter.otlp.grpc.trace` or
   `steffan-westcott.clj-otel.exporter.otlp.http.trace` instead."
  (:import (io.opentelemetry.exporter.jaeger.thrift JaegerThriftSpanExporter)))

(defn span-exporter
  "Returns a span exporter that exports to Jaeger via Thrift. May Take an
   option map as follows:

   | key       | description |
   |-----------|-------------|
   |`:endpoint`| Jaeger endpoint, used with the default sender (default: `\"http://localhost:14268/api/traces\"`).
   |`:sender`  | `ThriftSender` instance to use for communication with the backend (default: `ThriftSender` instance configured to use `:endpoint`)."
  (^JaegerThriftSpanExporter []
   (span-exporter {}))
  (^JaegerThriftSpanExporter [{:keys [endpoint thrift-sender]}]
   (let [builder (cond-> (JaegerThriftSpanExporter/builder)
                   endpoint      (.setEndpoint endpoint)
                   thrift-sender (.setThriftSender thrift-sender))]
     (.build builder))))