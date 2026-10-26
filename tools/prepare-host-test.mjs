import fs from 'node:fs';import path from 'node:path';
const source=path.resolve(process.argv[2]||'D:/SJS-table/yuan');
const target=path.resolve('host-test');
if(target===source||target.startsWith(source+path.sep))throw new Error('测试副本必须在原项目之外');
if(fs.existsSync(target))throw new Error('host-test 已存在，避免覆盖，请使用现有副本');
fs.mkdirSync(target,{recursive:true});
// 只复制源码与现有网页资源；不复制签名、存档、安装包或构建缓存。
fs.cpSync(path.join(source,'android/app/src/main'),path.join(target,'src/main'),{recursive:true});
const shared=fs.readdirSync('follow-core/src/main/java/com/local/yuanatlas').filter(n=>n.endsWith('.java')).map(n=>'com/local/yuanatlas/'+n);
fs.writeFileSync(path.join(target,'build.gradle'),`plugins { id 'com.android.application' }
android {
 namespace 'com.local.yuanatlas'; compileSdk 35; buildToolsVersion '35.0.0'
 defaultConfig { applicationId 'com.local.yuanatlas.plugintest'; minSdk 24; targetSdk 35; versionCode 1; versionName 'plugin-test'; testInstrumentationRunner 'com.local.yuanatlas.HostPluginChecks' }
 sourceSets.main.java.excludes = ${JSON.stringify(shared)}
 sourceSets.androidTest.manifest.srcFile '../src/tests/host/AndroidManifest.xml'
 sourceSets.androidTest.java.srcDirs = ['../src/tests/host']
 sourceSets.androidTest.assets.srcDirs = ['../src/tests/fixtures']
 buildFeatures { buildConfig true }
 compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
dependencies { implementation project(':follow-core'); implementation 'androidx.webkit:webkit:1.12.1' }
`);
const java=path.join(target,'src/main/java/com/local/yuanatlas');
let service=fs.readFileSync(path.join(java,'RuYuanCaptureService.java'),'utf8');
service=service.replace('extends AccessibilityService','extends FollowAccessibilityService').replace('    interface GameScreenshotCallback {void onSuccess(Bitmap bitmap);void onFailure(String error);}','    // 使用插件的截图回调类型。').replace('    boolean running(){return active;}','    @Override boolean running(){return active;}\n    @Override boolean isRecorderGame(String name){return RuYuanCapture.isRecorderGame(this,name);}').replace('recorderRounds.setRecorderReturn(returnToRecorder);','');
fs.writeFileSync(path.join(java,'RuYuanCaptureService.java'),service);
let manifest=fs.readFileSync(path.join(target,'src/main/AndroidManifest.xml'),'utf8');
manifest=manifest.replace('        <receiver android:name=".RecorderRunNotificationReceiver" android:exported="false"/>','        <service android:name="com.local.yuanatlas.FollowAccessibilityService" tools:node="remove"/>\n        <meta-data android:name="com.yuan.follow.service" android:value="com.local.yuanatlas.RuYuanCaptureService"/>');
fs.writeFileSync(path.join(target,'src/main/AndroidManifest.xml'),manifest);
console.log('Created isolated current-App host at '+target);
