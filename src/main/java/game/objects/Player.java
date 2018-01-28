package game.objects;

import engine.ecs.Entity;
import engine.ecs.components.Camera;
import engine.ecs.components.IComponent;
import engine.messageing.MessageObject;
import engine.messageing.MessageQueueNames;
import engine.messageing.interfaces.IMessageReciver;

public class Player extends Entity implements IMessageReciver {
    public Camera getCamera() {
        for (IComponent comp : this.getComponents()) {
            if (comp instanceof Camera) {
                return (Camera) comp;
            }
        }
        return null;
    }

    public Player() {
        addComponent(new Camera());
    }

    @Override
    public void reciveMessage(MessageObject message) {
        if (MessageQueueNames.QUEUES.get("MOUSE").equals(message.getQueueId())) {
            System.out.println(this);
        } else if (MessageQueueNames.QUEUES.get("KEYBOARD").equals(message.getQueueId())) {
            System.out.println(this);
        }

    }

}
