package test.main;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.util.FPSAnimator;

import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;
import engine.windows.MainWindow;
import test.messageing.DummyMessageReciver;
import test.render.TestRenderer;
import test.render.TestRenderer.ObjectType;

public class TestApp {
    private final IMessageService messageService;
    private final GLWindow window;

    private String version;

    public TestApp(IMessageService messageService, MainWindow window) {
        this.messageService = messageService;
        this.window = window.getWindow();
    }

    public void run() {
        IMessageReciver reciver1 = new DummyMessageReciver(1);
        this.messageService.register(reciver1, null);
        this.window.addGLEventListener(TestRenderer.createObject(ObjectType.Cube));
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
                        System.exit(0);
                    }
                }.start();
            };
        });
        animator.start();
        while (animator.isAnimating()) {
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
}
