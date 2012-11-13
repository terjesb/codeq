;; based on clj-jgit

(ns datomic.codeq.git
  (:require [clojure.java.io :as io]
            [clojure.string :refer [trim]]
            [clojure.core.memoize :refer [memo-lru]])
  (:import
   java.util.Date
   [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
   [org.eclipse.jgit.treewalk TreeWalk]
   [org.eclipse.jgit.lib Constants ObjectId RepositoryBuilder]
   [org.eclipse.jgit.api Git]))

(set! *warn-on-reflection* true)

(defn ^RevWalk new-rev-walk [^Git repo]
  (RevWalk. (.getRepository repo)))

(defn ^ObjectId resolve-object
  "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob."
  [^Git repo
   ^String commit-ish]
  (.resolve (.getRepository repo) commit-ish))

(defn ^RevCommit bound-commit
  "Find a RevCommit object in a RevWalk and bound to it."
  [^Git repo
   ^RevWalk rev-walk
   ^ObjectId rev-commit]
  (.parseCommit rev-walk rev-commit))

(def cached-bound-commit (memo-lru bound-commit 10000))

(defn find-rev-commit
  "Find RevCommit instance in RevWalk by commit-ish"
  [^Git repo
   ^RevWalk rev-walk
   commit-ish]
  (->> commit-ish
       (resolve-object repo)
       (cached-bound-commit repo rev-walk)))

(defn load-repo
  "Given a path (either to the parent folder or to the `.git` folder itself), load the Git repository"
  [path]
  (let [dir (if (not (re-find #"\.git$" path))
              (io/as-file (str path "/.git"))
              (io/as-file path))]
    (if (.exists dir)
      (let [repo (-> (RepositoryBuilder.)
                     (.setGitDir dir)
                     (.readEnvironment)
                     (.findGitDir)
                     (.build))]
        (Git. repo))
      (throw (java.io.FileNotFoundException.
              (str "The Git repository at '" path "' could not be located."))))))

(defmacro with-repo
  "Binds `repo` to a repository handle"
  [path & body]
  `(let [~'repo (load-repo ~path)
         ~'rev-walk (new-rev-walk ~'repo)]
     ~@body))

(defn commit-info
  ([^Git repo
    ^RevCommit rev-commit] (commit-info repo (new-rev-walk repo) rev-commit))
  ([^Git repo
    ^RevWalk rev-walk
    ^RevCommit rev-commit]
     (let [author (.getAuthorIdent rev-commit)
           committer (.getCommitterIdent rev-commit)
           msg (-> (.getFullMessage rev-commit) str trim)
           parents (.getParents rev-commit)]
       {:sha (.getName rev-commit)
        :msg msg
        :tree (-> (.getTree rev-commit) (.getName))
        :parents (map #(.getName %) parents)
        :author (.getEmailAddress author)
        :authored (.getWhen author)
        :committer (.getEmailAddress committer)
        :committed (.getWhen committer)
        })))

(defn cat-file
  ([^Git repo
    sha] (cat-file repo (new-rev-walk repo) sha))
  ([^Git repo
    ^RevWalk rev-walk
    sha]
     (let [id (resolve-object repo sha)
           repository (.getRepository repo)
           objectloader (.open repository id)
           stream (.openStream objectloader)]
       (slurp stream))))

(defn ^TreeWalk new-tree-walk
  "Create new TreeWalk instance"
  [^Git repo
   tree-sha]
  (doto
      (TreeWalk. (.getRepository repo))
    (.addTree (resolve-object repo tree-sha))))

(defn cat-tree
  [^Git repo tree-sha]
  (let [walker (new-tree-walk repo tree-sha)
        repository (.getRepository repo)
        tree (transient [])]
    (while (.next walker)
      (conj! tree [(.name (.getObjectId walker 0))
                   (keyword (Constants/typeString (.getType (.open repository (.getObjectId walker 0)))))
                   (.getNameString walker)]))
    (persistent! tree)))
