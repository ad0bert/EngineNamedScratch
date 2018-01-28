package game.objects;

import engine.ecs.Entity;
import engine.render.PointRendering;

public class Points extends Entity {

    public Points() {
        this.addComponent(new PointRendering());
    }
}
