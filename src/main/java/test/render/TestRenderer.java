package test.render;

import com.jogamp.opengl.GLEventListener;

import engine.ecs.Entity;
import engine.ecs.components.Camera;
import engine.render.CubeRendering;
import engine.render.ObjectRendering;
import engine.render.PointRendering;
import engine.render.RenderSystem;
import engine.render.interfaces.IDrawable;

public class TestRenderer {

    private RenderSystem renderSystem = new RenderSystem();
    private Entity cube;
    private Entity point;
    private Entity object;

    boolean init = false;

    ObjectType current = ObjectType.Cube;

    public TestRenderer() {
        this.cube = new Entity();
        this.point = new Entity();
        this.object = new Entity();
        this.cube.getPosition().setXYZ(0f, 0f, 0f);
        this.point.getPosition().setXYZ(1f, 1f, 1f);
        this.object.getPosition().setXYZ(2f, 2f, 2f);
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
        this.cube.addComponent(new CubeRendering("textures/cubeFace.png", 1f, this.cube));
        this.point.addComponent(new PointRendering(this.point));
        this.object.addComponent(
                new ObjectRendering("objects/capsule/capsule.obj", "objects/capsule/capsule0.jpg", this.object));

        this.renderSystem.getDrawingList().add(this.cube);
        this.renderSystem.getDrawingList().add(this.point);
        this.renderSystem.getDrawingList().add(this.object);
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
