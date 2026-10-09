package xyz.nifeather.morph.backends.server.renderer.network.datawatcher.watchers.types;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import xyz.nifeather.morph.backends.server.renderer.network.registries.ValueIndex;

public class SulfurCubeWatcher extends AgeableMobWatcher
{
    public SulfurCubeWatcher(Player bindingPlayer)
    {
        super(bindingPlayer, EntityType.SULFUR_CUBE);
    }

    @Override
    protected void initRegistry()
    {
        super.initRegistry();

        register(ValueIndex.SULFUR_CUBE);
    }
}
