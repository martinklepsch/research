#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def ^:dynamic *verbose* false)

(defn log
  "Log a message when verbose mode is enabled"
  [& args]
  (when *verbose*
    (apply println "🔍" args)))

(defn slugify
  "Convert a string to a URL-friendly slug"
  [s]
  (let [shorten #(->> (str/split % #"-")
                      (take 9)
                      (str/join "-"))]
    (-> s
        str/lower-case
        (str/replace #"[^a-z0-9\s-]" "")
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
  [content]
  (let [first-line (get-first-line content)
        filename (str (slugify first-line) ".md")]
    (spit filename content)
    filename))

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
                             (:system opts) (conj "-s" (:system opts))))
                    deref
                    :out)]
     (save-llm-output result)
     result)))

(defn generate-queries
  "Generate search queries based on the input and previous learnings"
  [query learnings]
  (log "Generating queries for:" query)
  (when (seq learnings)
    (log "Using" (count learnings) "previous learnings"))
  (let [result (llm-prompt
                {:system "You are a research assistant. Generate specific search queries based on the input query and previous learnings. Return one query per line."}
                (str "Query: " query "\n\n"
                     (when (seq learnings)
                       (str "Previous Learnings:\n"
                            (str/join "\n" learnings)))))]
    (log "Generated" (count (str/split-lines result)) "queries")
    result))

(defn extract-learnings
  "Extract key learnings from research content"
  [content]
  (log "Extracting learnings from content")
  (let [result (llm-prompt
                {:system "You are a research assistant. Extract key learnings from the provided content. Return one learning per line."}
                content)]
    (log "Extracted" (count (str/split-lines result)) "learnings")
    result))

(defn generate-followup-questions
  "Generate follow-up questions based on learnings"
  [learnings]
  (log "Generating follow-up questions from" (count learnings) "learnings")
  (let [result (llm-prompt
                {:system "You are a research assistant. Generate relevant follow-up questions based on the learnings. Return one question per line."}
                (str/join "\n" learnings))]
    (log "Generated" (count (str/split-lines result)) "follow-up questions")
    result))

(defn research-iteration
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
                          (let [research-result (llm-prompt
                                                 {:system "You are a research assistant. Provide detailed information about the query."}
                                                 q)
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
                            (:learnings (research-iteration q (dec depth) breadth combined-learnings)))))]
      {:learnings (into combined-learnings (distinct (flatten sub-results)))})))

(defn generate-final-report
  "Generate a final research report"
  [query initial-depth initial-breadth learnings]
  (log "\n📝 Generating final report")
  (log "Total learnings to summarize:" (count learnings))
  (llm-prompt
   {:system "You are a research assistant. Generate a comprehensive research report."}
   (str "Research Query: " query "\n"
        "Research Depth: " initial-depth "\n"
        "Research Breadth: " initial-breadth "\n\n"
        "Findings:\n"
        (str/join "\n" learnings))))

(def cli-options
  {:spec {:help {:coerce :boolean}
          :verbose {:coerce :boolean
                    :desc "Enable verbose logging"}
          :query {:desc "Research query"}
          :depth {:coerce :long
                  :default 2
                  :desc "Research depth (1-5)"
                  :validate {:pred #(<= 1 % 5)
                             :ex-msg "Depth must be between 1 and 5"}}
          :breadth {:coerce :long
                    :default 4
                    :desc "Research breadth (2-10)"
                    :validate {:pred #(<= 2 % 10)
                               :ex-msg "Breadth must be between 2 and 10"}}}})

(defn -main [& args]
  (let [{:keys [query depth breadth verbose] :as opts} (cli/parse-opts args cli-options)]
    (if (:help opts)
      (println (cli/format-opts cli-options))
      (binding [*verbose* verbose]
        (log "Starting research with depth:" depth "breadth:" breadth)
        (let [results (research-iteration query depth breadth [])
              report (generate-final-report query depth breadth (:learnings results))]
          (println "Research completed! Final report saved to:" (save-llm-output report)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*)) 