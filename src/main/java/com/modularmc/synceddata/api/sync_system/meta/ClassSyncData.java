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
    @Getter
    private final Set<FieldSyncData> serverUpdateFields = new ObjectOpenHashSet<>();
    @Getter
    private FieldSyncData[] orderedClientSyncFields = new FieldSyncData[0];
    @Getter
    private FieldSyncData[] orderedServerUpdateFields = new FieldSyncData[0];

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
        Set<String> clientListenerTargets = new HashSet<>();

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
            if (listener.fieldName().isBlank()) {
                throw new IllegalArgumentException("@ClientFieldChangeListener requires a non-blank fieldName: %s.%s"
                        .formatted(clazz.getName(), method.getName()));
            }
            changeListeners.computeIfAbsent(listener.fieldName(), $ -> new ArrayList<>()).add(handle);
            clientListenerTargets.add(listener.fieldName());
        }

        Map<String, FieldSyncData> localFieldsByName = new HashMap<>();
        Set<String> localSaveKeys = new HashSet<>();
        Set<String> localItemKeys = new HashSet<>();
        Set<String> localClientSyncKeys = new HashSet<>();
        Set<String> localServerSyncKeys = new HashSet<>();

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
            if (localFieldsByName.put(syncData.fieldName, syncData) != null) {
                throw new IllegalArgumentException("Duplicate managed field name in %s: %s"
                        .formatted(clazz.getName(), syncData.fieldName));
            }
            managedFields.add(syncData);

            if (hasSave) {
                checkDuplicateKey(localSaveKeys, syncData.nbtSaveKey, clazz, "save");
                worldSaveFields.add(syncData);
            }
            if (hasItem) {
                checkDuplicateKey(localItemKeys, syncData.itemNbtKey, clazz, "item");
                itemSaveFields.add(syncData);
            }
            if (hasS2C) {
                checkDuplicateKey(localClientSyncKeys, syncData.nbtSaveKey, clazz, "client sync");
                clientSyncFields.add(syncData);
            }
            if (hasC2S) {
                checkDuplicateKey(localServerSyncKeys, syncData.fieldName, clazz, "server sync");
                serverSyncFields.add(syncData);
                serverUpdateFields.add(syncData);
            }
            if (hasBoth) {
                checkDuplicateKey(localClientSyncKeys, syncData.nbtSaveKey, clazz, "client sync");
                checkDuplicateKey(localServerSyncKeys, syncData.fieldName, clazz, "server sync");
                bothSyncFields.add(syncData);
                clientSyncFields.add(syncData);
                serverSyncFields.add(syncData);
                serverUpdateFields.add(syncData);
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
            serverUpdateFields.addAll(parentData.serverUpdateFields);
        }

        for (String fieldName : clientListenerTargets) {
            FieldSyncData localField = localFieldsByName.get(fieldName);
            if (localField != null && !localField.hasSyncToClient && !localField.hasSyncBoth) {
                throw new IllegalArgumentException("@ClientFieldChangeListener targets a field that never syncs to client: %s.%s"
                        .formatted(clazz.getName(), fieldName));
            }
            if (localField == null && clientSyncFields.stream().noneMatch(field -> field.fieldName.equals(fieldName))) {
                throw new IllegalArgumentException("@ClientFieldChangeListener targets unknown field: %s.%s"
                        .formatted(clazz.getName(), fieldName));
            }
        }

        orderedClientSyncFields = clientSyncFields.stream()
                .sorted(Comparator.comparing(field -> field.nbtSaveKey))
                .toArray(FieldSyncData[]::new);
        orderedServerUpdateFields = serverUpdateFields.stream()
                .sorted(Comparator.comparing(field -> field.fieldName))
                .toArray(FieldSyncData[]::new);
    }

    private static void checkDuplicateKey(Set<String> keys, String key, Class<?> owner, String kind) {
        if (!keys.add(key)) {
            throw new IllegalArgumentException("Duplicate %s key in %s: %s"
                    .formatted(kind, owner.getName(), key));
        }
    }
}
