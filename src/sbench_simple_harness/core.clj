(ns sbench-simple-harness.core
  (:require [wkok.openai-clojure.api :as api]
            [wkok.openai-clojure.openai :as openai-impl]
            [martian.core :as martian]
            [schema.core :as schema]
            [clojure.edn :as edn]
            [sbench-simple-harness.utils :as utils]
            [sbench-simple-harness.run-bench :as run-bench])
  (:gen-class))

;; Qwen recommended sampling settings
(def ^:private temperature 0.6)
(def ^:private top-p 0.95)
(def ^:private top-k 20)
(def ^:private min-p 0)

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

(defn call-qwen
  ([config messages]
   (call-qwen config messages nil))
  ([config messages extra-params]
   (api/create-chat-completion
    (merge {:model (:model config)
            :messages messages
            :temperature temperature
            :top_p top-p
            :top_k top-k
            :min_p min-p}
           extra-params)

    {:api-endpoint (:llm-api config)
     :api-key (:api-key config)})))

(defn print-usage []
  (println
"Usage:

First argument must be one of:
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

(defn -main [& args]
  (let [config (read-edn-file "resources/config.edn")
        postfn (partial utils/post (:server-url config))]
    (case (first args)
      "start" (let [[_ problem-set attempts] args]
                (if (nil? problem-set)
                  (print-start-usage)
                  (let [run-deets (run-bench/start-run postfn problem-set (or attempts 1))]
                    (run-bench/main-loop (assoc run-deets :llmfn (partial call-qwen config)))
                    (run-bench/complete-run (:postfn run-deets) (:run-id run-deets) (:model config)))))
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
