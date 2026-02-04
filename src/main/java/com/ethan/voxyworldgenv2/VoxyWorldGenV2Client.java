package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.client.DebugRenderer;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class VoxyWorldGenV2Client implements ClientModInitializer {
    
    @SuppressWarnings("deprecation")
    @Override
    public void onInitializeClient() {
        VoxyWorldGenV2.LOGGER.info("initializing voxy world gen v2 client");
        
        // debug hud renderer
        HudRenderCallback.EVENT.register(DebugRenderer::render);

        // register pause check to stop background worker when game is paused
        ChunkGenerationManager.getInstance().setPauseCheck(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        });
    }
}
