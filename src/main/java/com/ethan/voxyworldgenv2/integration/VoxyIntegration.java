package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class VoxyIntegration {
    private static boolean initialized = false;
    private static boolean enabled = false;
    private static MethodHandle ingestMethod;
    private static MethodHandle rawIngestMethod;
    private static MethodHandle worldIdentifierOfMethod;

    private VoxyIntegration() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> ingestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            
            Object serviceInstance = null;
            try {
                Field instanceField = ingestServiceClass.getDeclaredField("INSTANCE");
                serviceInstance = instanceField.get(null);
            } catch (Exception ignored) {
            }

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // 1. find main ingest method
            String[] commonMethods = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            Method targetMethod = null;
            for (String methodName : commonMethods) {
                try {
                    targetMethod = ingestServiceClass.getMethod(methodName, LevelChunk.class);
                    if (targetMethod != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (targetMethod != null) {
                ingestMethod = lookup.unreflect(targetMethod);
                if (serviceInstance != null && !Modifier.isStatic(targetMethod.getModifiers())) {
                    ingestMethod = ingestMethod.bindTo(serviceInstance);
                }
                enabled = true;
            }

            // 2. find rawIngest method
            try {
                Method rawIngest = ingestServiceClass.getMethod("rawIngest", 
                    worldIdentifierClass, 
                    net.minecraft.world.level.chunk.LevelChunkSection.class, 
                    int.class, int.class, int.class, 
                    net.minecraft.world.level.chunk.DataLayer.class, 
                    net.minecraft.world.level.chunk.DataLayer.class);
                rawIngestMethod = lookup.unreflect(rawIngest);
            } catch (NoSuchMethodException ignored) {}

            // 3. find WorldIdentifier.of method
            try {
                Method ofMethod = worldIdentifierClass.getMethod("of", net.minecraft.world.level.Level.class);
                worldIdentifierOfMethod = lookup.unreflect(ofMethod);
            } catch (NoSuchMethodException ignored) {}

            VoxyWorldGenV2.LOGGER.info("voxy integration initialized (enabled: {}, raw: {})", enabled, rawIngestMethod != null);

        } catch (ClassNotFoundException e) {
            VoxyWorldGenV2.LOGGER.info("voxy not present, integration disabled");
            enabled = false;
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to initialize voxy integration", e);
            enabled = false;
        }
    }

    public static void ingestChunk(LevelChunk chunk) {
        if (!initialized) initialize();
        if (!enabled || ingestMethod == null) return;

        try {
            ingestMethod.invoke(chunk);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to ingest chunk", e);
        }
    }

    public static void rawIngest(LevelChunk chunk, net.minecraft.world.level.chunk.DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            int minSection = chunk.getMinSection();
            
            // get worldid once per chunk
            Object worldId = worldIdentifierOfMethod.invoke(chunk.getLevel());
            if (worldId == null) return;

            for (int i = 0; i < sections.length; i++) {
                net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                
                rawIngestMethod.invoke(worldId, section, cx, minSection + i, cz, null, skyLight);

            }
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to raw ingest chunk", e);
        }
    }
    
    public static void rawIngest(net.minecraft.world.level.Level level, net.minecraft.world.level.chunk.LevelChunkSection section, int cx, int cy, int cz, net.minecraft.world.level.chunk.DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            Object worldId = worldIdentifierOfMethod.invoke(level);
            if (worldId == null) return;
            
            rawIngestMethod.invoke(worldId, section, cx, cy, cz, null, skyLight);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to raw ingest section", e);
        }
    }

    public static boolean isVoxyAvailable() {
        if (!initialized) initialize();
        return enabled;
    }
}
