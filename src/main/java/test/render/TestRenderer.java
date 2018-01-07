package test.render;

import com.jogamp.opengl.GLEventListener;

import engine.render.CubeRendering;
import engine.render.ObjectRendering;
import engine.render.PointRendering;

public class TestRenderer {
    public enum ObjectType {
        Cube, Point, Object
    }

    public static GLEventListener createObject(ObjectType object) {
        switch (object) {
        case Cube:
            return new CubeRendering();
        case Point:
            return new PointRendering();
        case Object:
            return new ObjectRendering();
        }

        return null;
    }
}
