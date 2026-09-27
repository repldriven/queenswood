(ns com.repldriven.queenswood.modulr-simulator.interface
  "Modulr stand-in: the Reitit handler serving the Modulr API as the
  payment adapter calls it — accounts, payments and transfers, the
  sandbox credit, name checks and notification subscriptions — checking
  every call's signature, holding a balance for each account it opens,
  and delivering the signed PAYIN, PAYOUT and PAYMENTCOMPLIANCESTATUS
  notifications a real Modulr would. Serves the control routes every
  payment simulator shares. Composed into a service by an aggregator
  base, which injects `app` into the simulator server's handler slot;
  requiring this namespace also registers the simulator's own system
  component-kinds."
  (:require
    [com.repldriven.queenswood.modulr-simulator.system]

    [com.repldriven.queenswood.modulr-simulator.api :as api]))

(defn app
  "Ring handler for the Modulr simulator's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
