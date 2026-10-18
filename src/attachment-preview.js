import {attachmentURL,getToken} from './api.js';
import {esc} from './ui.js';
import {isNativeApp,shareNativeAttachment} from './native.js';

let currentPreview=null;
let sequence=0;

export const isAttachmentPreviewOpen=()=>!!currentPreview;

function dismissPreview(preview){
  if(currentPreview!==preview)return;
  currentPreview=null;
  preview.dialog.close();
  preview.dialog.remove();
  document.body.style.overflow=preview.bodyOverflow;
  if(preview.origin.isConnected)preview.origin.focus({preventScroll:true});
  window.scrollTo(preview.scrollX,preview.scrollY);
}

export function closeAttachmentPreview(){
  const preview=currentPreview;
  if(!preview)return;
  if(history.state?.attachmentPreviewKey===preview.key)history.back();
  else dismissPreview(preview);
}

window.addEventListener('popstate',()=>{
  if(currentPreview&&history.state?.attachmentPreviewKey!==currentPreview.key)dismissPreview(currentPreview);
});
window.addEventListener('auth-expired',()=>{
  const preview=currentPreview;
  if(!preview)return;
  dismissPreview(preview);
  if(history.state?.attachmentPreviewKey===preview.key)history.back();
});

export async function showAttachmentPreview(origin){
  if(currentPreview)return;
  const title=origin.querySelector('.attachment-caption')?.textContent||'附件预览';
  const dialog=document.createElement('dialog');
  dialog.className='attachment-viewer';
  dialog.setAttribute('aria-label','附件预览');
  dialog.innerHTML='<header class="attachment-viewer-header"><h2>附件预览</h2><a class="ghost-btn" data-preview-download hidden>下载附件</a></header><div class="attachment-viewer-content" aria-live="polite"><p class="attachment-viewer-message">正在加载附件…</p></div>';
  const preview={dialog,origin,key:`attachment-${++sequence}`,token:getToken(),scrollX:window.scrollX,scrollY:window.scrollY,bodyOverflow:document.body.style.overflow};
  currentPreview=preview;
  document.body.append(dialog);
  document.body.style.overflow='hidden';
  history.pushState({...history.state,attachmentPreviewKey:preview.key},'');
  dialog.showModal();
  dialog.focus();
  dialog.addEventListener('cancel',event=>{event.preventDefault();closeAttachmentPreview();});
  dialog.addEventListener('click',event=>{
    if(!event.target.closest('img,iframe,a,button'))closeAttachmentPreview();
  });
  const content=dialog.querySelector('.attachment-viewer-content');
  try{
    const url=await attachmentURL(origin.dataset.id,origin.dataset.attachmentTask);
    const response=await fetch(url);
    if(currentPreview!==preview||getToken()!==preview.token)return;
    const type=(response.headers.get('content-type')||'').toLowerCase();
    if(type.startsWith('image/')){
      const image=document.createElement('img');
      image.className='attachment-viewer-image';
      image.alt=title;
      image.addEventListener('error',()=>{if(currentPreview===preview)content.innerHTML='<p class="attachment-viewer-message" role="alert">图片暂时无法显示，请重试。</p>';});
      image.src=url;
      content.replaceChildren(image);
    }else if(type==='application/pdf'&&!isNativeApp()){
      const frame=document.createElement('iframe');
      frame.className='attachment-viewer-pdf';
      frame.title='PDF 附件';
      frame.src=url;
      content.replaceChildren(frame);
    }else content.innerHTML='<p class="attachment-viewer-message">此附件可下载查看。</p>';
    const download=dialog.querySelector('[data-preview-download]');
    download.href=url;
    download.download=`附件.${type==='application/pdf'?'pdf':type==='image/jpeg'?'jpg':type==='image/png'?'png':'bin'}`;
    download.hidden=false;
    if(isNativeApp()){
      const attachmentBlob=await response.blob();
      download.textContent=type==='application/pdf'?'打开 / 分享':'保存 / 分享';
      download.addEventListener('click',async event=>{
        event.preventDefault();
        if(download.dataset.busy)return;
        download.dataset.busy='true';
        try{await shareNativeAttachment(attachmentBlob,`${origin.dataset.id}.${type==='application/pdf'?'pdf':type==='image/jpeg'?'jpg':type==='image/png'?'png':'bin'}`);}
        catch(error){if(currentPreview===preview)content.insertAdjacentHTML('beforeend',`<p class="attachment-viewer-message" role="alert">${esc(error.message||'暂时无法打开附件')}</p>`);}
        finally{delete download.dataset.busy;}
      });
    }
  }catch(error){
    if(currentPreview===preview)content.innerHTML=`<p class="attachment-viewer-message" role="alert">${esc(error.message)}</p>`;
  }
}
