(ns sbench-e2e.run-bench
  (:require [sbench-e2e.utils :as utils]
            [sbench-e2e.prompts :as prompts]
            [cheshire.core :as json]))

(defn start-run [postfn problem-set attempts-per-problem]
  (let [post-data (conj (utils/map-of problem-set attempts-per-problem)
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
    (println args-norm "->" fnoutput)

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
    (loop [messages messages
           max-loop test-limit
           ]
      (prn messages)
      (let [{[{{tool_calls :tool_calls :as assistant-message} :message} & _] :choices} (llmfn messages tools)
            messages' (conj messages assistant-message)]
        (if (and (seq tool_calls) (< 0 max-loop))
          (let [tool-message (handle-tool-call postfn attempt-id arg-spec (first tool_calls))]
            (recur (conj messages' tool-message) (- max-loop 1)))
          messages')))))

(defn verification [postfn llmfn attempt messages]
  (loop []
    (let [{:keys [next-verification output-type] :as next} (postfn "next-verification" {:attempt-id (:attempt-id attempt)})
          verification-formatted (apply merge {} (for [[k v] (list-to-map next-verification)]
                                                   {k (:type v)}))
          verification-message (prompts/make-verification-message verification-formatted)
          {[{{json-content :content} :message}] :choices} (llmfn (into messages verification-message))
          {:keys [thoughts expected_output]} (json/parse-string json-content true)
          {v-status :status} (postfn "attempt-verification" {:attempt-id (:attempt-id attempt)
                                                             :prediction expected_output})]
      (print "\n### SYSTEM: inputs:")
      (print "\n" verification-formatted)

      (case v-status
        "correct"
        (do
          (print "\n### SYSTEM: CORRECT")
          (recur))
        "wrong"
        (do
          (print "\n### SYSTEM: WRONG\n")
          false)
        "done"
        (do
          (print "\n### SYSTEM: CORRECT\n")
          true)))))

(defn main-loop [{:keys [run-id attempts postfn llmfn]}]
  (doseq [attempt attempts]
    (let [messages (prompts/make-initial-messages (:test-limit attempt))
          messages' (investigation postfn llmfn messages attempt)]
      (verification postfn llmfn attempt messages'))))
