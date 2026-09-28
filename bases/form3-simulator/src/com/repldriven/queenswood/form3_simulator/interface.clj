(ns com.repldriven.queenswood.form3-simulator.interface
  "Form3 stand-in: the Reitit handler serving the Form3 API as the
  payment adapter calls it — account registrations, payments and their
  submissions, inbound admissions and the tasks the bank completes,
  returns both ways, name checks and notification subscriptions —
  checking every call's HTTP signature and delivering the notifications
  a real Form3 would. Serves the control routes every payment simulator
  shares. Composed into a service by an aggregator base, which injects
  `app` into the simulator server's handler slot; requiring this
  namespace also registers the simulator's own system component-kinds."
  (:require
    [com.repldriven.queenswood.form3-simulator.system]

    [com.repldriven.queenswood.form3-simulator.api :as api]))

(defn app
  "Ring handler for the Form3 simulator's HTTP surface.

  Args:
  - ctx: the started system's interceptor context, supplied by the
    server component the handler is injected into."
  [ctx]
  (api/app ctx))
