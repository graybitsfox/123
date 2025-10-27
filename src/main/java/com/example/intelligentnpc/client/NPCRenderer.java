package com.example.intelligentnpc.client;

import com.example.intelligentnpc.npc.NPCEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.util.Identifier;

public class NPCRenderer extends PlayerEntityRenderer {

    private static final Identifier STEVE_TEXTURE = Identifier.of("minecraft", "textures/entity/steve.png");

    public NPCRenderer(EntityRendererFactory.Context ctx) {
        // false означает, что используется модель со стандартными руками (не "slim" как у Алекс)
        super(ctx, false);
    }

    @Override
    public Identifier getTexture(NPCEntity npcEntity) {
        // Всегда возвращаем текстуру Стива
        return STEVE_TEXTURE;
    }
}
