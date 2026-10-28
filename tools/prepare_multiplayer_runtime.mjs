#!/usr/bin/env node
// MP05 isolated two-client harness runtime preparation.
//
// Creates and verifies the three isolated game directories used by the
// `runClientMultiA`, `runClientMultiB` and `runServerMultiplayer` Gradle runs.
// It never launches Minecraft or a dedicated server and never binds a port; the only
// network activity is a short reachability probe to report whether a harness port is
// already in use.
//
// It copies nothing but the two locally required mods from `neoforge-1.21.1/run/mods`,
// never reads credentials, never copies AgentHelper's `agenthelper.token`, and writes
// only inside the isolated directories and the ignored `evidence/multiplayer/` folder.
//
// Usage:
//   node tools/prepare_multiplayer_runtime.mjs prepare --target clientA|clientB|server|all
//   node tools/prepare_multiplayer_runtime.mjs verify  --target clientA|clientB|server|all

import {createHash} from 'node:crypto';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';
import {access, chmod, copyFile, mkdir, readFile, readdir, unlink, writeFile} from 'node:fs/promises';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const moduleDir = path.join(repoRoot, 'neoforge-1.21.1');
const modSourceDir = path.join(moduleDir, 'run', 'mods');
const evidenceDir = path.join(repoRoot, 'evidence', 'multiplayer');

// Mirrors `multiplayerHarness` in neoforge-1.21.1/build.gradle. The Gradle task
// `multiplayerRunConfigCheck` cross-checks the prepared files against that map, and
// verify mode additionally greps build.gradle, so the two cannot drift silently.
const SERVER_HOST = '127.0.0.1';
// Two isolated server roles: the fixed two-client harness server and the throwaway
// server used by the multiplayer self test. They must never share a port or directory.
const SERVERS = {
    server: {role: 'server', gameDir: 'run-multiplayer-server', port: 25569, operators: true},
    selftest: {role: 'selftest', gameDir: 'run-server-multi-battle-test', port: 25570, operators: false},
};
const SERVER = SERVERS.server;
const CLIENTS = {
    clientA: {role: 'clientA', gameDir: 'run-multiplayer-a', username: 'TacticalA', uuid: 'bf8308eb-ed73-3ea9-b5fa-a235825ad642', bridgePort: 25590},
    clientB: {role: 'clientB', gameDir: 'run-multiplayer-b', username: 'TacticalB', uuid: '72b726e2-87b6-3e68-a317-5b1b52afbe7a', bridgePort: 25591},
};
const MOD_PREFIXES = ['agenthelper-', 'dungeons_iso-'];
const POINTER = '.tactical-mp-harness.json';

const OPTIONS = {
    pauseOnLostFocus: 'false',
    skipMultiplayerWarning: 'true',
    onboardAccessibility: 'false',
    tutorialStep: 'none',
    guiScale: '2',
    fullscreen: 'false',
    narrator: '0',
    renderDistance: '8',
    simulationDistance: '8',
    entityShadows: 'false',
    ao: 'false',
    fancyGraphics: 'false',
    enableVsync: 'false',
    maxFps: '120',
};

/** Server properties for one isolated server role. No credential-bearing keys are written. */
function serverProperties(server) {
    return {
        'server-ip': SERVER_HOST,
        'server-port': String(server.port),
        'online-mode': 'false',
        'enforce-secure-profile': 'false',
        'max-players': '8',
        'motd': server.role === 'server' ? 'Tactical MP05 isolated harness' : 'Tactical MP05 multiplayer self test',
        'level-name': 'world',
        'gamemode': 'survival',
        'difficulty': 'normal',
        'allow-nether': 'false',
        'spawn-protection': '0',
        'pvp': 'false',
        'allow-flight': 'true',
        'enable-command-block': 'false',
        'view-distance': '8',
        'simulation-distance': '8',
        'sync-chunk-writes': 'false',
        'white-list': 'false',
        'enable-status': 'true',
    };
}

const report = {mode: null, target: null, prepared: [], verified: [], pending: [], problems: [], notes: []};

function log(line) {
    console.log(line);
}

function problem(text) {
    report.problems.push(text);
    log(`  PROBLEM  ${text}`);
}

function pending(text) {
    report.pending.push(text);
    log(`  PENDING  ${text}`);
}

function note(text) {
    report.notes.push(text);
}

async function exists(target) {
    try {
        await access(target);
        return true;
    } catch {
        return false;
    }
}

async function sha256(file) {
    return createHash('sha256').update(await readFile(file)).digest('hex');
}

async function readJson(file) {
    return JSON.parse(await readFile(file, 'utf8'));
}

/** Reads `key=value` lines from a properties-like file without echoing unrelated content. */
async function readProperties(file) {
    const map = new Map();
    for (const line of (await readFile(file, 'utf8')).split(/\r?\n/)) {
        if (!line || line.startsWith('#') || line.startsWith('!')) continue;
        const index = line.indexOf('=');
        if (index <= 0) continue;
        map.set(line.slice(0, index).trim(), line.slice(index + 1).trim());
    }
    return map;
}

function renderProperties(map, headers) {
    const lines = [...headers];
    for (const [key, value] of map) lines.push(`${key}=${value}`);
    return lines.join('\n') + '\n';
}

/** Ports are probed by connecting, never by binding, so nothing is occupied. */
function probePort(host, port) {
    return new Promise(resolve => {
        const socket = net.connect({host, port});
        const done = inUse => {
            socket.destroy();
            resolve(inUse);
        };
        socket.setTimeout(400, () => done(false));
        socket.once('connect', () => done(true));
        socket.once('error', () => done(false));
    });
}

async function resolveMods() {
    if (!(await exists(modSourceDir))) {
        throw new Error(`missing mod source directory ${path.relative(repoRoot, modSourceDir)}`);
    }
    const names = (await readdir(modSourceDir)).filter(name => name.endsWith('.jar'));
    const picked = [];
    for (const prefix of MOD_PREFIXES) {
        const matches = names.filter(name => name.startsWith(prefix));
        if (matches.length !== 1) {
            throw new Error(`expected exactly one ${prefix}*.jar in ${path.relative(repoRoot, modSourceDir)}, found ${matches.length}`);
        }
        picked.push(path.join(modSourceDir, matches[0]));
    }
    return picked;
}

/** Only the `eula` key of an already accepted agreement is inspected; nothing else is read out. */
async function eulaAccepted() {
    for (const candidate of [path.join(moduleDir, 'run', 'eula.txt'), path.join(moduleDir, 'run-server', 'eula.txt')]) {
        if (!(await exists(candidate))) continue;
        const accepted = (await readFile(candidate, 'utf8'))
            .split(/\r?\n/)
            .some(line => /^eula\s*=\s*true\s*$/i.test(line.trim()));
        if (accepted) return path.relative(repoRoot, candidate);
    }
    return null;
}

/**
 * Minecraft's options.txt is colon separated (`pauseOnLostFocus:false`); a `key=value`
 * line is skipped by the game with "Skipping bad option", so the harness options never
 * applied. server.properties keeps the classic `key=value` form.
 */
function renderOptions(map) {
    return [...map].map(([key, value]) => `${key}:${value}`).join('\n') + '\n';
}

async function readOptions(file) {
    const map = new Map();
    for (const line of (await readFile(file, 'utf8')).split(/\r?\n/)) {
        if (!line || line.startsWith('#')) continue;
        const index = line.indexOf(':');
        if (index <= 0) continue;
        map.set(line.slice(0, index).trim(), line.slice(index + 1).trim());
    }
    return map;
}

async function writeOptions(dir) {
    const file = path.join(dir, 'options.txt');
    if (!(await exists(file))) {
        await writeFile(file, renderOptions(new Map(Object.entries(OPTIONS))));
        return 'created';
    }
    const current = await readOptions(file);
    let changed = false;
    for (const [key, value] of Object.entries(OPTIONS)) {
        if (current.get(key) !== value) {
            current.set(key, value);
            changed = true;
        }
    }
    if (changed) await writeFile(file, renderOptions(current));
    return changed ? 'updated' : 'unchanged';
}

async function writeServerProperties(dir, server) {
    const properties = serverProperties(server);
    const file = path.join(dir, 'server.properties');
    const headers = ['#Minecraft server properties', `#Prepared by tools/prepare_multiplayer_runtime.mjs (isolated MP05 ${server.role})`];
    if (!(await exists(file))) {
        await writeFile(file, renderProperties(new Map(Object.entries(properties)), headers));
        return 'created';
    }
    const pointer = path.join(dir, POINTER);
    const current = await readProperties(file);
    if (!(await exists(pointer))) {
        // The throwaway self-test server may already have generated this file. Adopt it only
        // when it holds no credential *value* (an empty default rcon.password is not a
        // credential); the file is never printed and existing values are preserved verbatim.
        const credentialKeys = [...current.entries()]
            .filter(([key, value]) => /(password|passwd|token|secret)/i.test(key) && String(value ?? '').length > 0)
            .map(([key]) => key);
        if (credentialKeys.length) {
            throw new Error(`${path.relative(repoRoot, file)} already exists with credential values set (${credentialKeys.join(', ')}); refusing to modify it`);
        }
        note(`${server.role}: adopted an existing server.properties without our pointer (no credential keys present)`);
    }
    let changed = false;
    for (const [key, value] of Object.entries(properties)) {
        if (current.get(key) !== value) {
            current.set(key, value);
            changed = true;
        }
    }
    if (changed) await writeFile(file, renderProperties(current, headers));
    return changed ? 'updated' : 'unchanged';
}

async function writeOps(dir) {
    const file = path.join(dir, 'ops.json');
    const entries = Object.values(CLIENTS).map(client => ({
        uuid: client.uuid,
        name: client.username,
        level: 4,
        bypassesPlayerLimit: true,
    }));
    const rendered = JSON.stringify(entries, null, 2) + '\n';
    if (await exists(file)) {
        const current = await readFile(file, 'utf8');
        if (current !== rendered) {
            const currentUuids = new Map();
            for (const entry of JSON.parse(current)) currentUuids.set(entry.name, entry.uuid);
            for (const client of Object.values(CLIENTS)) {
                const existing = currentUuids.get(client.username);
                if (existing && existing !== client.uuid) {
                    throw new Error(`ops.json already maps ${client.username} to a different uuid; refusing to rewrite`);
                }
            }
            await writeFile(file, rendered);
            return 'updated';
        }
        return 'unchanged';
    }
    await writeFile(file, rendered);
    return 'created';
}

/** Copies a mod jar idempotently; the source jars are read-only on this checkout. */
async function syncMod(source, target) {
    const expected = await sha256(source);
    if (await exists(target)) {
        if ((await sha256(target)) === expected) return {state: 'unchanged', sha256: expected};
        // A read-only destination cannot be overwritten in place on Windows.
        await chmod(target, 0o666);
        await unlink(target);
    }
    await copyFile(source, target);
    const actual = await sha256(target);
    if (actual !== expected) throw new Error(`copy verification failed for ${path.basename(source)}`);
    return {state: 'copied', sha256: actual};
}

async function prepareClient(client) {
    const dir = path.join(moduleDir, client.gameDir);
    log(`[${client.role}] ${path.relative(repoRoot, dir)}`);
    await mkdir(dir, {recursive: true});
    await mkdir(path.join(dir, 'mods'), {recursive: true});
    await mkdir(path.join(dir, 'config'), {recursive: true});

    const mods = [];
    for (const source of await resolveMods()) {
        const target = path.join(dir, 'mods', path.basename(source));
        const synced = await syncMod(source, target);
        mods.push({name: path.basename(source), sha256: synced.sha256, state: synced.state});
    }

    const bridgeConfig = path.join(dir, 'config', 'agenthelper.json');
    const bridge = JSON.stringify({port: client.bridgePort}, null, 2) + '\n';
    await writeFile(bridgeConfig, bridge);

    const optionsState = await writeOptions(dir);
    await writeFile(path.join(dir, POINTER), JSON.stringify({
        role: client.role,
        generatedBy: 'tools/prepare_multiplayer_runtime.mjs',
        username: client.username,
        uuid: client.uuid,
        bridgePort: client.bridgePort,
        serverEndpoint: `${SERVER_HOST}:${SERVER.port}`,
        mods,
        apuiExternalDebug: 'disabled for this run by -Dapricityui.debug.enabled=false',
    }, null, 2) + '\n');

    report.prepared.push({role: client.role, gameDir: client.gameDir, mods, bridgePort: client.bridgePort, options: optionsState});
    log(`  mods=${mods.map(m => m.name).join(', ')}`);
    log(`  bridge port ${client.bridgePort} -> config/agenthelper.json (options ${optionsState})`);
    if (await probePort('127.0.0.1', client.bridgePort)) {
        pending(`port ${client.bridgePort} already accepts connections; stop that listener before launching ${client.role}`);
    }
}

async function prepareServer(server) {
    const dir = path.join(moduleDir, server.gameDir);
    log(`[${server.role}] ${path.relative(repoRoot, dir)}`);
    await mkdir(dir, {recursive: true});

    const eulaSource = await eulaAccepted();
    if (!eulaSource) {
        pending(`${server.role}: no accepted eula.txt found; the isolated server would refuse to start (accept the EULA first)`);
    } else {
        const eulaFile = path.join(dir, 'eula.txt');
        if (!(await exists(eulaFile))) await writeFile(eulaFile, '#Accepted EULA inherited from the existing development run.\neula=true\n');
        note(`${server.role}: eula=true inherited from ${eulaSource} (only the eula key was checked)`);
    }

    const propertiesState = await writeServerProperties(dir, server);
    const opsState = server.operators ? await writeOps(dir) : 'not written (no operators needed)';
    await writeFile(path.join(dir, POINTER), JSON.stringify({
        role: server.role,
        generatedBy: 'tools/prepare_multiplayer_runtime.mjs',
        bind: `${SERVER_HOST}:${server.port}`,
        onlineMode: false,
        operators: server.operators ? Object.values(CLIENTS).map(c => ({username: c.username, uuid: c.uuid})) : [],
    }, null, 2) + '\n');

    report.prepared.push({role: server.role, gameDir: server.gameDir, port: server.port, properties: propertiesState, ops: opsState});
    log(`  bind ${SERVER_HOST}:${server.port}, online-mode=false (${propertiesState}); ${opsState}`);
    if (await probePort('127.0.0.1', server.port)) {
        pending(`port ${server.port} already accepts connections; stop that listener before launching the ${server.role} server`);
    }
}

async function verifyGradleMentions() {
    const file = path.join(moduleDir, 'build.gradle');
    if (!(await exists(file))) {
        problem('neoforge-1.21.1/build.gradle is missing');
        return;
    }
    const text = await readFile(file, 'utf8');
    // The run blocks are named `clientMultiA`/`clientMultiB`; Gradle derives the task
    // names `runClientMultiA`/`runClientMultiB` from them, which the Gradle task
    // `multiplayerRunConfigCheck` verifies separately.
    const runs = {clientA: 'clientMultiA', clientB: 'clientMultiB', server: 'serverMultiplayer'};
    for (const client of Object.values(CLIENTS)) {
        const runBlock = runs[client.role];
        for (const needle of [runBlock, client.gameDir, client.username, client.uuid, String(client.bridgePort)]) {
            if (!text.includes(needle)) problem(`build.gradle does not mention ${needle}`);
        }
    }
    for (const needle of [runs.server, SERVER.gameDir, String(SERVER.port), 'apricityui.debug.enabled']) {
        if (!text.includes(needle)) problem(`build.gradle does not mention ${needle}`);
    }
    report.verified.push('build.gradle mentions every harness run block, directory, identity and port');
}

async function verifyDir(role, dir) {
    if (!(await exists(dir))) {
        pending(`${role}: ${path.relative(repoRoot, dir)} is not prepared (run prepare with --target ${role})`);
        return;
    }
    if (await exists(path.join(dir, 'agenthelper.token'))) {
        problem(`${role}: an agenthelper.token exists in the game directory; it must stay ephemeral and outside source control`);
    }
    const pointer = path.join(dir, POINTER);
    if (!(await exists(pointer))) {
        pending(`${role}: ${POINTER} is missing`);
    } else {
        report.verified.push(`${role}: ${POINTER} present`);
    }
}

async function verifyClient(client) {
    const dir = path.join(moduleDir, client.gameDir);
    await verifyDir(client.role, dir);
    if (!(await exists(dir))) return;

    const bridgeFile = path.join(dir, 'config', 'agenthelper.json');
    if (!(await exists(bridgeFile))) {
        pending(`${client.role}: config/agenthelper.json is missing`);
    } else {
        const bridge = await readJson(bridgeFile);
        if (bridge.port !== client.bridgePort) problem(`${client.role}: agenthelper port ${bridge.port} != ${client.bridgePort}`);
        else report.verified.push(`${client.role}: bridge port ${bridge.port}`);
    }

    const modsDir = path.join(dir, 'mods');
    const jars = (await exists(modsDir)) ? (await readdir(modsDir)).filter(name => name.endsWith('.jar')) : [];
    for (const prefix of MOD_PREFIXES) {
        const matches = jars.filter(name => name.startsWith(prefix));
        if (matches.length !== 1) {
            problem(`${client.role}: expected exactly one ${prefix}*.jar, found ${matches.length}`);
            continue;
        }
        const actual = await sha256(path.join(modsDir, matches[0]));
        const source = path.join(modSourceDir, matches[0]);
        if (!(await exists(source)) || (await sha256(source)) !== actual) {
            problem(`${client.role}: ${matches[0]} does not match ${path.relative(repoRoot, source)}`);
        } else {
            report.verified.push(`${client.role}: ${matches[0]} sha256 ${actual.slice(0, 16)}…`);
        }
    }
    const unexpected = jars.filter(name => !MOD_PREFIXES.some(prefix => name.startsWith(prefix)));
    if (unexpected.length) problem(`${client.role}: unexpected extra jars in mods/: ${unexpected.join(', ')}`);

    const optionsFile = path.join(dir, 'options.txt');
    if (!(await exists(optionsFile))) {
        pending(`${client.role}: options.txt is missing`);
    } else {
        const options = await readOptions(optionsFile);
        for (const [key, value] of Object.entries(OPTIONS)) {
            if (options.has(key) && options.get(key) !== value) problem(`${client.role}: options.txt ${key}=${options.get(key)} != ${value}`);
        }
        report.verified.push(`${client.role}: options.txt present`);
    }
}

async function verifyServer(server) {
    const dir = path.join(moduleDir, server.gameDir);
    await verifyDir(server.role, dir);
    if (!(await exists(dir))) return;

    const propertiesFile = path.join(dir, 'server.properties');
    if (!(await exists(propertiesFile))) {
        pending(`${server.role}: server.properties is missing`);
    } else {
        const properties = await readProperties(propertiesFile);
        for (const [key, value] of Object.entries(serverProperties(server))) {
            if (properties.get(key) !== value) problem(`${server.role}: server.properties ${key}=${properties.get(key)} != ${value}`);
        }
        report.verified.push(`${server.role}: binds ${properties.get('server-ip')}:${properties.get('server-port')} online-mode=${properties.get('online-mode')}`);
    }

    if (server.operators) {
        const opsFile = path.join(dir, 'ops.json');
        if (!(await exists(opsFile))) {
            pending('server: ops.json is missing');
        } else {
            const ops = await readJson(opsFile);
            for (const client of Object.values(CLIENTS)) {
                const entry = ops.find(candidate => candidate.name === client.username);
                if (!entry) problem(`server: ${client.username} is not an operator`);
                else if (entry.uuid !== client.uuid) problem(`server: ${client.username} operator uuid ${entry.uuid} != ${client.uuid}`);
                else if (entry.level !== 4) problem(`server: ${client.username} operator level ${entry.level} != 4`);
            }
            report.verified.push(`server: ${ops.length} operators (level 4)`);
        }
    }

    const eulaFile = path.join(dir, 'eula.txt');
    if (!(await exists(eulaFile))) pending(`${server.role}: eula.txt is missing`);
    else if (!(await readFile(eulaFile, 'utf8')).split(/\r?\n/).some(line => /^eula\s*=\s*true\s*$/i.test(line.trim()))) {
        problem(`${server.role}: eula.txt does not accept the EULA`);
    } else report.verified.push(`${server.role}: eula.txt accepted`);
}

function targets() {
    const index = process.argv.indexOf('--target');
    const value = index >= 0 ? process.argv[index + 1] : 'all';
    if (!['clientA', 'clientB', 'server', 'selftest', 'all'].includes(value)) throw new Error(`unknown --target ${value}`);
    return value === 'all' ? ['clientA', 'clientB', 'server'] : [value];
}

async function main() {
    const mode = process.argv[2] ?? 'verify';
    if (!['prepare', 'verify'].includes(mode)) throw new Error(`unknown mode ${mode}`);
    report.mode = mode;
    report.target = targets();

    if (!(await exists(moduleDir))) throw new Error('neoforge-1.21.1 module directory not found; run from the repository');
    if (mode === 'prepare' && !(await exists(modSourceDir))) {
        throw new Error('neoforge-1.21.1/run/mods not found; cannot source AgentHelper/Dungeons jars');
    }

    log(`MP05 harness ${mode} (${report.target.join(', ')})`);
    if (mode === 'prepare') {
        for (const target of report.target) {
            if (SERVERS[target]) await prepareServer(SERVERS[target]);
            else await prepareClient(CLIENTS[target]);
        }
    }

    await verifyGradleMentions();
    for (const target of report.target) {
        if (SERVERS[target]) await verifyServer(SERVERS[target]);
        else await verifyClient(CLIENTS[target]);
    }

    await mkdir(evidenceDir, {recursive: true});
    const reportFile = path.join(evidenceDir, `${mode}-${report.target.join('-')}.json`);
    await writeFile(reportFile, JSON.stringify(report, null, 2) + '\n');
    log(`report ${path.relative(repoRoot, reportFile)}`);
    log(`${mode} finished: ${report.verified.length} verified, ${report.pending.length} pending, ${report.problems.length} problem(s)`);
    if (report.problems.length) process.exitCode = 1;
}

main().catch(error => {
    console.error(`prepare_multiplayer_runtime: ${error.message}`);
    process.exitCode = 1;
});
