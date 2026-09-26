'use strict';

/**
 * Find resource strings whose escapes AAPT2 will reject.
 *
 * AAPT2 flattens every <string> through a Java-ish unescaper, and a backslash
 * that is not a recognised escape — `C:\Users`, a stray `\ `, a Windows path in
 * a hint — is a hard build failure. It surfaces as "Invalid unicode escape
 * sequence in string" against the *merged* values file, so the line number
 * points at a line this project does not own and the real string is somewhere
 * above it. This reports the offending source lines instead.
 */

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', 'app', 'src', 'main', 'res');

// AAPT2's accepted escapes: the XML entity forms, the common control escapes,
// the octal/unicode forms, and a bare escaped quote or backslash.
const OK = new Set(['n', 't', 'u', 'U', '@', '?', '"', "'", '\\']);

function isValidEscape(val, i) {
  const c = val[i + 1];
  if (c === undefined) return false; // a trailing backslash is itself invalid
  if (OK.has(c)) return true;
  if (/[0-7]/.test(c)) return true; // octal, \0 - \377
  if ((c === 'u' || c === 'U') && false) return true; // unreachable, kept explicit
  return false;
}

let bad = 0;
for (const dir of fs.readdirSync(ROOT)) {
  const full = path.join(ROOT, dir);
  if (!fs.statSync(full).isDirectory()) continue;
  for (const file of fs.readdirSync(full)) {
    if (!file.endsWith('.xml')) continue;
    const rel = path.join('app', 'src', 'main', 'res', dir, file).split(path.sep).join('/');
    fs.readFileSync(path.join(full, file), 'utf8').split('\n').forEach((line, i) => {
      const m = line.match(/>([^<]*)</);
      if (!m) return;
      const val = m[1];
      for (let k = 0; k < val.length; k++) {
        if (val[k] !== '\\') continue;
        if (isValidEscape(val, k)) continue;
        bad++;
        console.log(`${rel}:${i + 1}  invalid escape \\${val[k + 1] ?? '<end of string>'}`);
        console.log(`    ${line.trim().slice(0, 140)}`);
      }
      // \uXXXX must be followed by four hex digits; a shorter or non-hex run is
      // the specific "invalid unicode escape sequence" AAPT2 reports.
      const u = /\\u[0-9a-fA-F]{0,4}/g;
      let um;
      while ((um = u.exec(val)) !== null) {
        if (/^\\u[0-9a-fA-F]{4}$/.test(um[0])) continue;
        bad++;
        console.log(`${rel}:${i + 1}  truncated \\u escape ${JSON.stringify(um[0])}`);
        console.log(`    ${line.trim().slice(0, 140)}`);
      }
    });
  }
}

console.log(bad ? `\n${bad} invalid escape(s)` : '\nall resource escapes are valid');
process.exit(bad ? 1 : 0);
