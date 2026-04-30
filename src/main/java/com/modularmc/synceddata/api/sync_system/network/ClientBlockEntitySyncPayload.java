package com.modularmc.synceddata.api.sync_system.network;

import com.modularmc.synceddata.SyncedData;
import com.modularmc.synceddata.api.sync_system.ManagedSyncBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.jspecify.annotations.NonNull;

public record ClientBlockEntitySyncPayload(long pos, byte[] data) implements CustomPacketPayload {

    public static final Identifier ID = SyncedData.id("client_be_sync");
    public static final Type<ClientBlockEntitySyncPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientBlockEntitySyncPayload> CODEC = StreamCodec.ofMember(
            ClientBlockEntitySyncPayload::write,
            ClientBlockEntitySyncPayload::decode);

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(pos);
        buf.writeByteArray(data);
    }

    private static ClientBlockEntitySyncPayload decode(RegistryFriendlyByteBuf buf) {
        return new ClientBlockEntitySyncPayload(buf.readLong(), buf.readByteArray());
    }

    public static void execute(ClientBlockEntitySyncPayload packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!(serverPlayer.level().getBlockEntity(BlockPos.of(packet.pos)) instanceof ManagedSyncBlockEntity blockEntity)) {
            return;
        }
        blockEntity.handleClientUpdate(serverPlayer.level().registryAccess(), packet.data);
        blockEntity.markAsChanged();
    }
}
