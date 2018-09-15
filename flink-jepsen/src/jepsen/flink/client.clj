;; Licensed to the Apache Software Foundation (ASF) under one
;; or more contributor license agreements.  See the NOTICE file
;; distributed with this work for additional information
;; regarding copyright ownership.  The ASF licenses this file
;; to you under the Apache License, Version 2.0 (the
;; "License"); you may not use this file except in compliance
;; with the License.  You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns jepsen.flink.client
  (:require [clj-http.client :as http]
            [clojure.tools.logging :refer :all]
            [jepsen.client :as client]
            [jepsen.flink.zookeeper :as fz]
            [jepsen.flink.utils :as fu]
            [zookeeper :as zk])
  (:import (java.io ByteArrayInputStream ObjectInputStream)))

(defn connect-zk-client!
  [connection-string]
  (zk/connect connection-string :timeout-msec 60000))

(defn read-url
  [bytes]
  (with-open [object-input-stream (ObjectInputStream. (ByteArrayInputStream. bytes))]
    (.readUTF object-input-stream)))

(defn wait-for-zk-operation
  [zk-client operation path]
  (let [p (promise)]
    (letfn [(iter [_]
              (when-let [res (operation zk-client path :watcher iter)]
                (deliver p res)))
            ]
      (iter nil)
      p)))

(defn wait-for-path-to-exist
  [zk-client path]
  (info "Waiting for path" path "in ZK.")
  (wait-for-zk-operation zk-client zk/exists path))

(defn wait-for-children-to-exist
  [zk-client path]
  (wait-for-zk-operation zk-client zk/children path))

(defn find-application-id
  [zk-client]
  (do
    (->
      (wait-for-path-to-exist zk-client "/flink")
      (deref))
    (->
      (wait-for-children-to-exist zk-client "/flink")
      (deref)
      (first))))

(defn watch-node-bytes
  [zk-client path callback]
  (when (zk/exists zk-client path :watcher (fn [_] (watch-node-bytes zk-client path callback)))
    (->>
      (zk/data zk-client path :watcher (fn [_] (watch-node-bytes zk-client path callback)))
      :data
      (callback))))

(defn make-job-manager-url [test]
  (let [rest-url-atom (atom nil)
        zk-client (connect-zk-client! (fz/zookeeper-quorum test))
        init-future (future
                      (let [application-id (find-application-id zk-client)
                            path (str "/flink/" application-id "/leader/rest_server_lock")
                            _ (->
                                (wait-for-path-to-exist zk-client path)
                                (deref))]
                        (info "Determined application id to be" application-id)
                        (watch-node-bytes zk-client path
                                          (fn [bytes]
                                            (let [url (read-url bytes)]
                                              (info "Leading REST url changed to" url)
                                              (reset! rest-url-atom url))))))]
    {:rest-url-atom rest-url-atom
     :closer        (fn [] (zk/close zk-client))
     :init-future   init-future}))

(defn list-jobs!
  [base-url]
  (->>
    (http/get (str base-url "/jobs") {:as :json})
    :body
    :jobs
    (map :id)))

(defn job-running?
  [base-url job-id]
  (let [response (http/get (str base-url "/jobs/" job-id) {:as :json :throw-exceptions false})
        body (:body response)
        error (:errors body)]
    (cond
      (http/missing? response) false
      (not (http/success? response)) (throw (ex-info "Could not determine if job is running" {:job-id job-id :error error}))
      :else (do
              (assert (:vertices body) "Job does not have vertices")
              (->>
                body
                :vertices
                (map :status)
                (every? #(= "RUNNING" %)))))))

(defn- cancel-job!
  "Cancels the specified job. Returns true if the job could be canceled.
  Returns false if the job does not exist. Throws an exception if the HTTP status
  is not successful."
  [base-url job-id]
  (let [response (http/patch (str base-url "/jobs/" job-id) {:as :json :throw-exceptions false})
        error (-> response :body :errors)]
    (cond
      (http/missing? response) false
      (not (http/success? response)) (throw (ex-info "Job cancellation unsuccessful" {:job-id job-id :error error}))
      :else true)))

(defmacro dispatch-operation
  [op & body]
  `(try
     (assoc ~op :type :ok :value ~@body)
     (catch Exception e# (do
                           (warn e# "An exception occurred while running" (quote ~@body))
                           (assoc ~op :type :fail :error (.getMessage e#))))))

(defmacro dispatch-operation-or-fatal
  "Dispatches op by evaluating body, retrying a number of times if needed.
  Fails fatally if all retries are exhausted."
  [op & body]
  `(assoc ~op :type :ok :value (fu/retry (fn [] ~@body) :fallback (fn [e#]
                                                                    (fatal e# "Required operation did not succeed" (quote ~@body))
                                                                    (System/exit 1)))))

(defn- dispatch-rest-operation!
  [rest-url job-id op]
  (assert job-id)
  (if-not rest-url
    (assoc op :type :fail :error "Have not determined REST URL yet.")
    (case (:f op)
      :job-running? (dispatch-operation op (fu/retry
                                             (partial job-running? rest-url job-id)
                                             :retries 3
                                             :fallback #(throw %)))
      :cancel-job (dispatch-operation-or-fatal op (cancel-job! rest-url job-id)))))

(defrecord Client
  [deploy-cluster!                                          ; function that starts a non-standalone cluster and submits the job
   closer                                                   ; function that closes the ZK client
   rest-url                                                 ; atom storing the current rest-url
   init-future                                              ; future that completes if rest-url is set to an initial value
   job-id                                                   ; atom storing the job-id
   job-submitted?]                                          ; Has the job already been submitted? Used to avoid re-submission if the client is re-opened.
  client/Client
  (open! [this test _]
    (info "Open client.")
    (let [{:keys [rest-url-atom closer init-future]} (make-job-manager-url test)]
      (assoc this :closer closer
                  :rest-url rest-url-atom
                  :init-future init-future)))

  (setup! [_ test]
    (info "Setup client.")
    (when (compare-and-set! job-submitted? false true)
      (deploy-cluster! test)
      (deref init-future)
      (let [jobs (fu/retry (fn [] (list-jobs! @rest-url))
                           :fallback (fn [e]
                                       (fatal e "Could not get running jobs.")
                                       (System/exit 1)))
            num-jobs (count jobs)
            job (first jobs)]
        (assert (= 1 num-jobs) (str "Expected 1 job, was " num-jobs))
        (info "Submitted job" job)
        (reset! job-id job))))

  (invoke! [_ _ op]
    (dispatch-rest-operation! @rest-url @job-id op))

  (teardown! [_ _])
  (close! [_ _]
    (info "Closing client.")
    (closer)))

(defn create-client
  [deploy-cluster!]
  (Client. deploy-cluster! nil nil nil (atom nil) (atom false)))