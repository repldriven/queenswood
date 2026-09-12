(ns com.repldriven.queenswood.test-startup.interface
  "Checks that a service project starts as far as its system definitions
  without starting anything: the entry base the project is named after
  loads on the classpath, the production `application.yml` parses under
  the default profile, and every component-kind it names is registered.
  Pulled into a project's `:test` alias like the other test-only bricks,
  so `poly test brick:test-startup :all` runs the check once per
  service project, on that project's own classpath."
  (:require
    [com.repldriven.queenswood.test-startup.core :as core]))

(defn project
  "The name of the service project whose production `application.yml`
  is on the classpath, or nil on a classpath carrying none, such as the
  development project's."
  []
  (core/project))

(defn check
  "`{:namespace :registered}` when `project`'s entry base loads, its
  production config parses and every component-kind is registered;
  otherwise an anomaly naming what failed."
  [project]
  (core/check project))
