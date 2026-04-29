package com.modularmc.synceddata.api.sync_system;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.util.Objects;
import java.util.Set;

public class SyncDataHolder {

	private final ISyncManaged holder;

	private final ObjectSet<String> dirtySyncFields = new ObjectOpenHashSet<>();
	private boolean resyncAll = false;

	public SyncDataHolder(ISyncManaged o) {
		holder = o;
	}

}
