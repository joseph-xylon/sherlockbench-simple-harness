(ns sbench-simple-harness.utils
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defmacro map-of
  [& xs]
  `(hash-map ~@(mapcat (juxt keyword identity) xs)))

(defn post [base-url run-id path data]
  (let [data' (assoc data :run-id run-id)
        response (http/post (str base-url path)
                   {:headers {"Content-Type" "application/json"}
                    :body (json/generate-string
                           data')
                    :throw-exceptions false})
        body (json/parse-string (:body response) true)]  ; true means keywords
    (if (not= (:status response) 200)
      (throw (ex-info (str "Got status " (:status response))
                      {:status (:status response) :body body}))
      body)))

(defn http-get [base-url path]
  (let [response (http/get (str base-url path) {})]
    (if (not= (:status response) 200)
      (do
        (print (json/parse-string (:body response) true))
        (throw (Exception. (str "Got status " (:status response)))))
      (json/parse-string (:body response) true))))

(defn served-model
  "What the server actually has loaded, e.g. the gguf path. llama-server
   ignores the model name we send, so the config :model is only a label —
   this is the one thing that says which weights answered."
  [llm-api]
  (try
    (-> (http-get llm-api "/models") :data first :id)
    (catch Exception _ nil)))

(defn py-str
  "Render a value Python-repr style, for human-readable logging."
  [v]
  (cond
    (string? v)  (str "'" v "'")
    (keyword? v) (str "'" (name v) "'")
    (map? v)     (str "{"
                      (str/join ", "
                                (for [[k val] (sort-by (comp str key) v)]
                                  (str (py-str (if (keyword? k) (name k) k)) ": " (py-str val))))
                      "}")
    (sequential? v) (str "[" (str/join ", " (map py-str v)) "]")
    :else        (str v)))

(defn py-tuple
  "Render a sequence of values as a Python-style tuple: (a, b, c)."
  [vs]
  (str "(" (str/join ", " (map py-str vs)) ")"))

(defn print-indented
  "Print each line of s indented by two spaces."
  [s]
  (doseq [line (str/split-lines (str s))]
    (println (str "  " line))))

(defn show-config [config]
  (let [{categories :problem-sets} (http-get (:server-url config) "problem-sets")]
    (println
"Available problem sets:
=======================")
    (doseq [[category problems] categories]
      (println (str "\n" (name category) ":"))
      (doseq [{:keys [name id]} problems]
        (println
         (format "  - %-30s :: %-5s" name id))))))
