package test.render;

import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;

import engine.render.CubeRendering;

public class TestRenderer extends GLCanvas {
    private static final long serialVersionUID = 1L;

    public TestRenderer() {
        super(new GLCapabilities(GLProfile.get(GLProfile.GL2)));
        GLEventListener toRender = new CubeRendering();
        addGLEventListener(toRender);
        setFocusable(false);
        setEnabled(false);
        setSize(400, 400);
    }
}
