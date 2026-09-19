// Home, account detail, activity, me, open-account flow
function Home({S,push,go}){
  const total=S.accounts.reduce((a,b)=>a+b.bal,0);
  const recent=S.txns.slice(0,4);
  return <div className="scr" data-screen-label="Home">
    <div className="body">
      <div className="row" style={{justifyContent:'space-between',marginTop:6}}><div><div className="eyebrow">Good morning</div><div style={{fontSize:22,fontWeight:500}}>{S.user.first}</div></div><button className="ib av" style={{width:40,height:40,borderRadius:'50%',fontSize:14}} onClick={()=>go('me')}>{S.user.first[0]}{S.user.last[0]}</button></div>
      <div style={{margin:'28px 0 8px'}}><div className="eyebrow">Total balance</div><div className="big num" style={{textAlign:'left',fontSize:46}}>{gbp(total)}</div></div>
      <div className="qa"><button onClick={()=>push('pay')}><i>{Ic.pay}</i>Pay</button><button onClick={()=>push('move')}><i>{Ic.swap}</i>Move</button><button onClick={()=>push('open')}><i>{Ic.plus}</i>Open</button></div>
      <div className="list" style={{gap:12}}>{S.accounts.map(a=><div key={a.id} className={'acct '+a.kind} onClick={()=>push('account',{id:a.id})}>
        <div className="row" style={{justifyContent:'space-between'}}><div><div style={{fontSize:17,fontWeight:500}}>{a.name}</div><div style={{fontSize:13,color:'var(--fg-2)',opacity:.8}}>{a.type}</div></div><div className="num" style={{fontSize:22}}>{gbp(a.bal)}</div></div>
        <div style={{marginTop:14,opacity:.85}}><Spark pts={a.spark} color={a.kind==='fix'?'#ffb26b':'var(--lime)'}/></div>
      </div>)}</div>
      <div className="row" style={{justifyContent:'space-between',margin:'28px 0 4px'}}><h2 style={{margin:0}}>Recent</h2><button className="btn ghost sm" style={{height:32,fontSize:13,padding:'0 12px'}} onClick={()=>go('activity')}>See all</button></div>
      <div className="list">{recent.map(t=><Txn key={t.id} t={t} onClick={()=>push('txn',{id:t.id})}/>)}</div>
    </div>
    <Tabs tab="home" go={go}/>
  </div>;
}
function Txn({t,onClick}){const ini=t.who.split(' ').map(w=>w[0]).slice(0,2).join('');return <button className="li" onClick={onClick}><span className={'av'+(t.amt>0?' lime':'')}>{ini}</span><span><div className="ttl">{t.who}</div><div className="meta">{t.cat} · {t.when}</div></span><span className={'amt num'+(t.amt>0?' pos':'')}>{gbp(t.amt,{sign:true})}</span></button>}
function Account({S,params,pop,push,toast}){
  const a=S.accounts.find(x=>x.id===params.id);const txns=S.txns.filter(t=>t.acct===a.id);
  const groups=txns.reduce((m,t)=>{(m[t.date]=m[t.date]||[]).push(t);return m},{});
  const copy=()=>toast('Account details copied');
  return <div className="scr" data-screen-label="Account detail">
    <Top onBack={pop} title={a.name}/>
    <div className="body">
      <div className="eyebrow">{a.type}</div><div className="big num" style={{textAlign:'left',margin:'6px 0 14px'}}>{gbp(a.bal)}</div>
      <button className="li" style={{border:0,padding:'8px 0 20px'}} onClick={copy}><span className="mono" style={{color:'var(--fg-2)'}}>{a.sort}</span><span className="mono" style={{color:'var(--fg-2)'}}>{a.num}</span><span style={{marginLeft:'auto',color:'var(--muted)'}}>{Ic.copy}</span></button>
      <div className="row" style={{gap:10,marginBottom:28}}><button className="btn sm" onClick={()=>push('pay',{from:a.id})}>Pay</button><button className="btn ghost sm" onClick={()=>push('move',{from:a.id})}>Move money</button>{a.kind!=='cur'&&<span className="chip lime" style={{marginLeft:'auto'}}>Interest paid monthly</span>}</div>
      {Object.entries(groups).map(([d,ts])=><div key={d}><div className="eyebrow" style={{margin:'14px 0 2px'}}>{d}</div><div className="list">{ts.map(t=><Txn key={t.id} t={t} onClick={()=>push('txn',{id:t.id})}/>)}</div></div>)}
    </div>
  </div>;
}
function TxnDetail({S,params,pop}){
  const t=S.txns.find(x=>x.id===params.id);const a=S.accounts.find(x=>x.id===t.acct);
  return <div className="scr" data-screen-label="Transaction">
    <Top onBack={pop} close/>
    <div className="body" style={{textAlign:'center'}}>
      <span className={'av'+(t.amt>0?' lime':'')} style={{width:72,height:72,borderRadius:24,fontSize:24,margin:'10px auto 16px'}}>{t.who.split(' ').map(w=>w[0]).slice(0,2).join('')}</span>
      <div style={{fontSize:20,fontWeight:500}}>{t.who}</div><div className={'big num'+(t.amt>0?' pos':'')} style={{margin:'8px 0 6px'}}>{gbp(t.amt,{sign:true})}</div><div className="sub">{t.when}</div>
      <div className="card" style={{textAlign:'left'}}><div className="kv"><span>Category</span><span>{t.cat}</span></div><div className="kv"><span>Account</span><span>{a.name}</span></div>{t.ref&&<div className="kv"><span>Reference</span><span className="mono">{t.ref}</span></div>}<div className="kv"><span>Status</span><span className="pos">Complete</span></div></div>
    </div>
    <div className="foot"><button className="btn ghost">Report a problem</button></div>
  </div>;
}
function Activity({S,push,go}){
  const [f,setF]=useState('all');const txns=S.txns.filter(t=>f==='all'||(f==='in'?t.amt>0:t.amt<0));
  const groups=txns.reduce((m,t)=>{(m[t.date]=m[t.date]||[]).push(t);return m},{});
  return <div className="scr" data-screen-label="Activity"><div className="body"><h1>Activity</h1>
    <div className="seg" style={{marginBottom:14}}>{[['all','All'],['in','In'],['out','Out']].map(([k,l])=><button key={k} className={f===k?'on':''} onClick={()=>setF(k)}>{l}</button>)}</div>
    {Object.entries(groups).map(([d,ts])=><div key={d}><div className="eyebrow" style={{margin:'14px 0 2px'}}>{d}</div><div className="list">{ts.map(t=><Txn key={t.id} t={t} onClick={()=>push('txn',{id:t.id})}/>)}</div></div>)}
  </div><Tabs tab="activity" go={go}/></div>;
}
function Me({S,go,signOut}){
  const rows=[['Personal details','Amara Okafor · +44 7700 900123'],['Security','Passcode, Face ID'],['Cards','Everyday · ···· 4417'],['Statements & documents',''],['Help','Chat with us, 24/7']];
  return <div className="scr" data-screen-label="Me"><div className="body">
    <div className="row" style={{marginTop:10,marginBottom:24}}><span className="av lime" style={{width:56,height:56,borderRadius:18,fontSize:20}}>AO</span><div><div style={{fontSize:20,fontWeight:500}}>{S.user.first} {S.user.last}</div><div className="hint">Member since Sep 2026</div></div></div>
    <div className="list">{rows.map(([t,m])=><button className="li" key={t}><span><div className="ttl">{t}</div>{m&&<div className="meta">{m}</div>}</span><span style={{marginLeft:'auto',color:'var(--muted)'}}>{Ic.chev}</span></button>)}</div>
    <button className="btn ghost" style={{marginTop:28}} onClick={signOut}>Sign out</button>
    <p className="hint" style={{textAlign:'center',marginTop:20}}>Xepha is a trading name of Xepha Bank Ltd. Eligible deposits are protected by the FSCS up to £85,000.</p>
  </div><Tabs tab="me" go={go}/></div>;
}
function OpenAccount({S,pop,openAccount}){
  const [sel,setSel]=useState(null);const [step,setStep]=useState(0);const [dep,setDep]=useState('');
  const owned=S.accounts.map(a=>a.kind);const p=SEED.products.find(x=>x.id===sel);
  if(step===2)return <div className="scr" data-screen-label="Account opened"><div className="body"><div className="ok" style={{color:'var(--lime-ink)'}}>{Ic.check}</div><h1 style={{textAlign:'center'}}>{p.name} is open</h1><p className="sub" style={{textAlign:'center'}}>{dep?`${gbp(+dep)} moved from Everyday. `:''}You'll see it on your home screen.</p></div><div className="foot"><button className="btn" onClick={pop}>Done</button></div></div>;
  if(step===1)return <div className="scr" data-screen-label="Confirm new account"><Top onBack={()=>setStep(0)} title="Open account"/><div className="body"><h1>{p.name}</h1><p className="sub">{p.blurb}</p>
    {p.kind!=='cur'&&<Field label="Opening deposit from Everyday" hint={p.kind==='fix'?'Minimum £1,000':'Optional'}><input className="inp num" inputMode="decimal" placeholder="£0.00" value={dep} onChange={e=>setDep(e.target.value.replace(/[^\d.]/g,''))}/></Field>}
    <div className="card"><div className="kv"><span>Rate</span><span>{p.kind==='sav'?'4.10% AER variable':p.kind==='fix'?'4.65% AER fixed':'—'}</span></div><div className="kv"><span>Fees</span><span>None</span></div><div className="kv"><span>Protection</span><span>FSCS up to £85,000</span></div></div>
    <p className="hint" style={{marginTop:16}}>By opening this account you accept the <a href="#">summary box</a> and <a href="#">terms</a>.</p></div>
    <div className="foot"><button className="btn" disabled={p.kind==='fix'&&(+dep<1000)} onClick={()=>{openAccount(p,+dep||0);setStep(2)}}>Open {p.name}</button></div></div>;
  return <div className="scr" data-screen-label="Choose a product"><Top onBack={pop} title="Open account" close/><div className="body"><h1>What would you like to open?</h1><p className="sub">Takes a moment. No credit check for savings.</p>
    {SEED.products.map(x=>{const has=owned.includes(x.kind);return <button key={x.id} className={'opt'+(sel===x.id?' on':'')} disabled={has} style={{opacity:has?.45:1}} onClick={()=>setSel(x.id)}>
      <span className={'acct '+x.kind} style={{width:44,height:44,padding:0,borderRadius:14,flexShrink:0}}></span>
      <span style={{flex:1}}><div style={{fontWeight:500}}>{x.name} {has&&<span className="chip" style={{marginLeft:6,height:22,fontSize:11}}>Open</span>}</div><div className="hint">{x.blurb}</div></span>
      {sel===x.id&&<span className="pos">{Ic.tick}</span>}</button>})}
    {p&&<ul style={{margin:'6px 0 0',padding:'0 0 0 18px',color:'var(--fg-2)',fontSize:14,lineHeight:1.7}}>{p.pts.map(s=><li key={s}>{s}</li>)}</ul>}
  </div><div className="foot"><button className="btn" disabled={!sel} onClick={()=>setStep(1)}>Continue</button></div></div>;
}
Object.assign(window,{Home,Account,TxnDetail,Activity,Me,OpenAccount,Txn});
