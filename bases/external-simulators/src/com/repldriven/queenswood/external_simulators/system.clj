(ns com.repldriven.queenswood.external-simulators.system
  "Bare-require bundle for the external simulators service — the
  simulator that stands in for each payment and IDV vendor and for
  Companies House, and the payment scheme they share — every brick whose
  component-kinds its application.yml instantiates. Each composed base is
  reached through its `interface.clj`, which registers that base's own
  component-kinds on load. Loaded by main.clj before `system/start`;
  nothing else lives here. The service's composition is the project's
  application.yml (ADR-0036)."
  (:require
    [com.repldriven.queenswood.clearbank-simulator.interface]
    [com.repldriven.queenswood.clearbank-webhook.interface]
    [com.repldriven.queenswood.form3-simulator.interface]
    [com.repldriven.queenswood.form3-webhook.interface]
    [com.repldriven.queenswood.modulr-simulator.interface]
    [com.repldriven.queenswood.modulr-webhook.interface]
    [com.repldriven.queenswood.onfido-simulator.interface]
    [com.repldriven.queenswood.scheme-simulator.interface]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface]
    [com.repldriven.queenswood.zyphe-simulator.interface]

    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.telemetry.interface]))
