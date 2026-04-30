package com.modularmc.synceddata.test;

import com.modularmc.synceddata.api.blockentity.BlockEntityCreationInfo;
import com.modularmc.synceddata.api.sync_system.ISyncManaged;
import com.modularmc.synceddata.api.sync_system.ManagedSyncBlockEntity;
import com.modularmc.synceddata.api.sync_system.annotations.ClientFieldChangeListener;
import com.modularmc.synceddata.api.sync_system.annotations.ItemSave;
import com.modularmc.synceddata.api.sync_system.annotations.RerenderOnChanged;
import com.modularmc.synceddata.api.sync_system.annotations.SaveField;
import com.modularmc.synceddata.api.sync_system.annotations.SyncBoth;
import com.modularmc.synceddata.api.sync_system.annotations.SyncToClient;
import com.modularmc.synceddata.api.sync_system.holder.ItemSyncHolder;
import com.modularmc.synceddata.api.sync_system.holder.SyncDataHolder;
import com.modularmc.synceddata.api.sync_system.meta.FieldCodecs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import com.mojang.serialization.Codec;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class SyncTestFixtures {

    private SyncTestFixtures() {}

    enum CodecMode {
        IDLE,
        ACTIVE,
        ERROR
    }

    record CustomPayload(String name, int value) {}

    @SuppressWarnings("unchecked")
    static <T> void roundTrip(GameTestHelper helper, Class<T> type, T value) {
        Codec<?> codec = FieldCodecs.get(type);
        if (codec == null) {
            helper.fail("No codec for " + type.getSimpleName());
            return;
        }

        try {
            var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag encoded = ((Codec<T>) codec).encodeStart(ops, value).getOrThrow();
            Object decoded = ((Codec<T>) codec).parse(ops, encoded).getOrThrow();
            if (!matches(value, decoded)) {
                helper.fail("Mismatch " + type.getSimpleName());
            }
        } catch (Exception e) {
            helper.fail(type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean matches(Object expected, Object actual) {
        if (expected instanceof ItemStack expectedStack && actual instanceof ItemStack actualStack) {
            return ItemStack.matches(expectedStack, actualStack);
        }
        if (expected instanceof FluidStack expectedStack && actual instanceof FluidStack actualStack) {
            return FluidStack.matches(expectedStack, actualStack);
        }
        if (expected != null && actual != null && expected.getClass().isArray() && actual.getClass().isArray()) {
            int length = Array.getLength(expected);
            if (length != Array.getLength(actual)) return false;
            for (int i = 0; i < length; i++) {
                if (!Objects.equals(Array.get(expected, i), Array.get(actual, i))) return false;
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    static void registerCustomPayloadCodec() {
        FieldCodecs.register(CustomPayload.class, Codec.STRING.xmap(
                s -> {
                    String[] parts = s.split(":");
                    return new CustomPayload(parts[0], Integer.parseInt(parts[1]));
                },
                payload -> payload.name + ":" + payload.value));
    }

    static CompoundTag sampleTag() {
        var tag = new CompoundTag();
        tag.putString("k", "v");
        return tag;
    }

    static CompoundTag clientUpdateTag() {
        var tag = new CompoundTag();
        tag.putBoolean("active", true);
        tag.putLong("lastTime", 999L);
        tag.putInt("target", 50);
        return tag;
    }

    static TestBlockEntity populatedBlockEntity() {
        var blockEntity = new TestBlockEntity();
        blockEntity.energy = 500;
        blockEntity.owner = UUID.randomUUID();
        blockEntity.config = "x";
        blockEntity.inv = 27;
        return blockEntity;
    }

    static TestItemData populatedItemData() {
        var itemData = new TestItemData();
        itemData.e = 500;
        itemData.m = 1000;
        return itemData;
    }

    static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    abstract static class MockSyncManaged implements ISyncManaged {

        private final SyncDataHolder syncDataHolder = new SyncDataHolder(this);

        @Override
        public SyncDataHolder getSyncDataHolder() {
            return syncDataHolder;
        }

        @Override
        public void scheduleRenderUpdate() {}

        @Override
        public void markAsChanged() {}
    }

    static class TestBlockEntity extends MockSyncManaged {

        @SaveField
        int energy;
        @SaveField(nbtKey = "owner_id")
        UUID owner;
        @ItemSave
        String config;
        @ItemSave(nbtKey = "inv")
        int inv;
        @SyncToClient
        boolean active;
        @SyncToClient
        long lastTime;
        @SyncBoth
        int target;
        @SyncBoth
        Identifier block;
        @RerenderOnChanged
        @SyncToClient
        float prog;
        final List<String> callbacks = new ArrayList<>();

        @ClientFieldChangeListener(fieldName = "active")
        void onActive() {
            callbacks.add("active");
        }

        @ClientFieldChangeListener(fieldName = "lastTime")
        void onTime() {
            callbacks.add("lastTime");
        }

        @ClientFieldChangeListener(fieldName = "target")
        void onTarget() {
            callbacks.add("target");
        }
    }

    static final class TestManagedBlockEntity extends ManagedSyncBlockEntity {

        @SaveField
        int energy;
        @ItemSave
        String config;
        @SyncBoth
        int target;
        final List<String> callbacks = new ArrayList<>();

        TestManagedBlockEntity(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            super(SyncedTestContent.TEST_SYNC_BLOCK_ENTITY.get(), pos, state);
        }

        TestManagedBlockEntity() {
            super(new BlockEntityCreationInfo(SyncedTestContent.TEST_SYNC_BLOCK_ENTITY.get(), BlockPos.ZERO,
                    SyncedTestContent.TEST_SYNC_BLOCK.get().defaultBlockState()));
        }

        @ClientFieldChangeListener(fieldName = "target")
        void onTargetChanged() {
            callbacks.add("target");
        }

        @Override
        public void scheduleRenderUpdate() {}

        DataComponentMap collectItemComponentsForTest() {
            DataComponentMap.Builder builder = DataComponentMap.builder();
            collectImplicitComponents(builder);
            return builder.build();
        }

        void applyItemComponentsForTest(DataComponentGetter components) {
            applyImplicitComponents(components);
        }
    }

    static class TestItemData implements ISyncManaged {

        final ItemSyncHolder itemSyncHolder = new ItemSyncHolder(this);
        @ItemSave
        int e;
        @ItemSave
        int m;

        @Override
        public SyncDataHolder getSyncDataHolder() {
            return itemSyncHolder.getSyncDataHolder();
        }

        @Override
        public void scheduleRenderUpdate() {}

        @Override
        public void markAsChanged() {}
    }

    static class InvalidListenerBlockEntity extends MockSyncManaged {

        @SaveField
        int energy;

        @ClientFieldChangeListener(fieldName = "energy")
        void onEnergy() {}
    }

    static class DuplicateClientKeyBlockEntity extends MockSyncManaged {

        @SyncToClient
        int first;
        @SyncToClient
        @SaveField(nbtKey = "first")
        int second;
    }
}
