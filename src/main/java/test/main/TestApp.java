package test.main;

import java.io.IOException;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.jogamp.common.util.IOUtil;
import com.jogamp.newt.Display.PointerIcon;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.util.FPSAnimator;

import engine.ecs.Entity;
import engine.ecs.components.Camera;
import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;
import engine.render.CubeRendering;
import engine.windows.MainWindow;
import test.messageing.DummyMessageReciver;
import test.render.TestRenderer;
import test.render.TestRenderer.ObjectType;

public class TestApp implements IMessageReciver {
    private final IMessageService messageService;
    private TestRenderer testRenderer = new TestRenderer();
    private final GLWindow window;
    private String version;
    private PointerIcon pointerIcon;

    public TestApp(IMessageService messageService, MainWindow window) {
        this.messageService = messageService;
        this.window = window.getWindow();
    }

    public void run() {
        IMessageReciver reciver1 = new DummyMessageReciver(1);
        this.messageService.register(reciver1, null);
        this.messageService.register(this, null);

        Entity player = new Entity();
        Camera camera = new Camera(player);
        player.addComponent(camera);
        player.getPosition().setXYZ(0, 0, 5);
        this.testRenderer.addCamera(camera);
        this.messageService.register(camera, null);
        this.window.addGLEventListener(this.testRenderer.getEventListener());

        this.window.setVisible(true);
        final FPSAnimator animator = new FPSAnimator(this.window, 300, true);

        this.window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDestroyNotify(WindowEvent arg0) {
                // Use a dedicate thread to run the stop() to ensure that the
                // animator stops before program exits.
                new Thread() {
                    @Override
                    public void run() {
                        animator.stop(); // stop the animator loop
                    }
                }.start();
            };
        });

        animator.start();
        this.testRenderer.renderObject(ObjectType.Cube);

        try {
            ClassLoader loader = CubeRendering.class.getClassLoader();
            final IOUtil.ClassResources res = new IOUtil.ClassResources(
                    new String[] { loader.getResource("textures/pointerIcon.png").getPath() },
                    this.window.getScreen().getDisplay().getClass().getClassLoader(), null);
            this.pointerIcon = this.window.getScreen().getDisplay().createPointerIcon(res, 8, 8);
            this.window.setPointerIcon(this.pointerIcon);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            e.printStackTrace();
        }

        this.window.confinePointer(true);

        while (animator.isAnimating()) {
            try {
                Thread.sleep(1000000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        ApplicationContext context = new ClassPathXmlApplicationContext("applicationContext.xml");
        ((TestApp) context.getBean("testApp")).run();
        ((ClassPathXmlApplicationContext) context).close();
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @Override
    public void reciveMessage(MessageObject message) {
        if (message.getTransportedObject() instanceof MouseEvent) {
            if (this.window.isPointerConfined()) {
                this.window.warpPointer(this.window.getWidth() / 2, this.window.getHeight() / 2);
            }
            MouseEvent event = (MouseEvent) message.getTransportedObject();
            if (event.getEventType() == MouseEvent.EVENT_MOUSE_PRESSED) {
                this.testRenderer.nextObject();
            }
        }
        if (message.getTransportedObject() instanceof KeyEvent) {
            if ((((KeyEvent) message.getTransportedObject()).getKeyChar() == KeyEvent.VK_ESCAPE)
                    && (((KeyEvent) message.getTransportedObject()).getEventType() == KeyEvent.EVENT_KEY_PRESSED)) {
                this.window.confinePointer(!this.window.isPointerConfined());
            } else {
                System.out.println(((KeyEvent) message.getTransportedObject()).getKeyChar());
            }
        }
    }
}
