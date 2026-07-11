(ns sbench-simple-harness.prompts
  (:require [selmer.parser :as parser]
            [selmer.util :as selmer-util]
            [sbench-simple-harness.prompt-variations :as variations]))

;; These prompts are plain text, never HTML, so disable Selmer's HTML escaping.
(selmer-util/turn-off-escaping!)

(defn make-initial-messages
  "Build the opening system + user messages. `prompt-config` selects the
   variants from prompt-variations, e.g. {:identity 1 :instructions 3};
   unspecified indices default to 0."
  ([test-limit] (make-initial-messages test-limit nil))
  ([test-limit prompt-config]
   (let [{identity-idx :identity instructions-idx :instructions
          :or {identity-idx 0 instructions-idx 0}} prompt-config]
     [{:role "system"
       :content (variations/system-base (nth variations/identities identity-idx)
                                        (nth variations/instructions instructions-idx))}
      {:role "user"
       :content (parser/render "Hi. I have a mystery function and I want to find out what it does.

I would like you to test my function using the provided tool until you think you know what it does, then tell me.

You may test this function up-to {{ test-limit }} times."
                        {:test-limit test-limit})}
      ])))

(def output-type->json-type
  "Map SherlockBench output types onto JSON Schema types."
  {"string"  "string"
   "integer" "integer"
   "boolean" "boolean"
   "float"   "number"})

(defn make-prediction-schema
  "Build a response_format requesting a Prediction object, mirroring the old
   Python pydantic model: `thoughts` (string) plus `expected_output` typed
   according to output-type."
  [output-type]
  {:type "json_object"
   :schema {:type "object"
            :title "Prediction"
            :description "Prediction of the function output."
            :required ["thoughts" "expected_output"]
            :properties {:thoughts {:type "string"
                                    :title "Thoughts"}
                         :expected_output {:type (output-type->json-type output-type)
                                           :title "Expected Output"}}}})

(defn make-verification-message [inputs]
  [{:role "user"
    :content (parser/render "To test your theory, please tell me what is the expected output from the function with this input:

{{ inputs }}

Please respond in JSON with two keys: \"thoughts\" and \"expected_output\".
expected_output should contain the output you expect from the function."
                     {:inputs inputs})}])

(comment
  (print (-> (make-verification-message "boop") first :content))
  )
