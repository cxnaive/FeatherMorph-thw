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
FM_TEST_DIFFICULTY=peaceful npm run validate
```

Protocol preparation uses verified, pinned upstream minecraft-data sources and
their generator, as in Aiyatsbus. It creates a local tarball required by the npm
lockfile; it does not patch node_modules or substitute an older protocol.

The default server seed is `../thw_servers/26.2/test-server` relative to the
FeatherMorph root. Only `server.jar`, `cache`, `libraries`, and `versions` are
copied into a fresh temporary directory. The cached `plugins/FeatherMorph/mclang`
language assets are also copied to avoid online language downloads. Worlds and
configuration are created there. The runner listens only on `127.0.0.1:25627`, refuses an occupied
port, and stops only its own child. It writes the test server's EULA acceptance
for the already accepted local test environment.

Overrides can select another copy of the same core and a free loopback port:

```bash
FM_SERVER_SEED=/path/to/lophine-server FM_TEST_PORT=25628 npm run validate
```

`FM_PACKETEVENTS_JAR` overrides the 2.14.0 plugin path, and `FM_JAVA` selects the
Java executable. The default PacketEvents path is
`$FM_SERVER_SEED/plugins/packetevents-spigot-2.14.0.jar`.
`FM_TEST_DIFFICULTY` accepts `normal` (default) or `peaceful`; each invocation
creates fresh worlds and starts a new server process with empty plugin caches.

The runner builds the release JAR and separate `upgradeProbeJar`, verifies their
packaging, starts a fresh flat world, and disables natural mob spawning.
It reads the packaged default configuration as YAML and applies test overrides,
preserving the current schema version and required options.
In normal difficulty, twelve real player-command morph/unmorph cycles are checked
against both an owner-region state probe and observer packets. The probe also compares
23 metadata declarations with NMS and checks the modified Lophine waypoint API
in all worlds before bots connect. Normal metadata defaults may be omitted;
present fields must use native serializers. Slime/magma size 3 is explicitly
asserted in the received packets.

In peaceful difficulty, the suite enables bounding-box modification and space
checks. Evoker, slime, and magma cube requests must return the dedicated denial
message, both before any disguise and while disguised as a cow. The owner-region
probe verifies that the disguise ID, UUID, health, maximum health, and dimensions
remain unchanged, and the observer must receive no entity replacement or removal.
The other nine forms still complete their morph/unmorph cycles, including sulfur
cube, which vanilla allows in peaceful despite its monster category. The policy
uses NMS `isAllowedInPeaceful()`, rather than a list of monster names.

Both difficulty modes also perform actual FeatherMorph V3 payload handshakes
for API 19 and API 17. They verify the negotiated API, temporary-property versus
legacy animation messages, animation reset, BODY/SADDLE equipment
serialization/filtering, and persistent property discard/default restoration.
The real Fabric client tests live in `../FeatherMorphClient/validation/26.2`.

The printed temporary directory retains `build.log`, `server.log`,
`packets.jsonl`, `result.json`, worlds, and generated configuration. A failed run
still closes bots and stops the server. `result.json` includes artifact SHA-256,
check results, difficulty, metadata mappings, shutdown status, and explicit
untested cases.
Known PacketEvents update-check HTTP 403 warnings are preserved separately;
other exceptions, FeatherMorph warnings/errors, and forced shutdown fail the run.

The probe is console-only and is not included in the production artifact. It is
specific to the modified Lophine core whose restored waypoint implementation is
being evaluated. Pure Paper or Folia are not interchangeable test targets here.

## Skills and Abilities

```bash
cd validation/26.2/automation
FM_GAMEPLAY_ONLY=true npm run validate
FM_GAMEPLAY_ONLY=true FM_GAMEPLAY_CASES=skill_ghast,skill_warden,ability_chat_override npm run validate
```

This mode builds `gameplayProbeJar` separately against the shaded production JAR,
then exercises all ten effective active skill types, None, and all 27 registered
passive ability types in normal difficulty. The full matrix contains 60 fixtures,
including all generated default option parsers, permission/cooldown/spectator/event
gates, wet/dry conditions, missing targets, delayed cancellation, and unmorph cleanup.
Filtered runs retain incomplete coverage in `notRun` and do not assert full coverage.

Active skills execute through the permission-checking API on a real player in its
owner region. Targets, summoned entities, projectiles, health, potion effects,
velocity, attributes, flight, air, fire, snow, and bossbars are real server objects.
Explosion terrain damage is suppressed by the test listener to preserve the fixture.
Damage-reduction, attack-secondary-effect, vibration, anger, and chat listener tests
dispatch constructed Bukkit/Paper events through the registered event bus. Those
cases test plugin listener behavior, not the full natural event-generation path.
This does not cover every custom option/per-form combination or cross-region targets.

The runner can also copy a test skill-config seed before startup and verify its
migration. These fixtures contain the old generated ghast option and a customized
option; package version files are normalized to the production writer's format.

```bash
FM_SKILL_CONFIG_SEED="$PWD/../gameplay/migration/default" npm run validate
FM_TEST_DIFFICULTY=peaceful FM_SKILL_CONFIG_SEED="$PWD/../gameplay/migration/custom" npm run validate
```

See [the gameplay run record](runs/2026-10-10-gameplay.md) for results and fixes.
Representative actual Fabric-client skill and passive synchronization checks live
in `../FeatherMorphClient/validation/26.2` relative to the repository root.
