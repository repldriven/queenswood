(ns com.repldriven.queenswood.idv-simulator-page.core
  (:require
    [clojure.string :as str]))

(def ^:private outcome-groups
  "Each group's heading and its outcomes, as value, title and hint."
  [["Passes"
    [["match" "Everything checks out"
      "My own document, and every check passes"]]]
   ["Document"
    [["other-document" "Someone else's document"
      "The name or date of birth will not match"]
     ["document-review" "Needs a reviewer"
      "The document goes to manual review"]
     ["document-failed" "Forged document"
      "The document fails its authenticity checks"]]]
   ["Checks"
    [["liveness-failed" "Liveness fails"
      "The selfie does not match the document"]
     ["address-failed" "Address refused"
      "The proof of address is rejected"]]]
   ["Screening"
    [["sanctions-hit" "Sanctions hit" "Screening finds a sanctions match"]
     ["sanctions-possible-match" "Possible sanctions match"
      "Screening flags a possible match for review"]
     ["pep" "Politically exposed" "Screening finds a PEP"]]]
   ["Leaving"
    [["walk-away" "Walk away" "Abandon the check part way through"]]]])

(def ^:private css
  "The page's styles. On a laptop the page sits in a phone-sized frame,
  the size a tenant's app shows itself at, so a hand-off from one does
  not jump to the full window."
  (str/join
   "\n"
   [":root{--bg:#f4f5f7;--card:#fff;--ink:#1d2330;--muted:#5d6675;"
    "--line:#dfe3ea;--accent:#0f766e;--accent-ink:#fff;"
    "--accent-soft:#e6f3f1;--danger:#b42318;"
    "color-scheme:light dark}"
    "@media (prefers-color-scheme:dark){:root{--bg:#12151b;--card:#1b2029;"
    "--ink:#e8ebf0;--muted:#9aa3b2;--line:#2d3440;--accent:#2dd4bf;"
    "--accent-ink:#06201d;--accent-soft:#16312e;--danger:#f97066}}"
    "*{box-sizing:border-box}"
    "body{margin:0;background:var(--bg);color:var(--ink);"
    "font:15px/1.5 ui-sans-serif,system-ui,-apple-system,'Segoe UI',"
    "Roboto,sans-serif;-webkit-font-smoothing:antialiased}"
    ".bar{display:flex;align-items:center;gap:.6rem;max-width:36rem;"
    "margin:0 auto;padding:1.25rem 1rem .5rem}"
    ".mark{width:28px;height:28px;border-radius:8px;background:var(--accent);"
    "color:var(--accent-ink);display:grid;place-items:center}"
    ".brand{font-weight:600;letter-spacing:-.01em}"
    ".badge{margin-left:auto;font-size:.75rem;font-weight:600;"
    "color:var(--muted);border:1px solid var(--line);border-radius:999px;"
    "padding:.15rem .6rem}"
    ".card{max-width:36rem;margin:.5rem auto 2rem;background:var(--card);"
    "border:1px solid var(--line);border-radius:16px;padding:1.5rem;"
    "box-shadow:0 1px 2px rgba(16,24,40,.04),0 8px 24px rgba(16,24,40,.06)}"
    "@media (max-width:40rem){.card{margin:.5rem 16px 2rem;padding:1.25rem}}"
    "h1{font-size:1.4rem;line-height:1.25;letter-spacing:-.02em;"
    "margin:0 0 .25rem}"
    ".lede{color:var(--muted);margin:0 0 1.5rem}"
    "h2{font-size:.8rem;text-transform:uppercase;letter-spacing:.06em;"
    "color:var(--muted);margin:1.5rem 0 .6rem}"
    ".fields{display:grid;grid-template-columns:1fr 1fr;gap:.75rem}"
    ".fields .wide{grid-column:1/-1}"
    "@media (max-width:30rem){.fields{grid-template-columns:1fr}}"
    ".field span{display:block;font-size:.85rem;font-weight:500;"
    "margin-bottom:.3rem}"
    ".field input{width:100%;font:inherit;color:inherit;"
    "background:var(--card);border:1px solid var(--line);border-radius:10px;"
    "padding:.6rem .75rem}"
    ".field input:focus{outline:2px solid var(--accent);outline-offset:1px;"
    "border-color:transparent}"
    "fieldset{border:0;margin:0;padding:0}"
    ".group{font-size:.75rem;font-weight:600;color:var(--muted);"
    "margin:.9rem 0 .4rem}"
    ".tiles{display:grid;gap:.5rem}"
    ".tile{display:flex;gap:.7rem;align-items:flex-start;cursor:pointer;"
    "border:1px solid var(--line);border-radius:12px;padding:.7rem .85rem;"
    "transition:border-color .12s,background .12s}"
    ".tile:hover{border-color:var(--muted)}"
    ".tile:has(input:checked){border-color:var(--accent);"
    "background:var(--accent-soft)}"
    ".tile input{accent-color:var(--accent);margin:.2rem 0 0}"
    ".tile b{display:block;font-weight:600}"
    ".tile small{color:var(--muted);font-size:.85rem}"
    ".actions{position:sticky;bottom:0;margin:1.5rem -1.5rem -1.5rem;"
    "padding:1rem 1.5rem 1.5rem;background:var(--card);"
    "border-top:1px solid var(--line);border-radius:0 0 16px 16px}"
    "@media (max-width:40rem){.actions{margin:1.25rem -1.25rem -1.25rem;"
    "padding:1rem 1.25rem 1.25rem}}"
    "button{width:100%;font:inherit;font-weight:600;"
    "color:var(--accent-ink);background:var(--accent);border:0;"
    "border-radius:10px;padding:.8rem 1rem;cursor:pointer}"
    "button:disabled{opacity:.6;cursor:progress}"
    ".error{color:var(--danger);font-size:.9rem;margin:.75rem 0 0}"
    ".note{color:var(--muted);font-size:.8rem;text-align:center;"
    "max-width:36rem;margin:0 auto 2rem;padding:0 1rem}"
    ".done{text-align:center;padding:1rem 0}"
    ".done .mark{width:48px;height:48px;border-radius:50%;"
    "margin:0 auto 1rem}"
    "@media (min-width:600px) and (min-height:720px){"
    "body{background:#e9ebf2;min-height:100vh;display:grid;"
    "place-items:center}"
    ".device{width:402px;height:874px;max-height:calc(100vh - 48px);"
    "overflow-y:auto;border-radius:48px;background:var(--bg);"
    "padding-top:36px;box-shadow:0 40px 80px rgba(0,0,0,.18),"
    "0 0 0 1px rgba(0,0,0,.12)}"
    ".device .card{margin:.5rem 16px 1.5rem;padding:1.25rem}"
    ".device .actions{margin:1.25rem -1.25rem -1.25rem;"
    "padding:1rem 1.25rem 30px}"
    ".device .fields{grid-template-columns:1fr}}"]))

(def ^:private shield
  (str "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" "
       "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" "
       "stroke-linejoin=\"round\" aria-hidden=\"true\">"
       "<path d=\"M12 3l7 3v6c0 4.5-3 7.7-7 9-4-1.3-7-4.5-7-9V6z\"/>"
       "<path d=\"M9 12l2 2 4-4\"/></svg>"))

(def ^:private tick
  (str "<svg width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" "
       "stroke=\"currentColor\" stroke-width=\"2.6\" stroke-linecap=\"round\" "
       "stroke-linejoin=\"round\" aria-hidden=\"true\">"
       "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/></svg>"))

(defn- escape
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- document
  [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>"
       (escape title)
       "</title><style>"
       css
       "</style></head><body><div class=\"device\">"
       "<header class=\"bar\"><span class=\"mark\">"
       shield
       "</span><span class=\"brand\">Identity check</span>"
       "<span class=\"badge\">Simulated</span></header>"
       "<main class=\"card\" id=\"card\">"
       body
       "</main><p class=\"note\">This page stands in for an identity "
       "provider. Nothing entered here leaves this environment.</p>"
       "</div></body></html>"))

(defn- js-string
  [s]
  (str/replace (pr-str (str s)) "<" "\\u003c"))

(defn message
  [text]
  (document "Identity verification"
            (str "<div class=\"done\"><h1>" (escape text) "</h1></div>")))

(defn- field
  [label name type class]
  (str "<label class=\"field"
       (when class (str " " class))
       "\"><span>"
       label
       "</span><input type=\""
       type
       "\" name=\""
       name
       "\" required></label>"))

(defn- tile
  [[value title hint] checked?]
  (str "<label class=\"tile\"><input type=\"radio\" name=\"outcome\" value=\""
       value
       "\""
       (when checked? " checked")
       "><span><b>"
       (escape title)
       "</b><small>"
       (escape hint)
       "</small></span></label>"))

(defn- tiles
  []
  (str/join
   (map-indexed (fn [i [heading outcomes]]
                  (str "<div class=\"group\">"
                       (escape heading)
                       "</div><div class=\"tiles\">"
                       (str/join (map-indexed (fn [j o]
                                                (tile o
                                                      (and (zero? i)
                                                           (zero? j))))
                                              outcomes))
                       "</div>"))
                outcome-groups)))

(def ^:private script
  (str
   "const q=new URLSearchParams(location.search);"
   "const f=document.getElementById('verify');"
   "const btn=f.querySelector('button');"
   "const err=document.getElementById('error');"
   "const leaving=back&&!q.get('simulate');"
   "const done=()=>{document.getElementById('card').innerHTML="
   "'<div class=\"done\"><span class=\"mark\">'+tick+"
   "'</span><h1>You are done</h1><p class=\"lede\">'+"
   "(leaving?'Taking you back\\u2026':'You can close this page.')+"
   "'</p></div>'};"
   "f.addEventListener('submit',async e=>{"
   "e.preventDefault();err.textContent='';"
   "btn.disabled=true;btn.textContent='Checking\\u2026';"
   "const base=location.pathname.replace(new RegExp(page),'');"
   "try{const r=await fetch(base+decision,{method:'POST',"
   "headers:{'Content-Type':'application/json'},"
   "body:JSON.stringify(Object.fromEntries(new FormData(f)))});"
   "if(!r.ok)throw new Error(r.status);"
   "done();if(leaving)location.href=back}"
   "catch(x){err.textContent='Something went wrong. Please try again.';"
   "btn.disabled=false;btn.textContent='Continue'}});"
   "const wait=ms=>new Promise(r=>setTimeout(r,ms));"
   "const reveal=el=>{const d=document.querySelector('.device');"
   "const box=d&&getComputedStyle(d).overflowY==='auto'?d:null;"
   "const r=el.getBoundingClientRect();"
   "const by=r.top-(box?box.getBoundingClientRect().top+box.clientHeight/2"
   ":innerHeight/2);" "(box||window).scrollBy({top:by,behavior:'smooth'})};"
   "if(q.get('simulate')){(async()=>{"
   "const pace=Math.min(Number(q.get('pace'))||0,5000);"
   "await wait(pace);"
   "for(const k of ['givenNames','familyName','dateOfBirth']){"
   "const v=q.get(k)||'';const el=f.elements[k];"
   "if(pace&&el.type!=='date'){for(const c of v){el.value+=c;await wait(60)}}"
   "else{el.value=v}" "if(pace)await wait(pace/3)}"
   "for(const o of f.elements.outcome){o.checked=o.value===q.get('simulate');"
   "if(o.checked&&pace)reveal(o.closest('.tile'))}"
   "await wait(pace*1.5);" "f.requestSubmit()})()}"))

(defn form
  [page-path decision-path return-url]
  (document
   "Identity verification"
   (str
    "<h1>Verify your identity</h1>"
    "<p class=\"lede\">Tell us what your photo document says, then "
    "choose how the check goes.</p>"
    "<form id=\"verify\">"
    "<h2>Your document</h2><div class=\"fields\">"
    (field "Given names" "givenNames" "text" nil)
    (field "Family name" "familyName" "text" nil)
    (field "Date of birth" "dateOfBirth" "date" "wide")
    "</div><h2>What happens</h2><fieldset>"
    "<legend hidden>What happens</legend>"
    (tiles)
    "</fieldset><div class=\"actions\">"
    "<button type=\"submit\">Continue</button>"
    "<p class=\"error\" id=\"error\" role=\"alert\"></p></div></form>"
    "<script>const page="
    (js-string page-path)
    ";const decision="
    (js-string decision-path)
    ";const back="
    (js-string return-url)
    ";const tick="
    (js-string tick)
    ";"
    script
    "</script>")))

(defn response
  [status html]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body html})
