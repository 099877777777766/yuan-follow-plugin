import {build} from 'esbuild';
import fs from 'node:fs/promises';
const result=await build({entryPoints:['src/texture/entry.ts'],bundle:true,minify:true,format:'iife',target:'es2020',write:false});
const html='<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; img-src data:; connect-src \'none\'"><script>'+result.outputFiles[0].text.replace(/<\/script/gi,'<\\/script')+'</script>';
await fs.mkdir('follow-core/src/main/assets/yuan-follow',{recursive:true});
await fs.writeFile('follow-core/src/main/assets/yuan-follow/decoder.html',html);
console.log('Built offline texture decoder: '+html.length+' bytes');
