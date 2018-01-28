package game.objects;

import engine.ecs.Entity;
import engine.render.CubeRendering;

public class Qube extends Entity {
    public Qube() {
        this.addComponent(new CubeRendering("textures/cubeFace.png", 1f));
    }
}
