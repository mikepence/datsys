(ns catalysis.app
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [slingshot.slingshot :as slingshot :refer [throw+ try+]]
            [catalysis.datomic :as datomic]
            [catalysis.ws :as ws]
            [taoensso.sente :as sente]
            [datsync.server.core :as datsync]
            [datomic.api :as d]))


;; # Application

;; This namespace contains the core of the server's message handling logic.
;; Any custom message handlers on the server you want to hook up should go here


;; ## First we set up our event handler multimethod function, and a wrapper for it

(defmulti event-msg-handler
  ; Dispatch on event-id
  (fn [app {:as event-msg :keys [id]}] id))

;; Wrap with middleware instead
;(defn event-msg-handler* [app {:as event-msg :keys [id ?data event]}]
  ;(try+
    ;(event-msg-handler event-msg app)
    ;(catch Object e
      ;(log/error "failed to run message handler!")
      ;(.printStackTrace e)
      ;(throw+))))


;; ## Transaction report handler

;; TODO XXX write me to remove any :db/fn values
(declare filter-tx-deltas)
(def filter-tx-deltas identity)

(defn handle-transaction-report!
  [ws-connection tx-deltas]
  ;; This handler is where you would eventually set up subscriptions
  (try
    (let [tx-deltas (filter-tx-deltas tx-deltas)]
      (ws/broadcast! ws-connection [:datsync/tx-data tx-deltas]))
    (catch Exception e
      (log/error "Failed to send transaction report to clients!")
      (.printStackTrace e))))


;; ## The actual app component, which starts sente's chsk router and hooks up the msg handler

(defrecord App [config datomic ws-connection sente-stop-fn]
  component/Lifecycle
  (start [component]
    (log/info "Starting websocket router and transaction listener")
    (let [sente-stop-fn (sente/start-chsk-router!
                          (:ch-recv ws-connection)
                          ;(fn [event] (log/info "Just got event:" (with-out-str (clojure.pprint/pprint)))) 
                          ;; There sould be a way of specifying app-wide middleware here
                          (partial event-msg-handler component))]
      ;; Start our transaction listener
      (datsync/start-transaction-listener! (:tx-report-queue datomic) (partial handle-transaction-report! ws-connection))
      (assoc component :sente-stop-fn sente-stop-fn)))
  (stop [component]
    (log/debug "Stopping websocket router")
    (sente-stop-fn)
    component))

(defn new-app []
  (map->App {}))


;; ## Event handlers

;; don't really need this... should delete
(defmethod event-msg-handler :chsk/ws-ping
  [_ _]
  (log/info "Ping"))

;; Setting up our two main datsync hooks

;; General purpose transaction handler
(defmethod event-msg-handler :datsync.client/tx
  [{:as app :keys [datomic]} {:as event-msg :keys [id ?data]}]
  (let [tx-report @(datsync/transact-from-client! (:conn datomic) ?data)]
    (println "Do something with:" tx-report)))

;; We handle the bootstrap message by simply sending back the bootstrap data
(defmethod event-msg-handler :datsync.client/bootstrap
  ;; What is send-fn here? Does that wrap the uid for us? (0.o)
  [{:as app :keys [datomic ws-connection]} {:as event-msg :keys [id uid send-fn]}]
  (log/info "Sending bootstrap message")
  (ws/send! ws-connection uid [:datsync.client/bootstrap (datomic/bootstrap (d/db (:conn datomic)))]))

;; Fallback handler; should send message saying I don't know what you mean
(defmethod event-msg-handler :default ; Fallback
  [app {:as event-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (log/warn "Unhandled event:" id))


;; ## Debugging


;; You can leave, commented out, some code which grabs the active system and runs some quick checks for the
;; scope in which you
(comment
  (require 'user)
  (let [ws-connection (:ws-connection user/system)]
    (send-tx!))) 
  


