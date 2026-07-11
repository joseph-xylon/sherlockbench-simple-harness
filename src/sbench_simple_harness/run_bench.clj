(ns sbench-simple-harness.run-bench
  (:require [sbench-simple-harness.utils :as utils]
            [sbench-simple-harness.prompts :as prompts]
            [cheshire.core :as json])
  (:import [java.time Instant]))

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
        fnoutput (try
                   (:output (postfn "test-function" {:attempt-id attempt-id
                                                     :args args-norm}))
                   (catch clojure.lang.ExceptionInfo e
                     ;; The server returns a 400 with {:error "..."} once the
                     ;; test limit is exceeded. That message is a signal for the
                     ;; LLM, not a harness failure: feed it back as the tool
                     ;; result so the model can make its final decision.
                     (let [{:keys [status body]} (ex-data e)]
                       (if (and (= status 400) (:error body))
                         (:error body)
                         (throw e)))))]
    (println "\n### SYSTEM: calling tool")
    (println (str "  " (utils/py-tuple args-norm) " → " (utils/py-str fnoutput)))

    {:role "tool"
     :content (json/generate-string fnoutput)
     :tool_call_id (:id call)}))

(defn- tool-call-round?
  "True when every message since the last user message belongs to an
   uninterrupted tool-calling round: assistant messages that made tool calls
   without any text content, plus their tool responses."
  [messages]
  (every? (fn [{:keys [role content tool_calls]}]
            (or (= role "tool")
                (and (= role "assistant") (seq tool_calls) (empty? content))))
          (take-while #(not= "user" (:role %)) (rseq messages))))

(defn investigation [postfn llmfn messages attempt {:keys [interleaved-thinking]}]
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
      (let [{[{{tool_calls :tool_calls content :content
                reasoning_content :reasoning_content} :message} & _] :choices} (llmfn messages {:tools tools})
            ; we re-build assistant message without reasoning_content, becas
            ; it's not recommended to pass reasoning back into Qwen. With
            ; :interleaved-thinking we keep it while the round is purely tool
            ; calls (no assistant text since the last user message), so the
            ; chain of thought carries across tool results.
            keep-reasoning? (and interleaved-thinking
                                 (seq tool_calls)
                                 (empty? content)
                                 (seq reasoning_content)
                                 (tool-call-round? messages))
            assistant-message (cond-> {:role "assistant" :content content}
                                (seq tool_calls) (assoc :tool_calls tool_calls)
                                keep-reasoning? (assoc :reasoning_content reasoning_content))
            messages' (conj messages assistant-message)]
        (println (str "\n--- LLM ---"
                      (when (seq reasoning_content)
                        (str " (reasoning: " (count reasoning_content) " chars)"))))
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
            {[{{json-content :content
                v-reasoning :reasoning_content} :message}] :choices} (llmfn (into messages verification-message)
                                                                            {:response_format response-format})
            {:keys [thoughts expected_output]} (json/parse-string json-content true)
            {v-status :status} (postfn "attempt-verification" {:attempt-id attempt-id
                                                               :prediction expected_output})]
        (println "\n### SYSTEM: inputs:")
        (println (str "  " (utils/py-str verification-formatted)))
        (when (seq v-reasoning)
          (println (str "\n### SYSTEM: reasoning_content: " (count v-reasoning) " chars")))
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

(defn save-run-stats
  "Append one EDN map per attempt to run-stats.edn. Function names only become
   known from the complete-run response (problem-names)."
  [run-id model results problem-names]
  (let [fn-name (into {} (map (juxt :id :function_name) problem-names))]
    (doseq [{:keys [attempt-id result]} results]
      (spit "run-stats.edn"
            (str (pr-str {:timestamp (str (Instant/now))
                          :run-id run-id
                          :model model
                          :function-name (fn-name attempt-id)
                          :result result})
                 "\n")
            :append true))))

(defn complete-run [postfn run-id model results]
  (let [{:keys [score percent problem-names]} (postfn "complete-run" {})
        {:keys [numerator denominator]} score]
    (save-run-stats run-id model results problem-names)
    (println (str "\n### SYSTEM: run complete for model `" model "`."))
    (println (str "\nRun id: " run-id))
    (println (str "\nFinal score: " numerator "/" denominator
                  " (" (Math/round (double percent)) "%)"))))

(defn main-loop
  "Run all attempts and return a vector of {:attempt-id ... :result bool}."
  [{:keys [run-id attempts postfn llmfn prompt-config interleaved-thinking]}]
  (let [total (count attempts)]
    (vec
     (map-indexed
      (fn [idx attempt]
        (println (str "\n### SYSTEM: Starting attempt " (inc idx) "/" total))
        (let [messages (prompts/make-initial-messages (:test-limit attempt) prompt-config)
              messages' (investigation postfn llmfn messages attempt
                                       {:interleaved-thinking interleaved-thinking})]
          {:attempt-id (:attempt-id attempt)
           :result (verification postfn llmfn attempt messages')}))
      attempts))))
