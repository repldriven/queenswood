(ns com.repldriven.queenswood.onfido-simulator.interface
  "Onfido stand-in: the Reitit handler serving the applicants, workflow
  runs, checks, reports and webhooks the adapter calls, the hosted page
  a run's link opens, and a decision route settling a run as a person
  would, which delivers the signed `workflow_run.completed` a real
  Onfido would.
  Composed into a service by an aggregator base, which injects `app`
  into the simulator server's handler slot; requiring this namespace
  also registers the simulator's own system component-kinds."
  (:require
    [com.repldriven.queenswood.onfido-simulator.system]

    [com.repldriven.queenswood.onfido-simulator.api :as api]))

(defn app
  "Ring handler for the Onfido simulator's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
