'use strict';

/**
 * Prove the escape audit actually fails on what it claims to catch.
 *
 * A check that only ever passes is indistinguishable from no check at all. This
 * reintroduces the exact `phone's` that stopped the build, runs the audit, and
 * requires it to report that and exit non-zero. It restores the file afterwards,
 * so it is safe to run against a working tree.
 */

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const FILE = path.resolve(__dirname, '..', 'app', 'src', 'main', 'res', 'values-en', 'strings.xml');
const AUDIT = path.join(__dirname, 'escape-audit.js');

const BS = String.fromCharCode(92);
const QUOTE = String.fromCharCode(39);

const original = fs.readFileSync(FILE, 'utf8');
const BROKEN = original.replace(`phone${BS}${QUOTE}s`, `phone${QUOTE}s`);

if (BROKEN === original) {
  console.error('could not introduce the regression: no escaped apostrophe found to revert');
  process.exit(1);
}

try {
  fs.writeFileSync(FILE, BROKEN);
  let out = '';
  let code = 0;
  try {
    out = execFileSync(process.execPath, [AUDIT], { encoding: 'utf8' });
  } catch (e) {
    out = e.stdout || '';
    code = e.status;
  }
  if (code === 0) {
    console.error('the audit passed a file that AAPT2 would reject');
    process.exit(1);
  }
  if (!/unescaped apostrophe/.test(out)) {
    console.error(`the audit failed for the wrong reason:\n${out}`);
    process.exit(1);
  }
  console.log('the audit rejects the unescaped apostrophe, as it must');
} finally {
  fs.writeFileSync(FILE, original);
}
