(ns igoki.comms
  (:require
    [cljs.core.async :as async :refer (<! >! put! chan)]
    [taoensso.encore :as encore]
    [taoensso.sente :as sente :refer (cb-success?)]
    [re-frame.core :as rf]))

(defn ->output! [fmt & args]
  (let [output-el (.getElementById js/document "output")
        msg (apply encore/format fmt args)]
    (println msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same path as before
                                  {:type :auto ; e/o #{:auto :ajax :ws}
                                   })]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )

(defn send
  ([eventtype message]
   (chsk-send! [eventtype message]))
  ([eventtype message timeout success-fn & [fail-fn]]
    (chsk-send!
      [eventtype message] timeout
      (fn [reply]
        (if (sente/cb-success? reply)
          (success-fn reply)
          (if fail-fn (fail-fn)))))))

(defmulti -event-msg-handler
          "Multimethod to handle Sente `event-msg`s"
          :id ; Dispatch on event-id
          )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [id ?data]}]
  (->output! "Dispatching: " id)
  (rf/dispatch [id ?data]))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (->output! "Channel socket successfully established!")
    (->output! "Channel socket state change: %s" ?data)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data id]}]
  (->output! "Message received: " id)
  (-event-msg-handler (assoc ev-msg :id (first ?data) :?data (second ?data))))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (rf/dispatch [:camera/list])
    (->output! "Handshake: %s" ?data)))

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
            ch-chsk event-msg-handler)))

