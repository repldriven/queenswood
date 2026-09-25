(ns com.repldriven.queenswood.zyphe-simulator.interface
  "Zyphe stand-in: the Reitit handler that creates and resumes
  verification requests and, standing in for the person who would
  complete the hosted flow, settles each run and delivers the signed
  session-webhook events a real Zyphe would. Composed into a service by
  an aggregator base, which injects `app` into the simulator server's
  handler slot; requiring this namespace also registers the simulator's
  own system component-kinds."
  (:require
    [com.repldriven.queenswood.zyphe-simulator.system]

    [com.repldriven.queenswood.zyphe-simulator.api :as api]))

(defn app
  "Ring handler for the Zyphe simulator's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
