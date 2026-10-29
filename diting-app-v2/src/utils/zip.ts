export interface ZipEntry {
  name: string
  data: Uint8Array
}

let crcTable: Uint32Array | null = null

function getCrcTable(): Uint32Array {
  if (crcTable) {
    return crcTable
  }
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let value = n
    for (let k = 0; k < 8; k++) {
      value = value & 1 ? 0xEDB88320 ^ (value >>> 1) : value >>> 1
    }
    table[n] = value >>> 0
  }
  crcTable = table
  return table
}

function crc32(data: Uint8Array): number {
  const table = getCrcTable()
  let value = 0xFFFFFFFF
  for (let i = 0; i < data.length; i++) {
    value = table[(value ^ data[i]) & 0xFF] ^ (value >>> 8)
  }
  return (value ^ 0xFFFFFFFF) >>> 0
}

function u16(value: number): Uint8Array {
  const bytes = new Uint8Array(2)
  new DataView(bytes.buffer).setUint16(0, value & 0xFFFF, true)
  return bytes
}

function u32(value: number): Uint8Array {
  const bytes = new Uint8Array(4)
  new DataView(bytes.buffer).setUint32(0, value >>> 0, true)
  return bytes
}

/**
 * 小程序运行环境没有 TextEncoder（真机直接 ReferenceError），这里手写 UTF-8 编码。
 * 支持代理对，保证 emoji 等扩展字符也能写进文件名。
 */
function utf8Encode(text: string): Uint8Array {
  const bytes: number[] = []
  for (let i = 0; i < text.length; i++) {
    let code = text.charCodeAt(i)
    if (code >= 0xD800 && code <= 0xDBFF && i + 1 < text.length) {
      const next = text.charCodeAt(i + 1)
      if (next >= 0xDC00 && next <= 0xDFFF) {
        code = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00)
        i++
      }
    }
    if (code < 0x80) {
      bytes.push(code)
    }
    else if (code < 0x800) {
      bytes.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F))
    }
    else if (code < 0x10000) {
      bytes.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F))
    }
    else {
      bytes.push(
        0xF0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3F),
        0x80 | ((code >> 6) & 0x3F),
        0x80 | (code & 0x3F),
      )
    }
  }
  return new Uint8Array(bytes)
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const merged = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    merged.set(part, offset)
    offset += part.length
  }
  return merged
}

/**
 * 生成 ZIP（仅 store，不压缩）：音频/图片本身已是压缩格式，再 deflate 收益很低。
 * 文件名按 UTF-8 写入并置 general purpose bit 11，保证小程序端中文名可解压。
 */
export function createZip(entries: ZipEntry[]): Uint8Array {
  const files: Uint8Array[] = []
  const central: Uint8Array[] = []
  const now = new Date()
  const dosTime = ((now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1)) & 0xFFFF
  const dosDate = (((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()) & 0xFFFF
  let offset = 0
  let count = 0
  for (const entry of entries) {
    const name = utf8Encode(entry.name)
    const data = entry.data
    const crc = crc32(data)
    const local = concat([
      u32(0x04034B50), u16(20), u16(0x0800), u16(0), u16(dosTime), u16(dosDate),
      u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), name,
    ])
    files.push(local, data)
    central.push(concat([
      u32(0x02014B50), u16(20), u16(20), u16(0x0800), u16(0), u16(dosTime), u16(dosDate),
      u32(crc), u32(data.length), u32(data.length), u16(name.length), u16(0), u16(0),
      u16(0), u16(0), u32(0), u32(offset), name,
    ]))
    offset += local.length + data.length
    count++
  }
  const centralDir = concat(central)
  const end = concat([
    u32(0x06054B50), u16(0), u16(0), u16(count), u16(count),
    u32(centralDir.length), u32(offset), u16(0),
  ])
  return concat([...files, centralDir, end])
}
