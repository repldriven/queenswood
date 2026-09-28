(ns com.repldriven.queenswood.payment.domain.fixtures)

(defn side
  "Returns the leg on the requested side, or nil."
  [tx leg-side]
  (some (fn [leg] (when (= leg-side (:side leg)) leg)) (:legs tx)))

(defn balanced?
  "Σ debit == Σ credit over a transaction's postings."
  [tx]
  (let [legs (:legs tx)
        total (fn [s]
                (reduce + 0 (map :amount (filter #(= s (:side %)) legs))))]
    (= (total :leg-side-debit) (total :leg-side-credit))))

(defn allow-all
  "Minimal policy fixture that allow-lists every payment action and
  permits a high daily count, so the leg-shape assertions don't get
  short-circuited by a capability denial or a limit breach."
  []
  [{:enabled true
    :capabilities
    [{:effect :effect-allow
      :kind {:internal-payment
             {:action :internal-payment-action-submit}}}
     {:effect :effect-allow
      :kind {:inbound-payment
             {:action :inbound-payment-action-receive}}}
     {:effect :effect-allow
      :kind {:outbound-payment
             {:action :outbound-payment-action-send}}}]
    :limits
    [{:kind {:internal-payment {}}
      :bound {:kind {:max {:aggregate {:kind {:count {:value 1000000
                                                      :window
                                                      :time-window-daily}}}}}}}
     {:kind {:inbound-payment {}}
      :bound {:kind {:max {:aggregate {:kind {:count {:value 1000000
                                                      :window
                                                      :time-window-daily}}}}}}}
     {:kind {:outbound-payment {}}
      :bound {:kind {:max {:aggregate
                           {:kind {:count {:value 1000000
                                           :window :time-window-daily}}}}}}}]}])

(defn empty-aggregates
  "Aggregates fixture where today's count and value are both zero for
  the given payment kind — the shape `core/submit-*` builds before the
  domain checks. Combined with `allow-all`, leg-shape tests stay clear
  of any limit boundary."
  [kind]
  {kind {#{:bank-id :business-day} 0
         #{:bank-id :business-day :amount} 0}})

(defn account
  "Minimal cash-account fixture — just the fields the domain guards
  read. Opened unless a status is given."
  ([account-id currency]
   (account account-id currency :cash-account-status-opened))
  ([account-id currency account-status]
   {:account-id account-id
    :currency currency
    :account-status account-status}))
