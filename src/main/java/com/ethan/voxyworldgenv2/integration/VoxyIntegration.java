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

    private VoxyIntegration() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> ingestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            
            Object serviceInstance = null;
            try {
                Field instanceField = ingestServiceClass.getDeclaredField("INSTANCE");
                serviceInstance = instanceField.get(null);
            } catch (Exception ignored) {
                // instance might not exist or be accessible
            }

            // find method using standard reflection
            String[] commonMethods = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            Method targetMethod = null;
            
            for (String methodName : commonMethods) {
                try {
                    targetMethod = ingestServiceClass.getMethod(methodName, LevelChunk.class);
                    if (targetMethod != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (targetMethod != null) {
                // 2. convert to methodhandle for performance
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                try {
                    ingestMethod = lookup.unreflect(targetMethod);
                    
                    // 3. bind to instance if it exists and method is instance-based
                    if (serviceInstance != null && !Modifier.isStatic(targetMethod.getModifiers())) {
                        ingestMethod = ingestMethod.bindTo(serviceInstance);
                    }
                    
                    enabled = true;
                    VoxyWorldGenV2.LOGGER.info("Voxy integration initialized successfully via MethodHandle: {}", targetMethod.getName());
                } catch (IllegalAccessException e) {
                    VoxyWorldGenV2.LOGGER.error("Failed to unreflect Voxy method", e);
                }
            } else {
                VoxyWorldGenV2.LOGGER.warn("Voxy detected but no suitable ingest method found");
            }

        } catch (ClassNotFoundException e) {
            VoxyWorldGenV2.LOGGER.info("Voxy not present, integration disabled");
            enabled = false;
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("Failed to initialize Voxy integration", e);
            enabled = false;
        }
    }

    public static void ingestChunk(LevelChunk chunk) {
        if (!initialized) initialize();
        if (!enabled || ingestMethod == null) return;

        try {
            ingestMethod.invoke(chunk);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("Failed to ingest chunk into Voxy", e);
        }
    }

    public static boolean isVoxyAvailable() {
        if (!initialized) initialize();
        return enabled;
    }
}
