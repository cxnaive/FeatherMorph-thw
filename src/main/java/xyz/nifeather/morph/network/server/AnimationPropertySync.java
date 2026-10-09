package xyz.nifeather.morph.network.server;

import com.google.gson.Gson;
import net.minecraft.world.entity.Pose;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import xyz.nifeather.morph.misc.AnimationNames;
import xyz.nifeather.morph.network.commands.S2C.AbstractS2CCommand;
import xyz.nifeather.morph.network.commands.S2C.S2CDiscardTemporaryPropertiesCommand;
import xyz.nifeather.morph.network.commands.S2C.S2CUpdateTemporaryPropertiesCommand;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Translates renderer animation stages into API 19's temporary property overlays. */
public final class AnimationPropertySync
{
    private static final Gson gson = new Gson();
    private static final String POSE = "entity/static_pose";
    private static final String INVISIBLE = "living_entity/invisible";
    private static final String BED_POS = "living_entity/bed_pos";

    private final EntityType entityType;
    private final Supplier<Location> location;
    private final Consumer<AbstractS2CCommand<?>> sender;
    private final Map<String, String> temporaryProperties = new HashMap<>();
    private boolean wardenVanished;

    public AnimationPropertySync(EntityType entityType, Supplier<Location> location,
                                 Consumer<AbstractS2CCommand<?>> sender)
    {
        this.entityType = entityType;
        this.location = location;
        this.sender = sender;
    }

    public synchronized Map<String, String> properties()
    {
        return Map.copyOf(temporaryProperties);
    }

    public synchronized void sync()
    {
        if (!temporaryProperties.isEmpty())
            sender.accept(new S2CUpdateTemporaryPropertiesCommand(properties()));
    }

    public synchronized void onPersistentWrite(String property)
    {
        temporaryProperties.remove(property);
    }

    public synchronized void play(String animation)
    {
        if (entityType == EntityType.WARDEN)
        {
            // Match WardenWatcher's guards while the disguise is underground.
            switch (animation)
            {
                case AnimationNames.VANISH -> wardenVanished = true;
                case AnimationNames.APPEAR -> wardenVanished = false;
                case AnimationNames.ROAR, AnimationNames.SNIFF, AnimationNames.DIGDOWN, AnimationNames.TRY_RESET ->
                {
                    if (wardenVanished) return;
                }
                default -> { }
            }
        }

        Map<String, String> next;
        if (animation.equals(AnimationNames.RESET) || animation.equals(AnimationNames.TRY_RESET))
            next = Map.of();
        else
            next = propertiesFor(animation);

        // Internal execution stages and sounds do not replace the visual state.
        if (next == null) return;

        var discarded = temporaryProperties.keySet().stream().filter(key -> !next.containsKey(key)).toList();
        if (!discarded.isEmpty())
            sender.accept(new S2CDiscardTemporaryPropertiesCommand(discarded));

        var changed = new HashMap<String, String>();
        next.forEach((key, value) ->
        {
            if (!value.equals(temporaryProperties.get(key)))
                changed.put(key, value);
        });

        temporaryProperties.clear();
        temporaryProperties.putAll(next);
        if (!changed.isEmpty())
            sender.accept(new S2CUpdateTemporaryPropertiesCommand(changed));
    }

    private Map<String, String> propertiesFor(String animation)
    {
        return switch (entityType)
        {
            case CAT -> switch (animation)
            {
                case AnimationNames.LAY_START -> Map.of("cat/lying", "true", "cat/sitting", "false");
                case AnimationNames.SIT -> Map.of("cat/sitting", "true", "cat/lying", "false");
                case AnimationNames.STANDUP -> Map.of();
                default -> null;
            };
            case WOLF -> switch (animation)
            {
                case AnimationNames.SIT -> Map.of("wolf/sitting", "true");
                case AnimationNames.STANDUP -> Map.of();
                default -> null;
            };
            case FOX -> switch (animation)
            {
                case AnimationNames.SLEEP -> Map.of("fox/status", "SLEEPING");
                case AnimationNames.SIT -> Map.of("fox/status", "SITTING");
                case AnimationNames.STANDUP -> Map.of();
                default -> null;
            };
            case PANDA -> switch (animation)
            {
                case AnimationNames.SIT -> Map.of("panda/sitting", "true");
                case AnimationNames.STANDUP -> Map.of();
                default -> null;
            };
            case ALLAY -> dance(animation, "allay/dancing");
            case PIGLIN -> dance(animation, "piglin/dancing");
            case ARMADILLO -> switch (animation)
            {
                case AnimationNames.PANIC_ROLLING -> Map.of("armadillo/state", "ROLLING");
                case AnimationNames.PANIC_SCARED -> Map.of("armadillo/state", "SCARED");
                case AnimationNames.PANIC_UNROLLING -> Map.of("armadillo/state", "UNROLLING");
                case AnimationNames.PANIC_IDLE -> Map.of();
                default -> null;
            };
            case SHULKER -> switch (animation)
            {
                case AnimationNames.PEEK_START -> Map.of("shulker/shell_height", "30");
                case AnimationNames.OPEN_START -> Map.of("shulker/shell_height", "100");
                case AnimationNames.PEEK_STOP, AnimationNames.OPEN_STOP -> Map.of();
                default -> null;
            };
            case PUFFERFISH -> switch (animation)
            {
                case AnimationNames.INFLATE -> Map.of("pufferfish/puff_state", "LARGE");
                case AnimationNames.DEFLATE -> Map.of();
                default -> null;
            };
            case SNIFFER -> animation.equals(AnimationNames.SNIFF) ? Map.of("sniffer/state", "SNIFFING") : null;
            case FROG -> animation.equals(AnimationNames.EAT) ? Map.of(POSE, pose(Pose.USING_TONGUE)) : null;
            case CREAKING -> switch (animation)
            {
                case AnimationNames.MAKE_ACTIVE -> Map.of("creaking/eyes_glowing", "true");
                case AnimationNames.MAKE_INACTIVE -> Map.of();
                default -> null;
            };
            case WARDEN -> switch (animation)
            {
                case AnimationNames.ROAR -> Map.of(POSE, pose(Pose.ROARING));
                case AnimationNames.SNIFF -> Map.of(POSE, pose(Pose.SNIFFING));
                case AnimationNames.DIGDOWN -> Map.of(POSE, pose(Pose.DIGGING));
                case AnimationNames.VANISH -> Map.of(INVISIBLE, "true");
                case AnimationNames.APPEAR -> Map.of(POSE, pose(Pose.EMERGING));
                default -> null;
            };
            case PLAYER, MANNEQUIN -> switch (animation)
            {
                case AnimationNames.LAY ->
                {
                    var position = location.get();
                    yield Map.of(POSE, pose(Pose.SLEEPING), BED_POS,
                            gson.toJson(new int[]{position.getBlockX(), position.getBlockY(), position.getBlockZ()}));
                }
                case AnimationNames.CRAWL -> Map.of(POSE, pose(Pose.SWIMMING));
                case AnimationNames.STANDUP -> Map.of();
                default -> null;
            };
            default -> null;
        };
    }

    private static String pose(Pose pose)
    {
        return Integer.toString(pose.ordinal());
    }

    private static Map<String, String> dance(String animation, String property)
    {
        return switch (animation)
        {
            case AnimationNames.DANCE_START -> Map.of(property, "true");
            case AnimationNames.STOP -> Map.of();
            default -> null;
        };
    }
}
