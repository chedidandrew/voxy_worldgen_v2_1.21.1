package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class TellusIntegration {
    private static boolean initialized = false;
    private static boolean tellusPresent = false;
    
    // classes
    private static Class<?> earthChunkGeneratorClass;
    private static Class<?> tellusElevationSourceClass;
    private static Class<?> earthGeneratorSettingsClass;
    
    // methods
    private static MethodHandle sampleElevationMetersMethod;
    private static MethodHandle getSettingsMethod;
    
    // fields
    private static Field elevationSourceField;

    // cached methods for settings
    private static MethodHandle worldScaleHandle;
    private static MethodHandle terrestrialHeightScaleHandle;
    private static MethodHandle oceanicHeightScaleHandle;
    private static MethodHandle heightOffsetHandle;
    private static MethodHandle resolveSeaLevelHandle;

    private TellusIntegration() {}

    // additional fields/methods for surface logic
    private static Class<?> landCoverSourceClass;
    private static MethodHandle sampleCoverClassHandle;
    private static Object landCoverSource;
    private static Field landCoverSourceField;

    // constants from tellus
    private static final int ESA_WATER = 80;
    private static final int ESA_SNOW_ICE = 220;
    private static final int ESA_TREE_COVER = 10;

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            earthChunkGeneratorClass = Class.forName("com.yucareux.tellus.worldgen.EarthChunkGenerator");
            tellusElevationSourceClass = Class.forName("com.yucareux.tellus.world.data.elevation.TellusElevationSource");
            earthGeneratorSettingsClass = Class.forName("com.yucareux.tellus.worldgen.EarthGeneratorSettings");
            landCoverSourceClass = Class.forName("com.yucareux.tellus.world.data.cover.LandCoverSource");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            elevationSourceField = earthChunkGeneratorClass.getDeclaredField("ELEVATION_SOURCE");
            elevationSourceField.setAccessible(true);

            landCoverSourceField = earthChunkGeneratorClass.getDeclaredField("LAND_COVER_SOURCE");
            landCoverSourceField.setAccessible(true);
            landCoverSource = landCoverSourceField.get(null);

            Method sampleCoverMethod = landCoverSourceClass.getMethod("sampleCoverClass", int.class, int.class, double.class);
            sampleCoverClassHandle = lookup.unreflect(sampleCoverMethod);

            Method sampleMethod = tellusElevationSourceClass.getMethod("sampleElevationMeters", double.class, double.class, double.class, boolean.class);
            sampleElevationMetersMethod = lookup.unreflect(sampleMethod);
            
            Method settingsMethod = earthChunkGeneratorClass.getMethod("settings");
            getSettingsMethod = lookup.unreflect(settingsMethod);

            worldScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("worldScale"));
            terrestrialHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("terrestrialHeightScale"));
            oceanicHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("oceanicHeightScale"));
            heightOffsetHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("heightOffset"));
            resolveSeaLevelHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("resolveSeaLevel"));

            tellusPresent = true;
            VoxyWorldGenV2.LOGGER.info("tellus integration initialized");
        } catch (ClassNotFoundException e) {
            VoxyWorldGenV2.LOGGER.info("tellus not present");
            tellusPresent = false;
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to initialize tellus", e);
            tellusPresent = false;
        }
    }

    public static boolean isTellusWorld(ServerLevel level) {
        if (!initialized) initialize();
        if (!tellusPresent) return false;
        return earthChunkGeneratorClass.isInstance(level.getChunkSource().getGenerator());
    }

    public record TellusChunkData(int[] heights, int[] coverClasses) {}

    public static TellusChunkData sampleData(ServerLevel level, ChunkPos pos) {
        if (!initialized) initialize();
        if (!tellusPresent) return null;

        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object elevationSource = elevationSourceField.get(null);

            double worldScale = (double) worldScaleHandle.invoke(settings);
            double terrestrialHeightScale = (double) terrestrialHeightScaleHandle.invoke(settings);
            double oceanicHeightScale = (double) oceanicHeightScaleHandle.invoke(settings);
            int heightOffset = (int) heightOffsetHandle.invoke(settings);

            int[] heights = new int[256];
            int[] coverClasses = new int[256];
            int minBlockX = pos.getMinBlockX();
            int minBlockZ = pos.getMinBlockZ();

            for (int i = 0; i < 256; i++) {
                int z = i >> 4;
                int x = i & 15;
                int worldZ = minBlockZ + z;
                int worldX = minBlockX + x;
                try {
                    double elevation = (double) sampleElevationMetersMethod.invoke(elevationSource, (double) worldX, (double) worldZ, worldScale, true);
                    double heightScale = elevation >= 0.0 ? terrestrialHeightScale : oceanicHeightScale;
                    double scaled = elevation * heightScale / worldScale;
                    heights[i] = (elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled)) + heightOffset;
                    coverClasses[i] = (int) sampleCoverClassHandle.invoke(landCoverSource, worldX, worldZ, worldScale);
                } catch (Throwable e) {}
            }
            
            return new TellusChunkData(heights, coverClasses);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to sample tellus data for {}", pos, e);
            return null;
        }
    }

    public static int[] sampleHeights(ServerLevel level, ChunkPos pos) {
        TellusChunkData data = sampleData(level, pos);
        return data != null ? data.heights : null;
    }

    private static final DataLayer FULL_LIGHT = new DataLayer(15);

    public static void generateFromHeights(ServerLevel level, ChunkPos pos, TellusChunkData data) {
        if (data == null) return;
        int[] heights = data.heights;
        int[] coverClasses = data.coverClasses;
        
        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            int seaLevel = (int) resolveSeaLevelHandle.invoke(settings);
            
            PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());

            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
            BlockState water = Blocks.WATER.defaultBlockState();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            BlockState sand = Blocks.SAND.defaultBlockState();
            BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState();

            // calculate global bounds
            int globalMinH = 256;
            int globalMaxH = -256;
            for (int h : heights) {
                if (h < globalMinH) globalMinH = h;
                if (h > globalMaxH) globalMaxH = h;
            }

            LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
            int minSectionY = level.getMinSectionY();
            
            // optimize the solid section generation
            for (int i = 0; i < sections.length; i++) {
                int sectionY = minSectionY + i;
                int sectionTopY = (sectionY << 4) + 15;
                
                if (sectionTopY < globalMinH - 3) {
                    BlockState fill = sectionTopY < 0 ? deepslate : stone;
                    LevelChunkSection section = new LevelChunkSection(factory);
                    for (int by = 0; by < 16; by++)
                        for (int bz = 0; bz < 16; bz++)
                            for (int bx = 0; bx < 16; bx++)
                                section.setBlockState(bx, by, bz, fill, false);
                    section.recalcBlockCounts();
                    sections[i] = section;
                }
            }

            // surface generation
            for (int i = 0; i < 256; i++) {
                int z = i >> 4;
                int x = i & 15;
                int h = heights[i];
                int coverClass = coverClasses[i];
                
                BlockState surfaceBlock = grass;
                BlockState fillerBlock = dirt;

                int eastH = (x < 15) ? heights[i + 1] : h;
                int southH = (z < 15) ? heights[i + 16] : h;
                int slope = Math.max(Math.abs(eastH - h), Math.abs(southH - h));
                
                if (coverClass == ESA_SNOW_ICE) {
                   surfaceBlock = snow;
                   fillerBlock = dirt;
                }
                
                if (h <= seaLevel + 2 && slope < 2 && h >= seaLevel - 2) {
                     surfaceBlock = sand;
                     fillerBlock = sand;
                }
                
                if (slope > 2) {
                    surfaceBlock = stone;
                    fillerBlock = stone;
                }
                
                int columnTopY = Math.max(h, seaLevel);
                for (int y = level.getMinY(); y <= columnTopY; y++) {
                    int secIdx = (y >> 4) - minSectionY;
                    if (secIdx < 0 || secIdx >= sections.length) continue;
                    
                    LevelChunkSection section = sections[secIdx];
                    if (section == null) {
                        section = new LevelChunkSection(factory);
                        sections[secIdx] = section;
                    }
                    
                    int sectionTopY = ( (secIdx + minSectionY) << 4) + 15;
                    
                    // adaptive column skip
                    if (sectionTopY < globalMinH - 3) {
                        y = sectionTopY;
                        continue;
                    }

                    BlockState state = Blocks.AIR.defaultBlockState();
                    if (y < h - 3) {
                         state = y < 0 ? deepslate : stone;
                    } else if (y < h) {
                         state = fillerBlock;
                    } else if (y == h) {
                         state = surfaceBlock;
                    } else if (y <= seaLevel) {
                        state = water;
                    }
                    
                    if (!state.isAir()) {
                        section.setBlockState(x, y & 15, z, state, false);
                    }
                }
            }

            // ingest into voxy
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section != null && !section.hasOnlyAir()) {
                     section.recalcBlockCounts();
                     int sectionY = minSectionY + i;
                     VoxyIntegration.rawIngest(level, section, pos.x, sectionY, pos.z, FULL_LIGHT);
                }
            }
            
            VoxyWorldGenV2.LOGGER.info("ingested tellus chunk for {}", pos);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to build tellus chunk for {}", pos, e);
        }
    }

    public static void generateTellusChunkFast(ServerLevel level, ChunkPos pos) {
        TellusChunkData data = sampleData(level, pos);
        if (data != null) {
            generateFromHeights(level, pos, data);
        }
    }
}
