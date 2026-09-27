(ns com.repldriven.queenswood.modulr-adapter.interface
  "Inbound half of the Modulr payment adapter: the Reitit handler serving
  Modulr's signed PAYIN, PAYOUT and PAYMENTCOMPLIANCESTATUS
  notifications, and the outbound Confirmation of Payee route the
  payee-check processor calls. Composed into a service by an aggregator
  base, which injects `app` into the adapter server's handler slot;
  requiring this namespace also registers the adapter's own system
  component-kinds — the command processor that turns scheme and account
  commands into intents, and the registrar that subscribes the adapter
  to Modulr's notifications."
  (:require
    [com.repldriven.queenswood.modulr-adapter.system]

    [com.repldriven.queenswood.modulr-adapter.api :as api]))

(defn app
  "Ring handler for the Modulr adapter's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
