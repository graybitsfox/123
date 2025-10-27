package com.example.intelligentnpc.client;

import com.example.intelligentnpc.npc.NPCManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

@Environment(EnvType.CLIENT)
public class IntelligentNPCModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Регистрируем рендерер для нашего типа NPC
        EntityRendererRegistry.register(NPCManager.NPC_ENTITY_TYPE, IntelligentNPCRenderer::new);
    }
}
