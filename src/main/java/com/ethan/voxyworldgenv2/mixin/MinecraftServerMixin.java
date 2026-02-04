package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.core.MinecraftServerExtension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin implements MinecraftServerExtension {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Unique
    private final AtomicBoolean needHousekeeping = new AtomicBoolean(false);

    // 1.21.1: MinecraftServer has tickWorlds(BooleanSupplier)
    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void voxyworldgen$onTickWorlds(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        this.voxyworldgen$runHousekeeping(shouldKeepTicking);
    }

    @Override
    public void voxyworldgen$runHousekeeping(BooleanSupplier haveTime) {
        if (this.needHousekeeping.compareAndSet(true, false)) {
            for (ServerLevel level : this.getAllLevels()) {
                ((ChunkMapMixin) level.getChunkSource().chunkMap).invokeTick(haveTime);
                ((ServerLevelMixin) level).getEntityManager().tick();
            }
        }
    }

    @Override
    public void voxyworldgen$markHousekeeping() {
        this.needHousekeeping.set(true);
    }
}
