// Minimal PNG codec for the E2E suite (node:zlib only, no dependencies).
//
//   encodePng(width, height, rgb)  -> Buffer   8-bit RGB, filter 0
//   decodePng(buffer)              -> {width, height, channels, data}
//                                     8-bit greyscale / RGB / palette / grey+alpha /
//                                     RGBA, every scanline filter, non-interlaced
//   diffPixels(a, b)               -> {differing, firstDiff}  RGB compare of two decoded images
//
// Enough to write photos for upload and to compare the backend's page PNGs
// pixel by pixel (the backend renders with Java ImageIO: 8-bit RGB).
import zlib from 'node:zlib';

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const typeAndData = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeAndData));
  return Buffer.concat([length, typeAndData, crc]);
}

/** @param {number} width @param {number} height @param {Uint8Array} rgb width*height*3 bytes */
export function encodePng(width, height, rgb) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // colour type RGB
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (width * 3 + 1)] = 0;
    Buffer.from(rgb.buffer, rgb.byteOffset + y * width * 3, width * 3).copy(raw, y * (width * 3 + 1) + 1);
  }
  return Buffer.concat([SIGNATURE, chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

/** A width x height RGB raster painted by `paint(x, y) -> [r, g, b]`. */
export function raster(width, height, paint) {
  const rgb = new Uint8Array(width * height * 3);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const [r, g, b] = paint(x, y);
      const i = (y * width + x) * 3;
      rgb[i] = r;
      rgb[i + 1] = g;
      rgb[i + 2] = b;
    }
  }
  return rgb;
}

/** A small, recognisable photo-like PNG (gradient + disc) in the given tint. */
export function photoPng(width, height, [r0, g0, b0]) {
  return encodePng(
    width,
    height,
    raster(width, height, (x, y) => {
      const dx = x - width / 2;
      const dy = y - height / 3;
      if (dx * dx + dy * dy < (width / 4) ** 2) return [240, 220, 200];
      const t = (x + y) / (width + height);
      return [Math.round(r0 * (1 - t) + 200 * t), Math.round(g0 * (1 - t) + 170 * t), Math.round(b0 * (1 - t) + 120 * t)];
    })
  );
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/** Decodes an 8-bit, non-interlaced PNG into RGB(A) bytes (palette and grey expanded to RGB). */
export function decodePng(buffer) {
  if (!Buffer.isBuffer(buffer)) buffer = Buffer.from(buffer);
  if (!buffer.subarray(0, 8).equals(SIGNATURE)) throw new Error('not a PNG');
  let offset = 8;
  let width;
  let height;
  let depth;
  let colourType;
  let interlace;
  let palette = null;
  const idat = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('latin1', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    offset += 12 + length;
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colourType = data[9];
      interlace = data[12];
    } else if (type === 'PLTE') {
      palette = data;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
  }
  if (depth !== 8 || interlace !== 0) throw new Error(`unsupported PNG (depth ${depth}, interlace ${interlace})`);
  const bpp = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colourType];
  if (!bpp) throw new Error(`unsupported PNG colour type ${colourType}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const pixels = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const out = y * stride;
    for (let x = 0; x < stride; x += 1) {
      const a = x >= bpp ? pixels[out + x - bpp] : 0;
      const b = y > 0 ? pixels[out - stride + x] : 0;
      const c = x >= bpp && y > 0 ? pixels[out - stride + x - bpp] : 0;
      let value = line[x];
      if (filter === 1) value += a;
      else if (filter === 2) value += b;
      else if (filter === 3) value += (a + b) >> 1;
      else if (filter === 4) value += paeth(a, b, c);
      pixels[out + x] = value & 0xff;
    }
  }
  // Normalise to RGB (alpha kept as a 4th channel when present).
  const channels = colourType === 4 || colourType === 6 ? 4 : 3;
  const data = Buffer.alloc(width * height * channels);
  for (let i = 0; i < width * height; i += 1) {
    let r;
    let g;
    let b;
    let alpha = 255;
    if (colourType === 0) {
      r = g = b = pixels[i];
    } else if (colourType === 4) {
      r = g = b = pixels[i * 2];
      alpha = pixels[i * 2 + 1];
    } else if (colourType === 3) {
      [r, g, b] = [palette[pixels[i] * 3], palette[pixels[i] * 3 + 1], palette[pixels[i] * 3 + 2]];
    } else {
      [r, g, b] = [pixels[i * bpp], pixels[i * bpp + 1], pixels[i * bpp + 2]];
      if (colourType === 6) alpha = pixels[i * 4 + 3];
    }
    data[i * channels] = r;
    data[i * channels + 1] = g;
    data[i * channels + 2] = b;
    if (channels === 4) data[i * channels + 3] = alpha;
  }
  return { width, height, channels, data };
}

/** Counts the pixels whose RGB differs between two decoded images of the same size. */
export function diffPixels(a, b) {
  if (a.width !== b.width || a.height !== b.height) {
    throw new Error(`image sizes differ: ${a.width}x${a.height} vs ${b.width}x${b.height}`);
  }
  let differing = 0;
  let firstDiff = null;
  for (let y = 0; y < a.height; y += 1) {
    for (let x = 0; x < a.width; x += 1) {
      const i = (y * a.width + x) * a.channels;
      const j = (y * b.width + x) * b.channels;
      if (a.data[i] !== b.data[j] || a.data[i + 1] !== b.data[j + 1] || a.data[i + 2] !== b.data[j + 2]) {
        differing += 1;
        if (!firstDiff) firstDiff = { x, y };
      }
    }
  }
  return { differing, firstDiff };
}

/** Counts the pixels inside the top-left fraction box {fx, fy, fw, fh} that are not near-white. */
export function inkInBox(image, { fx, fy, fw, fh }, threshold = 200) {
  const x0 = Math.floor(fx * image.width);
  const y0 = Math.floor(fy * image.height);
  const x1 = Math.ceil((fx + fw) * image.width);
  const y1 = Math.ceil((fy + fh) * image.height);
  let ink = 0;
  for (let y = Math.max(0, y0); y < Math.min(image.height, y1); y += 1) {
    for (let x = Math.max(0, x0); x < Math.min(image.width, x1); x += 1) {
      const i = (y * image.width + x) * image.channels;
      if (image.data[i] < threshold || image.data[i + 1] < threshold || image.data[i + 2] < threshold) ink += 1;
    }
  }
  return ink;
}
