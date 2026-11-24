import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const defaultMetadata = fileURLToPath(new URL('../android/app/build/outputs/apk/release/output-metadata.json', import.meta.url));
const metadataPath = path.resolve(process.argv[2] ?? defaultMetadata);
const metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8'));
const [apk] = metadata.elements ?? [];
const channel = metadata.applicationId === 'cn.codexremote.app' && metadata.variantName === 'release' ? 'stable'
    : metadata.applicationId === 'cn.codexremote.app.preview' && ['debug', 'personal'].includes(metadata.variantName) ? 'preview' : null;
if (!channel ||
    metadata.elements?.length !== 1 || apk.filters?.length !== 0 ||
    !Number.isInteger(apk.versionCode) || apk.versionCode <= 0 || apk.versionCode > 2147483647 ||
    !/^[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}$/.test(apk.versionName) ||
    !/^[a-zA-Z0-9_-]+\.apk$/.test(apk.outputFile)) {
    throw new Error('Expected output metadata from a single codex suixing APK.');
}
const source = path.join(path.dirname(metadataPath), apk.outputFile);
const size = fs.statSync(source).size;
if (size <= 0 || size > 128 * 1024 * 1024) throw new Error('APK size is outside the bridge update limit.');
const localData = process.env.LOCALAPPDATA;
if (!localData) throw new Error('Run this command on the Windows bridge computer.');
const directory = path.join(localData, 'CodexRemote', 'android-updates', channel);
const target = path.join(directory, `codex-suixing-${apk.versionCode}-${apk.versionName}.apk`);
const temp = path.join(directory, `${randomUUID()}.tmp`);
fs.mkdirSync(directory, { recursive: true });
try {
    fs.copyFileSync(source, temp, fs.constants.COPYFILE_EXCL);
    fs.renameSync(temp, target);
} finally {
    fs.rmSync(temp, { force: true });
}
console.log(`Android ${apk.versionName} published to ${target}`);
