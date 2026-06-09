(ns sbench-simple-harness.run-bench
  (:require [sbench-simple-harness.utils :as utils]
            [sbench-simple-harness.prompts :as prompts]
            [cheshire.core :as json]))

(defn start-run [postfn problem-set attempts-per-problem]
  (let [post-data (conj {:problem-set problem-set
                         :attempts-per-problem (Integer/parseInt attempts-per-problem)}
                        {:client-id "sbench_e2e"})
        {:keys [run-id attempts]} (postfn nil "start-run" post-data)
        postfn' (partial postfn run-id)]
    
    (println (str "Started new run with id: " run-id))
    {:run-id run-id :attempts attempts :postfn postfn'}))

(defn list-to-map
  "openai doesn't like arrays much so just assign alphabetical keys"
  [input-list]
  (let [keys (map (comp str char) (range 97 (+ 97 (count input-list))))]
    (zipmap keys (map #(hash-map :type %) input-list))))

(defn normalise-args
  "map back to list"
  [input-map]
  (vals (sort input-map)))

(defn handle-tool-call [postfn attempt-id arg-spec call]
  (let [arguments (json/parse-string (:arguments (:function call)))
        args-norm (normalise-args arguments)
        fnoutput (:output (postfn "test-function" {:attempt-id attempt-id
                                                      :args args-norm}))]
    (println "\n### SYSTEM: calling tool")
    (println (str "  " (utils/py-tuple args-norm) " → " (utils/py-str fnoutput)))

    {:role "tool"
     :content (json/generate-string fnoutput)
     :tool_call_id (:id call)}))

(defn investigation [postfn llmfn messages attempt]
  (let [{:keys [attempt-id arg-spec output-type test-limit attempts-remaining]} attempt
        mapped-args (list-to-map arg-spec)
        tools [{:type "function",
                :function {:name "mystery_function"
                           :parameters {:type "object"
                                        :properties mapped-args
                                        :required (keys mapped-args)
                                        :additionalProperties false}}}]]
    (println (str "\n### SYSTEM: interrogating function with args " (utils/py-str (vec arg-spec))))
    (loop [messages messages
           max-loop (+ test-limit 2)  ; if the LLM goes over it's test limit,
                                      ; the server sends back a message to
                                      ; that effect. We want the LLM to see that.
           tool-count 0]
      (let [{[{{tool_calls :tool_calls content :content :as full-assistant-message} :message} & _] :choices} (llmfn messages {:tools tools})
            ; we re-build assistant message without reasoning_content, becas
            ; it's not recommended to pass reasoning back into Qwen
            assistant-message (cond-> {:role "assistant" :content content}
                                (seq tool_calls) (assoc :tool_calls tool_calls))
            messages' (conj messages assistant-message)]
        (println "\n--- LLM ---")
        (when (seq content) (utils/print-indented content))
        (if (and (seq tool_calls) (< 0 max-loop))
          (let [tool-messages (mapv #(handle-tool-call postfn attempt-id arg-spec %) tool_calls)]
            (recur (into messages' tool-messages) (- max-loop (count tool_calls)) (+ tool-count (count tool_calls))))
          (do
            (println (str "\n### SYSTEM: The tool was used " tool-count " times."))
            messages'))))))

(defn verification [postfn llmfn attempt messages]
  (let [{:keys [arg-spec attempt-id]} attempt]
    (println (str "\n### SYSTEM: verifying function with args " (utils/py-str (vec arg-spec))))
    (loop []
      (let [{:keys [next-verification output-type] :as next} (postfn "next-verification" {:attempt-id attempt-id})
            verification-formatted (apply merge {} (for [[k v] (list-to-map next-verification)]
                                                     {k (:type v)}))
            verification-message (prompts/make-verification-message verification-formatted)
            response-format (prompts/make-prediction-schema output-type)
            {[{{json-content :content} :message}] :choices} (llmfn (into messages verification-message)
                                                                   {:response_format response-format})
            {:keys [thoughts expected_output]} (json/parse-string json-content true)
            {v-status :status} (postfn "attempt-verification" {:attempt-id attempt-id
                                                               :prediction expected_output})]
        (println "\n### SYSTEM: inputs:")
        (println (str "  " (utils/py-str verification-formatted)))
        (println "\n--- LLM ---")
        (when (seq thoughts) (utils/print-indented thoughts))
        (println (str "\n  `" expected_output "`"))

        (case v-status
          "correct"
          (do
            (println "\n### SYSTEM: CORRECT")
            (recur))
          "wrong"
          (do
            (println "\n### SYSTEM: WRONG")
            false)
          "done"
          (do
            (println "\n### SYSTEM: CORRECT")
            true))))))

(defn complete-run [postfn run-id model]
  (let [{:keys [score percent]} (postfn "complete-run" {})
        {:keys [numerator denominator]} score]
    (println (str "\n### SYSTEM: run complete for model `" model "`."))
    (println (str "\nRun id: " run-id))
    (println (str "\nFinal score: " numerator "/" denominator
                  " (" (Math/round (double percent)) "%)"))))

(defn main-loop [{:keys [run-id attempts postfn llmfn]}]
  (let [total (count attempts)]
    (doseq [[idx attempt] (map-indexed vector attempts)]
      (println (str "\n### SYSTEM: Starting attempt " (inc idx) "/" total))
      (let [messages (prompts/make-initial-messages (:test-limit attempt))
            messages' (investigation postfn llmfn messages attempt)]
        (verification postfn llmfn attempt messages')))))
