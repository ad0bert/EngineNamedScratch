package test.render;

import com.jogamp.opengl.GLEventListener;

import engine.ecs.Entity;
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
    }

    public enum ObjectType {
        Cube, Point, Object
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
        }
        this.current = obj;
    }

    private void doInit() {
        this.cube.addComponent(new CubeRendering("textures/cubeFace.png", 1f));
        this.point.addComponent(new PointRendering());
        this.object.addComponent(new ObjectRendering("objects/capsule/capsule.obj", "objects/capsule/capsule0.jpg"));

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
}
