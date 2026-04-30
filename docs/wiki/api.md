# API 参考

## `ISyncManaged`

**包路径**：`com.modularmc.synceddata.api.sync_system.ISyncManaged`

所有需要同步系统的类必须实现的接口。

```java
public interface ISyncManaged {
    SyncDataHolder getSyncDataHolder();
    void scheduleRenderUpdate();
    void markAsChanged();
}
```

| 方法                       | 实现要求                   | 说明                           |
|--------------------------|------------------------|------------------------------|
| `getSyncDataHolder()`    | 返回 `SyncDataHolder` 实例 | 通常用 `ItemSyncHolder` 或直接 new |
| `scheduleRenderUpdate()` | 方块实体调度重渲染              | 物品类直接空实现                     |
| `markAsChanged()`        | 通知 Minecraft 该区块需要保存   | 调用 `setChanged()`            |

---

## `SyncDataHolder`

**包路径**：`com.modularmc.synceddata.api.sync_system.holder.SyncDataHolder`

数据同步的核心容器。管理注解字段的序列化、反序列化、变更检测和同步。

### 构造

```java
public SyncDataHolder(ISyncManaged owner)
```

构造时自动扫描 `owner.getClass()` 的注解，初始化缓存快照。

### 变更检测

```java
boolean scanAndMarkChanges(HolderLookup.Provider registries)
```

遍历所有 `@SyncToClient` / `@SyncBoth` 字段，与缓存的快照值比较。有变更时：
1. 序列化变更值 → `pendingClientChanges`
2. 更新快照

**返回值**：`true` 表示至少一个字段发生了变更。

### 获取变更

```java
CompoundTag getPendingChanges()
```

消费并返回待发送的客户端变更数据。返回后清空内部缓存。无变更时返回空 `CompoundTag`。

### 序列化

```java
CompoundTag serializeToSaveNBT(Provider registries);    // 世界存档写入
CompoundTag serializeToItemNBT(Provider registries);     // 物品存储写入
CompoundTag serializeFullClientSyncNBT(Provider registries); // 全量 S2C 同步
```

- `serializeToSaveNBT`：仅序列化 `@SaveField` 字段 → 用于 `saveAdditional()`
- `serializeToItemNBT`：仅序列化 `@ItemSave` 字段 → 用于 `collectImplicitComponents()`
- `serializeFullClientSyncNBT`：序列化所有 `@SyncToClient` / `@SyncBoth` 字段 → 用于 `getUpdateTag()`

### 反序列化

```java
void deserializeNBT(Provider registries, CompoundTag tag, boolean readingClientFields);
void deserializeItemNBT(Provider registries, CompoundTag tag);
```

- `deserializeNBT(true)`：反序列化 `@SyncToClient` / `@SyncBoth` 字段（客户端）。触发 `@ClientFieldChangeListener` 和 `@RerenderOnChanged`
- `deserializeNBT(false)`：反序列化 `@SaveField` 字段（服务端存档恢复）
- `deserializeItemNBT`：反序列化 `@ItemSave` 字段（从物品 DataComponent 恢复）

### C2S 更新

```java
void applyServerUpdate(Provider registries, CompoundTag tag);
```

接收客户端发送的更新包。仅处理 `@SyncToServer` / `@SyncBoth` 字段。

### 物品操作

```java
void applyToItemStack(ItemStack stack, Provider registries);
void loadFromItemStack(ItemStack stack, Provider registries);
```

- `applyToItemStack`：将 `@ItemSave` 字段写入 `SyncedComponents.BLOCK_ITEM_DATA` DataComponent
- `loadFromItemStack`：从 `BLOCK_ITEM_DATA` DataComponent 读取字段

### 其他

```java
void resyncAllFields();
```

强制下次扫描时将所有字段视为已变更（全量同步）。

---

## `ItemSyncHolder`

**包路径**：`com.modularmc.synceddata.api.sync_system.holder.ItemSyncHolder`

物品数据同步包装器。

```java
public final class ItemSyncHolder implements ISyncManaged {
    SyncDataHolder getSyncDataHolder();
    void saveToStack(ItemStack, Provider registries);
    void loadFromStack(ItemStack, Provider registries, boolean clientSide);
    boolean scanChanges(Provider registries);
    void flushToStack(ItemStack, Provider registries);
    void applyServerUpdate(Provider registries, CompoundTag tag);
}
```

| 方法                               | 说明                                                       |
|----------------------------------|----------------------------------------------------------|
| `saveToStack()`                  | `@ItemSave` 字段 → ItemStack DataComponent                 |
| `loadFromStack(stack, r, false)` | 服务端：DataComponent → 字段                                   |
| `loadFromStack(stack, r, true)`  | 客户端：DataComponent → 字段 + 触发 `@ClientFieldChangeListener` |
| `scanChanges()`                  | 扫描 `@SyncToClient` 字段变更                                  |
| `flushToStack()`                 | 变更增量写入 DataComponent                                     |
| `applyServerUpdate()`            | 处理 `@SyncToServer` 的 C2S 更新                              |

---

## `ManagedSyncBlockEntity`

**包路径**：`com.modularmc.synceddata.api.sync_system.ManagedSyncBlockEntity`

方块实体基类，封装了完整的同步生命周期。

```java
public abstract class ManagedSyncBlockEntity extends BlockEntity implements ISyncManaged {
    SyncDataHolder syncDataHolder;
    void markAsChanged();
    void updateTick();              // 定期扫描 + 自动推送
    void handleClientUpdate();      // C2S 处理入口
}
```

### 生命周期

| 事件                            | 触发时机           | 同步路径                                                  |
|-------------------------------|----------------|-------------------------------------------------------|
| `saveAdditional()`            | 世界存档           | `serializeToSaveNBT()` → `@SaveField`                 |
| `collectImplicitComponents()` | 掉落物生成          | `serializeToItemNBT()` → `@ItemSave` → DataComponent  |
| `loadAdditional()`            | 读档 / 客户端收到更新   | `deserializeNBT()` + `deserializeItemNBT()`           |
| `getUpdateTag()`              | 客户端初始加载        | `serializeFullClientSyncNBT()` → `@SyncToClient` 全量   |
| `updateTick()`                | 每个 server tick | `scanAndMarkChanges()` → `getPendingChanges()` → 发包   |
| `getUpdatePacket()`           | 增量更新           | `getPendingChanges()` 消费                              |
| `handleClientUpdate()`        | 收到 C2S 包       | `applyServerUpdate()` → `@SyncToServer` / `@SyncBoth` |

---

## `SyncedComponents`

**包路径**：`com.modularmc.synceddata.api.sync_system.SyncedComponents`

DataComponent 注册。

```java
public class SyncedComponents {
    DeferredRegister<DataComponentType<?>> COMPONENTS;
    Supplier<DataComponentType<CompoundTag>> BLOCK_ITEM_DATA;
}
```

| 组件                | 类型                               | 用途                  |
|-------------------|----------------------------------|---------------------|
| `BLOCK_ITEM_DATA` | `DataComponentType<CompoundTag>` | 存储 `@ItemSave` 字段数据 |

注册方式：

```java
public SyncedData(IEventBus bus) {
    SyncedComponents.COMPONENTS.register(bus);
}
```

---

## `ClassSyncData`

**包路径**：`com.modularmc.synceddata.api.sync_system.meta.ClassSyncData`

类级别的注解元数据缓存。通过 `ClassValue` 实现：

```java
ClassSyncData data = ClassSyncData.getClassData(MyMachineBE.class);
```

分组集合：

| 集合                 | 来源                            |
|--------------------|-------------------------------|
| `worldSaveFields`  | `@SaveField`                  |
| `itemSaveFields`   | `@ItemSave`                   |
| `clientSyncFields` | `@SyncToClient` 或 `@SyncBoth` |
| `serverSyncFields` | `@SyncToServer` 或 `@SyncBoth` |
| `bothSyncFields`   | `@SyncBoth`                   |

---

## `FieldSyncData`

**包路径**：`com.modularmc.synceddata.api.sync_system.meta.FieldSyncData`

字段的注解元数据。

| 字段                      | 类型                   | 说明                                |
|-------------------------|----------------------|-----------------------------------|
| `fieldName`             | `String`             | Java 字段名                          |
| `nbtSaveKey`            | `String`             | `@SaveField.nbtKey` 或字段名          |
| `itemNbtKey`            | `String`             | `@ItemSave.nbtKey` 或字段名           |
| `handle`                | `VarHandle`          | 字段读写句柄                            |
| `codec`                 | `Codec<Object>`      | 对应的 Codec                         |
| `triggerClientRerender` | `boolean`            | 是否标注 `@RerenderOnChanged`         |
| `changeListenerHandles` | `List<MethodHandle>` | `@ClientFieldChangeListener` 方法句柄 |

---

## `FieldCodecs`

**包路径**：`com.modularmc.synceddata.api.sync_system.meta.FieldCodecs`

Codec 注册表。根据 Java 类型查找对应的 Codec。

| 类型                    | Codec                                                 |
|-----------------------|-------------------------------------------------------|
| `int` / `Integer`     | `Codec.INT`                                           |
| `long` / `Long`       | `Codec.LONG`                                          |
| `float` / `Float`     | `Codec.FLOAT`                                         |
| `double` / `Double`   | `Codec.DOUBLE`                                        |
| `short` / `Short`     | `Codec.SHORT`                                         |
| `byte` / `Byte`       | `Codec.BYTE`                                          |
| `boolean` / `Boolean` | `Codec.BOOL`                                          |
| `String`              | `Codec.STRING`                                        |
| `UUID`                | `Codec.STRING.xmap(UUID::fromString, UUID::toString)` |
| `CompoundTag`         | `CompoundTag.CODEC`                                   |

**内置 Codec：**

| 类型                    | Codec                             |
|-----------------------|-----------------------------------|
| `int` / `Integer`     | `Codec.INT`                       |
| `long` / `Long`       | `Codec.LONG`                      |
| `float` / `Float`     | `Codec.FLOAT`                     |
| `double` / `Double`   | `Codec.DOUBLE`                    |
| `short` / `Short`     | `Codec.SHORT`                     |
| `byte` / `Byte`       | `Codec.BYTE`                      |
| `boolean` / `Boolean` | `Codec.BOOL`                      |
| `char` / `Character`  | 自动转换                              |
| `String`              | `Codec.STRING`                    |
| `UUID`                | `Codec.STRING.xmap()`             |
| `CompoundTag`         | `CompoundTag.CODEC`               |
| `BlockPos`            | `BlockPos.CODEC`                  |
| `Identifier`          | `Identifier.CODEC`                |
| `ItemStack`           | `ItemStack.CODEC`                 |
| `FluidStack`          | `FluidStack.CODEC`                |
| `Component`           | `ComponentSerialization.CODEC`    |
| `int[]`               | `Codec.INT_STREAM`                |
| `long[]`              | `Codec.LONG_STREAM`               |
| `byte[]`              | 自动转换                              |
| `T[]` (对象数组)          | `Codec.list(elem)` 自动构建           |
| `Enum<E>`             | `Enum.valueOf()` + `.name()` 自动构建 |
| `List<T>`             | `Codec.list(elem)` 自动解析泛型         |
| `Set<T>`              | 自动转换为 `LinkedHashSet`             |
| `Map<K,V>`            | `Codec.unboundedMap(K,V)` 自动解析泛型  |
| `ISyncManaged`        | 递归序列化（调用子 holder）                 |

**注册自定义 Codec：**

```java
FieldCodecs.register(MyType.class, MyType.CODEC);

// 或注册 Supplier（处理泛型继承）
FieldCodecs.registerSupplier(List.class, () -> ListCodec.INSTANCE);
```
