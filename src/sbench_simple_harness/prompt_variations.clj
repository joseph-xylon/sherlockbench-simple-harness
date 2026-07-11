(ns sbench-simple-harness.prompt-variations
  "Interchangeable prompt fragments and a builder that assembles a varied
   system prompt.

   The system prompt is split into two parts -- the model's identity and the
   task instructions.")

(def identities
  "Openers describing who the model is. [0] is canonical."
  ["You are a competent LLM, operating agentically."
   "You are a capable AI assistant working autonomously."
   "You are a skilled language model acting as an autonomous agent."
   "You are a proficient AI agent operating independently."
   ])

(def instructions
  "Statements of the investigation task. Each conveys the same five points --
   test a mystery function via the tool, respect the use-limit given in the user
   message, weigh candidate hypotheses (and alternatives) after each call,
   report the working hypothesis plus useful next tests, and stop once confident.
   [0] is canonical."
  ["Your task is to figure out what an unknown function does. Investigate it by calling the provided tool on inputs of your choosing. The tool may only be used a limited number of times in total; the user message will tell you the limit.

Advise for how to investigate follows: 

Start by trying a few diverse inputs to determine what the function does. Be creative and try some different things.

Then, once you have a few data points, consider what the function might do based on the information you have so-far. Consider what is the most useful next test to confirm your idea or rule things out.

There is no reward for finishing early, so keep investigating until you have used all your tool calls, or are entirely confident what the function does.

Once you are fully confident or have run out of tool calls, write up your best hypothesis and stop calling the tool."

   "You are provided with a mystery function which you will test to try to determine what it does. Use the provided tool to do this. There is a limit on how many total times this tool may be used, and the user message will specify what that limit is.

Once you are confident you know what the function does, you will inform the user.

n.b. it is your job to pick inputs for the mystery function. Do not ask the user to provide you with parameters to test. Test the function pro-actively with the provided tool until you work out what the mystery function does."

   "You are provided with a mystery function which you will test to try to determine what it does. Use the provided tool to do this. There is a limit on how many total times this tool may be used, and the user message will specify what that limit is.

After each time you call the tool, use your thinking to consider some candidate hypotheses for what the function might do. If you have a strong hypothesis already, consider if there may be any alternative explanations for the behaviour you are seeing.

Then respond with your current working hypothesis (if you have one), and talk through what further tests could be useful to gather more information. Then call the tool again.

Once you are confident you know what the function does, explain it in your output and stop calling the tool."

   "Your objective is to reverse-engineer a mystery function using the provided testing tool. You have a strict maximum number of tool calls, which the user message will provide.

To succeed, you must actively fight confirmation bias. Follow this rigorous scientific approach:
1. Probe: Test a baseline of standard inputs to get a feel for the    function.
2. Hypothesize: State at least two distinct, competing hypotheses that    fit the current data.
3. Falsify: Do not just test inputs that confirm your leading idea. Design your next input specifically to break your hypothesis or to differentiate between your competing theories. Always check edge cases (e.g., zero, negatives, empty values, very large    numbers).
4. Iterate: Update your theories based on the new data.

Continue this cycle. Never settle for the first obvious pattern you see. Use your tool calls to exhaustively test boundaries and break your own assumptions. Once you have eliminated all other possibilities or run out of calls, state your final, verified conclusion and stop."])

(defn system-base
  "The fixed system prompt for a run: identity + instructions."
  [identity instructions]
  (str identity "\n\n" instructions))
