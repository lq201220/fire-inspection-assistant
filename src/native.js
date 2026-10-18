import {Capacitor} from '@capacitor/core';

const onlineHost=()=>window.FireAssistantNative;
export const isNativeApp=()=>!!onlineHost()||Capacitor.isNativePlatform();

function watchOnlineVersion(){
  const current=document.querySelector('meta[name="app-web-version"]')?.content;
  if(!current)return;
  let pending=false;
  const apply=()=>{
    if(!pending||document.visibilityState!=='visible')return;
    if(document.querySelector('#app[aria-busy="true"],.modal-backdrop,.attachment-viewer,form[data-sync-dirty="true"]'))return;
    if([...document.querySelectorAll('form[data-form="login"] input')].some(input=>input.value))return;
    if(document.activeElement?.matches('input,textarea,select'))return;
    location.reload();
  };
  const check=async()=>{
    try{
      const response=await fetch('/app-version.json',{cache:'no-store',signal:AbortSignal.timeout(5000)});
      if(response.ok){const latest=await response.json();pending=typeof latest.version==='string'&&latest.version!==current;apply();}
    }catch{}
  };
  window.addEventListener('fire-assistant-resume',check);
  setInterval(()=>{if(pending)apply();},3000);
}

export async function initializeNativeApp(onBack){
  if(!isNativeApp())return;
  if(onlineHost()){
    window.addEventListener('fire-assistant-back',async()=>{if(!await onBack())onlineHost()?.minimize();});
    onlineHost().ready();
    watchOnlineVersion();
    return;
  }
  const {App}=await import('@capacitor/app');
  await App.addListener('backButton',async()=>{
    if(!await onBack())await App.minimizeApp();
  });
}

export async function shareNativeAttachment(blob,name){
  const data=await new Promise((resolve,reject)=>{
    const reader=new FileReader();
    reader.onload=()=>resolve(String(reader.result).split(',')[1]);
    reader.onerror=()=>reject(new Error('附件读取失败，请重试'));
    reader.readAsDataURL(blob);
  });
  if(onlineHost()){
    if(!onlineHost().shareFile(data,name,blob.type))throw new Error('暂时无法打开附件，请重试');
    return;
  }
  const [{Filesystem,Directory},{Share}]=await Promise.all([
    import('@capacitor/filesystem'),import('@capacitor/share')
  ]);
  const {uri}=await Filesystem.writeFile({path:`attachments/${name}`,data,directory:Directory.Cache,recursive:true});
  await Share.share({files:[uri],dialogTitle:'保存或分享附件'});
}
