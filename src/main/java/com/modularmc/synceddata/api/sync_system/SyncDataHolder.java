package com.modularmc.synceddata.api.sync_system;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

public class SyncDataHolder {

    private final ISyncManaged holder;

    private final ObjectSet<String> dirtySyncFields = new ObjectOpenHashSet<>();
    private boolean resyncAll = false;

    public SyncDataHolder(ISyncManaged o) {
        holder = o;
    }
}
