import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;

public final class UpgradeProbe extends JavaPlugin {
    private ClassLoader loader;

    @Override
    public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> verify(), 40);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private void verify() {
        try {
            var morph = Bukkit.getPluginManager().getPlugin("FeatherMorph");
            check(morph != null && morph.isEnabled(), "FeatherMorph enabled");
            var packetEvents = Bukkit.getPluginManager().getPlugin("packetevents");
            check(packetEvents != null && packetEvents.isEnabled()
                    && packetEvents.getDescription().getVersion().equals("2.14.0"), "PacketEvents 2.14.0 enabled");
            check(Bukkit.getMinecraftVersion().equals("26.2"), "native Minecraft 26.2");
            loader = morph.getClass().getClassLoader();
            var utils = load("xyz.nifeather.morph.utilities.EntityTypeUtils");
            var byString = utils.getMethod("getNmsType", String.class);
            var byBukkit = utils.getMethod("getNmsType", EntityType.class);
            var pig = byString.invoke(null, "minecraft:pig");
            check(pig != null, "known entity lookup");
            check(pig == byString.invoke(null, "pig"), "default namespace lookup");
            check(pig == byBukkit.invoke(null, EntityType.PIG), "Bukkit entity lookup");
            check(byString.invoke(null, "feathermorph:not_an_entity") == null, "unknown entity stays null");
            check(byString.invoke(null, "Invalid Entity!") == null, "invalid identifier stays null");
            check(byBukkit.invoke(null, EntityType.UNKNOWN) == null, "unknown Bukkit type stays null");

            // Read both implementations independently, including inherited AgeableMob fields.
            var metadata = new StringJoiner(",", "{", "}");
            compare(metadata, "AGEABLE_MOB", "IS_BABY", "AgeableMob", "DATA_BABY_ID");
            compare(metadata, "AGEABLE_MOB", "AGE_LOCKED", "AgeableMob", "AGE_LOCKED");
            compare(metadata, "COW", "COW_VARIANT", "animal.cow.Cow", "DATA_VARIANT_ID");
            compare(metadata, "COW", "COW_SOUND_VARIANT", "animal.cow.Cow", "DATA_SOUND_VARIANT_ID");
            compare(metadata, "PIG", "PIG_BOOST_TIME", "animal.pig.Pig", "DATA_BOOST_TIME");
            compare(metadata, "PIG", "PIG_VARIANT", "animal.pig.Pig", "DATA_VARIANT_ID");
            compare(metadata, "PIG", "PIG_SOUND_VARIANT", "animal.pig.Pig", "DATA_SOUND_VARIANT_ID");
            compare(metadata, "CHICKEN", "CHICKEN_VARIANT", "animal.chicken.Chicken", "DATA_VARIANT_ID");
            compare(metadata, "CHICKEN", "CHICKEN_SOUND_VARIANT", "animal.chicken.Chicken", "DATA_SOUND_VARIANT_ID");
            compare(metadata, "CAT", "CAT_VARIANT", "animal.feline.Cat", "DATA_VARIANT_ID");
            compare(metadata, "CAT", "IS_LYING", "animal.feline.Cat", "IS_LYING");
            compare(metadata, "CAT", "RELAXED", "animal.feline.Cat", "RELAX_STATE_ONE");
            compare(metadata, "CAT", "COLLAR_COLOR", "animal.feline.Cat", "DATA_COLLAR_COLOR");
            compare(metadata, "CAT", "CAT_SOUND_VARIANT", "animal.feline.Cat", "DATA_SOUND_VARIANT_ID");
            compare(metadata, "WOLF", "BEGGING", "animal.wolf.Wolf", "DATA_INTERESTED_ID");
            compare(metadata, "WOLF", "COLLAR_COLOR", "animal.wolf.Wolf", "DATA_COLLAR_COLOR");
            compare(metadata, "WOLF", "ANGER_TIME", "animal.wolf.Wolf", "DATA_ANGER_END_TIME");
            compare(metadata, "WOLF", "WOLF_VARIANT", "animal.wolf.Wolf", "DATA_VARIANT_ID");
            compare(metadata, "WOLF", "WOLF_SOUND_VARIANT", "animal.wolf.Wolf", "DATA_SOUND_VARIANT_ID");
            compare(metadata, "SLIME_MAGMA", "SIZE", "monster.cubemob.AbstractCubeMob", "ID_SIZE");
            compare(metadata, "SULFUR_CUBE", "SIZE", "monster.cubemob.AbstractCubeMob", "ID_SIZE");
            compare(metadata, "SULFUR_CUBE", "MAX_FUSE", "monster.cubemob.SulfurCube", "MAX_FUSE");
            compare(metadata, "SULFUR_CUBE", "FROM_BUCKET", "monster.cubemob.SulfurCube", "FROM_BUCKET");
            verifyWaypoints();
            getLogger().info("FM_PROBE_METADATA " + metadata);
            getLogger().info("FM_PROBE_PASS registry=6 metadata=23 waypoint_worlds=" + Bukkit.getWorlds().size());
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "FM_PROBE_FAIL startup", error);
        }
    }

    private void compare(StringJoiner results, String group, String property,
                         String nmsName, String nmsField) throws ReflectiveOperationException {
        var values = load("xyz.nifeather.morph.backends.server.renderer.network.registries.ValueIndex")
                .getField(group).get(null);
        var value = values.getClass().getField(property).get(values);
        int index = (int) value.getClass().getMethod("index").invoke(value);
        var field = load("net.minecraft.world.entity." + nmsName).getDeclaredField(nmsField);
        field.setAccessible(true);
        var accessor = field.get(null);
        int expectedIndex = (int) accessor.getClass().getMethod("id").invoke(accessor);
        var serializerClass = load("net.minecraft.network.syncher.EntityDataSerializer");
        var serializer = accessor.getClass().getMethod("serializer").invoke(accessor);
        int expectedType = (int) load("net.minecraft.network.syncher.EntityDataSerializers")
                .getMethod("getSerializedId", serializerClass).invoke(null, serializer);
        var clientVersionClass = load("com.github.retrooper.packetevents.protocol.player.ClientVersion");
        var version = clientVersionClass.getField("V_26_2").get(null);
        var type = value.getClass().getMethod("type").invoke(value);
        int typeId = (int) type.getClass().getMethod("getId", clientVersionClass).invoke(type, version);
        check(index == expectedIndex, group + "." + property + " index " + index + " != " + expectedIndex);
        check(typeId == expectedType, group + "." + property + " serializer " + typeId + " != " + expectedType);
        results.add("\"" + group + "." + property + "\":{\"index\":" + index + ",\"type\":" + typeId + "}");
    }

    private void verifyWaypoints() throws ReflectiveOperationException {
        var api = load("net.minecraft.world.waypoints.WaypointTransmitter");
        var waypoint = Proxy.newProxyInstance(loader, new Class<?>[]{api}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "UpgradeProbeWaypoint";
            case "isTransmittingWaypoint" -> true;
            case "makeWaypointConnectionWith" -> java.util.Optional.empty();
            default -> null;
        });
        var config = load("me.earthme.luminol.config.modules.experiment.CommandConfig");
        var flag = config.getField("waypointsAndWaypointCommand");
        boolean original = flag.getBoolean(null);
        try {
            for (var world : Bukkit.getWorlds()) {
                var nmsWorld = world.getClass().getMethod("getHandle").invoke(world);
                var manager = nmsWorld.getClass().getMethod("getWaypointManager").invoke(nmsWorld);
                check(manager.getClass().getSimpleName().equals("FoliaServerWaypointManager"), "restored Folia waypoint manager");
                var getTransmitters = manager.getClass().getMethod("transmitters");
                var track = manager.getClass().getMethod("trackWaypoint", api);
                var untrack = manager.getClass().getMethod("untrackWaypoint", api);
                flag.setBoolean(null, false);
                track.invoke(manager, waypoint);
                check(!((Set<?>) getTransmitters.invoke(manager)).contains(waypoint), "disabled locator refuses registration");
                flag.setBoolean(null, true);
                try {
                    track.invoke(manager, waypoint);
                    check(((Set<?>) getTransmitters.invoke(manager)).contains(waypoint), "enabled locator registers waypoint");
                } finally {
                    untrack.invoke(manager, waypoint);
                }
                check(!((Set<?>) getTransmitters.invoke(manager)).contains(waypoint), "waypoint removed");
            }
        } finally {
            flag.setBoolean(null, original);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 2 || !args[1].matches("[a-zA-Z0-9_]+")) return false;
        var player = Bukkit.getPlayerExact(args[0]);
        if (player == null) return false;
        player.getScheduler().run(this, task -> {
            try {
                check(Bukkit.isOwnedByCurrentRegion(player), "probe on player owner region");
                var api = load("xyz.nifeather.morph.api.FeatherMorphAPI").getMethod("instance").invoke(null);
                var access = api.getClass().getMethod("directAccess").invoke(api);
                var manager = access.getClass().getMethod("morphManager").invoke(access);
                var state = manager.getClass().getMethod("getDisguiseStateFor", Player.class).invoke(manager, player);
                if (args[1].startsWith("api_")) {
                    check(state != null, "protocol fixture needs a disguise");
                    if (args[1].equals("api_equipment")) {
                        var equipmentClass = load("xyz.nifeather.morph.misc.DisguiseEquipment");
                        var equipment = equipmentClass.getConstructor(Map.class).newInstance(Map.of(
                                EquipmentSlot.BODY, new ItemStack(Material.IRON_HORSE_ARMOR),
                                EquipmentSlot.SADDLE, new ItemStack(Material.SADDLE)));
                        state.getClass().getMethod("setEquipment", equipmentClass).invoke(state, equipment);
                        state.getClass().getMethod("setShowingDisguisedEquipment", boolean.class).invoke(state, true);
                        var output = load("xyz.nifeather.morph.misc.disguiseProperty.OutputHandles")
                                .getMethod("writeEquipment", String.class, equipmentClass).invoke(null, "entity/equipment", equipment);
                        var decoded = (java.util.Optional<?>) load("xyz.nifeather.morph.misc.disguiseProperty.InputHandles")
                                .getMethod("readEquipment", String.class, String.class).invoke(null, "entity/equipment", output);
                        var copy = decoded.orElseThrow();
                        check(((ItemStack) equipmentClass.getMethod("getItem", EquipmentSlot.class).invoke(copy, EquipmentSlot.BODY))
                                .getType() == Material.IRON_HORSE_ARMOR, "BODY round trip");
                        check(((ItemStack) equipmentClass.getMethod("getItem", EquipmentSlot.class).invoke(copy, EquipmentSlot.SADDLE))
                                .getType() == Material.SADDLE, "SADDLE round trip");
                    } else if (args[1].equals("api_refresh")) {
                        manager.getClass().getMethod("refreshClientState", state.getClass()).invoke(manager, state);
                    } else {
                        var properties = state.getClass().getMethod("disguisePropertyHandler").invoke(state);
                        var propertyClass = load("xyz.nifeather.morph.misc.disguiseProperty.SingleProperty");
                        var registry = load("xyz.nifeather.morph.misc.disguiseProperty.DisguiseProperties").getField("INSTANCE").get(null);
                        var property = registry.getClass().getMethod("lookup", String.class).invoke(registry, "entity/custom_name_visible");
                        if (args[1].equals("api_discard"))
                            properties.getClass().getMethod("discardProperty", propertyClass).invoke(properties, property);
                        else if (args[1].equals("api_property"))
                            properties.getClass().getMethod("set", propertyClass, Object.class).invoke(properties, property, true);
                        else throw new AssertionError("Unknown protocol fixture " + args[1]);
                    }
                    getLogger().info("FM_PROBE_PROTOCOL_PASS " + player.getName() + " " + args[1]);
                }
                String id = state == null ? "@none" : (String) state.getClass().getMethod("getDisguiseIdentifier").invoke(state);
                var wrapper = state == null ? null : state.getClass().getMethod("getDisguiseWrapper").invoke(state);
                var uuid = wrapper == null ? player.getUniqueId() : wrapper.getClass().getMethod("getVirtualEntityUUID").invoke(wrapper);
                var box = player.getBoundingBox();
                var metrics = new JsonObject();
                metrics.addProperty("health", player.getHealth());
                metrics.addProperty("maxHealth", player.getAttribute(Attribute.MAX_HEALTH).getValue());
                metrics.addProperty("width", box.getWidthX());
                metrics.addProperty("height", box.getHeight());
                metrics.addProperty("depth", box.getWidthZ());
                metrics.addProperty("difficulty", player.getWorld().getDifficulty().name());
                getLogger().info("FM_PROBE_STATE " + args[1] + " " + id + " " + player.getEntityId() + " " + uuid + " " + metrics);
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "FM_PROBE_FAIL state " + args[1], error);
            }
        }, null);
        return true;
    }
}
