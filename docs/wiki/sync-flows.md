# 同步流程与序列化

## 变更检测（核心机制）

**无需手动标记脏字段**。系统通过定期扫描 + 值比较自动检测变更。

### 算法

```
scanAndMarkChanges(registries):
  ├── fullSyncPending?
  │     ├── true → 所有字段视为已变更
  │     └── false → 逐字段比较
  │
  ├── for each field in @SyncToClient ∪ @SyncBoth:
  │     ├─ current = VarHandle.get(holder)     ← 取当前值
  │     ├─ previous = cachedValues.get(name)    ← 取快照值
  │     ├─ changed = fullSyncPending || !Objects.equals(current, previous)
  │     │
  │     └─ if changed:
  │           ├─ encodeField(field, current)    ← Codec 序列化
  │           ├─ changes.put(nbtSaveKey, tag)   ← 收集变更
  │           └─ cachedValues.put(name, current) ← 更新快照
  │
  ├─ changes → pendingClientChanges
  ├─ fullSyncPending = false
  └─ return hasChanges
```

### 比较方式

使用 `Objects.equals()` 进行值比较。对于：

| 类型                   | 比较行为                                 |
|----------------------|--------------------------------------|
| `int`, `long` 等原始包装类 | `equals()` 值比较                       |
| `String`             | 字符串内容比较                              |
| `UUID`               | `equals()`                           |
| `ItemStack`          | 需要 `ItemStack.matches()`（需自定义 Codec） |
| `List`, `Map`, `Set` | `AbstractCollection.equals()` 内容比较   |
| 数组                   | `Arrays.equals()`（需自定义 Codec）        |
| `ISyncManaged`       | 引用比较（对象是否变更由内部 holder 管理）            |
| `null`               | 用 `CompoundTag{"null": true}` 表示     |

**注意**：对于可变对象（如 `List` 内部元素变更），如果引用不变，`Objects.equals()` 会视为无变更。此时应调用 `resyncAllFields()` 强制全量同步，或替换为新对象。

## 序列化机制

所有序列化使用 Minecraft 原生 `Codec` + `NbtOps`。

### encodeField

```java
private Tag encodeField(FieldSyncData field, Object value, Provider registries) {
    if (value == null) return CompoundTag{"null": true};

    if (field.codec != null)
        return codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value);

    if (value instanceof ISyncManaged)
        return value.getSyncDataHolder().serializeToSaveNBT(registries);

    // 找不到 Codec → 报错
    return CompoundTag{};
}
```

### decodeField

```java
private Object decodeField(FieldSyncData field, Tag tag, Object current, Provider registries) {
    if (tag.isNullTag()) return null;
    if (tag.isEmpty()) return current;

    if (field.codec != null)
        return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);

    if (field.isSyncManaged && tag instanceof CompoundTag)
        return recurseDeserialize(current, tag, registries);

    return current; // 找不到 Codec → 保持原值
}
```

### Codec 查找

```java
FieldCodecs.get(field.getGenericType())
```

1. 原始类型 → 装箱类型
2. 查 `REGISTRY` Map
3. 查 `SUPPLIERS`（按类型可赋值性匹配）
4. 找不到 → `null`（字段将被跳过，日志报错）

## DataComponent 存储

`@ItemSave` 字段通过 `SyncedComponents.BLOCK_ITEM_DATA` DataComponent 存储在 ItemStack 上。

```
ItemStack
  └── DataComponentMap
       └── synced:block_item_data (DataComponentType<CompoundTag>)
            ├── "energy" → IntTag(100)      ← @ItemSave(nbtKey = "energy")
            ├── "cfg_override" → StringTag("v2")  ← @ItemSave(nbtKey = "cfg_override")
            └── ...
```

注册方式：

```java
// SyncedComponents.java
public static final Supplier<DataComponentType<CompoundTag>> BLOCK_ITEM_DATA =
    COMPONENTS.register("block_item_data",
        () -> DataComponentType.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .build());
```

## 同步流程对比

### 方块实体 S2C（定期扫描）

```
时间轴:
  T0:  值 = 0, 快照 = 0  → 无变更
  T1:  progress++ (值 = 1, 快照仍 = 0)
  T2:  updateTick()
         → 扫描: 值=1, 快照=0 → 变更!
         → serialize: {"nbtSaveKey": 1}
         → sendBlockUpdated()
  T2:  getUpdatePacket()
         → getPendingChanges() → {"nbtSaveKey": 1}
         → ClientboundBlockEntityDataPacket
  T3:  客户端收到
         → deserializeNBT(true)
         → progress = 1
         → 更新快照
         → 触发 @ClientFieldChangeListener
         → scheduleRenderUpdate()
```

### 物品 S2C（事件驱动）

```
服务器:
  battery.energy += 100;
  holder.scanChanges(registries)  → true
  holder.flushToStack(stack, registries)
  broadcastChanges()
  → ClientboundContainerSetSlotPacket

客户端:
  setItem(slot, stack)
  holder.loadFromStack(stack, registries, true)
    → deserializeItemNBT()        恢复 @ItemSave 字段
    → deserializeNBT(registries, data, true)
      → 更新 @SyncToClient 字段
      → 触发 @ClientFieldChangeListener 回调
```

## 注册自定义 Codec

```java
// 1. 定义 Codec
public static final Codec<MyData> CODEC = RecordCodecBuilder.create(instance ->
    instance.group(
        Codec.INT.fieldOf("value").forGetter(MyData::value),
        Codec.STRING.fieldOf("name").forGetter(MyData::name)
    ).apply(instance, MyData::new));

// 2. 注册
FieldCodecs.register(MyData.class, CODEC);

// 或注册 Supplier（处理泛型）
FieldCodecs.registerSupplier(List.class, () -> MyListCodec.INSTANCE);
```

## 类型支持表

| Java 类型               | Codec                          | 内置 | 说明                           |
|-----------------------|--------------------------------|----|------------------------------|
| `int` / `Integer`     | `Codec.INT`                    | ✅  |                              |
| `long` / `Long`       | `Codec.LONG`                   | ✅  |                              |
| `float` / `Float`     | `Codec.FLOAT`                  | ✅  |                              |
| `double` / `Double`   | `Codec.DOUBLE`                 | ✅  |                              |
| `short` / `Short`     | `Codec.SHORT`                  | ✅  |                              |
| `byte` / `Byte`       | `Codec.BYTE`                   | ✅  |                              |
| `boolean` / `Boolean` | `Codec.BOOL`                   | ✅  |                              |
| `char` / `Character`  | `Codec.STRING.xmap(...)`       | ✅  |                              |
| `String`              | `Codec.STRING`                 | ✅  |                              |
| `UUID`                | `Codec.STRING.xmap(...)`       | ✅  |                              |
| `CompoundTag`         | `CompoundTag.CODEC`            | ✅  |                              |
| `BlockPos`            | `BlockPos.CODEC`               | ✅  |                              |
| `Identifier`          | `Identifier.CODEC`             | ✅  |                              |
| `ItemStack`           | `ItemStack.CODEC`              | ✅  |                              |
| `FluidStack`          | `FluidStack.CODEC`             | ✅  |                              |
| `Component`           | `ComponentSerialization.CODEC` | ✅  | 聊天组件                         |
| `int[]`               | `Codec.INT_STREAM`             | ✅  |                              |
| `long[]`              | `Codec.LONG_STREAM`            | ✅  |                              |
| `byte[]`              | `Codec.list(Codec.BYTE)`       | ✅  |                              |
| `Enum<E>`             | 自动构建                           | ✅  | `Enum.valueOf()` + `.name()` |
| `T[]` (对象数组)          | `Codec.list(elem)`             | ✅  | 自动构建                         |
| `List<T>`             | `Codec.list(elem)`             | ✅  | 自动解析泛型                       |
| `Set<T>`              | `Codec.list(elem).xmap(...)`   | ✅  | → `LinkedHashSet`            |
| `Map<K,V>`            | `Codec.unboundedMap(K,V)`      | ✅  | 自动解析泛型                       |
| `ISyncManaged`        | 递归序列化                          | ✅  | 调用子 holder                   |
| 自定义类型                 | 需注册 `FieldCodecs.register`     | —  | 调用 `register()`              |
