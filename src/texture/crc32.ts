// 浏览器兼容导出标准 ZIP（原图不再次压缩）；Android 使用原生流式 ZIP，避免大文件穿过JS桥。
const table = Array.from({length:256},(_,n)=>{for(let k=0;k<8;k++)n=n&1?0xedb88320^(n>>>1):n>>>1;return n>>>0;});
export function crc32(bytes: Uint8Array) { let crc=0xffffffff;for(const b of bytes)crc=table[(crc^b)&255]^(crc>>>8);return (crc^0xffffffff)>>>0; }
