import { createHeaderTexture, extractHeaderTexture, type TexturePixels } from './recorder-header-texture';

export const FOLLOW_TEXTURE_FORMATS = ['follow-v2', 'follow-v1'] as const;
type FollowTextureFormat = typeof FOLLOW_TEXTURE_FORMATS[number];

/** 独立跟打像素协议：不同魔数/掩码/频率映射，数据头置于图片最底端，方便长表定位。 */
export function reverseTextureRows(pixels: TexturePixels): TexturePixels {
  const data = new Uint8ClampedArray(pixels.data.length), stride = pixels.width * 4;
  for (let row = 0; row < pixels.height; row++) data.set(pixels.data.subarray(row * stride, (row + 1) * stride), (pixels.height - row - 1) * stride);
  // ImageData 的宽高是原型 getter，不能通过对象展开复制，否则浏览器读取会丢失尺寸。
  return { width: pixels.width, height: pixels.height, data };
}
export function createRecorderFollowTexture(payload: Uint8Array, color?: string): TexturePixels {
  return reverseTextureRows(createHeaderTexture(payload, color, 'follow-v2'));
}
export function extractRecorderFollowTexture(pixels: TexturePixels, format?: FollowTextureFormat): Uint8Array | null {
  const reversed = reverseTextureRows(pixels);
  if (format) return extractHeaderTexture(reversed, format);
  for (const candidate of FOLLOW_TEXTURE_FORMATS) {
    const payload = extractHeaderTexture(reversed, candidate);
    if (payload) return payload;
  }
  return null;
}
