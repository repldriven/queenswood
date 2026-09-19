// Pay someone (payee → amount → review → Face ID → sent) and Move between own accounts
function Amount({value,onKey,label}){return <div style={{padding:'0 8px'}}><div className="eyebrow" style={{textAlign:'center',marginBottom:6}}>{label}</div><div className="big num" style={{color:value?'var(--fg)':'var(--muted)'}}><small>£</small>{value||'0'}</div><div style={{height:18}}></div><Pad onKey={onKey}/></div>}
const useAmt=()=>{const [v,setV]=useState('');const key=k=>{if(k==='⌫')return setV(v.slice(0,-1));if(k==='.'){if(!v.includes('.'))setV((v||'0')+'.');return}if(v.includes('.')&&v.split('.')[1].length>=2)return;if(v.length>7)return;setV(v==='0'?k:v+k)};return [v,key,setV]};
function Confirm({onDone,label}){useEffect(()=>{const t=setTimeout(onDone,1500);return()=>clearTimeout(t)},[]);return <div className="scr" data-screen-label="Face ID"><div className="body" style={{display:'grid',placeItems:'center'}}><div style={{textAlign:'center',color:'var(--lime)'}}><div style={{animation:'pop .5s'}}>{Ic.face}</div><div style={{color:'var(--fg-2)',marginTop:14}}>{label}</div></div></div></div>}
function Pay({S,params,pop,send,push}){
  const [step,setStep]=useState(0);const [payee,setPayee]=useState(null);const [q,setQ]=useState('');
  const [np,setNp]=useState({name:'',sort:'',num:''});const [cop,setCop]=useState('idle');
  const [amt,key]=useAmt();const [ref,setRef]=useState('');
  const from=S.accounts.find(a=>a.id===(params.from||'cur'));const n=+amt||0;
  useEffect(()=>{if(cop==='checking'){const t=setTimeout(()=>setCop('match'),1300);return()=>clearTimeout(t)}},[cop]);
  const npOk=np.name.length>1&&np.sort.replace(/\D/g,'').length===6&&np.num.replace(/\D/g,'').length===8;
  const fmtSort=s=>s.replace(/\D/g,'').slice(0,6).replace(/(\d{2})(?=\d)/g,'$1-');
  const list=S.payees.filter(p=>p.name.toLowerCase().includes(q.toLowerCase()));
  if(step===0)return <div className="scr" data-screen-label="Pay · choose payee"><Top onBack={pop} title="Pay" close/><div className="body">
    <input className="inp" placeholder="Search payees" value={q} onChange={e=>setQ(e.target.value)} style={{marginBottom:6}}/>
    <button className="li" onClick={()=>setStep(1)}><span className="av lime">{Ic.plus}</span><span className="ttl">New payee</span><span style={{marginLeft:'auto',color:'var(--muted)'}}>{Ic.chev}</span></button>
    <div className="eyebrow" style={{margin:'18px 0 4px'}}>Recent</div>
    <div className="list">{list.map(p=><button key={p.id} className="li" onClick={()=>{setPayee(p);setStep(2)}}><span className="av">{p.name.split(' ').map(w=>w[0]).slice(0,2).join('')}</span><span><div className="ttl">{p.name}</div><div className="meta">Last paid {p.last}</div></span><span style={{marginLeft:'auto',color:'var(--muted)'}}>{Ic.chev}</span></button>)}</div>
  </div></div>;
  if(step===1)return <div className="scr" data-screen-label="Pay · new payee"><Top onBack={()=>setStep(0)} title="New payee"/><div className="body">
    <Field label="Their name"><input className="inp" value={np.name} onChange={e=>{setNp({...np,name:e.target.value});setCop('idle')}} placeholder="Full name or business"/></Field>
    <div className="row" style={{gap:10,alignItems:'flex-start'}}><Field label="Sort code"><input className="inp mono" inputMode="numeric" placeholder="00-00-00" value={np.sort} onChange={e=>{setNp({...np,sort:fmtSort(e.target.value)});setCop('idle')}}/></Field><Field label="Account number"><input className="inp mono" inputMode="numeric" placeholder="12345678" value={np.num} onChange={e=>{setNp({...np,num:e.target.value.replace(/\D/g,'').slice(0,8)});setCop('idle')}}/></Field></div>
    {cop!=='idle'&&<div className="card" style={{background:cop==='match'?'rgba(200,245,66,.1)':'var(--bg-2)',display:'flex',gap:12,alignItems:'center'}}><span className={cop==='match'?'pos':''} style={{color:cop==='checking'?'var(--muted)':undefined}}>{cop==='match'?Ic.tick:Ic.lock}</span><div style={{fontSize:14}}>{cop==='checking'?'Checking the name with their bank…':<><b>Name matches.</b> The account is held by {np.name}.</>}</div></div>}
  </div><div className="foot">{cop==='match'?<button className="btn" onClick={()=>{const p={id:'new',name:np.name,sort:np.sort,num:np.num};setPayee(p);setStep(2)}}>Continue</button>:<button className="btn" disabled={!npOk||cop==='checking'} onClick={()=>setCop('checking')}>{cop==='checking'?'Checking…':'Check details'}</button>}</div></div>;
  if(step===2)return <div className="scr" data-screen-label="Pay · amount"><Top onBack={()=>setStep(payee.id==='new'?1:0)} title={payee.name}/><div className="body" style={{padding:'0 12px'}}>
    <div className="row" style={{justifyContent:'center',gap:8,marginBottom:6}}><span className="chip">From {from.name} · {gbp(from.bal)}</span></div>
    <Amount value={amt} onKey={key} label="Amount"/>
    <div style={{padding:'16px 8px 0'}}><input className="inp" placeholder="Reference (optional)" value={ref} onChange={e=>setRef(e.target.value.slice(0,18))}/></div>
  </div><div className="foot"><button className="btn" disabled={n<=0||n>from.bal} onClick={()=>setStep(3)}>{n>from.bal?'Not enough in '+from.name:'Review'}</button></div></div>;
  if(step===3)return <div className="scr" data-screen-label="Pay · review"><Top onBack={()=>setStep(2)} title="Review"/><div className="body">
    <div className="eyebrow" style={{textAlign:'center'}}>Sending</div><div className="big num">{gbp(n)}</div><div className="sub" style={{textAlign:'center'}}>to {payee.name}</div>
    <div className="card"><div className="kv"><span>From</span><span>{from.name}</span></div><div className="kv"><span>Sort code</span><span className="mono">{payee.sort}</span></div><div className="kv"><span>Account</span><span className="mono">{payee.num}</span></div><div className="kv"><span>Reference</span><span>{ref||'—'}</span></div><div className="kv"><span>Arrives</span><span>Within seconds</span></div><div className="kv"><span>Fee</span><span>Free</span></div></div>
    <p className="hint" style={{marginTop:16}}>Only pay people you know and trust. If someone asked you to move money urgently, stop and call us.</p>
  </div><div className="foot"><button className="btn" onClick={()=>setStep(4)}>Send {gbp(n)}</button></div></div>;
  if(step===4)return <Confirm label="Confirm with Face ID" onDone={()=>{send({from:from.id,payee,amt:n,ref});setStep(5)}}/>;
  return <div className="scr" data-screen-label="Pay · sent"><div className="body"><div className="ok" style={{color:'var(--lime-ink)'}}>{Ic.check}</div><h1 style={{textAlign:'center'}}>Sent</h1><p className="sub" style={{textAlign:'center'}}>{gbp(n)} to {payee.name}. It should arrive within seconds.</p></div><div className="foot"><button className="btn" onClick={pop}>Done</button></div></div>;
}
function Move({S,params,pop,transfer}){
  const [fromId,setFrom]=useState(params.from||'cur');const [toId,setTo]=useState(()=>S.accounts.find(a=>a.id!==(params.from||'cur'))?.id||null);const [amt,key]=useAmt();const [step,setStep]=useState(0);
  useEffect(()=>{if(!toId||toId===fromId)setTo(S.accounts.find(a=>a.id!==fromId)?.id||null)},[fromId]);
  const from=S.accounts.find(a=>a.id===fromId),to=S.accounts.find(a=>a.id===toId);const n=+amt||0;
  const swap=()=>{setFrom(toId);setTo(fromId)};
  if(step===1)return <Confirm label="Moving money…" onDone={()=>{transfer({from:fromId,to:toId,amt:n});setStep(2)}}/>;
  if(step===2&&to)return <div className="scr" data-screen-label="Move · done"><div className="body"><div className="ok" style={{color:'var(--lime-ink)'}}>{Ic.check}</div><h1 style={{textAlign:'center'}}>Moved</h1><p className="sub" style={{textAlign:'center'}}>{gbp(n)} from {from.name} to {to.name}.</p></div><div className="foot"><button className="btn" onClick={pop}>Done</button></div></div>;
  const Pick=({a,lbl})=><div className="card row" style={{padding:'12px 14px',flex:1}}><span className={'acct '+a.kind} style={{width:36,height:36,padding:0,borderRadius:11,flexShrink:0}}></span><div style={{minWidth:0}}><div className="eyebrow" style={{fontSize:10}}>{lbl}</div><div style={{fontSize:15}}>{a.name}</div><div className="hint num">{gbp(a.bal)}</div></div></div>;
  return <div className="scr" data-screen-label="Move money"><Top onBack={pop} title="Move money" close/><div className="body" style={{padding:'0 12px'}}>
    {S.accounts.length<2?<div className="card" style={{margin:8}}>Open a second account to move money between your own accounts.</div>:<>
    <div className="row" style={{gap:8,padding:'0 8px',marginBottom:18}}><Pick a={from} lbl="From"/><button className="ib" onClick={swap} aria-label="Swap">{Ic.swap}</button>{to&&<Pick a={to} lbl="To"/>}</div>
    <Amount value={amt} onKey={key} label="Amount"/></>}
  </div>{S.accounts.length>=2&&<div className="foot"><button className="btn" disabled={n<=0||n>from.bal} onClick={()=>setStep(1)}>{n>from.bal?'Not enough in '+from.name:'Move '+(n?gbp(n):'money')}</button></div>}</div>;
}
Object.assign(window,{Pay,Move});
