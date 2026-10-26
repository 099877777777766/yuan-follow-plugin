import { readRecorderFollowImage } from './image';
declare const FollowDecoder: { result(code: string, error: string): void };
// Android 私有 WebView 唯一入口，不加载任何外部页面。
Object.assign(window, { decodeFollow: async (data: string) => {
 try { const code=await readRecorderFollowImage(data); FollowDecoder.result(code??'',code?'':'图片中未找到跟打码'); }
 catch(error){FollowDecoder.result('',error instanceof Error?error.message:'图片解码失败');}
}});
