(ns com.repldriven.queenswood.zyphe-adapter.interface
  "Inbound half of the Zyphe adapter: the Reitit handler serving the
  signed session webhooks Zyphe delivers for each verification run.
  Composed into a service by an aggregator base, which injects `app`
  into the adapter server's handler slot; requiring this namespace also
  registers the adapter's own system component-kinds."
  (:require
    [com.repldriven.queenswood.zyphe-adapter.system]

    [com.repldriven.queenswood.zyphe-adapter.api :as api]))

(defn app
  "Ring handler for the Zyphe adapter's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
