package engine.windows;

import com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLProfile;

import engine.systems.InputSystem;

public class MainWindow {
    private GLWindow glWindow;

    public MainWindow(InputSystem inputSystem, int sizeX, int sizeY) {
        this.glWindow = GLWindow.create(new GLCapabilities(GLProfile.get(GLProfile.GL2)));
        this.glWindow.addKeyListener(inputSystem);
        this.glWindow.addMouseListener(inputSystem);
        // this.glWindow.addMouseMotionListener(inputSystem);
        this.glWindow.setSize(sizeX, sizeY);
        this.glWindow.setDefaultCloseOperation(WindowClosingMode.DISPOSE_ON_CLOSE);
    }

    public GLWindow getWindow() {
        return this.glWindow;
    }
}
