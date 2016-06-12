(ns dat.reactor.dispatcher
  #?(:cljs (:require-macros [cljs.core.async.macros :as async-macros :refer [go go-loop]]))
  (:require #?@(:clj [[clojure.core.async :as async :refer [go go-loop]]]
                :cljs [[cljs.core.async :as async]])
            [com.stuartsierra.component :as component]
            [dat.spec.protocols :as protocols]))


;; # The Dispatcher

;; This should probably be it's own project as well
;; The Dispatcher.
;; This is the muscle of the bunch.
;; He'll knock ya lights out and leave ya swimmin with the fishes.
;; So don't mess around with him, ya hear?

;; The dispatcher is what gives us control over how messages are processed.
;; For most applications, a pretty straightforward mechanism should work.
;; Events should get handled in the order they are fired in.
;; Simple.

;; But suppose you wanted more control over the situation?
;; Perhaps you want for error and/or warning messages to be handled with higher priority than other queues of
;; messages?
;; Perhaps you are sampling from an input device, and don't want for it's signal to swamp out other important
;; functionality?
;; Perhaps you want to be able to plug in your own crazy rube goldberg core.async pipeline for some malicious
;; scheme against humanity.
;; Perhaps you're mouthing these words as you read this...

;; Dispatchers are based on protocols, so as long as whatever you specify for a dispatcher satisties these
;; protocols (you should look at the DatProtocol github repo...), and you don't code it like a moron, it
;; should work fine.

;; However, we provide for you a few prepackaged dispatchers which should work for the 99%.


(defrecord StrictlyOrderedDispatcher [dispatch-chan]
  ;; Should make pluggable config options for the chan creation
  component/Lifecycle
  (start [component]
    (let [component (assoc component
                           :dispatch-chan (or dispatch-chan (async/chan 100)))])
    component)
  (stop [component]
    component)
  protocols/PDispatcher
  (dispatch! [component message level]
    (go (async/>! dispatch-chan)))
  ;; Here, the message chan is just the dispatch chan...
  (dispatcher-event-chan [component]
    ;; Don't know if this will compile properly
    dispatch-chan))

(defn new-strictly-ordered-dispatcher
  "Creates a new strictly ordered dispatcher. This is fine for most cases, and ensures that all messages are
  handled in the order they were dispatched (or should...). Can specify `{:dispatch-chan your-chan}` to customize
  the flow."
  ([options]
   (map->StrictlyOrderedDispatcher options))
  ([]
   (new-strictly-ordered-dispatcher {})))


;; Any messages with level :error will get processed by the reactor before _any_ other dispatched messages.
(defrecord ErrorPriorityDispatcher [default-chan error-chan event-chan]
  ;; Should make pluggable config options for the chan creation
  component/Lifecycle
  (start [component]
    (let [component (assoc component
                           :default-chan (or default-chan (async/chan 100))
                           :error-chan (or error-chan (async/chan 100))
                           ;; This should have no buffer so that it doesn't grab a dispatch message and put it in
                           ;; the buffer just before an error message comes in
                           :event-chan (async/chan))])
    (go-loop []
      ;; Hmm... this actually doesn't have the semantics we want, since 1 default chan message could get
      ;; through before the message chan is ready to take yet
      (let [[event chan] (async/alts! [error-chan default-chan])]
        (async/>! event-chan event)))
    component)
  (stop [component]
    component)
  protocols/PDispatcher
  (dispatch! [component message level]
    (go
      (async/>! 
        (if (= level :error) error-chan default-chan)
        message)))
  ;; Here, the message chan is just the dispatch chan...
  (dispatcher-event-chan [component]
    ;; Don't know if this will compile properly TODO
    event-chan))

(defn new-error-priority-dispatcher
  "Creates a new ErroPriorityDispatcher. Can customize :default-chan and :error-chan through options.
  Idea is that Reactor will get error messages before other queued messages, but the mechanism is a little flawed.
  Currently, it's still possible for a non error message to get through before the reactor is ready to consume from the event-chan.
  May need to adjust the protocols."
  ([options]
   (map->ErrorPriorityDispatcher options))
  ([]
   (new-error-priority-dispatcher {})))


;; A signal sampler might have functions that let you plug in your own signal channels that you mix/merge/alt
;; whatever, to gate vs critical system control messages.


;; ## Public API around dispatchers

(defn dispatch!
  "Dispatches message on the dispatcher component at the given level (:default if unspecified)."
  ([dispatcher message level]
   (protocols/dispatch! dispatcher message level))
  ([dispatcher message]
   (protocols/dispatch! dispatcher message :default)))

(defn dispatch-error!
  [datview message]
  (dispatch! datview message :error))


(defn event-chan [dispatcher]
  "Get the message channel for a dispatcher"
  (protocols/dispatcher-event-chan dispatcher))



