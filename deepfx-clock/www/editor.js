'use strict';
let state = {x:.5,y:.35,scale:.18,dateX:.5,dateY:.46,badgeX:.85,badgeY:.35,opacity:1,glow:.2,spacing:0,color:'#ffffff',style:'condensed',format24:false,showDate:true,amplitude:.015,speed:12,direction:'horizontal',depthStrength:.6,fps:24,feather:1,threshold:.5};
const sliders = [
 ['clock-fields','x','Clock X',0,1,.005],['clock-fields','y','Clock Y',0,1,.005],
 ['clock-fields','scale','Size',.04,.5,.005],['clock-fields','opacity','Opacity',0,1,.01],
 ['clock-fields','glow','Glow',0,1,.01],['clock-fields','spacing','Letter spacing',0,.2,.005],
 ['clock-fields','dateX','Date X',0,1,.005],['clock-fields','dateY','Date Y',0,1,.005],
 ['clock-fields','badgeX','AM/PM X',0,1,.005],['clock-fields','badgeY','AM/PM Y',0,1,.005],
 ['motion-fields','amplitude','Drift amount',0,.04,.001],['motion-fields','speed','Cycle seconds',4,40,1],
 ['motion-fields','depthStrength','Depth strength',0,1,.01],['motion-fields','fps','Frame rate',24,30,1],
 ['mask-fields','threshold','Threshold',.1,.9,.01],['mask-fields','feather','Feather',0,5,.1]
];
function call(method, ...args) {
 try {
  if (!window.DeepFX || typeof window.DeepFX[method] !== 'function') throw new Error('Open this editor inside the DeepFX-Clock Android app.');
  window.DeepFX[method](...args);
 } catch (error) { window.setStatus(error.message || 'Operation failed'); }
}
function send() { call('update', JSON.stringify(state)); }
window.setStatus = text => { document.getElementById('status').textContent = text; };
for (const [group,key,label,min,max,step] of sliders) {
 const row=document.createElement('div'); row.className='field';
 const caption=document.createElement('label'); caption.htmlFor=key; caption.textContent=label;
 const input=document.createElement('input'); input.type='range'; input.id=key; input.min=min; input.max=max; input.step=step; input.value=state[key];
 const output=document.createElement('output'); output.id=key+'-value'; output.htmlFor=key; output.textContent=String(state[key]);
 input.addEventListener('input',()=>{ state[key]=Number(input.value); output.textContent=input.value; send(); });
 row.append(caption,input,output); document.getElementById(group).append(row);
}
for (const key of ['color','style','direction','format24','showDate']) {
 const input=document.getElementById(key);
 input.addEventListener('input',()=>{ state[key]=input.type==='checkbox'?input.checked:input.value; send(); });
}
window.receiveSettings = settings => {
 state={...state,...settings};
 for (const key of Object.keys(state)) {
  const input=document.getElementById(key); if(!input) continue;
  if(input.type==='checkbox') input.checked=Boolean(state[key]); else input.value=String(state[key]);
  const output=document.getElementById(key+'-value'); if(output) output.textContent=String(Math.round(Number(state[key])*1000)/1000);
 }
};
document.getElementById('pick').addEventListener('click',()=>call('pick'));
document.getElementById('live').addEventListener('click',()=>call('applyLive'));
document.getElementById('static').addEventListener('click',()=>call('applyStatic'));
document.getElementById('target').addEventListener('change',event=>call('editTarget',event.target.value));
window.receiveSettings(state);
