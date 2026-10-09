package xyz.nifeather.morph.backends.server.renderer.network.datawatcher.values;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import xyz.nifeather.morph.backends.server.renderer.network.datawatcher.values.basetypes.AgeableMobValues;

public class SlimeValues extends AgeableMobValues
{
    public final SingleValue<Integer> SIZE = createSingle("slimemagma_size", 1, EntityDataTypes.INT);

    public SlimeValues()
    {
        super();

        registerSingle(SIZE);
    }
}
