package game.objects;

import engine.ecs.Entity;
import engine.render.ObjectRendering;

public class Capsule extends Entity {

    public Capsule() {
        this.addComponent(new ObjectRendering("objects/capsule/capsule.obj", "objects/capsule/capsule0.jpg"));
    }

}
