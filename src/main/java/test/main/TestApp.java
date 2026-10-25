package test.main;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.jogamp.common.util.IOUtil;
import com.jogamp.newt.Display.PointerIcon;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.MouseEvent;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;
import engine.util.Timer;
import engine.windows.MainWindow;
import game.objects.Player;
import test.messageing.DummyMessageReciver;
import test.render.TestRenderer;
import test.render.TestRenderer.ObjectType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import lombok.val;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class TestApp implements IMessageReciver {
  final IMessageService messageService;
  final TestRenderer testRenderer = new TestRenderer();
  final MainWindow window;
  @Getter
  @Setter
  String version;
  PointerIcon pointerIcon;

  Queue<MessageObject> messageQueue = new LinkedBlockingQueue<>();

  public TestApp(IMessageService messageService, MainWindow window) {
    this.messageService = messageService;
    this.window = window;
  }

  public void run() {
    val reciver1 = new DummyMessageReciver(1);
    this.messageService.register(reciver1, null);
    this.messageService.register(this, null);

    val player = new Player();
    player.getPosition().setXYZ(0, 0, 5);
    this.testRenderer.addCamera(player.getCamera());
    this.testRenderer.renderObject(ObjectType.Cube);
    this.window.getWindow().addGLEventListener(this.testRenderer.getEventListener());
    this.window.getWindow().setVisible(true);

    try {
      val loader = TestApp.class.getClassLoader();
      val res = new IOUtil.ClassResources(
          new String[] { loader.getResource("textures/pointerIcon.png").getPath() },
          this.window.getWindow().getScreen().getDisplay().getClass().getClassLoader(), null);
      this.pointerIcon = this.window.getWindow().getScreen().getDisplay().createPointerIcon(res, 8, 8);
      this.window.getWindow().setPointerIcon(this.pointerIcon);
    } catch (IllegalArgumentException | IllegalStateException | IOException e) {
      e.printStackTrace();
    }

    this.window.getWindow().confinePointer(true);

    while (!this.window.isClosed()) {
      Timer.updateGame(this);
    }
    System.out.println("stoped");
  }

  public static void main(String[] args) {
    try (val context = new ClassPathXmlApplicationContext("applicationContext.xml")) {
      context.getBean("testApp", TestApp.class).run();
    }
  }

  public void gameLogic(double t, double dt) {
    System.out.println(t + " -- " + dt);
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void reciveMessage(MessageObject message) {
    if ((message.getTransportedObject() instanceof MouseEvent)
        || (message.getTransportedObject() instanceof KeyEvent)) {
      this.messageQueue.add(message);
    }
  }
}
