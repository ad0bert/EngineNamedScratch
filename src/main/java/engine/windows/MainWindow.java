package engine.windows;

import javax.swing.JFrame;

import engine.systems.InputSystem;

public class MainWindow extends JFrame {
    private static final long serialVersionUID = 1L;

    public MainWindow(InputSystem inputSystem, int sizeX, int sizeY) {
        addKeyListener(inputSystem);
        addMouseListener(inputSystem);
        addMouseMotionListener(inputSystem);
        setSize(sizeX, sizeY);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
}
