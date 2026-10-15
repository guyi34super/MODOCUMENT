// Minimal PDF reader for E2E assertions (node:zlib only): page count and
// the text each page shows. It understands what PDFBox writes — classic and
// compressed object streams, FlateDecode content, simple fonts (WinAnsi
// bytes) and Type0/Identity-H fonts through their ToUnicode CMap, form
// XObjects — which is all the specs need to check a generated document.
// Not a general PDF parser: no encryption, no LZW/ASCII85, no xref repair.
import zlib from 'node:zlib';

// ------------------------------------------------------------ lexer/parser

const WHITESPACE = new Set([0x00, 0x09, 0x0a, 0x0c, 0x0d, 0x20]);
const DELIMITERS = new Set([0x28, 0x29, 0x3c, 0x3e, 0x5b, 0x5d, 0x7b, 0x7d, 0x2f, 0x25]);

class Ref {
  constructor(num) {
    this.num = num;
  }
}
class Name {
  constructor(value) {
    this.value = value;
  }
}
class Op {
  constructor(value) {
    this.value = value;
  }
}

/** Tokenises and parses PDF objects from a Buffer (objects and content streams alike). */
class Lexer {
  constructor(buf, pos = 0) {
    this.buf = buf;
    this.pos = pos;
  }

  skip() {
    const { buf } = this;
    while (this.pos < buf.length) {
      const c = buf[this.pos];
      if (WHITESPACE.has(c)) this.pos += 1;
      else if (c === 0x25) {
        while (this.pos < buf.length && buf[this.pos] !== 0x0a && buf[this.pos] !== 0x0d) this.pos += 1;
      } else break;
    }
  }

  regular() {
    const start = this.pos;
    while (this.pos < this.buf.length && !WHITESPACE.has(this.buf[this.pos]) && !DELIMITERS.has(this.buf[this.pos])) {
      this.pos += 1;
    }
    return this.buf.toString('latin1', start, this.pos);
  }

  literalString() {
    const { buf } = this;
    this.pos += 1;
    const out = [];
    let depth = 1;
    while (this.pos < buf.length) {
      const c = buf[this.pos];
      this.pos += 1;
      if (c === 0x5c) {
        const e = buf[this.pos];
        this.pos += 1;
        const simple = { 0x6e: 0x0a, 0x72: 0x0d, 0x74: 0x09, 0x62: 0x08, 0x66: 0x0c, 0x28: 0x28, 0x29: 0x29, 0x5c: 0x5c };
        if (e in simple) out.push(simple[e]);
        else if (e >= 0x30 && e <= 0x37) {
          let octal = e - 0x30;
          for (let k = 0; k < 2 && buf[this.pos] >= 0x30 && buf[this.pos] <= 0x37; k += 1) {
            octal = octal * 8 + (buf[this.pos] - 0x30);
            this.pos += 1;
          }
          out.push(octal & 0xff);
        } else if (e === 0x0d) {
          if (buf[this.pos] === 0x0a) this.pos += 1;
        } else if (e !== 0x0a) out.push(e);
      } else if (c === 0x28) {
        depth += 1;
        out.push(c);
      } else if (c === 0x29) {
        depth -= 1;
        if (depth === 0) break;
        out.push(c);
      } else out.push(c);
    }
    return Buffer.from(out);
  }

  hexString() {
    this.pos += 1;
    const end = this.buf.indexOf(0x3e, this.pos);
    let hex = this.buf.toString('latin1', this.pos, end).replace(/[^0-9a-fA-F]/g, '');
    this.pos = end + 1;
    if (hex.length % 2) hex += '0';
    return Buffer.from(hex, 'hex');
  }

  /** The next object, an Op for a bare keyword, or undefined at the end. */
  next() {
    this.skip();
    const { buf } = this;
    if (this.pos >= buf.length) return undefined;
    const c = buf[this.pos];
    if (c === 0x2f) {
      this.pos += 1;
      return new Name(this.regular().replace(/#([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16))));
    }
    if (c === 0x28) return this.literalString();
    if (c === 0x3c) {
      if (buf[this.pos + 1] === 0x3c) {
        this.pos += 2;
        const dict = {};
        for (;;) {
          this.skip();
          if (buf[this.pos] === 0x3e && buf[this.pos + 1] === 0x3e) {
            this.pos += 2;
            return dict;
          }
          const key = this.next();
          if (!(key instanceof Name)) return dict;
          dict[key.value] = this.value();
        }
      }
      return this.hexString();
    }
    if (c === 0x5b) {
      this.pos += 1;
      const array = [];
      for (;;) {
        this.skip();
        if (buf[this.pos] === 0x5d || this.pos >= buf.length) {
          this.pos += 1;
          return array;
        }
        array.push(this.value());
      }
    }
    if (c === 0x5d || c === 0x29 || c === 0x3e || c === 0x7b || c === 0x7d) {
      this.pos += 1;
      return new Op(String.fromCharCode(c));
    }
    const word = this.regular();
    if (/^[+-]?(\d+\.?\d*|\.\d+)$/.test(word)) return Number(word);
    if (word === 'true') return true;
    if (word === 'false') return false;
    if (word === 'null') return null;
    return new Op(word);
  }

  /** A value; `N G R` becomes a Ref. */
  value() {
    const first = this.next();
    if (typeof first === 'number' && Number.isInteger(first)) {
      const save = this.pos;
      const second = this.next();
      if (typeof second === 'number' && Number.isInteger(second)) {
        const third = this.next();
        if (third instanceof Op && third.value === 'R') return new Ref(first);
      }
      this.pos = save;
    }
    return first;
  }
}

// ------------------------------------------------------------ document

function decodeStream(dict, raw) {
  const filters = [].concat(dict.Filter ?? []).map((f) => f.value);
  let data = raw;
  for (const filter of filters) {
    if (filter !== 'FlateDecode') throw new Error(`unsupported filter ${filter}`);
    data = zlib.inflateSync(data, { finishFlush: zlib.constants.Z_SYNC_FLUSH });
  }
  const parms = dict.DecodeParms;
  if (parms && !Array.isArray(parms) && parms.Predictor >= 10) {
    const columns = parms.Columns ?? 1;
    const stride = columns + 1;
    const rows = Math.floor(data.length / stride);
    const out = Buffer.alloc(rows * columns);
    for (let r = 0; r < rows; r += 1) {
      const filter = data[r * stride];
      for (let x = 0; x < columns; x += 1) {
        const up = r > 0 ? out[(r - 1) * columns + x] : 0;
        const left = x > 0 ? out[r * columns + x - 1] : 0;
        let v = data[r * stride + 1 + x];
        if (filter === 1) v += left;
        else if (filter === 2) v += up;
        else if (filter === 3) v += (left + up) >> 1;
        out[r * columns + x] = v & 0xff;
      }
    }
    data = out;
  }
  return data;
}

/** Parses every object of the file (later definitions win), expanding object streams. */
export function readPdf(bytes) {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  const objects = new Map();
  const header = /(\d+)\s+(\d+)\s+obj\b/g;
  const text = buf.toString('latin1');
  let match;
  while ((match = header.exec(text))) {
    const num = Number(match[1]);
    const lexer = new Lexer(buf, match.index + match[0].length);
    let value;
    try {
      value = lexer.value();
    } catch {
      continue;
    }
    const entry = { value, stream: null };
    lexer.skip();
    if (buf.toString('latin1', lexer.pos, lexer.pos + 6) === 'stream') {
      let start = lexer.pos + 6;
      if (buf[start] === 0x0d) start += 1;
      if (buf[start] === 0x0a) start += 1;
      const declared = value && typeof value.Length === 'number' ? value.Length : -1;
      let end =
        declared >= 0 && buf.toString('latin1', start + declared, start + declared + 12).includes('endstream')
          ? start + declared
          : text.indexOf('endstream', start);
      entry.raw = buf.subarray(start, end);
      end = text.indexOf('endstream', end) + 9;
      header.lastIndex = end;
    } else {
      header.lastIndex = lexer.pos;
    }
    objects.set(num, entry);
  }

  const doc = {
    objects,
    resolve(value) {
      let v = value;
      for (let i = 0; i < 32 && v instanceof Ref; i += 1) v = objects.get(v.num)?.value;
      return v;
    },
    stream(value) {
      const entry = value instanceof Ref ? objects.get(value.num) : null;
      if (!entry?.raw) return null;
      if (!entry.stream) entry.stream = decodeStream(entry.value, entry.raw);
      return entry.stream;
    },
  };

  // Object streams: their members are plain objects once inflated.
  for (const [num, entry] of [...objects]) {
    if (entry.value?.Type?.value !== 'ObjStm' || !entry.raw) continue;
    const data = doc.stream(new Ref(num));
    const lexer = new Lexer(data);
    const pairs = [];
    for (let i = 0; i < entry.value.N; i += 1) pairs.push([lexer.next(), lexer.next()]);
    for (const [member, offset] of pairs) {
      if (objects.has(member) && objects.get(member).raw) continue;
      const memberLexer = new Lexer(data, entry.value.First + offset);
      objects.set(member, { value: memberLexer.value(), stream: null });
    }
  }
  return doc;
}

/** The page dictionaries in order, each with its inherited resources. */
function pages(doc) {
  const roots = [...doc.objects.values()].filter((e) => e.value?.Type?.value === 'Catalog');
  const catalog = roots[roots.length - 1]?.value;
  const out = [];
  const walk = (node, inherited) => {
    const dict = doc.resolve(node);
    if (!dict) return;
    const resources = dict.Resources ?? inherited;
    if (dict.Type?.value === 'Pages' || Array.isArray(dict.Kids)) {
      for (const kid of doc.resolve(dict.Kids) ?? []) walk(kid, resources);
    } else {
      out.push({ dict, resources });
    }
  };
  walk(catalog?.Pages, null);
  return out;
}

export function pageCount(bytes) {
  return pages(readPdf(bytes)).length;
}

// ------------------------------------------------------------ text

function parseCMap(data) {
  const map = new Map();
  let codeLength = 1;
  const text = data.toString('latin1');
  const range = /begincodespacerange([\s\S]*?)endcodespacerange/.exec(text);
  if (range) {
    const first = /<([0-9a-fA-F]+)>/.exec(range[1]);
    if (first) codeLength = first[1].length / 2;
  }
  const unicode = (hex) => {
    const bytes = Buffer.from(hex, 'hex');
    let s = '';
    for (let i = 0; i + 1 < bytes.length; i += 2) s += String.fromCharCode(bytes.readUInt16BE(i));
    return s;
  };
  for (const block of text.matchAll(/beginbfchar([\s\S]*?)endbfchar/g)) {
    for (const m of block[1].matchAll(/<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>/g)) {
      map.set(parseInt(m[1], 16), unicode(m[2]));
    }
  }
  for (const block of text.matchAll(/beginbfrange([\s\S]*?)endbfrange/g)) {
    for (const m of block[1].matchAll(/<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*(<[0-9a-fA-F]+>|\[[^\]]*\])/g)) {
      const lo = parseInt(m[1], 16);
      const hi = parseInt(m[2], 16);
      if (m[3].startsWith('[')) {
        [...m[3].matchAll(/<([0-9a-fA-F]+)>/g)].forEach((d, i) => map.set(lo + i, unicode(d[1])));
      } else {
        const base = Buffer.from(m[3].slice(1, -1), 'hex');
        for (let code = lo; code <= hi; code += 1) {
          const dst = Buffer.from(base);
          dst.writeUInt16BE(dst.readUInt16BE(dst.length - 2) + (code - lo), dst.length - 2);
          map.set(code, unicode(dst.toString('hex')));
        }
      }
    }
  }
  return { map, codeLength };
}

function fontDecoder(doc, fontRef) {
  const font = doc.resolve(fontRef) ?? {};
  const toUnicode = font.ToUnicode ? doc.stream(font.ToUnicode) : null;
  if (toUnicode) {
    const { map, codeLength } = parseCMap(toUnicode);
    const length = font.Subtype?.value === 'Type0' ? Math.max(2, codeLength) : codeLength;
    return (bytes) => {
      let s = '';
      for (let i = 0; i + length <= bytes.length; i += length) {
        const code = length === 2 ? bytes.readUInt16BE(i) : bytes[i];
        s += map.get(code) ?? '';
      }
      return s;
    };
  }
  if (font.Subtype?.value === 'Type0') return () => '';
  return (bytes) => bytes.toString('latin1');
}

/** The text shown by one content stream (and the form XObjects it draws), runs separated by spaces/newlines. */
function streamText(doc, data, resources, depth = 0) {
  const res = doc.resolve(resources) ?? {};
  const fonts = doc.resolve(res.Font) ?? {};
  const xobjects = doc.resolve(res.XObject) ?? {};
  const decoders = new Map();
  const decoder = (name) => {
    if (!decoders.has(name)) decoders.set(name, fontDecoder(doc, fonts[name]));
    return decoders.get(name);
  };
  const lexer = new Lexer(data);
  let operands = [];
  let font = null;
  let out = '';
  for (;;) {
    const token = lexer.value();
    if (token === undefined) break;
    if (!(token instanceof Op)) {
      operands.push(token);
      continue;
    }
    const op = token.value;
    if (op === 'BI') {
      const end = data.indexOf('EI', lexer.pos, 'latin1');
      lexer.pos = end < 0 ? data.length : end + 2;
    } else if (op === 'Tf') {
      font = operands[0] instanceof Name ? operands[0].value : font;
    } else if (op === 'Tj' || op === "'" || op === '"') {
      const s = operands[operands.length - 1];
      if (op !== 'Tj') out += '\n';
      if (Buffer.isBuffer(s) && font) out += decoder(font)(s);
    } else if (op === 'TJ') {
      for (const part of operands[0] ?? []) {
        if (Buffer.isBuffer(part) && font) out += decoder(font)(part);
        else if (typeof part === 'number' && part < -200) out += ' ';
      }
    } else if (op === 'Td' || op === 'TD' || op === 'T*' || op === 'Tm' || op === 'ET') {
      out += op === 'ET' ? '\n' : ' ';
    } else if (op === 'Do' && depth < 8) {
      const xobject = operands[0] instanceof Name ? xobjects[operands[0].value] : null;
      const dict = doc.resolve(xobject);
      if (dict?.Subtype?.value === 'Form') {
        const formData = doc.stream(xobject);
        if (formData) out += `\n${streamText(doc, formData, dict.Resources ?? resources, depth + 1)}\n`;
      }
    }
    operands = [];
  }
  return out;
}

function pageContent(doc, dict) {
  const contents = [].concat(dict.Contents ?? []).flatMap((c) => {
    const resolved = doc.resolve(c);
    return Array.isArray(resolved) ? resolved : [c];
  });
  return Buffer.concat(contents.map((c) => doc.stream(c) ?? Buffer.alloc(0)).flatMap((b) => [b, Buffer.from('\n')]));
}

/** The text of every page, in page order. */
export function pageTexts(bytes) {
  const doc = readPdf(bytes);
  return pages(doc).map(({ dict, resources }) => streamText(doc, pageContent(doc, dict), resources));
}

/** The decoded content stream of every page (latin1 text), in page order. */
export function pageContents(bytes) {
  const doc = readPdf(bytes);
  return pages(doc).map(({ dict }) => pageContent(doc, dict).toString('latin1'));
}

/** Whitespace-insensitive "does this text contain that value". */
export function containsText(haystack, needle) {
  const squash = (s) => String(s).replace(/\s+/g, '');
  return squash(haystack).includes(squash(needle));
}
