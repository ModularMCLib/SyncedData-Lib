# 注解参考

## 总览

| 注解                           | 目标 | 职责                      | 参数          |
|------------------------------|----|-------------------------|-------------|
| `@SaveField`                 | 字段 | 持久化到世界存档 NBT            | `nbtKey`    |
| `@ItemSave`                  | 字段 | 方块掉落时存入物品 DataComponent | `nbtKey`    |
| `@SyncToClient`              | 字段 | 服务端→客户端自动推送             | —           |
| `@SyncToServer`              | 字段 | 客户端→服务端上报标记             | —           |
| `@SyncBoth`                  | 字段 | 双端同步（S2C + C2S）         | —           |
| `@RerenderOnChanged`         | 字段 | 客户端收到更新后重渲染方块           | —           |
| `@ClientFieldChangeListener` | 方法 | 客户端收到字段更新后回调            | `fieldName` |

## `@SaveField`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SaveField {
    String nbtKey() default "";
}
```

**职责**：仅在世界存档时保存 / 读取字段值。

**存储路径**：
- 写入：`saveAdditional(ValueOutput)` → `serializeToSaveNBT()` → `output.store(nbtKey, codec, value)`
- 读取：`loadAdditional(ValueInput)` → `deserializeNBT()` → `codec.parse(input)`

`nbtKey` 指定 NBT 中的键名，默认使用字段名。一个字段只对应一个键。

**注意**：不影响方块掉落/放置时的物品数据，也不同步到客户端。

### 使用示例

```java
@SaveField
private UUID owner;

@SaveField(nbtKey = "charge")
private int chargeLevel;
```

## `@ItemSave`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ItemSave {
    String nbtKey() default "";
}
```

**职责**：方块破坏时自动将字段值存入掉落物的 `BLOCK_ITEM_DATA` DataComponent，放置时自动解析回字段。

**存储路径**：
- 方块破坏：`collectImplicitComponents()` → `components.set(BLOCK_ITEM_DATA, tag)` → 写入 ItemStack
- 方块放置：`applyImplicitComponents()` → `stack.get(BLOCK_ITEM_DATA)` → `deserializeItemNBT()`

`nbtKey` 指定 DataComponent 内部 CompoundTag 的键名。

### 使用示例

```java
@ItemSave
private int storedEnergy;

@ItemSave(nbtKey = "cfg_override")
private String configHash;
```

## `@SyncToClient`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncToClient {}
```

**职责**：标记字段需要服务端→客户端实时同步。

**机制**：
1. `updateTick()` 每 tick 调用 `scanAndMarkChanges()`
2. 遍历所有 `@SyncToClient` 字段，用 `Objects.equals()` 比较当前值与快照
3. 发现变更 → Codec 序列化 → 存储 `pendingClientChanges`
4. `sendBlockUpdated()` → `getUpdatePacket()` → `getPendingChanges()` 消费 → 发送到客户端
5. 客户端收到后 → `deserializeNBT()` → 更新字段 → 回调 → 重渲染

**无需手动调用任何方法**。变更会在下一个 server tick 自动检测并推送。

### 配合 `@RerenderOnChanged`

```java
@SyncToClient
@RerenderOnChanged   // 此字段变更时触发方块重渲染
private boolean active;
```

### 配合 `@ClientFieldChangeListener`

```java
@SyncToClient
private int progress;

@ClientFieldChangeListener(fieldName = "progress")
public void onProgress() {
    // 客户端 UI 更新
}
```

## `@SyncToServer`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncToServer {}
```

**职责**：标记字段允许客户端→服务端上报。

**注意**：此注解仅标记"此字段可接受 C2S 更新"。实际的网络包收发需要自行实现，服务端收到后调用：

```java
be.handleClientUpdate(registries, packetTag);
```

安全校验（权限、值范围、防作弊）必须在调用前完成。

## `@SyncBoth`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SyncBoth {}
```

**职责**：等价于 `@SyncToClient @SyncToServer`。

- **S2C**：`updateTick()` 自动扫描、检测变更并推送
- **C2S**：`handleClientUpdate()` + `applyServerUpdate()` 接收

不需要同时标注两个注解，一个 `@SyncBoth` 即可。

## `@RerenderOnChanged`

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RerenderOnChanged {}
```

**职责**：标记某个 `@SyncToClient` 或 `@SyncBoth` 字段在客户端收到更新后需要**重渲染方块**。

**注意**：此注解不负责同步。必须配合 `@SyncToClient` 或 `@SyncBoth` 使用。

```java
@SyncToClient @RerenderOnChanged
private boolean active;
```

## `@ClientFieldChangeListener`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClientFieldChangeListener {
    String fieldName();
}
```

**职责**：标记一个方法，在客户端收到指定字段的同步数据后自动调用。

**约束**：
- 方法不能是 static
- 方法无参数、无返回值
- 一个字段可以绑定多个 Listener

```java
@ClientFieldChangeListener(fieldName = "progress")
public void onProgressChanged() {
    // 自动调用
}
```

## 注解组合策略

| 场景        | 注解组合                                         | 说明          |
|-----------|----------------------------------------------|-------------|
| 仅世界存档     | `@SaveField`                                 | 存档持久化       |
| 仅物品存储     | `@ItemSave`                                  | 掉落/放置       |
| 存档+物品     | `@SaveField @ItemSave`                       | 既存世界又存物品    |
| 仅 S2C     | `@SyncToClient`                              | 服务端→客户端     |
| S2C+重渲染   | `@SyncToClient @RerenderOnChanged`           | 同步 + 重渲染    |
| S2C+回调    | `@SyncToClient + @ClientFieldChangeListener` | 同步 + 回调     |
| 双端        | `@SyncBoth`                                  | S2C + C2S   |
| 双端+重渲染    | `@SyncBoth @RerenderOnChanged`               | 双端 + 重渲染    |
| 存档+物品+S2C | `@SaveField @ItemSave @SyncToClient`         | 完整持久化 + S2C |
