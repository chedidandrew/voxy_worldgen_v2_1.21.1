package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.core.Config;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.voxyworldgenv2.title"));
            
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.general"));
            
            general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.enabled"), Config.DATA.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.enabled.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.enabled = newValue)
                .build());

            general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.f3_stats"), Config.DATA.showF3MenuStats)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.f3_stats.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.showF3MenuStats = newValue)
                .build());

            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.radius"), Config.DATA.generationRadius, 1, 512)
                .setDefaultValue(128)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.radius.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.generationRadius = newValue)
                .build());
            
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.update_interval"), Config.DATA.update_interval, 1, 200)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.update_interval.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.update_interval = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.max_queue"), Config.DATA.maxQueueSize)
                .setDefaultValue(20000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_queue.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxQueueSize = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.max_active"), Config.DATA.maxActiveTasks, 1, 128)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_active.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxActiveTasks = newValue)
                .build());
            
            builder.setSavingRunnable(() -> {
                Config.save();
                com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            });
            
            return builder.build();
        };
    }
}
