package com.modularmc.synceddata.api.sync_system.holder;

import com.modularmc.synceddata.SyncedData;
import com.modularmc.synceddata.api.sync_system.ISyncManaged;
import com.modularmc.synceddata.api.sync_system.SyncedComponents;
import com.modularmc.synceddata.api.sync_system.meta.ClassSyncData;
import com.modularmc.synceddata.api.sync_system.meta.FieldSyncData;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import java.util.*;

public class SyncDataHolder {

    private final ClassSyncData syncData;
    private final ISyncManaged holder;

    private final Map<FieldSyncData, Object> cachedValues = new Reference2ReferenceOpenHashMap<>();
    private CompoundTag pendingClientChanges = null;
    private boolean fullSyncPending = true;

    public SyncDataHolder(ISyncManaged o) {
        holder = o;
        syncData = ClassSyncData.getClassData(o.getClass());
        for (FieldSyncData field : syncData.getClientSyncFields()) {
            cachedValues.put(field, field.handle.get(holder));
        }
    }

    public void resyncAllFields() {
        fullSyncPending = true;
    }

    public boolean scanAndMarkChanges(HolderLookup.Provider registries) {
        CompoundTag changes = new CompoundTag();
        boolean hasChanges = fullSyncPending;

        for (FieldSyncData field : syncData.getClientSyncFields()) {
            Object currentValue = field.handle.get(holder);
            Object previousValue = cachedValues.get(field);
            boolean changed = fullSyncPending || !Objects.equals(currentValue, previousValue);
            if (changed) {
                Tag serialized = encodeField(field, currentValue, registries);
                changes.put(field.nbtSaveKey, serialized);
                cachedValues.put(field, currentValue);
                hasChanges = true;
            }
        }

        if (hasChanges) {
            pendingClientChanges = changes;
        }
        fullSyncPending = false;
        return hasChanges;
    }

    public CompoundTag getPendingChanges() {
        CompoundTag changes = pendingClientChanges;
        pendingClientChanges = null;
        return changes != null ? changes : new CompoundTag();
    }

    public byte[] collectClientNetworkChanges(RegistryAccess registries, boolean force) {
        if (force) {
            CompoundTag forcedChanges = new CompoundTag();
            for (FieldSyncData field : syncData.getClientSyncFields()) {
                Object currentValue = field.handle.get(holder);
                forcedChanges.put(field.nbtSaveKey, encodeField(field, currentValue, registries));
                cachedValues.put(field, currentValue);
            }
            pendingClientChanges = forcedChanges;
            fullSyncPending = false;
        }

        CompoundTag pendingChanges = getPendingChanges();
        if (pendingChanges.isEmpty()) {
            return new byte[0];
        }

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            FieldSyncData[] fields = syncData.getOrderedClientSyncFields();
            for (int i = 0; i < fields.length; i++) {
                FieldSyncData field = fields[i];
                Tag value = pendingChanges.get(field.nbtSaveKey);
                if (value == null) {
                    continue;
                }
                buf.writeVarInt(i);
                buf.writeNbt(value);
            }
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    public CompoundTag collectServerChanges(HolderLookup.Provider registries) {
        CompoundTag changes = new CompoundTag();
        for (FieldSyncData field : syncData.getServerUpdateFields()) {
            Object currentValue = field.handle.get(holder);
            Object previousValue = cachedValues.get(field);
            if (!Objects.equals(currentValue, previousValue)) {
                changes.put(field.fieldName, encodeField(field, currentValue, registries));
                cachedValues.put(field, currentValue);
            }
        }
        return changes;
    }

    public byte[] collectServerNetworkChanges(RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            boolean wroteAny = false;
            FieldSyncData[] fields = syncData.getOrderedServerUpdateFields();
            for (int i = 0; i < fields.length; i++) {
                FieldSyncData field = fields[i];
                Object currentValue = field.handle.get(holder);
                Object previousValue = cachedValues.get(field);
                if (Objects.equals(currentValue, previousValue)) {
                    continue;
                }
                buf.writeVarInt(i);
                buf.writeNbt(encodeField(field, currentValue, registries));
                cachedValues.put(field, currentValue);
                wroteAny = true;
            }
            if (!wroteAny) {
                return new byte[0];
            }
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    public CompoundTag serializeToSaveNBT(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        for (var field : syncData.getWorldSaveFields()) {
            Object value = field.handle.get(holder);
            Tag serialized = encodeField(field, value, registries);
            tag.put(field.nbtSaveKey, serialized);
        }
        return tag;
    }

    public CompoundTag serializeToItemNBT(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        for (var field : syncData.getItemSaveFields()) {
            Object value = field.handle.get(holder);
            Tag serialized = encodeField(field, value, registries);
            tag.put(field.itemNbtKey, serialized);
        }
        return tag;
    }

    public CompoundTag serializeFullClientSyncNBT(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        for (var field : syncData.getClientSyncFields()) {
            Object value = field.handle.get(holder);
            Tag serialized = encodeField(field, value, registries);
            tag.put(field.nbtSaveKey, serialized);
            cachedValues.put(field, value);
        }
        fullSyncPending = false;
        return tag;
    }

    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag, boolean readingClientFields) {
        Set<FieldSyncData> fields = readingClientFields ? syncData.getClientSyncFields() :
                syncData.getWorldSaveFields();

        for (var field : fields) {
            Tag savedValue = tag.get(field.nbtSaveKey);
            if (savedValue != null) {
                Object decoded = decodeField(field, savedValue, field.handle.get(holder), registries);
                if (decoded != null) {
                    field.handle.set(holder, decoded);
                }
            }

            if (readingClientFields) {
                cachedValues.put(field, field.handle.get(holder));
                for (var listener : field.changeListenerHandles) {
                    try {
                        listener.invoke(holder);
                    } catch (Throwable e) {
                        SyncedData.LOGGER.error("Sync: Error invoking change listener for field {}", field.fieldName);
                        SyncedData.LOGGER.error(e);
                    }
                }
                if (field.triggerClientRerender) holder.scheduleRenderUpdate();
            }
        }
    }

    public void deserializeItemNBT(HolderLookup.Provider registries, CompoundTag tag) {
        for (var field : syncData.getItemSaveFields()) {
            Tag savedValue = tag.get(field.itemNbtKey);
            if (savedValue != null) {
                Object decoded = decodeField(field, savedValue, field.handle.get(holder), registries);
                if (decoded != null) {
                    field.handle.set(holder, decoded);
                }
            }
        }
    }

    public void applyServerUpdate(HolderLookup.Provider registries, CompoundTag tag) {
        for (var field : syncData.getServerUpdateFields()) {
            Tag value = tag.get(field.fieldName);
            if (value != null) {
                Object decoded = decodeField(field, value, field.handle.get(holder), registries);
                if (decoded != null) {
                    field.handle.set(holder, decoded);
                }
            }
        }
    }

    public void applyServerNetworkUpdate(RegistryAccess registries, byte[] data) {
        if (data.length == 0) {
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
        try {
            FieldSyncData[] fields = syncData.getOrderedServerUpdateFields();
            while (buf.isReadable()) {
                int index = buf.readVarInt();
                if (index < 0 || index >= fields.length) {
                    throw new IllegalArgumentException("Invalid server sync field index: " + index);
                }
                FieldSyncData field = fields[index];
                Tag value = buf.readNbt(NbtAccounter.unlimitedHeap());
                if (value != null) {
                    Object decoded = decodeField(field, value, field.handle.get(holder), registries);
                    if (decoded != null) {
                        field.handle.set(holder, decoded);
                    }
                }
            }
        } finally {
            buf.release();
        }
    }

    public void applyClientNetworkUpdate(RegistryAccess registries, byte[] data) {
        if (data.length == 0) {
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
        try {
            FieldSyncData[] fields = syncData.getOrderedClientSyncFields();
            while (buf.isReadable()) {
                int index = buf.readVarInt();
                if (index < 0 || index >= fields.length) {
                    throw new IllegalArgumentException("Invalid client sync field index: " + index);
                }
                FieldSyncData field = fields[index];
                Tag value = buf.readNbt(NbtAccounter.unlimitedHeap());
                if (value != null) {
                    Object decoded = decodeField(field, value, field.handle.get(holder), registries);
                    if (decoded != null) {
                        field.handle.set(holder, decoded);
                    }
                    cachedValues.put(field, field.handle.get(holder));
                    for (var listener : field.changeListenerHandles) {
                        try {
                            listener.invoke(holder);
                        } catch (Throwable e) {
                            SyncedData.LOGGER.error("Sync: Error invoking change listener for field {}", field.fieldName);
                            SyncedData.LOGGER.error(e);
                        }
                    }
                    if (field.triggerClientRerender) {
                        holder.scheduleRenderUpdate();
                    }
                }
            }
        } finally {
            buf.release();
        }
    }

    public void applyToItemStack(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag data = serializeToItemNBT(registries);
        if (!data.isEmpty()) {
            stack.set(SyncedComponents.BLOCK_ITEM_DATA.get(), data);
        }
    }

    public void loadFromItemStack(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag data = stack.get(SyncedComponents.BLOCK_ITEM_DATA.get());
        if (data != null) {
            deserializeItemNBT(registries, data);
        }
    }

    private Tag encodeField(FieldSyncData field, Object value, HolderLookup.Provider registries) {
        if (value == null) {
            var nullTag = new CompoundTag();
            nullTag.putBoolean("null", true);
            return nullTag;
        }
        if (field.codec != null) {
            Codec<Object> codec = field.codec;
            DataResult<Tag> result = codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value);
            return result.getOrThrow();
        }
        if (field.isSyncManaged && value instanceof ISyncManaged syncObj) {
            return syncObj.getSyncDataHolder().serializeToSaveNBT(registries);
        }
        SyncedData.LOGGER.error("Sync: No codec for field {} in {}", field.fieldName, holder.getClass());
        return new CompoundTag();
    }

    private Object decodeField(FieldSyncData field, Tag tag, Object currentValue, HolderLookup.Provider registries) {
        if (tag instanceof CompoundTag compound && compound.getBoolean("null").orElse(false)) {
            return null;
        }
        if (tag instanceof CompoundTag compound && compound.isEmpty()) {
            return currentValue;
        }
        if (field.codec != null) {
            Codec<Object> codec = field.codec;
            DataResult<Object> result = codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);
            return result.getOrThrow();
        }
        if (field.isSyncManaged && tag instanceof CompoundTag compound) {
            if (currentValue instanceof ISyncManaged syncObj) {
                syncObj.getSyncDataHolder().deserializeNBT(registries, compound, false);
                return currentValue;
            }
        }
        SyncedData.LOGGER.error("Sync: No codec for field {} in {}", field.fieldName, holder.getClass());
        return currentValue;
    }

}
