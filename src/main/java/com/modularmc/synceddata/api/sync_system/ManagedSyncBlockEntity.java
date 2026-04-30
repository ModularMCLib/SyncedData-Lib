package com.modularmc.synceddata.api.sync_system;

import com.modularmc.synceddata.api.blockentity.BlockEntityCreationInfo;
import com.modularmc.synceddata.api.sync_system.holder.SyncDataHolder;
import com.modularmc.synceddata.api.sync_system.network.ClientBlockEntitySyncPayload;
import com.modularmc.synceddata.api.sync_system.network.ServerBlockEntitySyncPayload;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.serialization.MapCodec;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public abstract class ManagedSyncBlockEntity extends BlockEntity implements ISyncManaged {

    @Getter
    protected final SyncDataHolder syncDataHolder = new SyncDataHolder(this);

    public ManagedSyncBlockEntity(BlockEntityCreationInfo info) {
        super(info.type(), info.pos(), info.state());
    }

    public ManagedSyncBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        var registries = Objects.requireNonNull(getLevel()).registryAccess();
        CompoundTag tag = getSyncDataHolder().serializeToSaveNBT(registries)
                .merge(getSyncDataHolder().serializeToItemNBT(registries));
        if (!tag.isEmpty()) {
            output.store("synced", CompoundTag.CODEC, tag);
        }
    }

    @Override
    public void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (getLevel() == null) return;
        var registries = getLevel().registryAccess();
        boolean clientSide = getLevel() instanceof ClientLevel;
        input.read(MapCodec.assumeMapUnsafe(CompoundTag.CODEC)).ifPresent(fullTag -> {
            var synced = fullTag.getCompound("synced").orElse(new CompoundTag());
            if (!synced.isEmpty()) {
                getSyncDataHolder().deserializeNBT(registries, synced, clientSide);
                if (!clientSide) {
                    getSyncDataHolder().deserializeItemNBT(registries, synced);
                }
            }
        });
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        var registries = Objects.requireNonNull(getLevel()).registryAccess();
        components.set(SyncedComponents.BLOCK_ITEM_DATA.get(),
                getSyncDataHolder().serializeToItemNBT(registries));
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        var data = components.get(SyncedComponents.BLOCK_ITEM_DATA.get());
        if (data != null && getLevel() != null) {
            getSyncDataHolder().deserializeItemNBT(getLevel().registryAccess(), data);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        getSyncDataHolder().resyncAllFields();
        return getSyncDataHolder().serializeFullClientSyncNBT(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this,
                (be, r) -> ((ManagedSyncBlockEntity) be).syncDataHolder.getPendingChanges());
    }

    @Override
    public void markAsChanged() {
        setChanged();
    }

    public final void updateTick() {
        setChanged();
        if (getLevel() instanceof ServerLevel serverLevel) {
            if (syncDataHolder.scanAndMarkChanges(serverLevel.registryAccess())) {
                byte[] data = syncDataHolder.collectClientNetworkChanges(serverLevel.registryAccess(), false);
                if (data.length > 0) {
                    PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                            new ChunkPos(getBlockPos().getX() >> 4, getBlockPos().getZ() >> 4),
                            ServerBlockEntitySyncPayload.of(this, data));
                }
            }
        }
    }

    public final void handleClientUpdate(net.minecraft.core.RegistryAccess registries, byte[] data) {
        syncDataHolder.applyServerNetworkUpdate(registries, data);
    }

    public final void pushClientChangesToServer() {
        if (getLevel() instanceof ClientLevel clientLevel) {
            byte[] changes = syncDataHolder.collectServerNetworkChanges(clientLevel.registryAccess());
            if (changes.length > 0) {
                ClientPacketDistributor.sendToServer(new ClientBlockEntitySyncPayload(getBlockPos().asLong(), changes));
            }
        }
    }
}
