package engine.messageing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import engine.ecs.Entity;
import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;

public class MessageServiceImpl implements IMessageService {
    private final Map<Integer, List<IMessageReciver>> serviceMap = new HashMap<Integer, List<IMessageReciver>>();
    private final List<IMessageReciver> broadCastList = new ArrayList<IMessageReciver>();

    @Override
    public void send(MessageObject messageObject) {
        // broadcast
        if (messageObject.getQueueId() == null) {
            if (messageObject.getReciverId() == null) {
                for (int i = 0; i < this.broadCastList.size(); ++i) {
                    this.broadCastList.get(i).reciveMessage(messageObject);
                }
            } else {
                if (messageObject.getQueueId() == null) {
                    return;
                }
                for (IMessageReciver reciver : this.serviceMap.get(messageObject.getQueueId())) {
                    if (((Entity) reciver).getId() == messageObject.getReciverId()) {
                        reciver.reciveMessage(messageObject);
                    }
                }
            }
        } else {
            for (IMessageReciver reciver : this.serviceMap.get(messageObject.getQueueId())) {
                reciver.reciveMessage(messageObject);
            }
        }
    }

    @Override
    public boolean register(IMessageReciver toRegister, Integer id) {
        // add everything to broadcast list
        this.broadCastList.add(toRegister);
        // only broadcast
        if (id == null) {
            return true;
        }
        if (this.serviceMap.get(id.intValue()) == null) {
            this.serviceMap.put(id.intValue(), new ArrayList<IMessageReciver>());
        }
        this.serviceMap.get(id.intValue()).add(toRegister);
        return true;
    }

    @Override
    public boolean unregister(IMessageReciver toDeRegister, Integer id) {
        boolean ok = false;
        if (id == null) {
            ok = this.broadCastList.remove(toDeRegister);
        }
        if (this.serviceMap.get(id.intValue()) != null) {
            ok |= this.serviceMap.get(id.intValue()).remove(toDeRegister);
        }
        return ok;
    }
}
