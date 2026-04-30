package com.modularmc.synceddata.test;

import com.modularmc.synceddata.SyncedData;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.mojang.serialization.MapCodec;

public final class SyncedTestContent {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SyncedData.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SyncedData.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, SyncedData.MOD_ID);

    public static final DeferredBlock<TestSyncBlock> TEST_SYNC_BLOCK = BLOCKS.registerBlock("test_sync_block", TestSyncBlock::new, properties -> properties.strength(1.5F));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> TEST_SYNC_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("test_sync_block", TEST_SYNC_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SyncTestFixtures.TestManagedBlockEntity>> TEST_SYNC_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register("test_sync_block_entity",
            () -> new BlockEntityType<>(SyncTestFixtures.TestManagedBlockEntity::new, TEST_SYNC_BLOCK.get()));

    private SyncedTestContent() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITY_TYPES.register(bus);
    }

    public static final class TestSyncBlock extends BaseEntityBlock {

        public static final MapCodec<TestSyncBlock> CODEC = simpleCodec(TestSyncBlock::new);

        public TestSyncBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return RenderShape.MODEL;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SyncTestFixtures.TestManagedBlockEntity(pos, state);
        }
    }
}
