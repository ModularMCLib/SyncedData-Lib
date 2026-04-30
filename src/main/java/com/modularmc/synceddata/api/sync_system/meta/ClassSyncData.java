package com.modularmc.synceddata.api.sync_system.meta;

import com.modularmc.synceddata.SyncedData;
import com.modularmc.synceddata.api.sync_system.annotations.ClientFieldChangeListener;
import com.modularmc.synceddata.api.sync_system.annotations.ItemSave;
import com.modularmc.synceddata.api.sync_system.annotations.SaveField;
import com.modularmc.synceddata.api.sync_system.annotations.SyncBoth;
import com.modularmc.synceddata.api.sync_system.annotations.SyncToClient;
import com.modularmc.synceddata.api.sync_system.annotations.SyncToServer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.Getter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public final class ClassSyncData {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ClassValue<ClassSyncData> CACHE = new ClassValue<>() {

        @Override
        protected ClassSyncData computeValue(Class<?> type) {
            return new ClassSyncData(type);
        }
    };

    public static ClassSyncData getClassData(Class<?> cls) {
        return CACHE.get(cls);
    }

    @Getter
    private final List<FieldSyncData> managedFields = new ObjectArrayList<>();

    @Getter
    private final Set<FieldSyncData> worldSaveFields = new ObjectOpenHashSet<>();
    @Getter
    private final Set<FieldSyncData> itemSaveFields = new ObjectOpenHashSet<>();
    @Getter
    private final Set<FieldSyncData> clientSyncFields = new ObjectOpenHashSet<>();
    @Getter
    private final Set<FieldSyncData> serverSyncFields = new ObjectOpenHashSet<>();
    @Getter
    private final Set<FieldSyncData> bothSyncFields = new ObjectOpenHashSet<>();

    private ClassSyncData(Class<?> clazz) {
        MethodHandles.Lookup privateLookup;
        try {
            privateLookup = MethodHandles.privateLookupIn(clazz, LOOKUP);
        } catch (IllegalAccessException e) {
            SyncedData.LOGGER.error("Sync: Failed to create method handle lookup for class {}", clazz);
            SyncedData.LOGGER.error(e.getMessage());
            return;
        }

        Map<String, List<MethodHandle>> changeListeners = new HashMap<>();

        for (Method method : clazz.getDeclaredMethods()) {
            ClientFieldChangeListener listener = method.getAnnotation(ClientFieldChangeListener.class);
            if (listener == null) continue;

            if (Modifier.isStatic(method.getModifiers()))
                throw new IllegalArgumentException("@ClientFieldChangeListener on static method: %s.%s"
                        .formatted(clazz.getName(), method.getName()));

            MethodHandle handle;
            try {
                handle = privateLookup.unreflect(method);
            } catch (IllegalAccessException e) {
                SyncedData.LOGGER.error("Sync: Failed to acquire method handle for method {} {}",
                        method.getName(), clazz.getName());
                SyncedData.LOGGER.error(e.getMessage());
                continue;
            }
            changeListeners.computeIfAbsent(listener.fieldName(), $ -> new ArrayList<>()).add(handle);
        }

        for (Field field : clazz.getDeclaredFields()) {
            boolean hasSave = field.isAnnotationPresent(SaveField.class);
            boolean hasItem = field.isAnnotationPresent(ItemSave.class);
            boolean hasS2C = field.isAnnotationPresent(SyncToClient.class);
            boolean hasC2S = field.isAnnotationPresent(SyncToServer.class);
            boolean hasBoth = field.isAnnotationPresent(SyncBoth.class);

            if (!hasSave && !hasItem && !hasS2C && !hasC2S && !hasBoth) continue;

            if (Modifier.isStatic(field.getModifiers()))
                throw new IllegalArgumentException("Cannot apply sync annotations to static field: %s.%s"
                        .formatted(field.getDeclaringClass().getName(), field.getName()));

            VarHandle handle;
            try {
                handle = privateLookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                SyncedData.LOGGER.error("Sync: Failed to acquire variable handle for field {} {}",
                        field.getName(), clazz.getName());
                SyncedData.LOGGER.error(e.getMessage());
                continue;
            }

            FieldSyncData syncData = new FieldSyncData(field, handle,
                    changeListeners.getOrDefault(field.getName(), List.of()));
            managedFields.add(syncData);

            if (hasSave) worldSaveFields.add(syncData);
            if (hasItem) itemSaveFields.add(syncData);
            if (hasS2C) clientSyncFields.add(syncData);
            if (hasC2S) serverSyncFields.add(syncData);
            if (hasBoth) {
                bothSyncFields.add(syncData);
                clientSyncFields.add(syncData);
                serverSyncFields.add(syncData);
            }
        }

        Class<?> parent = clazz.getSuperclass();
        if (parent != null && parent != Object.class) {
            ClassSyncData parentData = CACHE.get(parent);
            managedFields.addAll(parentData.managedFields);
            worldSaveFields.addAll(parentData.worldSaveFields);
            itemSaveFields.addAll(parentData.itemSaveFields);
            clientSyncFields.addAll(parentData.clientSyncFields);
            serverSyncFields.addAll(parentData.serverSyncFields);
            bothSyncFields.addAll(parentData.bothSyncFields);
        }
    }
}
