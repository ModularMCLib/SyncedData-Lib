# SyncedData Lib Wiki

一个基于 **注解驱动 + 自动扫描 + DataComponent** 的数据同步框架，专为 NeoForge 26.1 设计。

## 特性

- **7 个注解，各司其职** — 保存、同步、重渲染、回调完全分离
- **自动变更检测** — 无需手动 `markDirty`，`updateTick()` 定期扫描字段并自动推送变更
- **基于 Codec** — 所有序列化使用 Minecraft 原生 `Codec` + `NbtOps`
- **基于 DataComponent** — 物品数据通过 `DataComponentType<CompoundTag>` 存储
- **可扩展** — 注册自定义 `Codec` 即可支持任意字段类型
- **无反射运行时开销** — `MethodHandles` + `VarHandle` 直接读写字段

## 项目结构

```
com.modularmc.synceddata
├── api
│   ├── blockentity/
│   │   └── BlockEntityCreationInfo.java    — BE 创建信息 record
│   └── sync_system/                        ← 同步系统核心
│       ├── annotations/                    — 7 个注解
│       ├── holder/                         — 数据持有实现
│       │   ├── SyncDataHolder.java         — 核心数据容器
│       │   └── ItemSyncHolder.java         — 物品数据包装
│       ├── meta/                           — 元数据处理
│       │   ├── ClassSyncData.java          — 类级注解缓存
│       │   ├── FieldSyncData.java          — 字段元数据
│       │   ├── FieldCodecs.java            — Codec 注册表
│       │   └── TypeDeclaration.java        — 类型声明
│       ├── ISyncManaged.java               — 核心接口
│       ├── ManagedSyncBlockEntity.java     — BE 基类
│       └── SyncedComponents.java           — DataComponent 注册
├── SyncedData.java                         — 主 Mod 入口
└── utils/
    └── FormattingUtil.java                 — 字符串工具
```

## 快速开始

### 1. 注册 DataComponent

在主 Mod 构造器中注册 `SyncedComponents`：

```java
public SyncedData(IEventBus bus) {
    SyncedComponents.COMPONENTS.register(bus);
}
```

### 2. 创建同步方块实体

```java
@Getter
public class MyMachineBE extends ManagedSyncBlockEntity {

    @SaveField
    private UUID owner;

    @SaveField @ItemSave
    private int storedEnergy;

    @SyncToClient @RerenderOnChanged
    private boolean active;

    @SyncBoth
    private int target;

    @ClientFieldChangeListener(fieldName = "active")
    public void onActiveChange() {
        if (getLevel() instanceof ClientLevel) playSound();
    }

    public MyMachineBE(BlockPos pos, BlockState state) {
        super(MyBlockEntities.MY_MACHINE.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, MyMachineBE be) {
        be.updateTick();  // 自动扫描 @SyncToClient/@SyncBoth
        if (be.active) be.storedEnergy++;
    }
}
```

### 3. 创建物品同步数据

```java
public class BatteryData implements ISyncManaged {

    private final ItemSyncHolder holder = new ItemSyncHolder(this);

    @ItemSave @SyncToClient
    private int energy;

    @SyncBoth
    private int maxEnergy;

    public SyncDataHolder getSyncDataHolder() { return holder.getSyncDataHolder(); }
    public void scheduleRenderUpdate() {}
    public void markAsChanged() {}
}
```

## 下一步

- [注解参考](annotations.md)
- [API 参考](api.md)
- [方块实体集成](blockentity.md)
- [物品集成](items.md)
- [同步流程与序列化](sync-flows.md)
