(ns sbench-e2e.core
  (:require [wkok.openai-clojure.api :as api]
            [clojure.edn :as edn]
            [sbench-e2e.utils :as utils]
            [sbench-e2e.run-bench :as run-bench])
  (:gen-class))

(defn read-edn-file [file-path]
  (edn/read-string
   (slurp file-path)))

(defn call-qwen
  ([config messages]
   (call-qwen config messages nil))
  ([config messages tools]
   (api/create-chat-completion
    (conj {:model "qwen3"
           :messages messages}
          (if tools {:tools tools} {}))

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
                    (print ((:postfn run-deets) "complete-run" {:run-id (:run-id run-deets)})))))
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
