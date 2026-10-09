import com.google.gson.Gson;
import io.papermc.paper.event.entity.WardenAngerChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import xyz.nifeather.morph.abilities.*;
import xyz.nifeather.morph.abilities.impl.SnowyAbility;
import xyz.nifeather.morph.abilities.options.*;
import xyz.nifeather.morph.api.FeatherMorphAPI;
import xyz.nifeather.morph.api.direct.FeatherMorphDirectAccess;
import xyz.nifeather.morph.api.events.gameplay.PlayerExecuteSkillEvent;
import xyz.nifeather.morph.config.*;
import xyz.nifeather.morph.misc.*;
import xyz.nifeather.morph.misc.permissions.CommonPermissions;
import xyz.nifeather.morph.skills.*;
import xyz.nifeather.morph.skills.options.*;
import xyz.nifeather.morph.storage.skill.ISkillAbilityOption;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/** Effects run on real players/entities in an isolated server; events use the registered listeners. */
public final class GameplayProbe extends JavaPlugin implements Listener {
    private Runner active;
    private final Gson gson = new Gson();

    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 3 || active != null) return false;
        var player = Bukkit.getPlayerExact(args[0]);
        if (player == null) return false;
        active = new Runner(player, args[1], args[2]);
        player.teleportAsync(new Location(player.getWorld(), 0.5, -60, 0.5, 0, 0)).thenAccept(ok ->
                player.getScheduler().run(this, task -> active.guard(active::start), null));
        return true;
    }

    @EventHandler public void onSpawn(EntitySpawnEvent event) {
        var runner = active;
        if (runner != null && Bukkit.isOwnedByCurrentRegion(runner.player)) runner.spawned.add(event.getEntity());
    }
    @EventHandler public void onExplode(EntityExplodeEvent event) {
        if (active != null && event.getEntity().equals(active.player)) {
            active.explosions++;
            event.blockList().clear();
        }
    }
    @EventHandler public void onSkill(PlayerExecuteSkillEvent event) {
        if (active != null && event.getPlayer().equals(active.player)) {
            active.executions++;
            if (active.cancelSkill) event.setCancelled(true);
        }
    }

    private final class Runner {
        final Player player;
        final String token, fixture;
        final FeatherMorphDirectAccess api = FeatherMorphAPI.instance().directAccess();
        final List<String> assertions = new ArrayList<>();
        final List<Entity> spawned = new ArrayList<>();
        final List<PermissionAttachment> permissions = new ArrayList<>();
        final Map<Location, Material> blocks = new HashMap<>();
        final Set<String> skillCoverage = new TreeSet<>(), abilityCoverage = new TreeSet<>();
        DisguiseState state;
        boolean done, cancelSkill;
        int explosions, executions;

        Runner(Player player, String token, String fixture) { this.player = player; this.token = token; this.fixture = fixture; }
        void guard(Runnable action) {
            if (done) return;
            try { action.run(); } catch (Throwable error) {
                done = true;
                getLogger().log(Level.SEVERE, "FM_GAMEPLAY_FAIL " + token + " " + fixture, error);
                cleanup(); active = null;
            }
        }
        void after(long ticks, Runnable action) {
            player.getScheduler().runDelayed(GameplayProbe.this, task -> guard(action), null, ticks);
        }
        void expect(BooleanSupplier condition, int ticks, Runnable next) {
            if (condition.getAsBoolean()) { next.run(); return; }
            if (ticks <= 0) throw new AssertionError("Timed out in " + fixture);
            after(1, () -> expect(condition, ticks - 1, next));
        }
        void check(boolean value, String message) {
            if (!value) throw new AssertionError(message);
            assertions.add(message);
        }
        void block(int x, int y, int z, Material material) {
            var location = new Location(player.getWorld(), x, y, z);
            blocks.putIfAbsent(location, location.getBlock().getType());
            location.getBlock().setType(material, false);
        }
        PermissionAttachment deny(String permission) {
            var attachment = player.addAttachment(GameplayProbe.this);
            attachment.setPermission(permission, false); permissions.add(attachment);
            return attachment;
        }
        void restore(PermissionAttachment attachment) {
            player.removeAttachment(attachment); permissions.remove(attachment);
        }
        void start() {
            check(Bukkit.isOwnedByCurrentRegion(player), "player owner region");
            api.morphManager().unMorph(player, true);
            player.setGameMode(GameMode.SURVIVAL); player.setInvulnerable(false);
            player.setSneaking(false); player.setVelocity(new Vector()); player.setFireTicks(0);
            player.setFreezeTicks(0); player.setFoodLevel(20); player.setSaturation(20);
            player.setRemainingAir(300); player.setNoDamageTicks(0);
            player.getInventory().clear();
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 17; z++) {
                block(x, -61, z, Material.GRASS_BLOCK);
                for (int y = -60; y <= -57; y++) block(x, y, z, Material.AIR);
            }
            if (fixture.equals("bindings")) { bindings(); return; }
            if (fixture.startsWith("skill_")) { skill(fixture.substring(6)); return; }
            if (fixture.startsWith("ability_")) { ability(fixture.substring(8)); return; }
            throw new IllegalArgumentException(fixture);
        }
        void morph(String form) {
            check(api.morphManager().morph(MorphParameters.create(player, "minecraft:" + form)
                    .setSource(Bukkit.getConsoleSender()).setBypassAvailableCheck(true).setForceExecute(true)), "morph " + form);
            state = Objects.requireNonNull(api.morphManager().getDisguiseStateFor(player));
        }
        void isolate() {
            state.getAbilityUpdater().update();
            state.getAbilityUpdater().setAbilities(List.of());
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        }
        LivingEntity target() {
            var target = (LivingEntity) player.getWorld().spawnEntity(new Location(player.getWorld(), 0.5, -60, 6.5), EntityType.VILLAGER);
            target.setAI(false); target.setGravity(false); target.setSilent(true);
            target.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            check(Bukkit.isOwnedByCurrentRegion(target), "target owner region");
            return target;
        }
        void execute() {
            skillCoverage.add(state.getSkill().getIdentifier().asString());
            state.setSkillCooldown(0, true);
            check(state.executeSkillCheckPermission(), "skill accepted");
            check(state.calculateRemainingCooldown() > 0, "cooldown starts");
        }
        IAbility<?> equip(String id, ISkillAbilityOption option) {
            var ability = Objects.requireNonNull(api.abilityManager().getAbility(NamespacedKey.fromString("morph:" + id)));
            if (option == null) {
                try { option = ability.optionHandler().readOptionNullable(null); }
                catch (Exception error) { throw new IllegalArgumentException(id, error); }
            }
            state.getAbilityUpdater().setAbilityConfig(ability.getIdentifier().asString(), option);
            state.getAbilityUpdater().setAbilities(List.of(ability)); state.getAbilityUpdater().update();
            check(ability.getAppliedPlayers().contains(player.getUniqueId()), "ability applied " + id);
            abilityCoverage.add(ability.getIdentifier().asString()); return ability;
        }
        void revoke(IAbility<?> ability) {
            state.getAbilityUpdater().setAbilities(List.of());
            check(!ability.getAppliedPlayers().contains(player.getUniqueId()), "ability revoked");
        }
        void bindings() {
            var defaults = DefaultConfigGenerator.createInstance().generateConfiguration();
            for (var entry : defaults.entrySet()) {
                var config = entry.getValue();
                var skill = api.skillHandler().getSkill(config.getSkillIdentifier().asString());
                check(skill.getIdentifier().equals(config.getSkillIdentifier()), "registered default skill " + entry.getKey());
                try {
                    check(config.readOptions(skill) != null, "parsed default skill option " + entry.getKey());
                    for (var id : config.getAbilitiyIdentifiers()) {
                        var ability = api.abilityManager().getAbility(NamespacedKey.fromString(id));
                        check(ability != null && config.readOptions(ability) != null, "parsed default ability " + entry.getKey() + " " + id);
                    }
                } catch (Exception error) { throw new IllegalArgumentException(entry.getKey(), error); }
            }
            check(api.skillHandler().getRegistedSkills().size() == 11, "eleven registered skills including none");
            check(api.abilityManager().getRegistedAbilities().size() == 27, "twenty-seven registered abilities"); finish();
        }
        void skill(String name) {
            String form = name.split("__")[0], branch = name.contains("__") ? name.split("__")[1] : "";
            morph(form); isolate();
            switch (form) {
                case "cow" -> {
                    check(!state.executeSkillCheckPermission(), "none skill denied"); finish();
                }
                case "pig" -> {
                    if (branch.equals("gates")) { skillGates(); return; }
                    boolean before = state.showingDisguisedItems(); execute();
                    check(state.showingDisguisedItems() != before, "fake equipment toggled");
                    execute(); check(state.showingDisguisedItems() == before, "fake equipment restored"); finish();
                }
                case "camel", "camel_husk", "nautilus", "zombie_nautilus" -> {
                    if (form.contains("nautilus")) {
                        execute(); check(player.getVelocity().lengthSquared() < 0.01, "dry dash denied");
                        wet(() -> { execute(); check(player.getVelocity().getZ() > 1.0, "water dash velocity"); finish(); });
                    } else { execute(); check(player.getVelocity().getZ() > 0.9, "land dash velocity"); finish(); }
                }
                case "dolphin", "elder_guardian" -> {
                    var observer = Bukkit.getPlayerExact("MorphObserver");
                    var type = form.equals("dolphin") ? PotionEffectType.DOLPHINS_GRACE : PotionEffectType.MINING_FATIGUE;
                    observer.removePotionEffect(type);
                    execute(); check(!observer.hasPotionEffect(type), "range effect denied without water");
                    wet(() -> { execute(); after(2, () -> { check(observer.hasPotionEffect(type), "nearby player receives range effect"); finish(); }); });
                }
                case "enderman" -> {
                    var before = player.getLocation(); execute();
                    check(player.getLocation().distanceSquared(before) < 0.01, "teleport denied without block");
                    block(0, -59, 8, Material.STONE); execute();
                    expect(() -> player.getZ() > 6, 60, () -> { check(player.getFallDistance() == 0, "teleport resets fall distance"); finish(); });
                }
                case "creeper" -> {
                    player.setGameMode(GameMode.CREATIVE); execute();
                    after(35, () -> { check(explosions == 1, "one delayed explosion");
                        check(!player.isDead(), "creative creeper survives self explosion"); player.setGameMode(GameMode.SURVIVAL); finish(); });
                }
                case "guardian", "warden" -> {
                    if (branch.equals("no_target")) {
                        execute(); after(form.equals("guardian") ? 85 : 40, () -> { check(spawned.isEmpty(), "no-target skill creates no entities"); finish(); });
                    } else {
                        var target = target(); check(player.getTargetEntity(16) == target, "target in line of sight");
                        execute();
                        if (branch.equals("cancel")) api.morphManager().unMorph(player, true);
                        after(form.equals("guardian") ? 85 : 40, () -> {
                            if (branch.equals("cancel")) { check(target.getHealth() == 100, "unmorph cancels delayed damage"); finish(); return; }
                            check(target.getHealth() < 100, "delayed target damage");
                            if (form.equals("warden")) {
                                check(target.getHealth() == 90, "sonic damage applied once");
                                check(target.getVelocity().length() >= 2.5 && target.getVelocity().length() < 3.0,
                                        "one sonic impulse plus normal damage knockback: " + target.getVelocity());
                            }
                            if (form.equals("guardian")) check(state.getSessionData("feathermorph:guardian_skill_execute_id", String.class) == null, "guardian tracking session cleared");
                            finish();
                        });
                    }
                }
                case "evoker" -> {
                    player.setSneaking(branch.equals("vex")); target(); execute();
                    after(35, () -> {
                        var type = branch.equals("vex") ? EntityType.VEX : EntityType.EVOKER_FANGS;
                        var entities = spawned.stream().filter(e -> e.getType() == type).toList();
                        check(!entities.isEmpty(), "evoker summons " + type);
                        if (type == EntityType.EVOKER_FANGS) check(((EvokerFangs) entities.getFirst()).getOwner() == player, "fang owner");
                        else {
                            api.morphManager().unMorph(player, true);
                            after(5, () -> { check(entities.stream().allMatch(e -> e.isDead() || !e.isValid()), "vex removed on unmorph"); finish(); }); return;
                        }
                        finish();
                    });
                }
                case "witch" -> {
                    execute(); after(1, () -> {
                        var potion = (ThrownPotion) spawned.stream().filter(e -> e instanceof ThrownPotion).findFirst().orElseThrow();
                        check(potion.getShooter() == player, "potion shooter");
                        check(potion.getPotionMeta().getCustomEffects().size() == 1, "random potion has one valid effect"); finish();
                    });
                }
                default -> projectile(form, branch);
            }
        }
        void skillGates() {
            boolean before = state.showingDisguisedItems();
            state.setSkillCooldown(0, true);
            var global = deny(CommonPermissions.SKILL);
            check(!state.executeSkillCheckPermission(), "global skill permission denied"); restore(global);
            var specific = deny(CommonPermissions.skillPermissionOf(state.getSkill().getIdentifier().asString(), state.getDisguiseIdentifier()));
            check(!state.executeSkillCheckPermission(), "specific skill permission denied"); restore(specific);
            state.setSkillCooldown(0, true); player.setGameMode(GameMode.SPECTATOR);
            check(!state.executeSkillCheckPermission(), "spectator skill denied"); player.setGameMode(GameMode.SURVIVAL);
            state.requestSkillState(this, true);
            check(!state.executeSkillCheckPermission(), "disabled skill denied"); state.requestSkillState(this, false);
            check(executions == 0 && state.showingDisguisedItems() == before, "denied requests have no effect or execution event");
            cancelSkill = true;
            check(!state.executeSkillCheckPermission(), "cancelled execution event denied"); cancelSkill = false;
            check(executions == 1 && state.showingDisguisedItems() == before, "cancelled execution has no effect");
            execute();
            boolean toggled = state.showingDisguisedItems();
            check(!state.executeSkillCheckPermission(), "cooldown rejects second request");
            check(executions == 2 && toggled == state.showingDisguisedItems(), "cooldown prevents duplicate execution"); finish();
        }
        void projectile(String form, String branch) {
            final ProjectileConfiguration option;
            try { option = (ProjectileConfiguration) api.skillHandler().lookupOptionFor((ISkill) state.getSkill(), "minecraft:" + form); }
            catch (Exception error) { throw new IllegalArgumentException(form, error); }
            if (form.equals("ghast")) {
                check(option.executeDelay == 16 && option.getDistanceLimit() == 0, "ghast charges without requiring target");
            }
            if (form.equals("shulker") && !branch.equals("no_target")) target();
            int before = spawned.size(); execute();
            if (branch.equals("cancel")) api.morphManager().unMorph(player, true);
            after(Math.max(2, option.executeDelay + 5), () -> {
                var launched = spawned.subList(before, spawned.size()).stream().filter(e -> e.getType() == option.entityType()).toList();
                if (branch.equals("cancel") || branch.equals("no_target")) check(launched.isEmpty(), "delayed projectile suppressed " + branch);
                else {
                    check(launched.size() == 1, "one projectile " + option.entityType() + ": " + launched.size());
                    var projectile = launched.getFirst();
                    if (projectile instanceof Projectile shot) check(shot.getShooter() == player, "projectile shooter");
                    if (projectile instanceof ShulkerBullet bullet) check(bullet.getTarget() != null, "shulker bullet target");
                    else check(projectile.getVelocity().lengthSquared() > 0.01, "projectile moves");
                }
                finish();
            });
        }
        void wet(Runnable next) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = -60; y <= -58; y++) block(x, y, z, Material.WATER);
            expect(player::isInWater, 20, next);
        }
        EntityDamageEvent damage(EntityDamageEvent.DamageCause cause, double amount) {
            var event = new EntityDamageEvent(player, cause, DamageSource.builder(DamageType.GENERIC).build(), amount);
            Bukkit.getPluginManager().callEvent(event); return event;
        }
        void ability(String id) {
            morph(id.equals("heals_from_entity") ? "ender_dragon" : "cow"); isolate();
            if (id.equals("permissions")) { abilityPermissions(); return; }
            var effect = switch (id) {
                case "breathe_under_water" -> PotionEffectType.CONDUIT_POWER;
                case "feather_falling" -> PotionEffectType.SLOW_FALLING;
                case "fire_resistance" -> PotionEffectType.FIRE_RESISTANCE;
                case "normal_jump_boost", "small_jump_boost" -> PotionEffectType.JUMP_BOOST;
                case "night_vision" -> PotionEffectType.NIGHT_VISION;
                case "speed" -> PotionEffectType.SPEED;
                default -> null;
            };
            if (effect != null) {
                var ability = equip(id, null);
                check(player.hasPotionEffect(effect), "potion applied");
                int amp = switch(id) { case "normal_jump_boost" -> 1; case "speed" -> 2; default -> 0; };
                check(player.getPotionEffect(effect).getAmplifier() == amp, "potion amplifier");
                after(25, () -> {
                    check(player.hasPotionEffect(effect), "potion refreshed beyond short duration"); revoke(ability);
                    int duration = player.getPotionEffect(effect).getDuration();
                    after(duration + 2, () -> { check(!player.hasPotionEffect(effect), "potion expires after revoke"); finish(); });
                }); return;
            }
            switch (id) {
                case "can_fly" -> {
                    deny(CommonPermissions.ALWAYS_CAN_FLY); var ability = equip(id, new FlyOption(0.07f, 6));
                    check(player.getAllowFlight() && Math.abs(player.getFlySpeed() - 0.07f) < 0.001, "survival flight enabled at configured speed");
                    player.setFoodLevel(0);
                    after(5, () -> { check(!player.getAllowFlight(), "hungry player cannot fly"); player.setFoodLevel(20);
                        after(5, () -> { check(player.getAllowFlight(), "fed player can fly again"); revoke(ability);
                            check(!player.getAllowFlight() && player.getFlySpeed() == 0.1f, "flight and speed restored"); finish(); }); });
                }
                case "override_maximum_air" -> {
                    player.setRemainingAir(150); var ability = equip(id, new ExtraAirOption(6000));
                    check(player.getMaximumAir() == 6000 && player.getRemainingAir() == 3000, "air scaled proportionally");
                    revoke(ability); check(player.getMaximumAir() == 300 && player.getRemainingAir() == 150, "air defaults restored proportionally"); finish();
                }
                case "attribute_modify" -> {
                    double before = player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
                    var ability = equip(id, AttributeModifyOption.from(Attribute.MOVEMENT_SPEED, AttributeModifyOption.OperationType.multiply_base, 0.5)
                            .with(Attribute.STEP_HEIGHT, AttributeModifyOption.OperationType.add, 0.4));
                    check(Math.abs(player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() - before * 1.5) < 1e-6, "movement attribute modified");
                    revoke(ability); check(Math.abs(player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() - before) < 1e-6, "attribute restored"); finish();
                }
                case "no_fall_damage", "reduce_fall_damage", "reduce_magic_damage", "reduce_wither_damage" -> {
                    var cause = id.contains("magic") ? EntityDamageEvent.DamageCause.MAGIC : id.contains("wither") ? EntityDamageEvent.DamageCause.WITHER : EntityDamageEvent.DamageCause.FALL;
                    var ability = equip(id, id.equals("no_fall_damage") ? null : new ReduceDamageOption(0.25, true));
                    var hit = damage(cause, 12);
                    check(id.equals("no_fall_damage") ? hit.isCancelled() : hit.getDamage() == 9, "matching damage blocked/reduced");
                    check(damage(EntityDamageEvent.DamageCause.CUSTOM, 12).getDamage() == 12, "other damage unaffected");
                    revoke(ability); hit = damage(cause, 12);
                    check(!hit.isCancelled() && hit.getDamage() == 12, "damage behavior restored"); finish();
                }
                case "no_sweet_bush_damage" -> {
                    block(3, -60, 3, Material.SWEET_BERRY_BUSH); var ability = equip(id, null);
                    check(bushDamage().isCancelled(), "berry bush contact cancelled"); revoke(ability);
                    check(!bushDamage().isCancelled(), "berry bush contact restored"); finish();
                }
                case "burns_under_sun" -> {
                    var ability = equip(id, null);
                    expect(() -> player.getFireTicks() > 0, 40, () -> {
                        check(player.getFireTicks() > 0, "sunlight ignites player"); player.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET)); player.setFireTicks(0);
                        after(5, () -> { check(player.getFireTicks() <= 0, "helmet prevents ignition"); revoke(ability); finish(); });
                    });
                }
                case "takes_damage_from_water" -> {
                    var ability = equip(id, new TakesDamageFromWaterOption(2)); double health = player.getHealth();
                    after(3, () -> { check(player.getHealth() == health, "dry player unaffected"); wet(() -> {
                        expect(() -> player.getHealth() < health, 20, () -> { check(player.getHealth() < health, "water damages player"); revoke(ability); finish(); });
                    }); });
                }
                case "dryout_in_air" -> {
                    var ability = equip(id, new DryoutAbilityOption(false)); player.setRemainingAir(-15); player.setNoDamageTicks(0); double health = player.getHealth();
                    ability.handle(player, state); check(player.getHealth() < health, "dryout deals damage");
                    wet(() -> { player.setRemainingAir(100); ability.handle(player, state); check(player.getRemainingAir() == 105, "water replenishes air"); revoke(ability); finish(); });
                }
                case "spider" -> {
                    block(0, -60, 1, Material.STONE); block(0, -59, 1, Material.STONE);
                    player.teleportAsync(new Location(player.getWorld(), 0.5, -60, 0.7)).thenRun(() -> after(2, () -> {
                        var ability = equip(id, null); player.setVelocity(new Vector()); ability.handle(player, state);
                        check(player.getVelocity().getY() > 0.14, "wall contact climbs"); player.setSneaking(true); player.setVelocity(new Vector()); ability.handle(player, state);
                        check(player.getVelocity().getY() == 0, "sneaking suppresses climb"); revoke(ability); finish();
                    }));
                }
                case "snowy" -> {
                    var ability = equip(id, null); player.setFreezeTicks(100); ability.handle(player, state);
                    check(player.getFreezeTicks() == 0, "snow form clears freezing");
                    check(damage(EntityDamageEvent.DamageCause.FREEZE, 4).getDamage() == 0, "freeze damage removed");
                    check(player.getLocation().getBlock().getType() == Material.SNOW, "snow trail placed");
                    blocks.put(new Location(player.getWorld(), 0, -60, 0), Material.AIR);
                    SnowyAbility.blockPlayer(player, this); block(0, -60, 0, Material.AIR); ability.handle(player, state);
                    check(player.getLocation().getBlock().getType() == Material.AIR, "blocked snow trail suppressed"); SnowyAbility.unBlockPlayer(player, this); revoke(ability); finish();
                }
                case "warden" -> {
                    var ability = equip(id, null); check(gameEvent(GameEvent.STEP).isCancelled(), "step vibration cancelled");
                    check(!gameEvent(GameEvent.ENTITY_INTERACT).isCancelled(), "other vibration retained"); revoke(ability);
                    check(!gameEvent(GameEvent.STEP).isCancelled(), "vibration restored"); finish();
                }
                case "warden_less_aware" -> {
                    var warden = (Warden) player.getWorld().spawnEntity(new Location(player.getWorld(), 8, -60, 8), EntityType.WARDEN); warden.setAI(false);
                    var ability = equip(id, null); check(anger(warden, 0, 20).getNewAnger() == 5, "anger increase quartered");
                    check(anger(warden, 0, 80).getNewAnger() == 80, "angry threshold retained"); revoke(ability);
                    check(anger(warden, 0, 20).getNewAnger() == 20, "anger restored"); finish();
                }
                case "extra_knockback", "potion_on_attack" -> {
                    var target = target(); var ability = equip(id, id.equals("extra_knockback") ? ExtraKnockbackOption.from(0, 0.8, 0) : PotionEffectOption.from(PotionEffectType.WITHER, 200, 0));
                    var event = new EntityDamageByEntityEvent(player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                            DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(player).withDirectEntity(player).build(), 1);
                    Bukkit.getPluginManager().callEvent(event);
                    after(2, () -> {
                        check(id.equals("extra_knockback") ? target.getVelocity().getY() > 0.4 : target.hasPotionEffect(PotionEffectType.WITHER), "attack secondary effect"); revoke(ability); finish();
                    });
                }
                case "bossbar" -> {
                    var ability = equip(id, new BossbarOption(new BossbarOption.BossbarCreateOption("<name> (<who>)", BossBar.Color.PINK, BossBar.Overlay.PROGRESS, Set.of()), 20));
                    check(state.getBossbar() != null, "bossbar created"); player.setHealth(player.getHealth() / 2);
                    after(5, () -> { check(Math.abs(state.getBossbar().progress() - 0.5) < 0.01, "bossbar tracks health"); revoke(ability);
                        check(state.getBossbar() == null, "bossbar removed"); finish(); });
                }
                case "chat_override" -> {
                    var config = api.getGlobalDependency(MorphConfigManager.class);
                    config.getBindable(ConfigOptions.ALLOW_CHAT_OVERRIDE).set(true);
                    config.getBindable(ConfigOptions.CHAT_OVERRIDE_USE_CUSTOM_RENDERER).set(true);
                    state.setServerDisplay(Component.text("ProbeDisguise"));
                    var ability = equip(id, new ChatOverrideOption("<who>: <message>"));
                    after(2, () -> {
                        check(renderChat().contains("ProbeDisguise") && renderChat().contains("probe-message"), "custom chat renders disguise and message");
                        config.getBindable(ConfigOptions.CHAT_OVERRIDE_USE_CUSTOM_RENDERER).set(false);
                        after(2, () -> {
                            var rendered = renderChat();
                            check(rendered.contains("original:") && rendered.contains("ProbeDisguise"), "original chat renderer uses disguise display: " + rendered);
                            config.getBindable(ConfigOptions.ALLOW_CHAT_OVERRIDE).set(false);
                            after(2, () -> {
                                check(!renderChat().contains("ProbeDisguise"), "disabled chat override retains original display"); revoke(ability); finish();
                            });
                        });
                    });
                }
                case "heals_from_entity" -> {
                    var crystal = (EnderCrystal) player.getWorld().spawnEntity(new Location(player.getWorld(), 4, -60, 4), EntityType.END_CRYSTAL);
                    crystal.setShowingBottom(false); var ability = equip(id, new HealsFromEntityOption(1, 10, 0.5, 16, EntityType.END_CRYSTAL));
                    player.setHealth(10);
                    expect(() -> player.getHealth() > 10, 100, () -> {
                        check(player.getHealth() > 10, "nearby crystal heals"); revoke(ability);
                        check(state.getSessionData("morph:HFEA_BEAM_TARGET", Object.class) == null, "healing target cleared"); finish();
                    });
                }
                default -> throw new IllegalArgumentException(id);
            }
        }
        void abilityPermissions() {
            var ability = equip("can_fly", new FlyOption(0.07f, 6));
            var permission = deny(CommonPermissions.abilityPermissionOf(ability.getIdentifier().asString(), state.getDisguiseIdentifier()));
            after(10, () -> {
                check(!player.getAllowFlight() && !ability.getAppliedPlayers().contains(player.getUniqueId()), "permission removal revokes flight and registration");
                restore(permission);
                after(10, () -> {
                    check(player.getAllowFlight() && ability.getAppliedPlayers().contains(player.getUniqueId()), "permission restoration reapplies flight");
                    revoke(ability); finish();
                });
            });
        }
        EntityDamageByBlockEvent bushDamage() {
            var event = new EntityDamageByBlockEvent(player.getWorld().getBlockAt(3, -60, 3), null, player,
                    EntityDamageEvent.DamageCause.CONTACT, DamageSource.builder(DamageType.SWEET_BERRY_BUSH).build(), 2);
            Bukkit.getPluginManager().callEvent(event); return event;
        }
        GenericGameEvent gameEvent(GameEvent type) {
            var event = new GenericGameEvent(type, player.getLocation(), player, 16, false);
            Bukkit.getPluginManager().callEvent(event); return event;
        }
        WardenAngerChangeEvent anger(Warden warden, int old, int next) {
            var event = new WardenAngerChangeEvent(warden, player, old, next);
            Bukkit.getPluginManager().callEvent(event); return event;
        }
        String renderChat() {
            var event = new AsyncChatEvent(false, player, new HashSet<>(Set.of(player)),
                    (source, display, message, viewer) -> Component.text("original:").append(display).append(Component.text(":")).append(message),
                    Component.text("probe-message"), Component.text("probe-message"), net.kyori.adventure.chat.SignedMessage.system("probe-message", Component.text("probe-message")));
            Bukkit.getPluginManager().callEvent(event);
            return PlainTextComponentSerializer.plainText().serialize(event.renderer().render(player, player.displayName(), event.message(), player));
        }
        void finish() {
            api.morphManager().unMorph(player, true);
            for (var ability : api.abilityManager().getRegistedAbilities())
                check(!ability.getAppliedPlayers().contains(player.getUniqueId()), "unmorph removes " + ability.getIdentifier());
            check(!player.getAllowFlight(), "unmorph restores survival flight");
            done = true; cleanup();
            getLogger().info("FM_GAMEPLAY_PASS " + token + " " + gson.toJson(Map.of("fixture", fixture, "assertions", assertions, "skills", skillCoverage, "abilities", abilityCoverage)));
            active = null;
        }
        void cleanup() {
            try {
                api.morphManager().unMorph(player, true);
                permissions.forEach(player::removeAttachment);
                for (var entity : spawned) if (entity.isValid()) entity.getScheduler().run(GameplayProbe.this, task -> entity.remove(), null);
                blocks.forEach((location, material) -> location.getBlock().setType(material, false));
                player.setFireTicks(0); player.setSneaking(false); player.setVelocity(new Vector());
                SnowyAbility.unBlockPlayer(player, this);
                var config = api.getGlobalDependency(MorphConfigManager.class);
                config.getBindable(ConfigOptions.ALLOW_CHAT_OVERRIDE).set(false);
                config.getBindable(ConfigOptions.CHAT_OVERRIDE_USE_CUSTOM_RENDERER).set(true);
            } catch (Throwable error) { getLogger().log(Level.SEVERE, "FM_GAMEPLAY_FAIL cleanup " + token, error); }
        }
    }
}
