import fs from 'node:fs';import path from 'node:path';import {spawnSync} from 'node:child_process';
const java=process.env.JAVA_HOME||'C:/Program Files/Java/jdk-17';
const home=process.env.GRADLE_HOME||'C:/Program Files/gradle-8.13';
const jar=fs.readdirSync(path.join(home,'lib')).find(n=>n.startsWith('gradle-gradle-cli-main-')&&n.endsWith('.jar'));
const r=spawnSync(path.join(java,'bin',process.platform==='win32'?'java.exe':'java'),['-classpath',path.join(home,'lib',jar),'org.gradle.launcher.GradleMain','-g',path.resolve('.tooling/gradle-home'),...process.argv.slice(2),'--console=plain','--no-daemon'],{stdio:'inherit',env:{...process.env,JAVA_HOME:java,ANDROID_USER_HOME:path.resolve(".tooling/android-user")}});
process.exit(r.status??1);
