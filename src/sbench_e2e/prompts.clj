(ns sbench-e2e.prompts
  (:require [selmer.parser :as parser]))

(defn make-initial-messages [test-limit]
  [{:role "system"
    :content "You are a competent LLM, operating agentically.

You are provided with a mystery function which you will test to try to determine what it does. Use the provided tool to do this. There is a limit on how many total times this tool may be used, and the user message will specify what that limit is.

After each time you call the tool, use your thinking to consider some candidate hypotheses for what the function might do. If you have a strong hypothesis already, consider if there may be any alternative explanations for the behaviour you are seeing.

Then respond with your current working hypothesis (if you have one), and talk through what further tests could be useful to gather more information. Then call the tool again.

Once you are confident you know what the function does, explain it in your output and stop calling the tool."}
   {:role "user"
    :content (parser/render "Hi. I have a mystery function and I want to find out what it does.

I would like you to test my function using the provided tool until you think you know what it does, then tell me.

You may test this function up-to {{ test-limit }} times."
                     {:test-limit test-limit})}
   ])

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
