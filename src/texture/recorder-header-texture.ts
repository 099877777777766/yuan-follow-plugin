import { crc32 } from './crc32';
import { colorChannels, defaultPalette, validThemeColor } from './theme';
import { protectWatermark, protectedLength, restoreWatermark } from './watermark-ecc';

// 表头装饰带：每个8×8纹理单元使用四个低频分量承载一个字节，没有二维码定位方块。
// 独立保护数据头和正文，正文容量决定带高，正文表格区域不绘制这些纹理。
export const TEXTURE_WIDTH = 800;
export type TextureFormat = 'header-v1' | 'follow-v1' | 'follow-v2';
// 跟打纹理使用独立魔数、字节掩码和频率顺序；原文字图片始终使用默认格式。
const formats = {
  'header-v1': { width: 800, headerRows: 1, copies: 1, amplitude: 22, magic: [89,82,84,69,88,48,48,49], maxHeight: 1024, order: [0,1,2,3], mask: (i: number) => ((i * 73 + 19) ^ ((i >>> 3) * 29)) & 255 },
  'follow-v1': { width: 800, headerRows: 1, copies: 1, amplitude: 22, magic: [89,82,70,84,88,48,48,49], maxHeight: 8192, order: [2,0,3,1], mask: (i: number) => ((i * 151 + 97) ^ ((i >>> 2) * 53)) & 255 },
  // v2 减少每行单元数、提高低频振幅、双份保护头；缩图后的纹理仍有足够像素。
  'follow-v2': { width: 480, headerRows: 3, copies: 2, amplitude: 40, magic: [89,82,70,84,88,48,48,50], maxHeight: 16384, order: [2,0,3,1], mask: (i: number) => ((i * 151 + 97) ^ ((i >>> 2) * 53)) & 255 }
};
export function textureGeometry(format: TextureFormat) {
  return { width: formats[format].width, headerHeight: formats[format].headerRows * 8 };
}
const BASE = [188, 204, 196], GRAY = [0, 1, 3, 2];
const waves = [[1, 0], [0, 1], [1, 1], [2, 0]].map(([u, v]) => Float64Array.from({ length: 64 }, (_, i) =>
  (u && v ? .25 : 1 / (4 * Math.SQRT2)) * Math.cos((2 * (i % 8) + 1) * u * Math.PI / 16) * Math.cos((2 * Math.floor(i / 8) + 1) * v * Math.PI / 16)));
export interface TexturePixels { width: number; height: number; data: Uint8ClampedArray }
// 将相邻受损单元分散到不同 RS 分组，避免一条 JPEG 压缩色带耗尽同一组纠错量。
function bodyOrder(length: number, format: TextureFormat): Uint32Array {
  const count = protectedLength(length);
  if (format !== 'follow-v2') return Uint32Array.from({ length: count }, (_, i) => i);
  const blocks = Math.ceil(length / 191), order = new Uint32Array(count); let at = 0;
  for (let col = 0; col < 255; col++) for (let block = 0; block < blocks; block++) {
    const index = block * 255 + col;
    if (index < count) order[at++] = index;
  }
  return order;
}
export function headerTextureHeight(length: number, format: TextureFormat = 'header-v1'): number {
  const config = formats[format];
  const height = (config.headerRows + Math.ceil(protectedLength(length) / (config.width / 8))) * 8;
  if (!Number.isInteger(length) || length <= 0 || height > formats[format].maxHeight) throw new Error('图像编码容量不足，请拆分操作表');
  return height;
}
export function createHeaderTexture(payload: Uint8Array, color: string = defaultPalette.accent, format: TextureFormat = 'header-v1'): TexturePixels {
  const { magic, mask, order, width, headerRows, amplitude, copies } = formats[format];
  const cols = width / 8, headerCells = cols * headerRows;
  // 仅改变直流底色，保留数据振幅；限制通道范围，避免波峰裁切影响解码。
  if (!validThemeColor(color)) throw new Error('纹理主题色无效');
  const base = format === 'follow-v2' ? colorChannels(color).map(value => 104 + value * .18)
    : color.toLowerCase() === defaultPalette.accent ? BASE : colorChannels(color).map(value => 120 + value * .3);
  const height = headerTextureHeight(payload.length, format), data = new Uint8ClampedArray(width * height * 4);
  const header = new Uint8Array(16); header.set(magic);
  const view = new DataView(header.buffer); view.setUint32(8, payload.length); view.setUint32(12, crc32(payload));
  const head = protectWatermark(header), body = protectWatermark(payload), positions = bodyOrder(payload.length, format);
  for (let cell = 0; cell < cols * height / 8; cell++) {
    const value = cell < headerCells ? (cell < head.length * copies ? head[cell % head.length] : mask(cell + 127))
      : body[positions[cell - headerCells]] ?? mask(cell + 257);
    const byte = value ^ mask(cell);
    const values = waves.map((_, c) => (GRAY.indexOf((byte >> (c * 2)) & 3) * 2 - 3) * amplitude);
    for (let i = 0; i < 64; i++) {
      const offset = ((Math.floor(cell / cols) * 8 + Math.floor(i / 8)) * width + cell % cols * 8 + i % 8) * 4;
      let delta = 0; for (let c = 0; c < 4; c++) delta += values[c] * waves[order[c]][i];
      for (let c = 0; c < 3; c++) data[offset + c] = base[c] + delta;
      data[offset + 3] = 255;
    }
  }
  return { width, height, data };
}
function readCoefficients(pixels: TexturePixels, cell: number, format: TextureFormat) {
  const cols = formats[format].width / 8;
  return formats[format].order.map(index => {
    const wave = waves[index];
    let value = 0;
    for (let i = 0; i < 64; i++) {
      const p = ((Math.floor(cell / cols) * 8 + Math.floor(i / 8)) * pixels.width + cell % cols * 8 + i % 8) * 4;
      value += (.299 * pixels.data[p] + .587 * pixels.data[p + 1] + .114 * pixels.data[p + 2]) * wave[i];
    }
    return value;
  });
}
function readByte(pixels: TexturePixels, cell: number, format: TextureFormat, gains = [1, 1, 1, 1]) {
  let byte = 0;
  readCoefficients(pixels, cell, format).forEach((value, c) => {
    const level = Math.max(0, Math.min(3, Math.round((value / gains[c] / formats[format].amplitude + 3) / 2)));
    byte |= GRAY[level] << (c * 2);
  });
  return byte ^ formats[format].mask(cell);
}
function measureGains(pixels: TexturePixels, format: TextureFormat, known: number[], offset: number) {
  const { mask, amplitude } = formats[format], sums = [0, 0, 0, 0], squares = [0, 0, 0, 0];
  known.forEach((value, index) => {
    const cell = index + offset, measured = readCoefficients(pixels, cell, format), byte = value ^ mask(cell);
    for (let c = 0; c < 4; c++) {
      const target = (GRAY.indexOf((byte >> (c * 2)) & 3) * 2 - 3) * amplitude;
      sums[c] += target * measured[c]; squares[c] += target * target;
    }
  });
  return sums.map((sum, c) => Math.max(.25, Math.min(2, sum / squares[c])));
}
export function readTextureHeader(pixels: TexturePixels, format: TextureFormat = 'header-v1'): { length: number; crc: number; height: number; gains: number[] } | null {
  const { magic, mask, width, headerRows, copies } = formats[format];
  if (pixels.width !== width || pixels.height < headerRows * 8 || pixels.data.length !== pixels.width * pixels.height * 4) return null;
  let header: Uint8Array | undefined, offset = 0;
  // 固定导频先估计多次缩放的模糊衰减，避免必须先读出数据头才能校准的循环依赖。
  const calibrations = [[1, 1, 1, 1]];
  if (format === 'follow-v2') calibrations.unshift(measureGains(pixels, format,
    Array.from({ length: width / 8 * headerRows - copies * 80 }, (_, i) => mask(i + copies * 80 + 127)), copies * 80));
  search: for (const gains of calibrations) for (let copy = 0; copy < copies; copy++) {
    try {
      const decoded = restoreWatermark(Uint8Array.from({ length: 80 }, (_, i) => readByte(pixels, copy * 80 + i, format, gains)), 16);
      if (magic.every((v, i) => decoded[i] === v)) { header = decoded; offset = copy * 80; break search; }
    } catch { /* 独立副本可恢复被局部污染的数据头。 */ }
  }
  if (!header) return null;
  const view = new DataView(header.buffer), length = view.getUint32(8);
  // 用已纠正的数据头估计缩放对各频率的衰减，正文据此恢复判定区间。
  const gains = measureGains(pixels, format, Array.from(protectWatermark(header)), offset);
  return { length, crc: view.getUint32(12), height: headerTextureHeight(length, format), gains };
}
export function extractHeaderTexture(pixels: TexturePixels, format: TextureFormat = 'header-v1'): Uint8Array | null {
  const header = readTextureHeader(pixels, format); if (!header) return null;
  if (pixels.height < header.height) throw new Error('表头纹理不完整，请使用原图');
  const config = formats[format], encoded = new Uint8Array(protectedLength(header.length));
  bodyOrder(header.length, format).forEach((target, i) => { encoded[target] = readByte(pixels, config.width / 8 * config.headerRows + i, format, header.gains); });
  const payload = restoreWatermark(encoded, header.length);
  if (crc32(payload) !== header.crc) throw new Error('表头纹理校验失败，请使用原图');
  return payload;
}
