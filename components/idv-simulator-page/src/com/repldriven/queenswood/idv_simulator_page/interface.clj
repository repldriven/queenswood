(ns com.repldriven.queenswood.idv-simulator-page.interface
  "The hosted page an identity-provider simulator's hand-off points the
  person at, playing the provider's flow: the person's details, their
  photo document, a selfie and their address, one step at a time, with
  *Leave* on every step. Submitting posts what the person entered to the
  simulator's decision route, and returns the person to the tenant's
  return URL; `decision` turns it into an outcome by the sandbox values
  the page lists. A test posts an `outcome` to the same route instead.
  The page finds the decision route from its own path, so it works
  behind a prefix the simulator does not know about. A sandbox playing a
  person through the page appends `simulate`, an outcome, and the
  document's `givenNames`, `familyName` and `dateOfBirth` to the hand-off
  URL: the page fills each step with the values producing that outcome,
  submits, and stays where it is, taking no input from the viewer while
  it plays. Adding `pace`, in milliseconds, has it
  do so at a speed a viewer can follow."
  (:require
    [com.repldriven.queenswood.idv-simulator-page.core :as core]
    [com.repldriven.queenswood.idv-simulator-page.decision :as decision]))

(def ^{:doc "Every outcome a run can be settled with."} outcomes
  decision/outcomes)

(def
  ^{:doc
    "The decision route's body: an `outcome`, as a test posts,
  or what a person entered on the page, as the page posts."}
  Submission
  decision/Submission)

(defn decision
  "`{:outcome :document}` for a decision route's body: its `outcome`
  where it names one, else the one its sandbox values produce, and
  `document`, the `givenNames`, `familyName` and `dateOfBirth` it says
  the document carries.

  Args:
  - submission: a `Submission`."
  [submission]
  (decision/decision submission))

(defn form
  "The page's HTML for one run.

  Args:
  - page-path: a regular expression matching the end of the page's own
    path, which removed leaves the prefix the simulator is served at.
  - decision-path: the run's decision route, below that prefix.
  - return-url: where the person goes once they submit, or nil."
  [page-path decision-path return-url]
  (core/form page-path decision-path return-url))

(defn message
  "A page saying `text` and nothing else, for a run the person cannot
  act on."
  [text]
  (core/message text))

(defn response
  "The Ring response serving `html` with `status`."
  [status html]
  (core/response status html))
