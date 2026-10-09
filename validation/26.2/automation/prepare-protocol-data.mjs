// Same pinned upstream sources and packaging approach as Aiyatsbus validation/folia.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const exec = promisify(execFile);
const driver = fileURLToPath(new URL('.', import.meta.url));
const sources = JSON.parse(await readFile(join(driver, 'artifacts.json')));
const cache = join(driver, 'cache');
await mkdir(cache, { recursive: true });
for (const [name, source] of Object.entries(sources)) {
  const path = join(cache, `${name}-${source.commit}.tar.gz`);
  let bytes = await readFile(path).catch(() => null);
  if (!bytes) {
    const response = await fetch(source.url, { signal: AbortSignal.timeout(120_000) });
    if (!response.ok) throw new Error(`Download failed: ${source.url} (${response.status})`);
    bytes = Buffer.from(await response.arrayBuffer());
  }
  if (createHash('sha256').update(bytes).digest('hex') !== source.sha256) throw new Error(`Checksum mismatch: ${name}`);
  await writeFile(path, bytes);
}
const workspace = await mkdtemp(join(cache, 'protocol-'));
try {
  const wrapper = join(workspace, 'wrapper');
  await mkdir(wrapper);
  await exec('tar', ['-xzf', join(cache, `wrapper-${sources.wrapper.commit}.tar.gz`), '--strip-components=1', '-C', wrapper]);
  await exec('tar', ['-xzf', join(cache, `data-${sources.data.commit}.tar.gz`), '--strip-components=1', '-C', join(wrapper, 'minecraft-data')]);
  await exec(process.execPath, ['bin/generate_data.js'], { cwd: wrapper });
  const paths = JSON.parse(await readFile(join(wrapper, 'minecraft-data/data/dataPaths.json')));
  if (paths.pc['26.2'].protocol !== 'pc/26.2') throw new Error('Expected native 26.2 protocol schema');
  await exec(process.execPath, ['-e', "const d=require('./')('26.2');if(d?.version.version!==776)process.exit(1)"], { cwd: wrapper });
  const manifestPath = join(wrapper, 'package.json');
  const manifest = JSON.parse(await readFile(manifestPath));
  // The generated runtime does not need upstream's TypeScript prepare script.
  delete manifest.scripts;
  delete manifest.devDependencies;
  manifest.featherMorphProtocolSources = sources;
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  const packed = await exec('npm', ['pack', '--ignore-scripts', '--json', '--pack-destination', workspace], { cwd: wrapper });
  await rename(join(workspace, JSON.parse(packed.stdout)[0].filename), join(cache, 'minecraft-data-26.2-68ea7b59-308134a8.tgz'));
  console.log('Prepared native Minecraft 26.2 / protocol 776. Run npm ci --ignore-scripts.');
} finally {
  await rm(workspace, { recursive: true, force: true });
}
