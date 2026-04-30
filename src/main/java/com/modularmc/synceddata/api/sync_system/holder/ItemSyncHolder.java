package com.modularmc.synceddata.api.sync_system.holder;

import com.modularmc.synceddata.api.sync_system.ISyncManaged;
import com.modularmc.synceddata.api.sync_system.SyncedComponents;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import lombok.Getter;

/**
 * Wraps an ItemStack with the sync annotation system.
 * All annotated field data uses {@link SyncedComponents#BLOCK_ITEM_DATA} DataComponent.
 */
public final class ItemSyncHolder implements ISyncManaged {

    @Getter
    private final SyncDataHolder syncDataHolder;

    public ItemSyncHolder(ISyncManaged owner) {
        this.syncDataHolder = new SyncDataHolder(owner);
    }

    public void saveToStack(ItemStack stack, HolderLookup.Provider registries) {
        syncDataHolder.applyToItemStack(stack, registries);
    }

    public void loadFromStack(ItemStack stack, HolderLookup.Provider registries, boolean clientSide) {
        syncDataHolder.loadFromItemStack(stack, registries);
        if (clientSide) {
            var data = stack.get(SyncedComponents.BLOCK_ITEM_DATA.get());
            if (data != null) {
                syncDataHolder.deserializeNBT(registries, data, true);
            }
        }
    }

    public boolean scanChanges(HolderLookup.Provider registries) {
        return syncDataHolder.scanAndMarkChanges(registries);
    }

    public void flushToStack(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag pending = syncDataHolder.getPendingChanges();
        if (!pending.isEmpty()) {
            var existing = stack.get(SyncedComponents.BLOCK_ITEM_DATA.get());
            stack.set(SyncedComponents.BLOCK_ITEM_DATA.get(),
                    existing != null ? existing.merge(pending) : pending);
        }
    }

    public void applyServerUpdate(HolderLookup.Provider registries, CompoundTag tag) {
        syncDataHolder.applyServerUpdate(registries, tag);
    }

    @Override
    public void scheduleRenderUpdate() {}

    @Override
    public void markAsChanged() {}
}
