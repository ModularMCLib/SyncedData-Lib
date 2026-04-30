package com.modularmc.synceddata.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

import java.util.*;

import static com.modularmc.synceddata.test.SyncTestFixtures.registerCustomPayloadCodec;
import static com.modularmc.synceddata.test.SyncTestFixtures.roundTrip;
import static com.modularmc.synceddata.test.SyncTestFixtures.sampleTag;

public class FieldCodecsTest {

    @TestHolder(value = "codec_primitives")
    @EmptyTemplate("3")
    @GameTest
    public static void primitives(GameTestHelper helper) {
        roundTrip(helper, int.class, 42);
        roundTrip(helper, long.class, 1L);
        roundTrip(helper, float.class, 3f);
        roundTrip(helper, double.class, 2.0);
        roundTrip(helper, short.class, (short) 7);
        roundTrip(helper, byte.class, (byte) 1);
        roundTrip(helper, boolean.class, true);
        roundTrip(helper, char.class, 'X');
        helper.succeed();
    }

    @TestHolder(value = "codec_java")
    @EmptyTemplate("3")
    @GameTest
    public static void javaTypes(GameTestHelper helper) {
        roundTrip(helper, String.class, "hello");
        roundTrip(helper, UUID.class, UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        roundTrip(helper, CompoundTag.class, sampleTag());
        helper.succeed();
    }

    @TestHolder(value = "codec_mc")
    @EmptyTemplate("3")
    @GameTest
    public static void mcTypes(GameTestHelper helper) {
        roundTrip(helper, BlockPos.class, new BlockPos(10, -5, 30));
        roundTrip(helper, Identifier.class, Identifier.fromNamespaceAndPath("minecraft", "stone"));
        roundTrip(helper, ItemStack.class, new ItemStack(Items.STONE));
        roundTrip(helper, FluidStack.class, new FluidStack(Fluids.WATER, 1000));
        roundTrip(helper, Component.class, Component.literal("hi"));
        helper.succeed();
    }

    @TestHolder(value = "codec_arrays")
    @EmptyTemplate("3")
    @GameTest
    public static void arrays(GameTestHelper helper) {
        roundTrip(helper, int[].class, new int[] { 1, 2, 3 });
        roundTrip(helper, long[].class, new long[] { 1L });
        roundTrip(helper, byte[].class, new byte[] { 1, 2 });
        roundTrip(helper, String[].class, new String[] { "a", "b" });
        helper.succeed();
    }

    @TestHolder(value = "codec_enum")
    @EmptyTemplate("3")
    @GameTest
    public static void enums(GameTestHelper helper) {
        roundTrip(helper, SyncTestFixtures.CodecMode.class, SyncTestFixtures.CodecMode.ACTIVE);
        helper.succeed();
    }

    @TestHolder(value = "codec_custom")
    @EmptyTemplate("3")
    @GameTest
    public static void custom(GameTestHelper helper) {
        registerCustomPayloadCodec();
        roundTrip(helper, SyncTestFixtures.CustomPayload.class, new SyncTestFixtures.CustomPayload("x", 1));
        helper.succeed();
    }
}
