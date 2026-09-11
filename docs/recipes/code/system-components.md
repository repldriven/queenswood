# System components

<!-- tessl-plugin: design -->

## Problem

You want to register a brick's components and group for use in
system configurations.

## Solution

We use `system/defcomponents` to register a brick's runtime
components — instances with start/stop lifecycle — under a
named group. A
[system configuration](system-configurations.md) references each
by its `<group>/<component>` keyword, and donut.system resolves
them at start time.

The call lives in a `system.clj` (single namespace) or a
`system/core.clj` (when there are multiple definition
namespaces). The brick's `interface.clj` bare-requires that
namespace, using the bracketed unaliased form, so the
multimethods are extended whenever the brick is loaded.

### Defining a component

A component is a map with a few standard keys:

```clojure
(def ^:private processor
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance (commands/->CashAccountProcessor config)))

   :system/config
   {:record-db    system/required-component
    :record-store system/required-component
    :schemas      system/required-component}

   :system/instance-schema some?})
```

- `:system/start` — fn returning the instance. Receives a map
  with `config` (resolved from YAML) and `instance` (any prior
  instance from a hot-reload). Pattern: `(or instance ...)` to
  preserve instances across reloads.
- `:system/stop` — optional, fn that closes/releases the
  instance.
- `:system/config` — map of expected config keys. Use
  `system/required-component` to mark slots that must come from
  YAML (or be injected by the caller).
- `:system/instance-schema` — predicate or Malli schema
  validating the started instance.

### Simple pattern: `system.clj`

When a brick has one cluster of component definitions, put them
all in `system.clj`. The `defcomponents` call at the bottom
groups them under a keyword group name.

Adapted from `components/cash-account/.../system.clj`:

```clojure
(ns com.repldriven.queenswood.cash-account.system
  (:require
    [com.repldriven.queenswood.cash-account.commands :as commands]
    [com.repldriven.queenswood.cash-account.events :as events]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start ...
   :system/config {...}
   :system/instance-schema some?})

(def ^:private event-processor
  {:system/start ...
   :system/config {...}
   :system/instance-schema some?})

(system/defcomponents :cash-account
                      {:processor processor
                       :event-processor event-processor})
```

The brick's `interface.clj` bare-requires the system namespace:

```clojure
(ns com.repldriven.queenswood.cash-account.interface
  (:require
    [com.repldriven.queenswood.cash-account.system]
    [com.repldriven.queenswood.cash-account.core :as core]))
```

### Structured pattern: `system/` folder

When a brick has two or more clusters of component definitions,
split them across files in a `system/` folder. The actual
definitions live in one or more files (typically
`components.clj`, `stores.clj`); a `system/core.clj` imports
them and makes a single `defcomponents` call aggregating
everything.

Adapted from `components/fdb/.../system/core.clj`:

```clojure
(ns com.repldriven.queenswood.fdb.system.core
  (:require
    [com.repldriven.queenswood.fdb.system.components :as components]
    [com.repldriven.mono.system.interface :as system]))

(system/defcomponents :fdb
                      {:cluster-file-path components/cluster-file-path
                       :db                components/db
                       :record-db         components/record-db
                       :store             components/store
                       :meta-store        components/meta-store
                       :keyspace-prefix   components/keyspace-prefix})
```

Layout:

```
components/<brick>/
  src/com/repldriven/queenswood/<brick>/
    system/
      core.clj         ; aggregates and calls defcomponents
      components.clj   ; the component definition maps
      ...              ; further files if needed
```

The brick's `interface.clj` bare-requires `system.core`:

```clojure
(ns com.repldriven.queenswood.<brick>.interface
  (:require
    [com.repldriven.queenswood.<brick>.system.core]
    [com.repldriven.queenswood.<brick>.core :as core]))
```

### Choosing between the patterns

- One cluster of definitions → `system.clj`.
- Two or more clusters → `system/` folder.

Don't promote a `system.clj` into a folder until you actually
have a second cluster.

### Naming shared resource components

Don't bake an environment name (`prod`, `dev`, `staging`,
`demo`) into a shared resource component or its config. Name
the component by the concern it represents:

- OK: `resources`, `infra-resources`.
- Not OK: `prod-resources`, `staging-resources`.

The same artefact gets deployed to multiple environments. An
env-in-the-name component reads as "this is only valid in
that environment", which encourages duplicate components when
a second environment shows up. Discriminate environments via
env vars and Helm values, not via component names. The
existing `*-test-resources` exception is fine — `test` here
is the runtime mode, not a deployment environment.

### Bundling system requires for tests

A base or project whose tests need many bricks' components
extended consolidates the bare requires into a single
`system.clj` in its test source tree. Test namespaces require
*that* file rather than listing every bare require themselves.

Example, from `bases/monolith`:

```
bases/monolith/
  test/com/repldriven/queenswood/monolith/
    system.clj            ; consolidates all bare requires
    payee_check_test.clj  ; (:require [...monolith.system])
```

The test `system.clj` namespace:

```clojure
(ns com.repldriven.queenswood.monolith.system
  (:require
    [com.repldriven.queenswood.cash-account.interface]
    [com.repldriven.queenswood.payment.interface]
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.testcontainers.interface]
    ;; ...
    ))
```

Test files require it as a single bare require, alongside any
aliased requires they need for actual use:

```clojure
(ns com.repldriven.queenswood.monolith.payee-check-test
  (:require
    [com.repldriven.queenswood.monolith.system]
    [com.repldriven.queenswood.cash-account.interface :as cash]
    [clojure.test :refer [deftest is testing]]))
```

A component is welcome to appear both bare (in the bundle) and
aliased (where the test calls it). The bare require extends the
system multimethods; the aliased require lets the test call
functions on it.

## Rules

**MUST:**

- System component definitions are registered through
  `defcomponents` from `system.clj` (single namespace) or a
  `system/` folder with `system/core.clj` aggregating (multiple
  namespaces).
- Bare requires use the bracketed unaliased form
  (`[com.repldriven.queenswood.x.system]`).
- A brick's `interface.clj` bare-requires the system namespace
  so multimethods are extended on load.
- Tests in a base or project consolidate system-component bare
  requires into a single `test/.../system.clj` namespace; test
  files require that namespace rather than listing the bricks
  individually.

**MUST NOT:**

- Call `defcomponents` directly from `interface.clj` (use
  `system.clj` or `system/core.clj`).
- Use the unbracketed bare-require form (deprecated convention).
- Bake an environment name (`prod`, `dev`, `staging`) into a
  shared resource component or config. Name by concern
  (e.g. `resources`).

**SHOULD:**

- Use the simple `system.clj` pattern when a brick has one
  defcomponents namespace; switch to a `system/` folder when
  there are two or more.

## Discussion

The two-pattern split exists because most bricks have one tight
cluster of definitions (simple), but some have several. The
`fdb` brick has clusters, stores, and keyspaces; forcing all
that into one file gets unwieldy. Forcing a folder when there's
one entry is overkill.

Bracketed unaliased requires (`[com.repldriven.queenswood.x.system]`)
are the current convention. An earlier version used the
unbracketed form (`com.repldriven.queenswood.x.system`) as a visual
marker for "this is a bare require"; we walked that back —
brackets-without-alias are clearer, survive automated namespace
sorters, and don't make the require list look ragged.

The test `system.clj` consolidation cuts duplication. When a
dozen test files all need the same bricks extended with system
multimethods, listing the same bare requires in each is
busywork; bundling them into one namespace and requiring that
turns it into a single line per test. A component may also be
aliased in the test for actual use — the two requires don't
conflict, they serve different purposes.

## References

- [ADR-0007 — System-as-data](../../adr/0007-system-as-data.md)
- [components.md](components.md)
- [bases.md](bases.md)
- [donut.system](https://github.com/donut-party/system)
