#!/usr/bin/env bb

(require '[clojure.string :as str])

;; System Prompt
(defn system-prompt []
  (let [now (.toString (java.time.Instant/now))]
    (str "You are an expert researcher. Today is " now ". Follow these instructions when responding:\n"
         "  - You may be asked to research subjects that is after your knowledge cutoff, assume the user is right when presented with news.\n"
         "  - The user is a highly experienced analyst, no need to simplify it, be as detailed as possible and make sure your response is correct.\n"
         "  - Be highly organized.\n"
         "  - Suggest solutions that I didn't think about.\n"
         "  - Be proactive and anticipate my needs.\n"
         "  - Treat me as an expert in all subject matter.\n"
         "  - Mistakes erode my trust, so be accurate and thorough.\n"
         "  - Provide detailed explanations, I'm comfortable with lots of detail.\n"
         "  - Value good arguments over authorities, the source is irrelevant.\n"
         ;; "  - Consider new technologies and contrarian ideas, not just the conventional wisdom.\n"
         "  - You may use high levels of speculation or prediction, just flag it for me.")))

;; Research Queries Generation Prompt
(defn queries-prompt
  [{:keys [query num-queries learnings]}]
  (str "Given the following prompt from the user, generate a list of questions to research the topic. "
       "Return a maximum of " num-queries " queries, but feel free to return less if the original prompt is clear. "
       "Make sure each query is unique and not similar to each other: "
       "<prompt>" query "</prompt>\n\n"
       (when (seq learnings)
         (str "Here are some learnings from previous research, use them to generate more specific queries: "
              (str/join "\n" learnings)))))

;; Research Results Processing Prompt
(defn research-results-prompt
  [{:keys [query contents num-learnings]}]
  (str "Given the following contents from a research document for the query <query>" query "</query>, "
       "generate a list of learnings from the contents. Return a maximum of " num-learnings " learnings, "
       "but feel free to return less if the contents are clear. Make sure each learning is unique and not similar to each other. "
       "The learnings should be concise and to the point, as detailed and infromation dense as possible. "
       "Make sure to include any entities like people, places, companies, products, things, etc in the learnings, "
       "as well as any exact metrics, numbers, or dates. The learnings will be used to research the topic further.\n\n"
       "<contents>"
       (str/join "\n"
                 (map #(str "<content>\n" % "\n</content>")
                      contents))
       "</contents>"))

;; Final Report Generation Prompt
(defn final-report-prompt
  [{:keys [prompt learnings]}]
  (str "Given the following prompt from the user, write a final report on the topic using the learnings from research. "
       "Make it as as detailed as possible, aim for 3 or more pages, include ALL the learnings from research:\n\n"
       "<prompt>" prompt "</prompt>\n\n"
       "Here are all the learnings from previous research:\n\n"
       "<learnings>\n"
       (str/join "\n"
                 (map #(str "<learning>\n" % "\n</learning>")
                      learnings))
       "\n</learnings>"))

;; Feedback Generation Prompt
(defn feedback-prompt
  [{:keys [query num-questions]}]
  (str "Given the following query from the user, ask some follow up questions to clarify the research direction."
       "Return a maximum of " num-questions " questions, but feel free to return less if the original query is clear: "
       "<query>" query "</query>"))

;; Example usage:
(comment
  ;; System prompt
  (println (system-prompt))

  ;; Research queries prompt
  (println (queries-prompt
            {:query "What are the latest developments in quantum computing?"
             :num-queries 3
             :learnings ["IBM announced new 1000-qubit processor"
                         "Google achieved quantum supremacy in 2023"]}))

  ;; Research results processing prompt
  (println (research-results-prompt
            {:query "quantum computing developments"
             :num-learnings 3
             :contents ["IBM released new quantum processor"
                        "Google announces breakthrough"]}))

  ;; Final report prompt
  (println (final-report-prompt
            {:prompt "Analyze quantum computing progress"
             :learnings ["IBM achievement"
                         "Google breakthrough"
                         "Microsoft advances"]}))

  ;; Feedback prompt
  (println (feedback-prompt
            {:query "Tell me about quantum computing"
             :num-questions 3})))

(require '[babashka.cli :as cli]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def ^:dynamic *verbose* false)
(def ^:dynamic *output-dir* nil)

(defn log
  "Log a message when verbose mode is enabled"
  [& args]
  (when *verbose*
    (apply println args)))

(defn slugify
  "Convert a string to a URL-friendly slug"
  [s]
  (let [shorten #(->> (str/split % #"-")
                      (take 9)
                      (str/join "-"))]
    (-> s
        str/lower-case
        (str/replace #"[^a-z\s-]" "")
        (str/replace #"\s+" "-")
        (str/replace #"-+" "-")
        (shorten)
        (str/trim))))

(defn get-first-line
  "Get the first non-empty line from text"
  [text]
  (first (filter (complement str/blank?) (str/split-lines text))))

(defn save-llm-output
  "Save LLM output to a file, using the first line as the slug for the filename"
  [type content]
  (let [first-line (get-first-line content)
        filename (str (slugify first-line) ".md")
        dir (fs/file *output-dir* (name type))]
    (fs/create-dirs dir)
    (let [output-file (fs/file dir filename)]
      (spit output-file content)
      (str output-file))))

(def default-model
  "deepseek-chat")

(defn llm-prompt
  "Execute an LLM prompt and save the output"
  ([prompt] (llm-prompt {} prompt))
  ([opts prompt]
   (let [result (-> (apply p/process
                           {:out :string
                            :in prompt}
                           (cond-> ["llm"]
                             true (conj "-m" (or (:model opts) default-model))
                             true (conj "-s" (system-prompt))
                             (:system opts) (conj "-s" (:system opts))))
                    deref
                    :out)]
     result)))

(defn generate-queries
  "Generate search queries based on the input and previous learnings"
  [query learnings]
  (log "Generating queries for:" query)
  (when (seq learnings)
    (log "Using" (count learnings) "previous learnings"))
  (let [result (llm-prompt
                {}
                (queries-prompt {:query query
                                 :num-queries 10
                                 :learnings learnings}))]
    (save-llm-output :queries result)
    (log "Generated" (count (str/split-lines result)) "queries")
    result))

(defn extract-learnings
  "Extract key learnings from research content"
  [content]
  (log "Extracting learnings from content")
  (let [result (llm-prompt
                {}
                (research-results-prompt {:query "Current research iteration"
                                          :contents [content]
                                          :num-learnings 10}))]
    (save-llm-output :learnings result)
    (log "Extracted" (count (str/split-lines result)) "learnings")
    result))

(defn generate-followup-questions
  "Generate follow-up questions based on learnings"
  [learnings]
  (log "Generating follow-up questions from" (count learnings) "learnings")
  (let [result (llm-prompt
                {}
                (feedback-prompt {:query (str/join "\n" learnings)
                                  :num-questions 10}))]
    (save-llm-output :questions result)
    (log "Generated" (count (str/split-lines result)) "follow-up questions")
    result))

(defn iterative-research
  "Perform one iteration of research"
  [query depth breadth current-learnings]
  (log "\n📚 Starting research iteration")
  (log "Depth:" depth "Breadth:" breadth)
  (log "Current learnings:" (count current-learnings))
  (if (zero? depth)
    (do
      (log "Reached maximum depth, returning current learnings")
      {:learnings current-learnings})
    (let [queries (str/split-lines (generate-queries query current-learnings))
          limited-queries (take breadth queries)
          _ (log "Researching" (count limited-queries) "queries")
          new-results (for [q limited-queries]
                        (do
                          (log "\n🔎 Researching query:" q)
                          (let [research-result (llm-prompt {} q)
                                learnings (str/split-lines (extract-learnings research-result))]
                            learnings)))
          combined-learnings (into current-learnings (distinct (flatten new-results)))
          _ (log "Combined learnings count:" (count combined-learnings))
          followup-questions (when (pos? depth)
                               (str/split-lines (generate-followup-questions combined-learnings)))
          _ (when followup-questions
              (log "\n❓ Following up on" (count followup-questions) "questions"))
          sub-results (when (and (pos? depth) (seq followup-questions))
                        (for [q (take breadth followup-questions)]
                          (do
                            (log "\n📖 Diving deeper into:" q)
                            (:learnings (iterative-research q (dec depth) breadth combined-learnings)))))]
      {:learnings (into combined-learnings (distinct (flatten sub-results)))})))

(defn generate-final-report
  "Generate a final research report"
  [query initial-depth initial-breadth learnings]
  (log "\n📝 Generating final report")
  (log "Total learnings to summarize:" (count learnings))
  (let [result (llm-prompt
                {}
                (final-report-prompt {:prompt (str "Research Query: " query "\n"
                                                   "Research Depth: " initial-depth "\n"
                                                   "Research Breadth: " initial-breadth)
                                      :learnings learnings}))]
    (save-llm-output :reports result)))

(def cli-options
  {:coerce {:output-dir fs/file}
   :spec {:help {:coerce :boolean
                 :desc "Show this help message"}
          :verbose {:coerce :boolean
                    :desc "Enable verbose logging"}
          :query {:desc "Research query to investigate"}
          :depth {:coerce :long
                  :default 2
                  :desc "Research depth - how many levels deep to explore (1-5)"
                  :validate {:pred #(<= 1 % 5)
                             :ex-msg "Depth must be between 1 and 5"}}
          :breadth {:coerce :long
                    :default 4
                    :desc "Research breadth - how many parallel queries to explore (2-10)"
                    :validate {:pred #(<= 2 % 10)
                               :ex-msg "Breadth must be between 2 and 10"}}
          :output-dir {:default "research-output"
                       :desc "Directory to store research outputs (will be created if it doesn't exist)"}}
   :order [:help :query :depth :breadth :output-dir :verbose]
   :description "Deep Research Assistant - Recursively research any topic using LLMs

This tool performs iterative research on a given topic by:
1. Generating specific search queries
2. Extracting key learnings
3. Generating follow-up questions
4. Recursively exploring those questions
5. Combining all findings into a final report

All intermediate results and the final report are saved as markdown files."})

(defn -main [& args]
  (let [{:keys [query depth breadth verbose output-dir] :as opts} (cli/parse-opts args cli-options)]
    (if (or (:help opts)
            (not query))
      (do
        (println (:description cli-options) "\n")
        (println (cli/format-opts cli-options)))
      (binding [*verbose* verbose
                *output-dir* output-dir]
        (log "Starting research with depth:" depth "breadth:" breadth)
        (log "Saving output to:" (str output-dir))
        (let [results (iterative-research query depth breadth [])
              report (generate-final-report query depth breadth (:learnings results))]
          (println "Research completed! Final report saved to:" (save-llm-output :reports report)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*)) 