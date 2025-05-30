(ns main
  (:require
   [babashka.pods :as pods]
   [babashka.process :as p]
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]))

(def db "rsynczy.db")
(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn cleanup-and-exit [code]
  (try
    (doseq [file ["/tmp/rsynczy-jobs.edn" "/tmp/rsynczy-remotes.edn"]]
      (when (.exists (java.io.File. file))
        (.delete (java.io.File. file))))
    (catch Exception _))
  (System/exit code))

(defn clean-tmp-preview-files []
  (try
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(try
                 (doseq [file ["/tmp/rsynczy-jobs.edn" "/tmp/rsynczy-remotes.edn"]]
                   (when (.exists (java.io.File. file))
                     (.delete (java.io.File. file))))
                 (catch Exception _))))
    (catch Exception e
      (println (.getMessage e)))))

(defn format! [query]
  (if (string? query)
    query
    (sql/format query)))

(defn query! [query]
  (sqlite/query db (format! query)))

(defn execute! [query]
  (sqlite/execute! db (format! query)))

(defn init-db []
  (execute! "CREATE TABLE IF NOT EXISTS remotes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            hostname TEXT NOT NULL,
            username TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP)")
  (execute! "CREATE TABLE IF NOT EXISTS sync_jobs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            remote_id INTEGER NOT NULL,
            source_path TEXT NOT NULL,
            destination_path TEXT NOT NULL,
            rsync_options TEXT DEFAULT '',
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (remote_id) REFERENCES remotes (id))"))

(defn remotes []
  (query! (-> (h/select :*)
              (h/from :remotes)
              (h/order-by :name))))

(defn jobs []
  (query! (-> (h/select :sj.* [:r.name :remote_name] :r.hostname :r.username)
              (h/from [:sync_jobs :sj])
              (h/join [:remotes :r] [:= :sj.remote_id :r.id])
              (h/order-by :sj.name))))

(defn remote-by-id [id]
  (first (query! (-> (h/select :*)
                     (h/from :remotes)
                     (h/where [:= :id id])))))

(defn job-by-id [id]
  (first (query! (-> (h/select :sj.*
                               [:r.name :remote_name] :r.hostname :r.username)
                     (h/from [:sync_jobs :sj])
                     (h/join [:remotes :r] [:= :sj.remote_id :r.id])
                     (h/where [:= :sj.id id])))))

(defn jobs-for-remote [remote-id]
  (query! (-> (h/select :id :name)
              (h/from :sync_jobs)
              (h/where [:= :remote_id remote-id]))))

(defn remote-by-name [name]
  (first (query! (-> (h/select :*)
                     (h/from :remotes)
                     (h/where [:= :name name])))))

;; TODO can clean this up and make less rigid
(defn fzf [items &
           {:keys [prompt preview empty-msg print-query allow-custom]
            :or {prompt "> " empty-msg "No items" allow-custom false}}]
  (let [display-items (if (empty? items) [empty-msg] items)
        help-text (str "=== rsynczy help ===\\n"
                       "Navigation: ↑/↓ Navigate, Enter Select, Esc Cancel\\n"
                       "Press Ctrl-H for help, Ctrl-D to exit")]
    (loop []
      (let [input (str/join "\n" display-items)
            base-args ["fzf"
                       "--prompt" prompt
                       "--height=20%" "--border"
                       (str "--bind=ctrl-h:execute(echo '" help-text "' && read -n 1)+abort")
                       "--layout=reverse"
                       "--header=Press Ctrl-H for help"]
            args (cond-> base-args
                   preview (concat ["--preview" preview "--preview-window=right:50%"])
                   (or print-query allow-custom) (conj "--print-query"))
            result (try
                     (let [fzf-result (apply p/shell {:in input :out :string :err :string} args)]
                       (if (= 0 (:exit fzf-result))
                         (let [output (str/trim (:out fzf-result))]
                           (if (or print-query allow-custom)
                             (let [lines (str/split-lines output)
                                   query (first lines)
                                   selection (second lines)]
                               (if (and selection (not= selection empty-msg))
                                 {:success true :value selection}
                                 (if (and query (seq query))
                                   {:success true :value query}
                                   {:success false :retry false})))
                             (if (= output empty-msg)
                               {:success false :retry false}
                               {:success true :value output})))
                         (if (= 130 (:exit fzf-result))
                           {:success false :retry true} ; ctrl-h pressed show help & retry
                           {:success false :retry false}))) ; cancelled or misc error

                     (catch clojure.lang.ExceptionInfo e
                       (let [ex-data (ex-data e)]
                         (if (= (:type ex-data) :babashka.process/error)
                           (when-let [output (str/trim (:out ex-data))]
                             (if (or print-query allow-custom)
                               (let [lines (str/split-lines output)
                                     query (first lines)]
                                 (if (seq query)
                                   {:success true :value query}
                                   {:success false :retry false}))
                               {:success false :retry false}))
                           (throw e)))))]
        (cond
          (:success result) (:value result)
          (:retry result) (recur)
          :else nil)))))

(defn confirm? [text]
  (= "Yes" (fzf ["Yes" "No"] :prompt (str text "? "))))

(defn fmt-remote [r]
  (str "[" (:id r) "] " (:name r) " (" (:username r) "@" (:hostname r) ")"))

(defn fmt-job [j]
  (str "[" (:id j) "] " (:name j) " | " (:remote_name j) " | " (:source_path j) " -> " (:destination_path j)))

(defn fmt-remote-detail [r]
  (str "ID: " (:id r) "\nName: " (:name r) "\nHost: " (:hostname r)
       "\nUser: " (:username r) "\nCreated: " (:created_at r)))

(defn fmt-job-detail [j]
  (str "ID: " (:id j) "\nName: " (:name j) "\nRemote: " (:remote_name j)
       " (" (:username j) "@" (:hostname j) ")\nSource: " (:source_path j)
       "\nDest: " (:destination_path j) "\nOptions: " (:rsync_options j)
       "\nCreated: " (:created_at j)))

(defn parse-id [s]
  (when (and s (string? s)) (when-let [m (re-find #"^\[(\d+)\]" s)] (parse-long (second m)))))

;; Preview system
(defn write-preview-data []
  (let [job-data (into {} (map #(vector (:id %) (fmt-job-detail %)) (jobs)))
        remote-data (into {} (map #(vector (:id %) (fmt-remote-detail %)) (remotes)))]
    (spit "/tmp/rsynczy-jobs.edn" (pr-str job-data))
    (spit "/tmp/rsynczy-remotes.edn" (pr-str remote-data))))

(defn write-preview-script [type]
  (let [file (str "/tmp/rsynczy-" type ".edn")]
    (str "bb -e \"(let [input (first *command-line-args*) "
         "id-match (re-find #\\\"\\\\[\\\\d+\\\\]\\\" input) "
         "id (when id-match (parse-long (subs id-match 1 (dec (count id-match))))) "
         "data-file \\\"" file "\\\"] "
         "(if (and id (.exists (java.io.File. data-file))) "
         "(let [data (read-string (slurp data-file))] "
         "(println (get data id \\\"Not found\\\"))) "
         "(println \\\"Not found\\\")))\" {}")))

(defn add-remote [name host user]
  (execute! (-> (h/insert-into :remotes) (h/values [{:name name :hostname host :username user}]))))

(defn add-job [name remote-id src dest opts]
  (execute! (-> (h/insert-into :sync_jobs)
                (h/values [{:name name :remote_id remote-id :source_path src
                            :destination_path dest :rsync_options (or opts "")}]))))

(defn del-job [id]
  (when (job-by-id id)
    (execute! (-> (h/delete-from :sync_jobs) (h/where [:= :id id])))))

(defn del-remote [id]
  (let [deps (jobs-for-remote id)]
    (if (seq deps)
      (println "Cannot delete: has" (count deps) "dependent jobs")
      (when (remote-by-id id)
        (execute! (-> (h/delete-from :remotes) (h/where [:= :id id])))
        #_(println "Deleted remote" id)))))

(defn force-del-remote [id]
  (let [deps (jobs-for-remote id)]
    (doseq [job deps] (execute! (-> (h/delete-from :sync_jobs) (h/where [:= :id (:id job)]))))
    (execute! (-> (h/delete-from :remotes) (h/where [:= :id id])))
    (println "Force deleted remote" id "and" (count deps) "jobs")))

(defn build-rsync-cmd [job]
  (let [{:keys [source_path destination_path rsync_options username hostname]} job
        dest (if hostname (str username "@" hostname ":" destination_path) destination_path)
        opts (if (seq rsync_options) (str "-av " rsync_options) "-av")]
    (str "rsync " opts " " source_path " " dest)))

(defn run-rsync [id dry-run?]
  (if-let [job (job-by-id id)]
    (let [cmd (build-rsync-cmd job)
          cmd (if dry-run? (str cmd " --dry-run") cmd)]
      (try
        (let [result (p/shell {:out :string :err :string} cmd)]
          (when (seq (:out result)) (println (:out result)))
          (when (seq (:err result)) (println (:err result)))
          result)
        (catch Exception e (println "Error:" (.getMessage e)))))
    (println "Job not found")))

(defmacro when-let*
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)

    (if (not (even? (count bindings)))
      `(throw (IllegalArgumentException. "when-let* bindings must contain an even number of forms (pairs)."))
      `(when-let [~(first bindings) ~(second bindings)]
         (when-let* ~(vec (drop 2 bindings)) ~@body)))))

(def ^:private name-regex
  #"^[a-zA-Z0-9_-]+$")

(def ^:private host-name-regex
  #"^[a-zA-Z0-9.-]+$")

(defn check-job-duplicate [name]
  (some #(= (:name %) name) (jobs)))

(defn create-remote-interactive [name]
  (when-let* [host (fzf ["localhost"]
                        :prompt "Hostname (or type): " :allow-custom true)
              user (fzf [(System/getProperty "user.name")]
                        :prompt "Username (or type): " :allow-custom true)]
             (when (and (re-matches host-name-regex host)
                      (re-matches name-regex user)
                      (<= (count host) 253)
                      (<= (count user) 32))
               (add-remote name host user)
               (remote-by-name name))))

(defn select-remote []
  (let [rs (remotes)
        remote-names (set (map :name rs))
        choice (fzf (map fmt-remote rs) :prompt "Remote (or type new): " :allow-custom true :empty-msg "Type remote name")]
    (when choice
      (cond
        (parse-id choice)
        (remote-by-id (parse-id choice))

        (contains? remote-names choice)
        (remote-by-name choice)

        :else ; entered a new name
        (when (and (re-matches name-regex choice)
                 (<= (count choice) 50)
                 (not (contains? remote-names choice)))
          (create-remote-interactive choice))))))

(defn create-job []
  (when-let* [remote (select-remote)
              src (fzf ["~/Documents" "~/Downloads" "/tmp/" "."]
                       :prompt "Source path (or type): " :allow-custom true)
              dest (fzf ["/tmp/" "~/backup/" "/var/backups/"]
                        :prompt "Destination path (or type): " :allow-custom true)
              opts-map {"Default" "-av" 
                        "Mirror" "-av --delete"
                        "Compress" "-avz"}
              opts-choice (fzf (keys opts-map) :prompt "Rsync options (or type): " :allow-custom true)
              opts (get opts-map opts-choice opts-choice)
              name (fzf [] :prompt "Job name: " :allow-custom true :empty-msg "Enter job name")]
             (if (and (seq src)
                      (seq dest)
                      (seq name)
                      (re-matches name-regex name)
                      (<= (count name) 100)
                      (not (check-job-duplicate name)))
               (add-job name (:id remote) src dest opts)
               (println "✗ Invalid input. Check job name format and ensure it's unique."))))

(defn browse-jobs []
  (write-preview-data)
  (fzf (map fmt-job (jobs))
       :prompt "Jobs: "
       :preview (write-preview-script "jobs")
       :empty-msg "No jobs found"))

(defn browse-remotes []
  (write-preview-data)
  (fzf (map fmt-remote (remotes))
       :prompt "Remotes: "
       :preview (write-preview-script "remotes")
       :empty-msg "No remotes found"))

(defn run-job []
  (when-let* [choice (fzf (map fmt-job (jobs)) :prompt "Run: " :empty-msg "No jobs to run")
              id (parse-id choice)
              run-type (fzf ["Dry run (preview only)" "Execute sync"] :prompt "Run type: ")]
             (run-rsync id (= run-type "Dry run (preview only)"))))

(defn delete-job []
  (when-let* [choice (fzf (map fmt-job (jobs)) :prompt "Delete job: " :empty-msg "No jobs to delete")
              id (parse-id choice)]
             (when (confirm? "Delete")
               (del-job id))))

(defn delete-remote []
  (when-let* [choice (fzf (map fmt-remote (remotes)) :prompt "Delete remote: " :empty-msg "No remotes to delete")
              id (parse-id choice)]
             (if-let [deps (seq (jobs-for-remote id))]
               (do
                 (doseq [job deps] (println "-" (:name job)))
                 (let [action (fzf ["Cancel" "Force delete all" "Delete jobs first"] :prompt "Action: ")]
                   (condp = action
                     "Force delete all" (when (confirm? "Force delete") (force-del-remote id))
                     "Delete jobs first" (do (doseq [job deps] (del-job (:id job)))
                                             (when (confirm? "Delete remote") (del-remote id)))
                     nil)))
               (when (confirm? "Delete") (del-remote id)))))

(defn delete-menu []
  (when-let [choice (fzf ["Delete job" "Delete remote"] :prompt "Delete: ")]
    (case choice
      "Delete job" (delete-job)
      "Delete remote" (delete-remote))))

(defn main-menu []
  (let [actions {"Create job" create-job
                 "Browse jobs" browse-jobs
                 "Run job" run-job
                 "Browse remotes" browse-remotes
                 "Delete" delete-menu
                 "Exit" #(cleanup-and-exit 0)}]
    (try
      (loop []
        (let [choice (fzf (keys actions) :prompt "Action: ")]
          (if choice
            (do
              ((get actions choice))
              (recur))
            (cleanup-and-exit 0))))
      (catch InterruptedException _
        (cleanup-and-exit 0))
      (catch java.io.EOFException _
        (cleanup-and-exit 0)))))

(defn -main []
  (clean-tmp-preview-files)
  (init-db)
  (try
    (main-menu)
    (catch Exception e
      (println "Error:" (.getMessage e))
      (cleanup-and-exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
