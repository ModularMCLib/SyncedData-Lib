package com.modularmc.synceddata.api.sync_system;

import com.modularmc.synceddata.api.blockentity.BlockEntityCreationInfo;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public abstract class ManagedSyncBlockEntity extends BlockEntity implements ISyncManaged {

	@Getter
	protected final SyncDataHolder syncDataHolder = new SyncDataHolder(this);
	@Getter
	@Setter
	private boolean isDirty;

	public ManagedSyncBlockEntity(BlockEntityCreationInfo info) {
		super(info.type(), info.pos(), info.state());
	}

	public ManagedSyncBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
		super(type, pos, blockState);
	}

	@Override
	public final void markAsChanged() {
		isDirty = true;
	}


	public final void updateTick() {
		setChanged();
		if (isDirty) {
			Objects.requireNonNull(getLevel()).sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
					Block.UPDATE_CLIENTS);
			isDirty = false;
		}
	}
}
