package test.render;

import com.jogamp.opengl.GLEventListener;

import engine.ecs.Entity;
import engine.ecs.components.Camera;
import engine.render.RenderSystem;
import engine.render.interfaces.IDrawable;
import game.objects.Capsule;
import game.objects.Points;
import game.objects.Qube;
import game.objects.Sprite;

public class TestRenderer {

    private RenderSystem renderSystem = new RenderSystem();
    private Entity cube;
    private Entity point;
    private Entity object;
    private Entity sprite;

    boolean init = false;

    ObjectType current = ObjectType.Cube;

    public TestRenderer() {
        this.cube = new Qube();
        this.point = new Points();
        this.object = new Capsule();
        this.sprite = new Sprite();
        this.cube.getPosition().setXYZ(0f, 0f, 0f);
        this.point.getPosition().setXYZ(1f, 1f, 1f);
        this.object.getPosition().setXYZ(2f, 2f, 2f);
        this.sprite.getPosition().setXYZ(0f, 0f, 0f);
        this.sprite.activateComponent(IDrawable.class);
    }

    public enum ObjectType {
        Cube, Point, Object, All
    }

    public void renderObject(ObjectType obj) {
        if (!this.init) {
            doInit();
        }
        switch (obj) {
        case Cube:
            this.cube.activateComponent(IDrawable.class);
            this.point.deactivateComponent(IDrawable.class);
            this.object.deactivateComponent(IDrawable.class);
            break;
        case Point:
            this.cube.deactivateComponent(IDrawable.class);
            this.point.activateComponent(IDrawable.class);
            this.object.deactivateComponent(IDrawable.class);
            break;
        case Object:
            this.cube.deactivateComponent(IDrawable.class);
            this.point.deactivateComponent(IDrawable.class);
            this.object.activateComponent(IDrawable.class);
            break;
        case All:
            this.cube.activateComponent(IDrawable.class);
            this.point.activateComponent(IDrawable.class);
            this.object.activateComponent(IDrawable.class);
        }
        this.current = obj;
    }

    private void doInit() {
        this.renderSystem.getDrawingList().add(this.cube);
        this.renderSystem.getDrawingList().add(this.point);
        this.renderSystem.getDrawingList().add(this.object);
        this.renderSystem.getDrawingList().add(this.sprite);
        this.init = true;
    }

    public void nextObject() {
        switch (this.current) {
        case Cube:
            renderObject(ObjectType.Point);
            this.current = ObjectType.Point;
            break;
        case Point:
            renderObject(ObjectType.Object);
            this.current = ObjectType.Object;
            break;
        case Object:
            renderObject(ObjectType.All);
            this.current = ObjectType.All;
            break;
        case All:
            renderObject(ObjectType.Cube);
            this.current = ObjectType.Cube;
            break;
        default:
            renderObject(ObjectType.Cube);
            this.current = ObjectType.Cube;
        }
    }

    public GLEventListener getEventListener() {
        return this.renderSystem;
    }

    public void addCamera(Camera camera) {
        this.renderSystem.setCamera(camera);
    }
}
