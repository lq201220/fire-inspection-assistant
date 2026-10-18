import {defineConfig} from 'vite';

export default defineConfig(()=>{
  const version=Date.now().toString(36);
  return {plugins:[{
    name:'web-release-version',
    transformIndexHtml(){return [{tag:'meta',attrs:{name:'app-web-version',content:version},injectTo:'head'}];},
    generateBundle(){this.emitFile({type:'asset',fileName:'app-version.json',source:JSON.stringify({version})});}
  }]};
});
