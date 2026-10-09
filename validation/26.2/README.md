# Lophine 26.2 Validation

This suite follows `../aiyatsbus/validation/folia`: pinned native protocol data,
an isolated server owned by the runner, two offline protocol bots, an independent
probe on the player owner region, and retained evidence.

Requirements: Java/JDK 25, Node 22+, npm, tar, jar, unzip, and the local modified
Lophine 26.2 server with its downloaded libraries. PacketEvents must be exactly
**2.14.0**. No ViaVersion or additional gameplay plugins are loaded.

```bash
cd validation/26.2/automation
npm run protocol:prepare
npm ci --ignore-scripts
npm run validate
```

Protocol preparation uses verified, pinned upstream minecraft-data sources and
their generator, as in Aiyatsbus. It creates a local tarball required by the npm
lockfile; it does not patch node_modules or substitute an older protocol.

The default server seed is `../thw_servers/26.2/test-server` relative to the
FeatherMorph root. Only `server.jar`, `cache`, `libraries`, and `versions` are
copied into a fresh temporary directory. Worlds, configs, and plugin data are
created there. The runner listens only on `127.0.0.1:25627`, refuses an occupied
port, and stops only its own child. It writes the test server's EULA acceptance
for the already accepted local test environment.

Overrides can select another copy of the same core and a free loopback port:

```bash
FM_SERVER_SEED=/path/to/lophine-server FM_TEST_PORT=25628 npm run validate
```

`FM_PACKETEVENTS_JAR` overrides the 2.14.0 plugin path, and `FM_JAVA` selects the
Java executable. The default PacketEvents path is
`$FM_SERVER_SEED/plugins/packetevents-spigot-2.14.0.jar`.

The runner builds the release JAR and separate `upgradeProbeJar`, verifies their
packaging, starts a fresh normal-difficulty flat world, and disables natural mob
spawning. Twelve real player-command morph/unmorph cycles are checked against
both an owner-region state probe and observer packets. The probe also compares
23 metadata declarations with NMS and checks the modified Lophine waypoint API
in all worlds before bots connect. Normal metadata defaults may be omitted;
present fields must use native serializers. Slime/magma size 3 is explicitly
asserted in the received packets.

The printed temporary directory retains `build.log`, `server.log`,
`packets.jsonl`, `result.json`, worlds, and generated configuration. A failed run
still closes bots and stops the server. `result.json` includes artifact SHA-256,
check results, metadata mappings, shutdown status, and explicit untested cases.
Known PacketEvents update-check HTTP 403 warnings are preserved separately;
other exceptions, FeatherMorph warnings/errors, and forced shutdown fail the run.

The probe is console-only and is not included in the production artifact. It is
specific to the modified Lophine core whose restored waypoint implementation is
being evaluated. Pure Paper or Folia are not interchangeable test targets here.
