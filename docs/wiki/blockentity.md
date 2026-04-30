# 方块实体集成

继承 `ManagedSyncBlockEntity` 即可获得完整的同步生命周期。

## 基类

```java
public abstract class ManagedSyncBlockEntity extends BlockEntity implements ISyncManaged {

    protected final SyncDataHolder syncDataHolder = new SyncDataHolder(this);

    // ✅ 自动实现的方法
    // saveAdditional(ValueOutput)    → @SaveField 世界存档
    // loadAdditional(ValueInput)     → 存档/客户端恢复
    // collectImplicitComponents()    → @ItemSave 物品 DataComponent
    // applyImplicitComponents()      → 放置时从物品恢复
    // getUpdateTag(Provider)         → 客户端首次加载（全量）
    // getUpdatePacket()              → 增量更新（仅变更字段）
    // markAsChanged()                → setChanged()
}
```

## 实现步骤

### 1. 继承基类

```java
public class MyMachineBlockEntity extends ManagedSyncBlockEntity {

    public MyMachineBlockEntity(BlockPos pos, BlockState state) {
        super(MyBlockEntities.MY_MACHINE.get(), pos, state);
    }
}
```

### 2. 添加注解字段

```java
@Getter
public class MyMachineBlockEntity extends ManagedSyncBlockEntity {

    @SaveField                 // 世界存档
    private UUID ownerUUID;

    @SaveField @ItemSave       // 世界存档 + 物品存储
    private int storedEnergy;

    @SyncToClient @RerenderOnChanged  // 同步 + 重渲染
    private boolean active;

    @SyncToClient @RerenderOnChanged  // 同步 + 重渲染
    private int progress;

    @SyncBoth                  // 双端同步
    private int targetValue;
}
```

### 3. 添加变更回调

```java
@ClientFieldChangeListener(fieldName = "progress")
public void onProgressChanged() {
    if (getLevel() instanceof ClientLevel) {
        updateProgressBar();
    }
}

@ClientFieldChangeListener(fieldName = "active")
public void onActiveChanged() {
    if (getLevel() instanceof ClientLevel) {
        playActivationSound();
    }
}
```

### 4. 注册 Tick 方法

```java
public static void tick(Level level, BlockPos pos, BlockState state, MyMachineBlockEntity be) {
    be.updateTick();  // ← 自动扫描 @SyncToClient/@SyncBoth 字段

    if (be.active && be.progress < 100) {
        be.progress++;  // 下一 tick 自动检测到变更
    }
}
```

`updateTick()` 执行的操作：
1. 调用 `setChanged()`（标记区块需要保存）
2. 仅服务端：调用 `scanAndMarkChanges()`
3. 有变更 → `sendBlockUpdated()` → `getUpdatePacket()` → `getPendingChanges()` → 发送到客户端

## 数据流详解

### 世界存档与读取

```
存档:
  saveAdditional(ValueOutput output)
    → 获取 registryAccess
    → serializeToSaveNBT(registries)      // 仅 @SaveField
    → serializeToItemNBT(registries)      // 仅 @ItemSave
    → output.store("synced", CODEC, mergedTag)

读档:
  loadAdditional(ValueInput input)
    → 获取 registryAccess
    → input.read(MapCodec) → 得到 "synced" 标签
    → deserializeNBT(registries, tag, false)     // @SaveField
    → deserializeItemNBT(registries, tag)         // @ItemSave（仅服务端）
```

### S2C 同步

```
每个 Server Tick:
  updateTick()
    → scanAndMarkChanges(registries)
       for each @SyncToClient field:
         → VarHandle.get(holder) → 缓存中的旧值
         → Objects.equals() 比较
         → 不同 → codec.encodeStart(NbtOps, value)
                → changes.put(nbtSaveKey, serialized)
                → 更新缓存快照
       → 存储 pendingClientChanges
    → sendBlockUpdated()
    → getUpdatePacket()
       → getPendingChanges() 消费 pending
       → ClientboundBlockEntityDataPacket

客户端收到:
  → loadAdditional()
    → deserializeNBT(registries, tag, true)
       → codec.parse(NbtOps, tag)
       → VarHandle.set(holder)
       → 更新缓存
       → 调用 @ClientFieldChangeListener 方法
       → @RerenderOnChanged → scheduleRenderUpdate()
```

### 物品掉落与放置

```
方块破坏:
  collectImplicitComponents(DataComponentMap.Builder)
    → serializeToItemNBT(registries)
    → components.set(BLOCK_ITEM_DATA, tag)

方块放置:
  applyImplicitComponents(DataComponentGetter)
    → components.get(BLOCK_ITEM_DATA)
    → deserializeItemNBT(registries, tag)
```

### C2S

```
客户端 → 自定义网络包

服务端收到:
  → 权限校验（调用方负责）
  → handleClientUpdate(registries, packetTag)
    → applyServerUpdate(registries, tag)
      → for each @SyncToServer/@SyncBoth:
        → codec.parse(NbtOps, tag)
        → VarHandle.set(holder)
```

## 完整示例

```java
@Getter
public class EnergyCellBlockEntity extends ManagedSyncBlockEntity {

    @SaveField @ItemSave(nbtKey = "e")
    private int energy;

    @SaveField @ItemSave(nbtKey = "cap")
    private int capacity = 10000;

    @SyncToClient @RerenderOnChanged
    private boolean active;

    @SyncBoth
    private int outputTarget;

    public EnergyCellBlockEntity(BlockPos pos, BlockState state) {
        super(MyBlockEntities.ENERGY_CELL.get(), pos, state);
    }

    @ClientFieldChangeListener(fieldName = "active")
    public void onActiveStateChanged() {
        if (getLevel() instanceof ClientLevel) {
            renderActiveState(active);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EnergyCellBlockEntity be) {
        be.updateTick();

        if (be.active && be.energy < be.capacity) {
            be.energy = Math.min(be.capacity, be.energy + 10);
        }
    }
}
```
