package com.modularmc.synceddata.test;

import com.modularmc.synceddata.api.sync_system.meta.ClassSyncData;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

import static com.modularmc.synceddata.test.SyncTestFixtures.check;
import static com.modularmc.synceddata.test.SyncTestFixtures.clientUpdateTag;
import static com.modularmc.synceddata.test.SyncTestFixtures.populatedBlockEntity;
import static com.modularmc.synceddata.test.SyncTestFixtures.populatedItemData;

public class SyncAnnotationTest {

    @TestHolder(value = "sync_discovery")
    @EmptyTemplate("3")
    @GameTest
    public static void discovery(GameTestHelper helper) {
        var data = ClassSyncData.getClassData(SyncTestFixtures.TestBlockEntity.class);
        check(helper, data.getWorldSaveFields().size() == 2, "save=" + data.getWorldSaveFields().size());
        check(helper, data.getItemSaveFields().size() == 2, "item=" + data.getItemSaveFields().size());
        check(helper, data.getClientSyncFields().size() == 5, "s2c=" + data.getClientSyncFields().size());
        check(helper, data.getBothSyncFields().size() == 2, "both=" + data.getBothSyncFields().size());
        helper.succeed();
    }

    @TestHolder(value = "sync_nbtkey")
    @EmptyTemplate("3")
    @GameTest
    public static void nbtKeys(GameTestHelper helper) {
        var data = ClassSyncData.getClassData(SyncTestFixtures.TestBlockEntity.class);
        check(helper, data.getWorldSaveFields().stream().anyMatch(f -> "owner_id".equals(f.nbtSaveKey)), "nbtKey");
        check(helper, data.getItemSaveFields().stream().anyMatch(f -> "inv".equals(f.itemNbtKey)), "itemNbtKey");
        helper.succeed();
    }

    @TestHolder(value = "sync_listeners")
    @EmptyTemplate("3")
    @GameTest
    public static void listeners(GameTestHelper helper) {
        var blockEntity = new SyncTestFixtures.TestBlockEntity();
        blockEntity.getSyncDataHolder().deserializeNBT(helper.getLevel().registryAccess(), clientUpdateTag(), true);
        check(helper, blockEntity.callbacks.size() == 3, "3 listeners, got " + blockEntity.callbacks.size());
        helper.succeed();
    }

    @TestHolder(value = "sync_serialize")
    @EmptyTemplate("3")
    @GameTest
    public static void serialize(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var blockEntity = populatedBlockEntity();
        var saveTag = blockEntity.getSyncDataHolder().serializeToSaveNBT(registries);
        var itemTag = blockEntity.getSyncDataHolder().serializeToItemNBT(registries);
        check(helper, saveTag.contains("energy"), "save");
        check(helper, itemTag.contains("config"), "item");

        var restored = new SyncTestFixtures.TestBlockEntity();
        restored.getSyncDataHolder().deserializeNBT(registries, saveTag, false);
        restored.getSyncDataHolder().deserializeItemNBT(registries, itemTag);
        check(helper, restored.energy == 500, "rt");
        helper.succeed();
    }

    @TestHolder(value = "sync_nochange")
    @EmptyTemplate("3")
    @GameTest
    public static void noChange(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var blockEntity = new SyncTestFixtures.TestBlockEntity();
        blockEntity.getSyncDataHolder().serializeFullClientSyncNBT(registries);
        check(helper, !blockEntity.getSyncDataHolder().scanAndMarkChanges(registries), "no change");
        helper.succeed();
    }

    @TestHolder(value = "sync_server_update")
    @EmptyTemplate("3")
    @GameTest
    public static void serverUpdate(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var blockEntity = new SyncTestFixtures.TestBlockEntity();
        blockEntity.target = 77;
        blockEntity.block = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "stone");
        blockEntity.active = true;

        byte[] changes = blockEntity.getSyncDataHolder().collectServerNetworkChanges(registries);
        check(helper, changes.length > 0, "server network change missing");

        var restored = new SyncTestFixtures.TestBlockEntity();
        restored.getSyncDataHolder().applyServerNetworkUpdate(registries, changes);
        check(helper, restored.target == 77, "target not applied");
        check(helper, net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "stone").equals(restored.block),
                "block not applied");
        check(helper, !restored.active, "client field should stay untouched");
        helper.succeed();
    }

    @TestHolder(value = "sync_client_network_update")
    @EmptyTemplate("3")
    @GameTest
    public static void clientNetworkUpdate(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var blockEntity = new SyncTestFixtures.TestBlockEntity();
        blockEntity.active = true;
        blockEntity.lastTime = 99L;
        blockEntity.target = 12;

        check(helper, blockEntity.getSyncDataHolder().scanAndMarkChanges(registries), "scan should detect client changes");
        byte[] changes = blockEntity.getSyncDataHolder().collectClientNetworkChanges(registries, false);
        check(helper, changes.length > 0, "client network change missing");

        var restored = new SyncTestFixtures.TestBlockEntity();
        restored.getSyncDataHolder().applyClientNetworkUpdate(registries, changes);
        check(helper, restored.active, "active not applied");
        check(helper, restored.lastTime == 99L, "lastTime not applied");
        check(helper, restored.target == 12, "target not applied");
        check(helper, restored.callbacks.size() == 3, "listeners not invoked");
        helper.succeed();
    }

    @TestHolder(value = "sync_invalid_listener")
    @EmptyTemplate("3")
    @GameTest
    public static void invalidListener(GameTestHelper helper) {
        try {
            ClassSyncData.getClassData(SyncTestFixtures.InvalidListenerBlockEntity.class);
            helper.fail("invalid listener target should throw");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    @TestHolder(value = "sync_duplicate_client_key")
    @EmptyTemplate("3")
    @GameTest
    public static void duplicateClientKey(GameTestHelper helper) {
        try {
            ClassSyncData.getClassData(SyncTestFixtures.DuplicateClientKeyBlockEntity.class);
            helper.fail("duplicate client key should throw");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    @TestHolder(value = "sync_item")
    @EmptyTemplate("3")
    @GameTest
    public static void item(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var itemData = populatedItemData();
        var stack = new ItemStack(Items.STONE);
        itemData.itemSyncHolder.saveToStack(stack, registries);

        var restored = new SyncTestFixtures.TestItemData();
        restored.itemSyncHolder.loadFromStack(stack, registries, false);
        check(helper, restored.e == 500, "item rt");
        helper.succeed();
    }

    @TestHolder(value = "sync_registered_block_entity")
    @EmptyTemplate("3")
    @GameTest
    public static void registeredBlockEntity(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, SyncedTestContent.TEST_SYNC_BLOCK.get());
        var blockEntity = helper.getBlockEntity(new BlockPos(1, 1, 1), SyncTestFixtures.TestManagedBlockEntity.class);
        blockEntity.energy = 240;
        blockEntity.config = "gt";
        blockEntity.target = 9;

        var saveTag = blockEntity.getSyncDataHolder().serializeToSaveNBT(helper.getLevel().registryAccess());
        check(helper, saveTag.getInt("energy").orElse(0) == 240, "energy save missing");

        byte[] serverChanges = blockEntity.getSyncDataHolder().collectServerNetworkChanges(helper.getLevel().registryAccess());
        check(helper, serverChanges.length > 0, "server network payload missing");

        var restored = new SyncTestFixtures.TestManagedBlockEntity();
        restored.getSyncDataHolder().applyServerNetworkUpdate(helper.getLevel().registryAccess(), serverChanges);
        check(helper, restored.target == 9, "registered block entity target not restored");
        helper.succeed();
    }

    @TestHolder(value = "sync_registered_block_item")
    @EmptyTemplate("3")
    @GameTest
    public static void registeredBlockItem(GameTestHelper helper) {
        helper.setBlock(1, 1, 1, SyncedTestContent.TEST_SYNC_BLOCK.get());
        var blockEntity = helper.getBlockEntity(new BlockPos(1, 1, 1), SyncTestFixtures.TestManagedBlockEntity.class);
        blockEntity.energy = 512;
        blockEntity.config = "item-data";
        blockEntity.target = 33;

        ItemStack stack = SyncedTestContent.TEST_SYNC_BLOCK_ITEM.toStack();
        var components = blockEntity.collectItemComponentsForTest();
        stack.applyComponents(components);

        helper.setBlock(2, 1, 1, SyncedTestContent.TEST_SYNC_BLOCK.get());
        var restored = helper.getBlockEntity(new BlockPos(2, 1, 1), SyncTestFixtures.TestManagedBlockEntity.class);
        restored.applyItemComponentsForTest(stack.getComponents());
        check(helper, restored.config.equals("item-data"), "block item config not restored");
        check(helper, restored.target == 0, "block item should not restore sync-only field");
        check(helper, restored.energy == 0, "block item should not restore save-only field");

        restored.getSyncDataHolder().loadFromItemStack(stack, helper.getLevel().registryAccess());
        check(helper, restored.config.equals("item-data"), "loadFromItemStack config not restored");
        helper.succeed();
    }
}
