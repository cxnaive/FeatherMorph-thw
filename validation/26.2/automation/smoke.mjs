// Isolated native-protocol smoke test, following Aiyatsbus's Folia validation pattern.
import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { appendFileSync, constants } from 'node:fs';
import { cp, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import mc from 'minecraft-protocol';
import minecraftData from 'minecraft-data';
import nbt from 'prismarine-nbt';
import YAML from 'yaml';

const exec = promisify(execFile);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const seed = resolve(process.env.FM_SERVER_SEED ?? join(root, '../thw_servers/26.2/test-server'));
const packetEvents = resolve(process.env.FM_PACKETEVENTS_JAR ?? join(seed, 'plugins/packetevents-spigot-2.14.0.jar'));
const port = Number(process.env.FM_TEST_PORT ?? 25627);
assert(Number.isInteger(port) && port > 1024 && port <= 65535, 'Invalid FM_TEST_PORT');
const difficulty = process.env.FM_TEST_DIFFICULTY ?? 'normal';
assert(['peaceful', 'normal'].includes(difficulty), 'FM_TEST_DIFFICULTY must be peaceful or normal');
const data = minecraftData('26.2');
assert.equal(data?.version.version, 776, 'Native 26.2 protocol data required');
const metadataTypes = data.protocol.types.entityMetadataEntry[1].find(field => field.name === 'type').type[1].mappings;
const run = await mkdtemp(join(tmpdir(), 'feathermorph-26.2-'));
const report = { status: 'RUNNING', directory: run, protocol: 776, difficulty, checks: [],
  notRun: ['rendered client visuals', 'client integration mod', 'locator connections between player disguises across regions',
    'third-party plugins', 'all skills and abilities'] };
console.log(`Evidence: ${run}`);
const record = event => appendFileSync(join(run, 'packets.jsonl'), JSON.stringify({ time: Date.now(), ...event }) + '\n');
const pass = (name, detail) => { report.checks.push({ name, status: 'PASS', detail }); console.log(`PASS ${name}`); };
const digest = async path => createHash('sha256').update(await readFile(path)).digest('hex');
let server;
const bots = [];
const abort = new AbortController();
const interrupted = () => abort.abort(new Error('Validation interrupted'));
process.once('SIGINT', interrupted);
process.once('SIGTERM', interrupted);

async function until(label, condition, timeout = 15_000, interruptible = true) {
  const deadline = performance.now() + timeout;
  while (true) {
    if (interruptible) abort.signal.throwIfAborted();
    const result = await condition();
    if (result) return result;
    if (performance.now() >= deadline) throw new Error(`Timed out: ${label}`);
    await delay(50);
  }
}

async function assertPortFree() {
  const socket = createServer();
  await new Promise((resolve, reject) => {
    socket.once('error', reject);
    socket.listen({ host: '127.0.0.1', port, exclusive: true }, resolve);
  });
  await new Promise((resolve, reject) => socket.close(error => error ? reject(error) : resolve()));
}

function startServer() {
  const child = spawn(process.env.FM_JAVA ?? 'java', ['-Xms512M', '-Xmx1G', '-XX:ActiveProcessorCount=2',
    '-Dfile.encoding=UTF-8', '-Duser.language=en', '-Duser.country=US', '-Dpacketevents.disable-update-checker=true',
    '-jar', 'server.jar', '--nogui'],
  { cwd: run, stdio: ['pipe', 'pipe', 'pipe'] });
  const state = { output: '', closed: false, exit: null, error: null };
  child.on('error', error => { state.error = error; });
  child.stdin.on('error', error => { state.error ??= error; });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
    state.output += chunk.toString();
    appendFileSync(join(run, 'server.log'), chunk);
  });
  child.once('close', (code, signal) => { state.closed = true; state.exit = { code, signal }; });
  const check = () => {
    if (state.error) throw state.error;
    if (state.closed) throw new Error(`Server exited early: ${JSON.stringify(state.exit)}`);
    if (state.output.includes('FM_PROBE_FAIL')) throw new Error('Runtime probe failed; see server.log');
  };
  return {
    state, check,
    command(line) {
      check();
      assert(!/[\r\n]/.test(line), 'Expected one console command');
      record({ console: line });
      child.stdin.write(line + '\n');
    },
    async stop() {
      let forced = false;
      if (!state.closed) {
        if (child.stdin.writable) child.stdin.write('stop\n');
        try { await until('normal server shutdown', () => state.closed, 60_000, false); }
        catch {
          forced = true;
          child.kill('SIGTERM');
          try { await until('server terminated', () => state.closed, 5000, false); }
          catch { child.kill('SIGKILL'); await until('server killed', () => state.closed, 5000, false); }
        }
      }
      return { ...state.exit, forced, error: state.error?.message };
    }
  };
}

function plainText(value) {
  const text = value?.type ? nbt.simplify(value) : value;
  if (typeof text === 'string') return text;
  if (Array.isArray(text)) return text.map(plainText).join('');
  return text && typeof text === 'object' ? plainText(text.text ?? text.translate ?? '') + plainText(text.extra ?? []) : '';
}

function connect(username) {
  const client = mc.createClient({ host: '127.0.0.1', port, username, version: '26.2', auth: 'offline',
    disableChatSigning: true, hideErrors: true, clientSettings: { locale: 'en_us', viewDistance: 2 } });
  const state = { position: null, ended: false, closing: false, error: null, events: [], messages: [] };
  const emit = (event, value) => {
    state.events.push({ event, value });
    record({ bot: username, event, value });
  };
  client.on('error', error => { state.error = error; emit('error', error.message); });
  client.on('end', reason => { state.ended = true; emit('end', reason); });
  client.on('kick_disconnect', packet => { state.error = new Error(`Kicked: ${plainText(packet.reason)}`); });
  client.on('position', packet => {
    const previous = state.position ?? { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 };
    state.position = Object.fromEntries(['x', 'y', 'z', 'yaw', 'pitch'].map(key =>
      [key, packet[key] + (packet.flags[key] ? previous[key] : 0)]));
    client.write('teleport_confirm', { teleportId: packet.teleportId });
    client.write('player_loaded', {});
    client.write('position_look', { ...state.position, flags: { onGround: true, hasHorizontalCollision: false } });
    emit('position', state.position);
  });
  client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 16 }));
  client.on('system_chat', packet => {
    if (!packet.isActionBar) { const text = plainText(packet.content); state.messages.push(text); emit('chat', text); }
  });
  for (const name of ['spawn_entity', 'entity_metadata', 'entity_destroy']) client.on(name, packet => emit(name, packet));
  const ticking = setInterval(() => {
    if (client.state === 'play' && !state.ended && !state.closing) client.write('tick_end', {});
  }, 50);
  const check = () => {
    server.check();
    if (state.error) throw state.error;
    if (state.ended && !state.closing) throw new Error(`${username} disconnected unexpectedly`);
  };
  const bot = { username, state, client, check,
    wait(label, condition, timeout) { return until(`${username}: ${label}`, () => { check(); return condition(); }, timeout); },
    command(command) { check(); client.chat(command); emit('command', command); },
    async close() {
      state.closing = true;
      clearInterval(ticking);
      if (!state.ended) client.end('validation complete');
      try { await until(`${username} socket closed`, () => state.ended, 3000, false); }
      catch { client.socket?.destroy(); await until(`${username} socket destroyed`, () => state.ended, 3000, false); }
    }
  };
  bots.push(bot);
  return bot;
}

let sequence = 0;
async function playerState() {
  const token = `state_${++sequence}`;
  server.command(`fmprobe MorphActor ${token}`);
  return until('owner-region player state', () => {
    server.check();
    const match = server.state.output.match(new RegExp(`FM_PROBE_STATE ${token} (\\S+) (\\d+) (\\S+) (\\{[^\\n]+\\})`));
    return match && { id: match[1], entityId: Number(match[2]), uuid: match[3], metrics: JSON.parse(match[4]) };
  });
}

async function expectState(id) {
  return until(`disguise state ${id}`, async () => {
    const state = await playerState();
    return state.id === id && state;
  });
}

async function observedSpawn(observer, offset, type, uuid) {
  return observer.wait(`${type} spawn for ${uuid}`, () => observer.state.events.slice(offset)
    .find(e => e.event === 'spawn_entity' && e.value.type === data.entitiesByName[type].id
      && e.value.objectUUID.toLowerCase() === uuid.toLowerCase())?.value);
}

async function expectPeacefulDenial(actor, observer, type) {
  const before = await playerState();
  assert.equal(before.metrics.difficulty, 'PEACEFUL');
  const messageOffset = actor.state.messages.length;
  const packetOffset = observer.state.events.length;
  actor.command(`/morph minecraft:${type}`);
  await actor.wait(`${type} peaceful denial`, () => actor.state.messages.slice(messageOffset)
    .some(message => message.includes('You cannot disguise as this monster on Peaceful difficulty')));
  await delay(300);
  const after = await playerState();
  assert.deepEqual(after, before, `${type} denial must preserve disguise, UUID, health and dimensions`);
  assert(!observer.state.events.slice(packetOffset).some(event =>
    (event.event === 'spawn_entity' && event.value.entityId === before.entityId)
    || (event.event === 'entity_destroy' && event.value.entityIds.includes(before.entityId))),
  `${type} denial must not replace or remove the observed disguise`);
  pass(`peaceful rejects ${type} while ${before.id}, preserving state/health/dimensions/packets`, after);
}

try {
  await assertPortFree();
  const built = await exec(join(root, 'gradlew'), ['build', 'upgradeProbeJar', '--no-daemon'],
    { cwd: root, timeout: 300_000, maxBuffer: 10_000_000 });
  await writeFile(join(run, 'build.log'), built.stdout + built.stderr);
  const properties = await readFile(join(root, 'gradle.properties'), 'utf8');
  const projectVersion = properties.match(/^project_version=(.+)$/m)[1].trim();
  const plugin = join(root, 'build/libs', `feathermorph-${projectVersion}-26.2-final.jar`);
  const entries = (await exec('jar', ['tf', plugin])).stdout.split('\n');
  assert(entries.includes('paper-plugin.yml'));
  assert(!entries.some(path => path.endsWith('UpgradeProbe.class') || /datapacks\/.*README\.md/.test(path)));
  const descriptor = (await exec('unzip', ['-p', plugin, 'paper-plugin.yml'])).stdout;
  assert(/api-version: ['"]?26\.2['"]?/.test(descriptor));
  assert(/folia-supported: true/.test(descriptor));
  const peDescriptor = (await exec('unzip', ['-p', packetEvents, 'plugin.yml'])).stdout;
  assert(/^version: ['"]?2\.14\.0['"]?\s*$/m.test(peDescriptor), 'Tests require PacketEvents 2.14.0');
  for (const name of ['disguise_tags', 'loot_tables', 'recipes']) {
    const pack = JSON.parse((await exec('unzip', ['-p', plugin, `datapacks/${name}/pack.mcmeta`])).stdout);
    assert.deepEqual(pack.pack.min_format, [107, 1]);
    assert.deepEqual(pack.pack.max_format, [107, 1]);
  }
  report.artifacts = { featherMorph: { path: plugin, sha256: await digest(plugin) },
    server: { path: join(seed, 'server.jar'), sha256: await digest(join(seed, 'server.jar')) },
    packetEvents: { path: packetEvents, sha256: await digest(packetEvents) } };
  pass('build and packaged descriptors/datapacks');
  for (const name of ['server.jar', 'cache', 'libraries', 'versions']) {
    await cp(join(seed, name), join(run, name), { recursive: true, mode: constants.COPYFILE_FICLONE });
  }
  await mkdir(join(run, 'plugins/FeatherMorph'), { recursive: true });
  await cp(plugin, join(run, 'plugins/FeatherMorph.jar'));
  await cp(packetEvents, join(run, 'plugins/PacketEvents.jar'));
  await cp(join(root, 'build/validation/FeatherMorphUpgradeProbe.jar'), join(run, 'plugins/FeatherMorphUpgradeProbe.jar'));
  await writeFile(join(run, 'eula.txt'), 'eula=true\n');
  const config = YAML.parse((await exec('unzip', ['-p', plugin, 'config.yml'])).stdout);
  config.root.check_update = false;
  config.root.language.code = 'en_us';
  config.root.language.single_language = true;
  if (difficulty === 'peaceful') {
    config.root.bounding_boxes.modify_boxes = true;
    config.root.bounding_boxes.check_space = true;
  }
  await writeFile(join(run, 'plugins/FeatherMorph/config.yml'), YAML.stringify(config));
  const flat = { biome: 'minecraft:plains', layers: [{ block: 'minecraft:bedrock', height: 1 },
    { block: 'minecraft:dirt', height: 2 }, { block: 'minecraft:grass_block', height: 1 }],
    lakes: false, features: false, structure_overrides: [] };
  await writeFile(join(run, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\n` +
    'enforce-secure-profile=false\nmax-players=3\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n' +
    `level-type=minecraft:flat\ngenerator-settings=${JSON.stringify(flat)}\nlevel-seed=2602\ndifficulty=${difficulty}\n` +
    'gamemode=creative\nallow-flight=true\nenable-rcon=false\nenable-query=false\n');
  await assertPortFree();
  server = startServer();
  await until('server ready', () => { server.check(); return /Done \([\d.]+s\)!/.test(server.state.output); }, 180_000);
  await until('startup runtime probe', () => { server.check(); return server.state.output.includes('FM_PROBE_PASS'); }, 30_000);
  const metadata = JSON.parse(server.state.output.match(/FM_PROBE_METADATA (\{[^\n]+\})/)[1]);
  report.metadata = metadata;
  pass('six registry lookups and twenty-three runtime metadata indices/serializers');
  pass('Lophine waypoint disabled/enabled/cleanup in all three worlds');
  server.command('datapack list enabled');
  server.command('gamerule minecraft:spawn_mobs false');
  await until('datapacks enabled', () => {
    server.check();
    return ['disguise_tags', 'loot_tables', 'recipes'].every(name => server.state.output.includes(`FeatherMorph/${name} (plugin)`));
  });
  const status = await new Promise((resolve, reject) => mc.ping({ host: '127.0.0.1', port, closeTimeout: 5000 },
    (error, response) => error ? reject(error) : resolve(response)));
  assert.equal(status.version.protocol, 776);
  pass('native server protocol 776 and enabled built-in datapacks');
  const actor = connect('MorphActor');
  let observer = connect('MorphObserver');
  for (const bot of [actor, observer]) await bot.wait('login and position', () => bot.state.position, 60_000);
  server.command('op MorphActor');
  server.command('tp MorphActor 0.5 4 0.5');
  server.command('tp MorphObserver 3.5 4 0.5');
  await actor.wait('fixture position', () => actor.state.position.x === 0.5 && actor.state.position.z === 0.5);
  await observer.wait('fixture position', () => observer.state.position.x === 3.5 && observer.state.position.z === 0.5);
  const forms = ['cow', 'pig', 'chicken', 'cat', 'wolf', 'sheep', 'mooshroom', 'nautilus', 'evoker', 'slime', 'magma_cube', 'sulfur_cube'];
  for (const type of forms) {
    server.command(`fm manage grant MorphActor minecraft:${type}`);
  }
  await delay(1000);
  const peacefulBlocked = ['evoker', 'slime', 'magma_cube'];
  if (difficulty === 'peaceful') {
    assert.equal((await playerState()).id, '@none');
    for (const type of peacefulBlocked) await expectPeacefulDenial(actor, observer, type);
  }
  for (const type of forms) {
    if (difficulty === 'peaceful' && peacefulBlocked.includes(type)) continue;
    const offset = observer.state.events.length;
    actor.command(`/morph minecraft:${type}` + (['slime', 'magma_cube'].includes(type) ? ' [slime_magma/size=3]' : ''));
    const state = await expectState(`minecraft:${type}`);
    const spawned = await observedSpawn(observer, offset, type, state.uuid);
    const required = ['cow', 'pig', 'chicken', 'cat', 'wolf'].includes(type)
      ? ['AGEABLE_MOB.IS_BABY', 'AGEABLE_MOB.AGE_LOCKED', ...Object.keys(metadata).filter(key => key.startsWith(type.toUpperCase() + '.'))]
      : [];
    if (required.length) {
      const received = await observer.wait(`${type} metadata`, () => {
        const entries = observer.state.events.slice(offset).filter(e => e.event === 'entity_metadata' && e.value.entityId === spawned.entityId)
          .flatMap(e => e.value.metadata);
        const variant = metadata[`${type.toUpperCase()}.${type.toUpperCase()}_VARIANT`];
        if (!entries.some(entry => entry.key === variant.index)) return false;
        // Vanilla defaults may be omitted. Every transmitted field must use the native serializer.
        for (const key of required) {
          for (const entry of entries.filter(entry => entry.key === metadata[key].index)) {
            assert.equal(entry.type, metadataTypes[metadata[key].type], `${type} metadata ${key}`);
          }
        }
        return entries;
      });
      record({ assertion: `${type} required metadata`, received });
    }
    if (['slime', 'magma_cube'].includes(type)) {
      await observer.wait(`${type} native size field`, () => observer.state.events.slice(offset)
        .filter(e => e.event === 'entity_metadata' && e.value.entityId === spawned.entityId)
        .flatMap(e => e.value.metadata).some(entry => entry.key === metadata['SLIME_MAGMA.SIZE'].index
          && entry.type === 'int' && entry.value === 3));
    }
    pass(`player /morph ${type}, owner-region state and observer packets`, { uuid: state.uuid, entityId: spawned.entityId });
    if (type === 'cow') {
      await observer.close();
      observer = connect('MorphObserver');
      await observer.wait('reconnect position', () => observer.state.position, 60_000);
      await observedSpawn(observer, 0, type, state.uuid);
      pass('observer reconnect retains cow disguise');
      if (difficulty === 'peaceful') {
        const cow = await playerState();
        assert.equal(cow.metrics.maxHealth, 10, 'cow health scaling enabled');
        assert(Math.abs(cow.metrics.width - 0.9) < 1e-5, 'cow bounding-box modification enabled');
        for (const blocked of peacefulBlocked) await expectPeacefulDenial(actor, observer, blocked);
      }
    }
    await delay(300);
    const restoredOffset = observer.state.events.length;
    actor.command('/unmorph');
    const restored = await expectState('@none');
    await observedSpawn(observer, restoredOffset, 'player', restored.uuid);
    pass(`/unmorph after ${type} restores player packets`);
    await delay(300);
  }
  actor.check();
  observer.check();
  report.status = 'PASS';
} catch (error) {
  report.status = 'FAIL';
  report.error = error.stack;
  console.error(error);
} finally {
  for (const bot of bots) {
    try { await bot.close(); } catch (error) { report.status = 'FAIL'; report.cleanupError = error.stack; }
  }
  if (server) {
    report.shutdown = await server.stop();
    const lines = server.state.output.replace(/\u001b\[[0-9;]*m/g, '').split('\n');
    // Keep evidence of the optional GitHub update check's rate limit, without treating it as a gameplay failure.
    const updateRateLimit = lines.some(line => line.includes('HTTP response code: 403 for URL: https://api.github.com/repos/retrooper/packetevents/releases/latest'))
      && lines.some(line => line.includes('packetevents.util.updatechecker.UpdateChecker'));
    const externalWarning = line => updateRateLimit && (line.includes('java.lang.IllegalStateException: Failed to parse packetevents version!')
      || line.includes('java.io.IOException: Server returned HTTP response code: 403 for URL: https://api.github.com/repos/retrooper/packetevents/releases/latest'));
    report.environmentWarnings = lines.filter(externalWarning);
    const issues = lines.filter(line => !externalWarning(line))
      .filter(line => /\bERROR\b|Exception|thread.*(?:check|violation)|watchdog.*timeout/i.test(line)
        || /\bWARN\b.*\[FeatherMorph[^\]]*\]/.test(line));
    report.logIssues = issues;
    if (issues.length || report.shutdown.forced || report.shutdown.code !== 0 || report.shutdown.error) report.status = 'FAIL';
  }
  await writeFile(join(run, 'result.json'), JSON.stringify(report, null, 2) + '\n');
  process.removeListener('SIGINT', interrupted);
  process.removeListener('SIGTERM', interrupted);
  console.log(`${report.status}: ${join(run, 'result.json')}`);
  process.exitCode = report.status === 'PASS' ? 0 : 1;
}
