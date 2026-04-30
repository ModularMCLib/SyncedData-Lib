package com.modularmc.synceddata.test;

import com.modularmc.synceddata.SyncedData;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.testframework.conf.FrameworkConfiguration;

@Mod(value = SyncedData.MOD_ID, depends = "testframework")
public final class SyncedTestFramework {

    public SyncedTestFramework(IEventBus bus, ModContainer container) {
        FrameworkConfiguration.builder(SyncedData.id("tests"))
                .build()
                .create()
                .init(bus, container);
    }
}
