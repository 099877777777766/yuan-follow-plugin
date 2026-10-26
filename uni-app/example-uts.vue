<template><view><input v-model="gamePackage" placeholder="目标游戏包名"/><button @click="settings">开启无障碍服务</button><button @click="choose">选择跟打图片</button><button @click="pause">暂停</button><button @click="close">关闭浮窗</button><text>{{message}}</text></view></template>
<script>
import {openSettings,chooseImage,pause,close} from '@/uni_modules/yuan-follow';
// 复制 uni_modules/yuan-follow 后重新制作 Android 自定义基座。
export default {data(){return {gamePackage:'',message:''}},methods:{settings(){openSettings()},choose(){chooseImage(this.gamePackage,result=>{this.message=result.message})},pause(){pause()},close(){close()}}};
</script>
