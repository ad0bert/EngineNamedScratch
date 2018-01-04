package engine.messageing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;

public class MessageServiceImpl implements IMessageService {
    private final Map<Integer, Map<Integer, IMessageReciver>> serviceMap = new HashMap<Integer, Map<Integer, IMessageReciver>>();
    private final List<IMessageReciver> broadCastList = new ArrayList<IMessageReciver>();

    @Override
    public void send(MessageObject messageObject) {

        // broadcast
        if (messageObject.getReciverId() == null) {
            for (int i = 0; i < broadCastList.size(); ++i) {
                broadCastList.get(i).reciveMessage(messageObject);
            }
            return;
        }
        // get group
        Map<Integer, IMessageReciver> serviceGroup = serviceMap.get((messageObject.getReciverId().intValue() >> 8) & 0xFF);
        if (serviceGroup == null) {
            return;
        }
        // broadcast to group
        if ((messageObject.getReciverId().intValue() & 0xFF) == 0) {
            for (Entry<Integer, IMessageReciver> entry : serviceGroup.entrySet()) {
                entry.getValue().reciveMessage(messageObject);
            }
        } else { // specific reciver
            IMessageReciver reciver = serviceGroup.get((messageObject.getReciverId().intValue() & 0xFF));
            if (reciver != null) {
                reciver.reciveMessage(messageObject);
            }
        }
    }

    @Override
    public boolean register(IMessageReciver toRegister, Integer id) {
        // cant add to group broadcast id
        if ((id != null) && ((id.intValue() & 0xFF) == 0)) {
            return false;
        }
        // add everything to broadcast list
        broadCastList.add(toRegister);
        // only broadcast
        if (id == null) {
            return true;
        }
        if (serviceMap.get((id.intValue() >> 8) & 0xFF) == null) {
            serviceMap.put((id.intValue() >> 8) & 0xFF, new HashMap<Integer, IMessageReciver>());
        }
        serviceMap.get((id.intValue() >> 8) & 0xFF).put(id.intValue() & 0xFF, toRegister);
        return true;
    }

    @Override
    public boolean unregister(IMessageReciver toDeRegister, Integer id) {
        boolean ok = false;
        ok = broadCastList.remove(toDeRegister);
        if (serviceMap.get((id.intValue() >> 8) & 0xFF) != null) {
            serviceMap.get((id.intValue() >> 8) & 0xFF).remove(id.intValue() & 0xFF);
        }
        return ok;
    }
}
