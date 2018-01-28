package engine.windows;

import com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode;
import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.FPSAnimator;

import engine.systems.InputSystem;

public class MainWindow {
    private GLWindow glWindow;
    private FPSAnimator animator;

    private boolean isClosed = false;

    public MainWindow(InputSystem inputSystem, int sizeX, int sizeY) {
        this.glWindow = GLWindow.create(new GLCapabilities(GLProfile.get(GLProfile.GL2)));
        this.glWindow.addKeyListener(inputSystem);
        this.glWindow.addMouseListener(inputSystem);
        this.glWindow.setSize(sizeX, sizeY);
        this.glWindow.setDefaultCloseOperation(WindowClosingMode.DISPOSE_ON_CLOSE);
        this.animator = new FPSAnimator(this.glWindow, 300, true);
        this.animator.start();
        this.glWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDestroyNotify(WindowEvent event) {
                MainWindow.this.animator.stop();
                MainWindow.this.isClosed = true;
            };
        });
    }

    public GLWindow getWindow() {
        return this.glWindow;
    }

    public FPSAnimator getAnimator() {
        return this.animator;
    }

    public boolean isClosed() {
        return this.isClosed;
    }

    public void setClosed(boolean isClosed) {
        this.isClosed = isClosed;
    }
}
