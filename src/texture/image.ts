import { readTextureHeader, textureGeometry, type TexturePixels } from './recorder-header-texture';
import { extractRecorderFollowTexture, reverseTextureRows, FOLLOW_TEXTURE_FORMATS } from './recorder-follow-texture';
// 原生仍负责完整 AES-GCM 认证；这里仅将恢复的二进制封装为协议文本。
export function recorderAutoFollowCodeFromBytes(bytes: Uint8Array): string {
 let raw='';for(let i=0;i<bytes.length;i+=8192)raw+=String.fromCharCode(...bytes.subarray(i,i+8192));
 return 'YRAF1:'+btoa(raw).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}

// #ifdef H5
/** bottom 指定识别码底边，其下可放说明或留白；读取时不依赖表格行数或 OCR。 */
export function drawRecorderFollowTexture(canvas: HTMLCanvasElement, pixels: TexturePixels, bottom = canvas.height) {
  const tile = document.createElement('canvas'); tile.width = pixels.width; tile.height = pixels.height;
  try {
    const source = tile.getContext('2d'), target = canvas.getContext('2d');
    if (!source || !target) throw new Error('图片导出画布不可用');
    source.putImageData(new ImageData(pixels.data, pixels.width, pixels.height), 0, 0);
    const height = Math.round(pixels.height * canvas.width / pixels.width);
    target.save(); target.imageSmoothingEnabled = true; target.imageSmoothingQuality = 'high';
    target.drawImage(tile, 0, bottom - height, canvas.width, height); target.restore();
  } finally { tile.width = 0; }
}

export async function readRecorderFollowTextureImage(image: HTMLImageElement, signal?: AbortSignal): Promise<Uint8Array | null> {
  if (signal?.aborted) throw new Error('已取消导入');
  const canvas = document.createElement('canvas');
  try {
    const ctx = canvas.getContext('2d', { willReadFrequently: true }); if (!ctx) throw new Error('图片识别画布不可用');
    // 先尝试历史图片底边，再寻找有色带；说明文字可能位于码下方，不能停在第一行文字上。
    function contentBottoms() {
      canvas.width = 32; canvas.height = image.naturalHeight;
      ctx!.drawImage(image, 0, 0, canvas.width, canvas.height);
      const pixels = ctx!.getImageData(0, 0, canvas.width, canvas.height).data;
      const ends: number[] = [];
      let bandEnd = 0;
      for (let y = canvas.height - 1; y >= -1; y--) {
        let ink = 0;
        for (let x = 0; y >= 0 && x < canvas.width; x++) {
          const i = (y * canvas.width + x) * 4;
          if (pixels[i + 3] > 127 && (pixels[i] + pixels[i + 1] + pixels[i + 2]) / 3 < 235) ink++;
        }
        // 多数横向采样点有色才算一条色带；忽略细笔画，最多检查底部 16 条候选带。
        if (ink >= canvas.width / 2) { if (!bandEnd) bandEnd = y + 1; }
        else if (bandEnd) {
          if (bandEnd - y - 1 >= Math.max(2, image.naturalWidth / 480 * 8)) ends.push(bandEnd);
          bandEnd = 0;
          if (ends.length >= 16) break;
        }
      }
      return ends;
    }
    let error: Error | undefined;
    const bottoms = [image.naturalHeight];
    for (let candidate = 0; candidate < bottoms.length; candidate++) {
      if (signal?.aborted) throw new Error('已取消导入');
      const bottom = bottoms[candidate];
      for (const format of FOLLOW_TEXTURE_FORMATS) {
        const { width, headerHeight } = textureGeometry(format);
        canvas.width = width; canvas.height = headerHeight; ctx.imageSmoothingQuality = 'high';
        const sourceHeader = headerHeight * image.naturalWidth / width;
        if (!sourceHeader || bottom < sourceHeader) continue;
        ctx.drawImage(image, 0, bottom - sourceHeader, image.naturalWidth, sourceHeader, 0, 0, width, headerHeight);
        const header = readTextureHeader(reverseTextureRows(ctx.getImageData(0, 0, width, headerHeight)), format);
        if (!header) continue;
        const sourceHeight = header.height * image.naturalWidth / width;
        if (sourceHeight > bottom + 1) { error = new Error('跟打图像码不完整，请使用原图'); continue; }
        canvas.height = header.height; ctx.imageSmoothingQuality = 'high';
        // 吸收导出和平台缩图的取整误差，以识别码底边为定位基准。
        for (const height of [sourceHeight, Math.round(sourceHeight), sourceHeight - .5, sourceHeight + .5, sourceHeight - 1, sourceHeight + 1]) {
          if (signal?.aborted) throw new Error('已取消导入');
          const bandHeight = Math.min(height, bottom);
          ctx.drawImage(image, 0, bottom - bandHeight, image.naturalWidth, bandHeight, 0, 0, width, header.height);
          try {
            const bytes = extractRecorderFollowTexture(ctx.getImageData(0, 0, width, header.height), format);
            if (bytes) return bytes;
          } catch { /* 继续尝试有限的像素取整偏差，全部失败才提示原图。 */ }
          await new Promise(resolve => setTimeout(resolve, 0));
        }
        error = new Error('跟打图像码校验失败，请使用原图');
      }
      if (candidate === 0) {
        // 白边边界可能经过缩图和 JPEG 混色，有限搜索邻近像素；旧图无需增加扫描解码次数。
        for (const end of contentBottoms()) if (end < image.naturalHeight - 2)
          for (const offset of [0, -.5, .5, -1, 1, -2, 2])
            if (end + offset > 0 && end + offset <= image.naturalHeight) bottoms.push(end + offset);
      }
    }
    if (error) throw error;
    return null;
  } finally { canvas.width = 0; }
}

/** 战斗辅助桥接入口只提取专用码，认证解密仍由跟打端负责；不接受远程图片地址。 */
export async function readRecorderFollowImage(dataURL: string): Promise<string | null> {
  if (!/^data:image\/(png|jpeg|webp);base64,/.test(dataURL)) throw new Error('请选择本地操作表图片');
  if (dataURL.length > 17 * 1024 * 1024) throw new Error('图片不能超过12MB');
  const image = new Image();
  await new Promise<void>((resolve, reject) => {
    image.onload = () => resolve(); image.onerror = () => reject(new Error('无法读取操作表图片')); image.src = dataURL;
  });
  if (!image.naturalWidth || image.naturalWidth * image.naturalHeight > 50_000_000) throw new Error('图片尺寸过大');
  const bytes = await readRecorderFollowTextureImage(image);
  return bytes ? recorderAutoFollowCodeFromBytes(bytes) : null;
}

