import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import {chromium} from 'playwright';
// 测试最终打入 AAR 的资源，包含无附件的 v1/v2 转发图片。
test('bundled decoder restores both texture generations and rejects ordinary image',async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{const page=await browser.newPage();let result;await page.exposeFunction('decodeResult',(code,error)=>{result={code,error};});
 await page.addInitScript(()=>{});await page.setContent(fs.readFileSync('follow-core/src/main/assets/yuan-follow/decoder.html','utf8'));
 await page.evaluate(()=>{window.FollowDecoder={result:(code,error)=>window.decodeResult(code,error)};});
 for(const file of ['follow-texture-original.jpg','follow-texture-pixels.jpg','follow-texture-v1-pixels.jpg']){
 result=null;const data='data:image/jpeg;base64,'+fs.readFileSync('src/tests/fixtures/'+file).toString('base64');
 await page.evaluate(data=>window.decodeFollow(data),data);assert.ok(result?.code?.startsWith('YRAF1:'),file+': '+JSON.stringify(result));assert.equal(result.error,'');
 }
 result=null;await page.evaluate(()=>window.decodeFollow('https://example.com/image.jpg'));assert.equal(result.code,'');assert.match(result.error,/本地/);
 }finally{await browser.close();}
});
