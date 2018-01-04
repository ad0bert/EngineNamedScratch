package test.main;

import javax.swing.JFrame;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;

import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;
import test.messageing.DummyMessageReciver;
import test.render.TestRenderer;

public class TestApp {
    private final IMessageService messageService;
    private final JFrame window;

    private String version;

    public TestApp(IMessageService messageService, JFrame window) {
        this.messageService = messageService;
        this.window = window;
    }

    public void run() {
        IMessageReciver reciver1 = new DummyMessageReciver(1);
        this.messageService.register(reciver1, null);
        GLCanvas glcanvas = new TestRenderer();
        this.window.getContentPane().add(glcanvas);
        this.window.setVisible(true);
        final FPSAnimator animator = new FPSAnimator(glcanvas, 300, true);
        animator.start();
        while (this.window.isActive()) {
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
