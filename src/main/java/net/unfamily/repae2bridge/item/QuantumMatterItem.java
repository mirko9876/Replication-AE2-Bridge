package net.unfamily.repae2bridge.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.client.color.item.ItemColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class QuantumMatterItem extends MatterItem {
    public QuantumMatterItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
} 