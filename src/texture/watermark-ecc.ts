// 缩图副本的字节纠错：GF(256)/0x11d，分组RS(255,191)，最多修复每组32个错误字节。
// 解码后仍必须验证外层CRC32，不能将无法校正的消息当成操作表。
const DATA = 191, PARITY = 64;
const exp = new Uint8Array(512), log = new Uint8Array(256);
let field = 1;
for (let i = 0; i < 255; i++) { exp[i] = field; log[field] = i; field <<= 1; if (field & 256) field ^= 0x11d; }
for (let i = 255; i < exp.length; i++) exp[i] = exp[i - 255];
const mul = (a: number, b: number) => a && b ? exp[log[a] + log[b]] : 0;
const inv = (a: number) => { if (!a) throw new Error('抗缩图纠错失败，请使用原图'); return exp[255 - log[a]]; };
const evaluate = (p: Uint8Array | number[], x: number) => { let value = 0; for (const c of p) value = mul(value, x) ^ c; return value; };
let generator = [1];
for (let i = 0; i < PARITY; i++) {
  const next = Array(generator.length + 1).fill(0);
  generator.forEach((c, j) => { next[j] ^= c; next[j + 1] ^= mul(c, exp[i]); }); generator = next;
}
export const protectedLength = (length: number) => length + Math.ceil(length / DATA) * PARITY;
export function protectWatermark(bytes: Uint8Array): Uint8Array {
  const result = new Uint8Array(protectedLength(bytes.length)); let at = 0;
  for (let start = 0; start < bytes.length; start += DATA) {
    const data = bytes.subarray(start, start + DATA), work = new Uint8Array(data.length + PARITY); work.set(data);
    for (let i = 0; i < data.length; i++) {
      const value = work[i]; if (value) for (let j = 0; j < generator.length; j++) work[i + j] ^= mul(value, generator[j]);
    }
    result.set(data, at); result.set(work.subarray(data.length), at + data.length); at += work.length;
  }
  return result;
}
function repair(received: Uint8Array): Uint8Array {
  const syndromes = Array.from({ length: PARITY }, (_, i) => evaluate(received, exp[i]));
  if (syndromes.every(v => !v)) return received;
  // Berlekamp-Massey确定错误位置多项式（系数按升幂存储）。
  let locator = [1], previous = [1], degree = 0, shift = 1, discrepancy = 1;
  for (let n = 0; n < PARITY; n++) {
    let d = syndromes[n];
    for (let i = 1; i <= degree; i++) d ^= mul(locator[i] || 0, syndromes[n - i]);
    if (!d) { shift++; continue; }
    const saved = locator.slice(), ratio = mul(d, inv(discrepancy));
    while (locator.length < previous.length + shift) locator.push(0);
    previous.forEach((v, i) => { locator[i + shift] ^= mul(ratio, v); });
    if (2 * degree <= n) { degree = n + 1 - degree; previous = saved; discrepancy = d; shift = 1; } else shift++;
  }
  const fail = () => new Error('抗缩图纠错失败，请使用原图');
  if (!degree || degree > PARITY / 2) throw fail();
  const positions: number[] = [], locations: number[] = [];
  for (let i = 0; i < received.length; i++) {
    const location = exp[received.length - 1 - i];
    if (evaluate(locator.slice().reverse(), inv(location)) === 0) { positions.push(i); locations.push(location); }
  }
  if (positions.length !== degree) throw fail();
  // 小型GF线性方程组求错误幅值，之后重新验证全部校验方程。
  const matrix = Array.from({ length: degree }, (_, row) => [
    ...locations.map(x => row === 0 ? 1 : exp[(log[x] * row) % 255]), syndromes[row]
  ]);
  for (let col = 0; col < degree; col++) {
    const pivot = matrix.findIndex((row, i) => i >= col && row[col] !== 0);
    if (pivot < 0) throw fail();
    [matrix[col], matrix[pivot]] = [matrix[pivot], matrix[col]];
    const factor = inv(matrix[col][col]);
    for (let j = col; j <= degree; j++) matrix[col][j] = mul(matrix[col][j], factor);
    for (let row = 0; row < degree; row++) if (row !== col) {
      const value = matrix[row][col];
      for (let j = col; j <= degree; j++) matrix[row][j] ^= mul(value, matrix[col][j]);
    }
  }
  const corrected = received.slice(); positions.forEach((p, i) => { corrected[p] ^= matrix[i][degree]; });
  if (Array.from({ length: PARITY }, (_, i) => evaluate(corrected, exp[i])).some(v => v !== 0)) throw fail();
  return corrected;
}
export function restoreWatermark(encoded: Uint8Array, length: number): Uint8Array {
  if (!Number.isInteger(length) || length <= 0 || length > 1024 * 1024 || encoded.length !== protectedLength(length))
    throw new Error('抗缩图纠错数据长度不正确');
  const result = new Uint8Array(length); let at = 0;
  for (let start = 0; start < length; start += DATA) {
    const count = Math.min(DATA, length - start), repaired = repair(encoded.subarray(at, at + count + PARITY));
    result.set(repaired.subarray(0, count), start); at += count + PARITY;
  }
  return result;
}
