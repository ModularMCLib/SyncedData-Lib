package com.modularmc.synceddata.api.sync_system;

import com.modularmc.synceddata.SyncedData;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class SyncedComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, SyncedData.MOD_ID);

    public static final Supplier<DataComponentType<CompoundTag>> BLOCK_ITEM_DATA = COMPONENTS.register("block_item_data",
            () -> DataComponentType.<CompoundTag>builder().persistent(CompoundTag.CODEC).build());
}
