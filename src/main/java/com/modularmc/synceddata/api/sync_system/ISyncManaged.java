package com.modularmc.synceddata.api.sync_system;

import com.modularmc.synceddata.api.sync_system.holder.SyncDataHolder;

public interface ISyncManaged {

    SyncDataHolder getSyncDataHolder();

    void scheduleRenderUpdate();

    void markAsChanged();
}
