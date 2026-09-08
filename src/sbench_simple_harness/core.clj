(ns sbench-simple-harness.core
  (:require [wkok.openai-clojure.api :as api]
            [wkok.openai-clojure.openai :as openai-impl]
            [martian.core :as martian]
            [schema.core :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [sbench-simple-harness.utils :as utils]
            [sbench-simple-harness.run-bench :as run-bench])
  (:import [java.time Instant])
  (:gen-class))

;; Qwen3.5 recommended sampling settings (thinking mode, general tasks)
(def ^:private temperature 1.0)
(def ^:private top-p 0.95)
(def ^:private top-k 20)
(def ^:private min-p 0)
(def ^:private presence-penalty 0)

;; openai-clojure coerces the request body against the bundled OpenAI swagger
;; spec, which silently strips any params not in the spec (e.g. Qwen's top_k and
;; min_p). Open up the chat-completion body schema so these pass through.
(defonce ^:private allow-extra-chat-params
  (alter-var-root #'openai-impl/m
                  (fn [m]
                    (delay
                     (martian/update-handler @m :create-chat-completion
                                             update-in [:body-schema :body]
                                             assoc schema/Any schema/Any)))))

(defn read-edn-file [file-path]
  (edn/read-string
   (slurp file-path)))

(def ^:private base-config-path "resources/config.edn")

(defn- deep-merge
  "Merge maps recursively, so an override file can set a single nested key
   (e.g. {:prompt {:identity 3}}) without dropping its siblings."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn load-config
  "Read resources/config.edn, then merge any keys from `override-path` on top."
  [override-path]
  (cond-> (read-edn-file base-config-path)
    override-path (deep-merge (read-edn-file override-path))))

(defn parse-args
  "Pull an optional leading \"--config <file>\" off the front of the args."
  [args]
  (if (= "--config" (first args))
    {:config-path (second args) :args (drop 2 args)}
    {:config-path nil :args args}))

(def ^:private max-retries 5)

(defn- log-api-error [attempt exception]
  (spit "api-errors.log"
        (str (Instant/now) " retry=" attempt " error=" (.getMessage exception) "\n")
        :append true))

(defn- call-with-retry [request-params api-opts]
  (loop [attempt 0]
    (let [result (try
                   {:ok (api/create-chat-completion request-params api-opts)}
                   (catch Exception e {:err e}))]
      (if-let [err (:err result)]
        (if (< attempt max-retries)
          (do
            (log-api-error (inc attempt) err)
            (println (str "### SYSTEM: API error (retry " (inc attempt) "/" max-retries "): " (.getMessage err)))
            (recur (inc attempt)))
          (throw err))
        (:ok result)))))

(defn parallel-tool-calls?
  "Whether the model may request several tool calls in one turn. Absent from
   config means true, which is llama.cpp's own default for a template that
   supports it — so existing configs keep their behaviour.

   Setting it false makes llama.cpp constrain the grammar to at most one call
   per turn. The bench budgets total tool calls, not turns, so one call per
   turn buys more reasoning per call at no extra budget."
  [config]
  (not (false? (:parallel-tool-calls config))))

(defn call-qwen
  ([config messages]
   (call-qwen config messages nil))
  ([config messages extra-params]
   (call-with-retry
    (merge {:model (:model config)
            :messages messages
            :temperature temperature
            :top_p top-p
            :top_k top-k
            :min_p min-p
            :presence_penalty presence-penalty
            ;; Sent explicitly rather than left to the server default, so the
            ;; request says which mode produced the run.
            :parallel_tool_calls (parallel-tool-calls? config)}
           extra-params)
    {:api-endpoint (:llm-api config)
     :api-key (:api-key config)
     :request {:timeout 600000}})))

(defn print-usage []
  (println
"Usage:

Optional first argument:
- --config <file> :: merge <file> over resources/config.edn

Next argument must be one of:
- start :: start a test run
- list  :: list problem-sets
"))

(defn print-start-usage []
  (println
"Usage:

\"start\" action requires args:
- problem-set
- attempts-per-problem
"))

(defn -main [& argv]
  (let [{:keys [config-path args]} (parse-args argv)
        config (load-config config-path)
        postfn (partial utils/post (:server-url config))]
    (case (first args)
      "start" (let [[_ problem-set attempts] args]
                (if (nil? problem-set)
                  (print-start-usage)
                  ;; :model is just a label llama-server ignores; ask the server
                  ;; which weights are actually loaded so runs identify themselves.
                  (let [served-model (utils/served-model (:llm-api config))
                        run-deets (run-bench/start-run postfn problem-set (or attempts 1))
                        run-meta {:run-id (:run-id run-deets)
                                  :model (:model config)
                                  :served-model served-model
                                  :parallel-tool-calls (parallel-tool-calls? config)}
                        _ (do (println (str "Model loaded on the LLM server: " (or served-model "(couldn't ask)")))
                              (println (str "Parallel tool calls: " (:parallel-tool-calls run-meta))))
                        results (run-bench/main-loop (assoc run-deets
                                                            :llmfn (partial call-qwen config)
                                                            :prompt-config (:prompt config)
                                                            :interleaved-thinking (:interleaved-thinking config)))]
                    (run-bench/complete-run (:postfn run-deets) run-meta results))))
      "list"  (utils/show-config config)
      "show_config" (prn (:server-url config))
      (print-usage))))


(comment
  (def config (read-edn-file "resources/config.edn"))

  (call-qwen config [{:role "user" :content "Would you love a monsterman?"}])

  (utils/post (:server-url config) nil "start-run" {:client-id "boop"
                                                    :attempts-per-problem 10
                                                    :problem-set "sherlock1/all"})
  
  (print-usage)

  (utils/show-config config)

  (-main "start" "sherlock1/all")

  (let [config (read-edn-file "resources/config.edn")
        postfn (partial utils/post (:server-url config))
        run-deets (run-bench/start-run postfn "sherlock1/all" 1)
        postfn' (partial postfn (:run-id run-deets))]
    (postfn' "next-verification" {:attempt-id (:attempt-id (first (:attempts run-deets)))})
      )
  
  )
