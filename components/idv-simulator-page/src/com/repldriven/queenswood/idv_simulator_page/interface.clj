(ns com.repldriven.queenswood.idv-simulator-page.interface
  "The hosted page an identity-provider simulator's hand-off points the
  person at. It asks what their document says and offers the outcomes a
  person or a reviewer produces: `match`, `other-document`,
  `document-review`, `document-failed`, `liveness-failed`,
  `address-failed`, `sanctions-hit`, `sanctions-possible-match`, `pep`
  and `walk-away`. Submitting posts `{outcome givenNames familyName
  dateOfBirth}` to the simulator's decision route, the same body a test
  sends, and returns the person to the tenant's return URL. The page
  finds the decision route from its own path, so it works behind a
  prefix the simulator does not know about. A sandbox playing a person
  through the page appends `simulate`, an outcome, and the document's
  `givenNames`, `familyName` and `dateOfBirth` to the hand-off URL: the
  page fills itself in, submits, and stays where it is. Adding `pace`,
  in milliseconds, has it do so at a speed a viewer can follow."
  (:require
    [com.repldriven.queenswood.idv-simulator-page.core :as core]))

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
