function App(){
  const [S,setS]=useState(()=>JSON.parse(JSON.stringify(SEED)));
  const [mode,setMode]=useState('onboarding');const [tab,setTab]=useState('home');const [stack,setStack]=useState([]);const [msg,setMsg]=useState(null);
  const push=(name,params={})=>setStack(s=>[...s,{name,params}]);const pop=()=>setStack(s=>s.slice(0,-1));const go=t=>{setStack([]);setTab(t)};
  const toast=m=>{setMsg(m);setTimeout(()=>setMsg(null),1800)};
  const stamp=()=>{const d=new Date();return 'Today, '+d.toTimeString().slice(0,5)};
  const send=({from,payee,amt,ref})=>setS(s=>({...s,accounts:s.accounts.map(a=>a.id===from?{...a,bal:a.bal-amt,spark:[...a.spark.slice(1),a.bal-amt]}:a),payees:payee.id==='new'?[{...payee,id:'p'+Date.now(),last:gbp(amt)+' · today'},...s.payees]:s.payees,txns:[{id:Date.now(),acct:from,who:payee.name,cat:'Payment',amt:-amt,when:stamp(),date:'Today',ref},...s.txns]}));
  const transfer=({from,to,amt})=>setS(s=>{const F=s.accounts.find(a=>a.id===from),T=s.accounts.find(a=>a.id===to);return {...s,accounts:s.accounts.map(a=>a.id===from?{...a,bal:a.bal-amt}:a.id===to?{...a,bal:a.bal+amt}:a),txns:[{id:Date.now()+1,acct:to,who:'Transfer from '+F.name,cat:'Saved',amt,when:stamp(),date:'Today'},{id:Date.now(),acct:from,who:'Transfer to '+T.name,cat:'Saved',amt:-amt,when:stamp(),date:'Today'},...s.txns]}});
  const openAccount=(p,dep)=>setS(s=>{const acct={id:p.id+Date.now(),kind:p.kind,name:p.name,type:p.kind==='sav'?'Easy-access saver · 4.10% AER':p.kind==='fix'?'1 Year Fixed · 4.65% AER':'Current account',bal:dep,sort:'04-00-75',num:'3190'+String(8200+Math.floor(Math.random()*700)),spark:[0,0,0,0,0,0,dep]};return {...s,accounts:[...s.accounts.map(a=>a.id==='cur'&&dep?{...a,bal:a.bal-dep}:a),acct],txns:dep?[{id:Date.now(),acct:'cur',who:'Transfer to '+p.name,cat:'Saved',amt:-dep,when:stamp(),date:'Today'},...s.txns]:s.txns}});
  const signOut=()=>{setMode('onboarding');setStack([]);setTab('home')};
  const top=stack[stack.length-1];
  let view;
  if(mode==='onboarding')view=<Onboarding onDone={()=>setMode('app')}/>;
  else if(top){const P={S,params:top.params,pop,push,toast,send,transfer,openAccount};view={account:<Account {...P}/>,txn:<TxnDetail {...P}/>,pay:<Pay {...P}/>,move:<Move {...P}/>,open:<OpenAccount {...P}/>}[top.name]}
  else view={home:<Home S={S} push={push} go={go}/>,activity:<Activity S={S} push={push} go={go}/>,me:<Me S={S} go={go} signOut={signOut}/>,pay:<Pay S={S} params={{}} pop={()=>go('home')} push={push} send={send}/>}[tab];
  const jump=[['Welcome',()=>signOut()],['Home',()=>{setMode('app');go('home')}],['Open an account',()=>{setMode('app');go('home');push('open')}],['Account detail',()=>{setMode('app');go('home');push('account',{id:'cur'})}],['Pay someone',()=>{setMode('app');go('home');push('pay')}],['Move money',()=>{setMode('app');go('home');push('move')}],['Activity',()=>{setMode('app');go('activity')}],['Me',()=>{setMode('app');go('me')}]];
  return <div className="stage">
    <div className="rail"><h4>Xepha · flows</h4>{jump.map(([l,f])=><button key={l} onClick={f}>{l}</button>)}<p style={{fontSize:12,marginTop:18,lineHeight:1.5,color:'#6b7290'}}>Everything in the phone is live: onboarding fills itself in, payments update balances and activity.</p></div>
    <IOSDevice dark><div style={{position:'relative',height:'100%',fontFamily:'var(--sans)'}}>{view}{msg&&<div className="toast">{msg}</div>}</div></IOSDevice>
  </div>;
}
ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
