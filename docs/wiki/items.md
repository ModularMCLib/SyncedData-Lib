# 物品集成

物品使用 `ItemSyncHolder` 包装同步字段。所有数据通过 `SyncedComponents.BLOCK_ITEM_DATA` DataComponent 存储。

## 核心类

`ItemSyncHolder` 是 `ISyncManaged` 的实现，包装物品的同步字段：

```java
public final class ItemSyncHolder implements ISyncManaged {
    SyncDataHolder getSyncDataHolder();
    void saveToStack(ItemStack, Provider);              // 写入 DataComponent
    void loadFromStack(ItemStack, Provider, boolean);   // 读取 DataComponent
    boolean scanChanges(Provider);                      // 扫描变更
    void flushToStack(ItemStack, Provider);             // 增量写入
    void applyServerUpdate(Provider, CompoundTag);      // C2S
}
```

## 定义物品数据类

```java
public class BatteryData implements ISyncManaged {

    private final ItemSyncHolder holder = new ItemSyncHolder(this);

    @ItemSave @SyncToClient
    private int energy;

    @ItemSave
    private int capacity = 10000;

    @SyncBoth
    private int outputRate;

    @ClientFieldChangeListener(fieldName = "energy")
    public void onEnergyChanged() {
        // 客户端 GUI 收到更新后自动刷新
    }

    public SyncDataHolder getSyncDataHolder() { return holder.getSyncDataHolder(); }
    public void scheduleRenderUpdate() {}
    public void markAsChanged() {}
}
```

## 数据流

### 服务端：修改并同步

```java
// 在容器菜单或机器 tick 中
battery.energy += chargeAmount;
battery.capacity = newMax;

// 事件驱动：扫描检测变更
if (battery.holder.scanChanges(level.registryAccess())) {
    // 将变更写入 ItemStack DataComponent
    battery.holder.flushToStack(batteryStack, level.registryAccess());

    // 通知容器广播
    broadcastChanges();
    // 或针对单槽：
    // sendSlotUpdate(slotIndex, batteryStack);
}
```

### 客户端：接收更新

```java
@Override
public ItemStack quickMoveStack(Player player, int index) {
    // ...
}

@Override
public void slotsChanged(Container container) {
    // 容器变更后加载同步数据
    ItemStack slotStack = container.getItem(slotIndex);
    battery.holder.loadFromStack(slotStack, level.registryAccess(), true);
    // true = 客户端模式, 触发 @ClientFieldChangeListener
}
```

### C2S：客户端主动上报

```java
// 客户端 GUI 操作
void setOutputRate(int newRate) {
    battery.outputRate = newRate;

    // 构建更新包
    CompoundTag tag = new CompoundTag();
    tag.putInt("outputRate", newRate);

    // 发送到服务端
    PacketDistributor.sendToServer(new C2SItemUpdatePacket(slotIndex, tag));
}

// 服务端接收
void handleC2SUpdate(ServerPlayer player, int slotIndex, CompoundTag tag) {
    // 1. 权限校验
    if (!canModify(player)) return;

    // 2. 数值校验
    int rate = tag.getInt("outputRate");
    if (rate < 0 || rate > 100) return;

    // 3. 应用更新
    battery.holder.applyServerUpdate(level.registryAccess(), tag);

    // 4. 写回物品并广播
    battery.holder.flushToStack(batteryStack, level.registryAccess());
    broadcastChanges();
}
```

## 容器集成示例

```java
public class MachineContainer extends AbstractContainerMenu {

    private final BatteryData battery = new BatteryData();
    private ItemStack batteryStack = ItemStack.EMPTY;

    @Override
    public void broadcastChanges() {
        // 扫描变更
        if (battery.holder.scanChanges(registryAccess)) {
            // 写入物品
            battery.holder.flushToStack(batteryStack, registryAccess);
        }
        super.broadcastChanges();
    }

    @Override
    public void setItem(int slot, int state, ItemStack stack) {
        super.setItem(slot, state, stack);
        if (slot == BATTERY_SLOT) {
            batteryStack = stack.copy();
            battery.holder.loadFromStack(batteryStack, registryAccess, isClientSide());
        }
    }
}
```

## 注解支持

| 注解                           | 在物品上适用 | 说明                |
|------------------------------|--------|-------------------|
| `@ItemSave`                  | ✅      | 存储到 DataComponent |
| `@SyncToClient`              | ✅      | S2C 同步（事件驱动）      |
| `@SyncToServer`              | ✅      | C2S 标记            |
| `@SyncBoth`                  | ✅      | 双端同步              |
| `@RerenderOnChanged`         | ❌      | 物品无渲染概念           |
| `@ClientFieldChangeListener` | ✅      | 客户端回调             |
| `@SaveField`                 | ❌      | 仅世界存档             |

**注意**：`@SaveField` 在物品上不适用（物品数据通过 DataComponent 而非世界 NBT 持久化）。

## 与方块实体的对比

| 方面   | 方块实体                         | 物品                                |
|------|------------------------------|-----------------------------------|
| 基类   | `ManagedSyncBlockEntity`     | `ItemSyncHolder` + `ISyncManaged` |
| 数据存储 | `ValueOutput` / `ValueInput` | `DataComponent`                   |
| 同步驱动 | `updateTick()` 定期扫描          | `scanChanges()` 事件驱动              |
| 对象管理 | 由 Minecraft 管理生命周期           | 开发者管理生命周期                         |
| 容器广播 | `sendBlockUpdated()`         | `broadcastChanges()`              |
