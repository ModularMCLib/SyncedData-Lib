# 架构设计

## 包结构

```
sync_system/                          ← 同步模块根目录
│
├── annotations/                      ← 注解定义（7个文件）
│   ├── SaveField.java                — 世界存档
│   ├── ItemSave.java                 — 物品存储
│   ├── SyncToClient.java             — S2C
│   ├── SyncToServer.java             — C2S
│   ├── SyncBoth.java                 — 双端
│   ├── RerenderOnChanged.java        — 重渲染
│   └── ClientFieldChangeListener.java — 回调
│
├── meta/                             ← 元数据处理（内部实现）
│   ├── ClassSyncData.java            — 类级注解缓存
│   ├── FieldSyncData.java            — 字段注解数据
│   ├── FieldCodecs.java              — Codec 注册表
│   └── TypeDeclaration.java          — 类型声明包装
│
├── holder/                           ← 数据持有层
│   ├── SyncDataHolder.java           — 核心数据容器
│   └── ItemSyncHolder.java           — 物品数据包装
│
├── ISyncManaged.java                 ← 核心接口
├── ManagedSyncBlockEntity.java       ← BE 基类
└── SyncedComponents.java             ← DataComponent 注册
```

## 类图

```
┌─────────────────────────────────────────────────────────────┐
│                    ISyncManaged (interface)                   │
│  ┌──────────────────────┐────────────────────────────────┐   │
│  │ SyncDataHolder 的内容│ scheduleRenderUpdate()          │   │
│  │ getSyncDataHolder()  │ markAsChanged()                 │   │
│  └──────────────────────┴────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │ implements
            ┌────────────┴────────────┐
            ▼                         ▼
┌──────────────────────┐  ┌──────────────────────────────┐
│ ManagedSyncBlockEntity│  │     ItemSyncHolder             │
│  (extends BlockEntity) │  │        (holder wrapper)       │
│                       │  │                               │
│  @Override            │  │  saveToStack()                │
│  saveAdditional()     │  │  loadFromStack()              │
│  loadAdditional()     │  │  scanChanges()                │
│  updateTick()         │  │  flushToStack()               │
│  handleClientUpdate() │  │  applyServerUpdate()          │
└──────────┬───────────┘  └──────────────┬────────────────┘
           │ owns                        │ owns
           ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│                 SyncDataHolder (核心引擎)                     │
│                                                             │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │ 变更检测引擎  │ │ 序列化引擎     │ │ 反序列化引擎          │  │
│  │ scanAndMark │ │ encodeField   │ │ decodeField          │  │
│  │             │ │ serializeNBT  │ │ deserializeNBT       │  │
│  │ cachedValues│ │               │ │                      │  │
│  │ pending     │ │               │ │                      │  │
│  └─────────────┘ └──────────────┘ └──────────────────────┘  │
│                                                             │
│  持有: ClassSyncData (从 holder 的 Class 解析)               │
└─────────────────────────┬───────────────────────────────────┘
                          │ uses
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    ClassSyncData                              │
│  ClassValue<ClassSyncData> CACHE                             │
│                                                             │
│  Set<FieldSyncData> worldSaveFields     (@SaveField)         │
│  Set<FieldSyncData> itemSaveFields      (@ItemSave)          │
│  Set<FieldSyncData> clientSyncFields    (@SyncToClient)       │
│  Set<FieldSyncData> serverSyncFields    (@SyncToServer)       │
│  Set<FieldSyncData> bothSyncFields      (@SyncBoth)           │
│                                                             │
│  private ClassSyncData(Class<?>)                            │
│    → 扫描 @ClientFieldChangeListener 方法                     │
│    → 扫描 @SaveField/@SyncToClient/... 字段                  │
│    → 创建 FieldSyncData + FieldCodecs 解析                   │
│    → 递归处理父类                                            │
└─────────────────────────────────────────────────────────────┘
```

## 数据流总图

```
                    ┌─────────────────┐
                    │    Minecraft     │
                    │    Server       │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────────┐
              │              │                   │
              ▼              ▼                   ▼
      ┌──────────────┐ ┌──────────┐ ┌──────────────────┐
      │ saveAdditional│ │updateTick│ │collectImplicit   │
      │ (存档)        │ │(每tick) │ │Components(掉落)   │
      └──────┬───────┘ └────┬─────┘ └────────┬─────────┘
             │              │                │
             ▼              ▼                ▼
      ┌────────────────────────────────────────────┐
      │              SyncDataHolder                 │
      │                                            │
      │  serializeToSaveNBT()    ← @SaveField       │
      │  serializeToItemNBT()    ← @ItemSave        │
      │  scanAndMarkChanges()    ← @SyncToClient    │
      │  getPendingChanges()     → 增量更新包       │
      │  serializeFullClientSync → @SyncToClient    │
      │  deserializeNBT()        → 恢复字段         │
      │  deserializeItemNBT()    → @ItemSave 恢复    │
      │  applyServerUpdate()     → @SyncToServer    │
      └──────────────┬─────────────────────────────┘
                     │
                     ▼
            ┌────────────────┐
            │    网络/存储     │
            └────────────────┘
                     │
                     ▼
            ┌────────────────┐
            │   ClientLevel   │
            │  (客户端)       │
            │                │
            │ loadAdditional │
            │ → deserializeNBT(true)            │
            │ → 更新字段值 + 缓存              │
            │ → @ClientFieldChangeListener 回调  │
            │ → @RerenderOnChanged → 重渲染      │
            └────────────────┘
```

## 变更检测引擎

```
                ┌──────────────┐
                │ cachedValues  │
                │ Map<String,   │
                │  Object>      │
                └──────┬───────┘
                       │
            Objects.equals(current, previous)?
            ┌──────┐         ┌───────┐
            │ 相同  │         │ 不同   │
            │ 跳过  │         │ 序列化 │
            └──────┘         │ 更新   │
                             │ 快照   │
                             └───┬───┘
                                 │ hasChanges = true
                                 ▼
                        ┌────────────────┐
                        │ pendingClient   │
                        │ Changes         │
                        │ (CompoundTag)   │
                        └────────────────┘
                              │ getPendingChanges()
                              ▼
                        getUpdatePacket()
                        → sendBlockUpdated()
```

## Time Complexity

| 操作       | 复杂度           | 说明                                       |
|----------|---------------|------------------------------------------|
| 注解扫描     | `O(F)` 每类一次   | F = 字段数，`ClassValue` 全局缓存                |
| 变更检测     | `O(F)` 每 tick | 仅遍历 `@SyncToClient` 字段                   |
| 序列化      | `O(F')`       | F' = 实际变数字段数                             |
| 反序列化     | `O(F')`       | F' = 收到包中的字段数                            |
| Codec 查找 | `O(R)`        | R = 注册数，`Reference2ReferenceOpenHashMap` |

## 线程安全

**非线程安全**。`SyncDataHolder` 及其关联类设计在**单线程环境**使用：
- 方块实体：服务端 tick thread
- 物品：容器所属的 server/client thread

不要在异步线程中读写同步字段。
