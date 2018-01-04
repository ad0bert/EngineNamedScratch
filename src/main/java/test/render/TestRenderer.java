package test.render;

import com.jogamp.opengl.GLEventListener;

import engine.render.CubeRendering;
import engine.render.PointRendering;

public class TestRenderer {
    public enum ObjectType {
        Cube, Point
    }

    public static GLEventListener createObject(ObjectType object) {
        switch (object) {
        case Cube:
            return new CubeRendering();
        case Point:
            return new PointRendering();
        }
        return null;
    }
}
