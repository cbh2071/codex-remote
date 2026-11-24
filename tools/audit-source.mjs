import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = execFileSync('git', ['ls-files', '-z'], {cwd:root, encoding:'utf8'}).split('\0').filter(Boolean);
if (files.length === 0) throw Error('No tracked or staged source files to audit');
const forbidden = /(^|\/)(?:bin|obj|build|dist|\.gradle|toolchains|signing|work|outputs)(?:\/|$)|(?:^|\/)local\.properties$|\.(?:dpapi|keystore|jks|pfx|p12|apk|aab|zip|log|pem|key)$/i;
const secrets = [
  /gh[pousr]_[A-Za-z0-9]{20,}/,
  /github_pat_[A-Za-z0-9_]{20,}/,
  /sk-(?:proj-)?[A-Za-z0-9_-]{24,}/,
  /-----BEGIN (?:(?:RSA|EC|OPENSSH) )?PRIVATE KEY-----/,
  /"(?:token|secret|password)"\s*:\s*"[A-Za-z0-9_+/=-]{32,}"/i,
  /https:\/\/[^\s/@]+:[^\s/@]+@github\.com/i
];
const issues = [];
// Match the two distributed native assets from leondgarse/tailscale_mobile v1.0.4.
const tailnetLibraries = new Map([
  ['android/app/src/main/jniLibs/arm64-v8a/libtailscale.so', '7aa06439520dc06b7b5b690f030f17e480ccbe6190381960b1c67774ce34bf3e'],
  ['android/app/src/main/jniLibs/x86_64/libtailscale.so', 'b6fbc73f257dd747e222d220824bb14d6653acdd834e2e50e4e93347e0c182dc'],
]);
for (const file of files) {
  if (forbidden.test(file) || /(^|\/)\.env(?:\.|$)/.test(file) && !file.endsWith('.env.example')) issues.push(`${file}: forbidden publish path`);
  const bytes = fs.readFileSync(path.join(root,file));
  if (tailnetLibraries.has(file)) {
    if (createHash('sha256').update(bytes).digest('hex') !== tailnetLibraries.get(file)) issues.push(`${file}: unexpected native library digest`);
    continue;
  }
  if (bytes.includes(0)) { issues.push(`${file}: binary source requires manual review`); continue; }
  const text = bytes.toString('utf8');
  for (const pattern of secrets) if (pattern.test(text)) issues.push(`${file}: possible credential; inspect locally without logging its value`);
}
if (issues.length) { console.error(issues.join('\n')); process.exitCode = 1; }
else console.log(`Source audit passed: ${files.length} tracked files; no prohibited artifacts or known credential patterns. This does not replace manual review.`);
