(ns com.repldriven.queenswood.form3-adapter.interface
  "Inbound half of the Form3 payment adapter: the Reitit handler serving
  Form3's notifications — each read back from Form3 before anything is
  recorded, an inbound's admission task answered from the platform's
  `admit-inbound-payment` — and the outbound Confirmation of Payee route
  the payee-check processor calls. Composed into a service by an
  aggregator base, which injects `app` into the adapter server's handler
  slot; requiring this namespace also registers the adapter's own system
  component-kinds — the command processor that turns scheme and account
  commands into intents, and the registrar that subscribes the adapter
  to Form3's notifications."
  (:require
    [com.repldriven.queenswood.form3-adapter.system]

    [com.repldriven.queenswood.form3-adapter.api :as api]))

(defn app
  "Ring handler for the Form3 adapter's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
