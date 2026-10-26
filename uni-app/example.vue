<template><view><input v-model="gamePackage" placeholder="目标游戏包名"/><button @click="settings">开启无障碍服务</button><button @click="choose">选择跟打图片</button><button @click="pause">暂停</button><button @click="close">关闭悬浮窗</button><text>{{message}}</text></view></template>
<script>
// 仅 Android App 自定义基座/正式包可用，标准基座和 H5 不含原生插件。
const follow=uni.requireNativePlugin('Yuan-Follow');
export default {data(){return {gamePackage:'',message:''}},onLoad(){follow.subscribe(event=>{this.message=event.type+'：'+event.message})},onUnload(){follow.unsubscribe()},methods:{settings(){follow.openSettings(result=>{this.message=result.message})},choose(){follow.chooseImage({gamePackage:this.gamePackage},result=>{this.message=result.message})},pause(){follow.pause()},close(){follow.close()}}};
</script>
