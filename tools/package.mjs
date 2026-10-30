import fs from 'node:fs/promises';import path from 'node:path';import crypto from 'node:crypto';
const out='output/release';const aar='follow-core/build/outputs/aar/follow-core-release.aar';
await fs.access(aar);await fs.mkdir(out+'/android',{recursive:true});
await fs.copyFile(aar,out+'/android/yuan-follow-core-1.0.0.aar');
const uts='uni-app/uni_modules/yuan-follow/utssdk/app-android/libs';await fs.mkdir(uts,{recursive:true});await fs.copyFile(aar,uts+'/yuan-follow-core-1.0.0.aar');
await fs.cp('uni-app/uni_modules',out+'/uni-app/uni_modules',{recursive:true});
await fs.copyFile('uni-app/example-uts.vue',out+'/uni-app/example.vue');await fs.copyFile('README.md',out+'/README.md');
await fs.mkdir(out+'/docs',{recursive:true});await fs.copyFile('docs/验证记录.md',out+'/docs/验证记录.md');
// 同步面向接入方的非测试说明，保证发行包包含最新功能流程。
await fs.copyFile('docs/插件拆分与接入说明.md',out+'/docs/插件拆分与接入说明.md');
const hash=crypto.createHash('sha256').update(await fs.readFile(aar)).digest('hex');
await fs.writeFile(out+'/SHA256SUMS',hash+'  android/yuan-follow-core-1.0.0.aar\n');
await fs.writeFile(out+'/android/dependencies.gradle',"implementation 'com.google.mlkit:text-recognition-chinese:16.0.1'\nimplementation 'androidx.webkit:webkit:1.12.1'\n");
console.log('Packaged Android AAR and UTS plugin; SHA256='+hash);
