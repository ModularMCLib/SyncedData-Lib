package com.modularmc.synceddata.api.sync_system.meta;

import com.modularmc.synceddata.api.sync_system.ISyncManaged;
import com.modularmc.synceddata.api.sync_system.annotations.ItemSave;
import com.modularmc.synceddata.api.sync_system.annotations.RerenderOnChanged;
import com.modularmc.synceddata.api.sync_system.annotations.SaveField;
import com.modularmc.synceddata.api.sync_system.annotations.SyncBoth;
import com.modularmc.synceddata.api.sync_system.annotations.SyncToClient;
import com.modularmc.synceddata.api.sync_system.annotations.SyncToServer;

import com.mojang.serialization.Codec;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.List;

public final class FieldSyncData {

    public final String fieldName;
    public final VarHandle handle;
    public final boolean triggerClientRerender;
    public final boolean isSyncManaged;

    public final boolean hasSaveField;
    public final boolean hasItemSave;
    public final boolean hasSyncToClient;
    public final boolean hasSyncToServer;
    public final boolean hasSyncBoth;

    public final String nbtSaveKey;
    public final String itemNbtKey;

    @Getter
    public final @Nullable Codec<Object> codec;
    public final List<MethodHandle> changeListenerHandles;
    public final TypeDeclaration type;

    @SuppressWarnings("unchecked")
    public FieldSyncData(Field field, VarHandle handle,
                         List<MethodHandle> changeListenerHandles) {
        this.fieldName = field.getName();
        this.isSyncManaged = ISyncManaged.class.isAssignableFrom(field.getType());
        this.handle = handle;
        this.triggerClientRerender = field.isAnnotationPresent(RerenderOnChanged.class);
        this.changeListenerHandles = changeListenerHandles;
        this.type = new TypeDeclaration(field.getGenericType());

        SaveField saveField = field.getAnnotation(SaveField.class);
        ItemSave itemSave = field.getAnnotation(ItemSave.class);
        this.hasSaveField = saveField != null;
        this.hasItemSave = itemSave != null;
        this.hasSyncToClient = field.isAnnotationPresent(SyncToClient.class);
        this.hasSyncToServer = field.isAnnotationPresent(SyncToServer.class);
        this.hasSyncBoth = field.isAnnotationPresent(SyncBoth.class);

        this.nbtSaveKey = (saveField != null && !saveField.nbtKey().isBlank()) ? saveField.nbtKey() : fieldName;
        this.itemNbtKey = (itemSave != null && !itemSave.nbtKey().isBlank()) ? itemSave.nbtKey() : fieldName;

        Codec<?> resolved = FieldCodecs.get(field.getGenericType());
        this.codec = (Codec<Object>) resolved;
    }
}
