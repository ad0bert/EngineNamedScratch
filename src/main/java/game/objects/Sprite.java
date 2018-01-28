package game.objects;

import engine.ecs.Entity;
import engine.render.SpriteRenderer;

public class Sprite extends Entity {
    public Sprite() {
        this.addComponent(new SpriteRenderer("textures/pointerIcon.png"));
    }
}
