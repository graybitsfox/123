package com.example.intelligentnpc.client;

import com.example.intelligentnpc.npc.NPCEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

public class NPCRenderer extends BipedEntityRenderer<NPCEntity, PlayerEntityModel<NPCEntity>> {

    private static final Identifier STEVE_TEXTURE = Identifier.of("minecraft", "textures/entity/steve.png");

    public NPCRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public Identifier getTexture(NPCEntity entity) {
        return STEVE_TEXTURE;
    }
}
