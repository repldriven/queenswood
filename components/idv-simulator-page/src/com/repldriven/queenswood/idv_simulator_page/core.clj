(ns com.repldriven.queenswood.idv-simulator-page.core
  (:require
    [com.repldriven.queenswood.idv-simulator-page.decision :as decision]

    [clojure.string :as str]))

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
    ".field select{width:100%;font:inherit;color:inherit;"
    "background:var(--card);border:1px solid var(--line);border-radius:10px;"
    "padding:.6rem .75rem}"
    ".progress{display:flex;gap:.4rem;list-style:none;padding:0;"
    "margin:0 0 1.25rem}"
    ".progress li{flex:1;font-size:.72rem;font-weight:600;color:var(--muted);"
    "border-top:3px solid var(--line);padding-top:.35rem}"
    ".progress li.on{color:var(--ink);border-color:var(--accent)}"
    ".capture{display:grid;place-items:center;gap:.5rem;text-align:center;"
    "border:1px dashed var(--line);border-radius:12px;padding:1.5rem 1rem;"
    "margin-top:.75rem;color:var(--muted)}"
    ".capture.taken{border-style:solid;border-color:var(--accent);"
    "background:var(--accent-soft);color:var(--ink)}"
    ".choices{display:grid;gap:.5rem;margin-top:.75rem}"
    ".secondary{color:var(--ink);background:transparent;"
    "border:1px solid var(--line)}"
    ".link{color:var(--muted);background:transparent;font-weight:500;"
    "padding:.5rem;margin-top:.25rem}"
    ".sandbox{max-width:36rem;margin:0 auto 1rem;padding:0 1rem;"
    "font-size:.8rem;color:var(--muted)}"
    ".sandbox summary{cursor:pointer;font-weight:600}"
    ".sandbox code{font-size:.78rem}"
    "[inert]{cursor:default;user-select:none}"
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
  [title body & [after]]
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
       "</main>"
       after
       "<p class=\"note\">This page stands in for an identity "
       "provider. Nothing entered here leaves this environment.</p>"
       "</div></body></html>"))

(defn- js-string
  [s]
  ;; nosemgrep: no-edn-serialization — a quoted string literal for a script
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

(defn- select
  [label name options]
  (str "<label class=\"field wide\"><span>"
       label
       "</span><select name=\""
       name
       "\" required>"
       (str/join (map (fn [[value text]]
                        (str "<option value=\"" value "\">" text "</option>"))
                      options))
       "</select></label>"))

(defn- capture
  [name prompt taken missing]
  (str "<input type=\"hidden\" name=\""
       name
       "\" required data-missing=\""
       (escape missing)
       "\"><div class=\"capture\" data-for=\""
       name
       "\" data-taken=\""
       (escape taken)
       "\"><span>"
       (escape prompt)
       "</span></div>"))

(def ^:private steps
  [["Details"
    (str "<h2>Your details</h2><div class=\"fields\">"
         (field "Given names" "givenNames" "text" nil)
         (field "Family name" "familyName" "text" nil)
         (field "Date of birth" "dateOfBirth" "date" nil)
         (field "Nationality" "nationality" "text" nil)
         "</div>")]
   ["Document"
    (str "<h2>Your photo document</h2><div class=\"fields\">"
         (select "Document"
                 "documentType"
                 [["passport" "Passport"]
                  ["driving-licence" "Driving licence"]
                  ["national-identity-card" "National identity card"]])
         (field "Issuing country" "issuingCountry" "text" nil)
         (field "Document number" "documentNumber" "text" nil)
         "</div>"
         (capture "documentPhoto" "Photograph the front of your document"
                  "Document photographed"
                  "Photograph your document to continue.")
         "<div class=\"choices\"><button type=\"button\" class=\"secondary\" "
         "data-set=\"documentPhoto\" data-value=\"taken\">Take photo</button>"
         "</div>")]
   ["Selfie"
    (str "<h2>A selfie</h2>"
         "<p class=\"lede\">We match your face to your document's photo.</p>"
         (capture "selfie" "Hold your phone at eye level"
                  "Selfie taken" "Take a selfie to continue.")
         "<div class=\"choices\"><button type=\"button\" class=\"secondary\" "
         "data-set=\"selfie\" data-value=\"taken\">Take selfie</button>"
         "<button type=\"button\" class=\"secondary\" data-set=\"selfie\" "
         "data-value=\"looked-away\">Look away</button></div>")]
   ["Address"
    (str "<h2>Your address</h2><div class=\"fields\">"
         (field "Address" "addressLine" "text" "wide")
         (field "Town" "town" "text" nil)
         (field "Postcode" "postcode" "text" nil)
         (field "Country" "country" "text" "wide")
         (select "Proof of address"
                 "proofType"
                 [["utility-bill" "Utility bill"]
                  ["bank-statement" "Bank statement"]
                  ["council-tax-bill" "Council tax bill"]])
         "</div>")]])

(defn- sandbox
  []
  (str
   "<details class=\"sandbox\"><summary>Sandbox values</summary><ul>"
   "<li>A name other than the one registered is someone else's document."
   "</li>"
   (str/join
    (map (fn [[prefix outcome]]
           (str "<li>A document number starting <code>"
                prefix
                "</code>: "
                outcome
                ".</li>"))
         decision/document-prefixes))
   "<li><b>Look away</b> fails liveness.</li>"
   "<li>The postcode <code>"
   decision/failing-postcode
   "</code> fails the address.</li>"
   "<li><b>Leave</b> walks away from the check.</li>"
   "<li>Anything else passes, and screens clear.</li></ul></details>"))

(def ^:private script
  (str
   "const q=new URLSearchParams(location.search);"
   "const f=document.getElementById('verify');"
   "const next=document.getElementById('next');"
   "const leave=document.getElementById('leave');"
   "const err=document.getElementById('error');"
   "const steps=[...f.querySelectorAll('.step')];"
   "const marks=[...document.querySelectorAll('.progress li')];"
   "const leaving=back&&!q.get('simulate');"
   "let at=0;" "const show=i=>{at=i;steps.forEach((s,j)=>s.hidden=j!==i);"
   "marks.forEach((m,j)=>m.classList.toggle('on',j<=i));"
   "next.textContent=i===steps.length-1?'Submit':'Continue'};"
   "const set=(name,value)=>{f.elements[name].value=value;"
   "const c=f.querySelector('.capture[data-for='+name+']');"
   "c.classList.add('taken');"
   "c.querySelector('span').textContent=c.dataset.taken+"
   "(value==='looked-away'?' (looking away)':'');err.textContent=''};"
   "f.querySelectorAll('[data-set]').forEach(b=>b.addEventListener('click',"
   "()=>set(b.dataset.set,b.dataset.value)));"
   "const valid=()=>{for(const el of steps[at].querySelectorAll("
   "'input,select')){if(el.type==='hidden'){if(!el.value){"
   "err.textContent=el.dataset.missing;return false}}"
   "else if(!el.checkValidity()){el.reportValidity();return false}}"
   "return true};"
   "const done=()=>{document.getElementById('card').innerHTML="
   "'<div class=\"done\"><span class=\"mark\">'+tick+"
   "'</span><h1>You are done</h1><p class=\"lede\">'+"
   "(leaving?'Taking you back\\u2026':'You can close this page.')+"
   "'</p></div>'};"
   "const send=async body=>{err.textContent='';next.disabled=true;"
   "leave.disabled=true;next.textContent='Checking\\u2026';"
   "const base=location.pathname.replace(new RegExp(page),'');"
   "try{const r=await fetch(base+decision,{method:'POST',"
   "headers:{'Content-Type':'application/json'},"
   "body:JSON.stringify(body)});" "if(!r.ok)throw new Error(r.status);"
   "done();if(leaving)location.href=back}"
   "catch(x){err.textContent='Something went wrong. Please try again.';"
   "next.disabled=false;leave.disabled=false;show(at)}};"
   "const submission=()=>{const d=Object.fromEntries(new FormData(f));"
   "const looked=d.selfie==='looked-away';"
   "delete d.selfie;delete d.documentPhoto;"
   "return Object.assign(d,{lookedAway:looked})};"
   "f.addEventListener('submit',e=>{e.preventDefault();err.textContent='';"
   "if(!valid())return;"
   "if(at<steps.length-1){show(at+1);return}send(submission())});"
   "leave.addEventListener('click',()=>send({left:true}));" "show(0);"
   "const wait=ms=>new Promise(r=>setTimeout(r,ms));"
   "const reveal=el=>{const d=document.querySelector('.device');"
   "const box=d&&getComputedStyle(d).overflowY==='auto'?d:null;"
   "const r=el.getBoundingClientRect();"
   "const by=r.top-(box?box.getBoundingClientRect().top+box.clientHeight/2"
   ":innerHeight/2);"
   "(box||window).scrollBy({top:by,behavior:'smooth'})};" "const sandbox={"
   "'other-document':{givenNames:'Trillian',familyName:'Astra'},"
   "'document-review':{documentNumber:'REVIEW0001'},"
   "'document-failed':{documentNumber:'FORGED0001'},"
   "'sanctions-hit':{documentNumber:'HIT0001'},"
   "'sanctions-possible-match':{documentNumber:'POSSIBLE0001'},"
   "'pep':{documentNumber:'PEP0001'},"
   "'liveness-failed':{selfie:'looked-away'},"
   "'address-failed':{postcode:'XX0 0XX'}};"
   "const values=o=>Object.assign({givenNames:q.get('givenNames')||'',"
   "familyName:q.get('familyName')||'',"
   "dateOfBirth:q.get('dateOfBirth')||'1970-01-01',nationality:'GB',"
   "documentType:'passport',issuingCountry:'GBR',"
   "documentNumber:'123456789',documentPhoto:'taken',selfie:'taken',"
   "addressLine:q.get('addressLine')||'155 Country Lane',"
   "town:q.get('town')||'Cottington',postcode:q.get('postcode')||'QC1 4XY',"
   "country:'GBR',proofType:'utility-bill'},"
   "sandbox[o]||{});" "if(q.get('simulate')){"
   "document.getElementById('card').inert=true;"
   "document.querySelector('.badge').textContent='Playing';"
   "(async()=>{" "const o=q.get('simulate');const v=values(o);"
   "const pace=Math.min(Number(q.get('pace'))||0,5000);" "await wait(pace);"
   "for(const s of steps){"
   "for(const el of s.querySelectorAll('input,select')){"
   "const x=v[el.name]??'';" "if(el.type==='hidden'){const b=s.querySelector("
   "'[data-set='+el.name+'][data-value=\"'+x+'\"]');"
   "if(pace)reveal(b);await wait(pace/2);b.click()}"
   "else if(pace&&el.type==='text'){el.value='';"
   "for(const c of x){el.value+=c;await wait(30)}}"
   "else{el.value=x}" "if(pace)await wait(pace/3)}"
   "if(o==='walk-away'){await wait(pace);leave.click();return}"
   "await wait(pace);f.requestSubmit()}})()}"))

(defn form
  [page-path decision-path return-url]
  (document
   "Identity verification"
   (str
    "<h1>Verify your identity</h1>"
    "<p class=\"lede\">Four short steps. What you give here goes to the "
    "identity provider, not to the bank.</p>"
    "<ol class=\"progress\">"
    (str/join (map (fn [[title]] (str "<li>" title "</li>")) steps))
    "</ol><form id=\"verify\" novalidate>"
    (str/join (map (fn [[_ body]]
                     (str "<section class=\"step\">"
                          body
                          "</section>"))
                   steps))
    "<div class=\"actions\">"
    "<button type=\"submit\" id=\"next\">Continue</button>"
    "<button type=\"button\" class=\"link\" id=\"leave\">Leave</button>"
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
    "</script>")
   (sandbox)))

(defn response
  [status html]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body html})
