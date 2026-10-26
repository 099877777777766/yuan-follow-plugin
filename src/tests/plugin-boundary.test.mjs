import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
const root='follow-core/src/main/java/com/local/yuanatlas/';
test('published core has no editor, app activity or LAN dependency',()=>{
 for(const name of fs.readdirSync(root).filter(n=>n.endsWith('.java'))){const source=fs.readFileSync(root+name,'utf8');assert.doesNotMatch(source,/\b(?:RecorderFollowEditorActivity|RecorderFollowEditSession|MainActivity|RecorderLanBridge|RecorderLanTransport|RuYuanCapture)\b/,name);}
 assert.doesNotMatch(fs.readFileSync(root+'RecorderRoundOverlay.java','utf8'),/调整表操作|openTableEditor/);
});
test('independent Gradle build never includes original application sources',()=>{
 for(const name of ['settings.gradle','follow-core/build.gradle','demo/build.gradle'])assert.doesNotMatch(fs.readFileSync(name,'utf8'),/yuan\/android|\.\.\/app\/src|shared-sources/);
});
