package com.modularmc.synceddata.api.sync_system;

public interface ISyncManaged {

	SyncDataHolder getSyncDataHolder();

	/**
	 * Function called when a synced field requests a rerender
	 */
	void scheduleRenderUpdate();

	/**
	 * Function called to notify the server that this object has been updated and must be synced to clients
	 */
	void markAsChanged();
}
