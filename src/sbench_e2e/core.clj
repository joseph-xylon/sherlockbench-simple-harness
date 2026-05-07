(ns sbench-e2e.core
  (:require [wkok.openai-clojure.api :as api]
            [clojure.edn :as edn])
  (:gen-class))

(defn read-edn-file [file-path]
  (edn/read-string
   (slurp file-path)))

(defn call-qwen
  [config prompt]
  (api/create-chat-completion
    {:model "qwen3"
     :messages [{:role "user" :content prompt}]}

    {:api-endpoint (:llm-api config)}))

(defn -main [problem-set & args]
  (let [config (read-edn-file "resources/config.edn")]
    (prn (str "problem-set: " problem-set))
    (prn (:server-url config))
    )
  )

(comment
  (def config (read-edn-file "resources/config.edn"))

  (call-qwen config "Would you love a monsterman?")
  )
