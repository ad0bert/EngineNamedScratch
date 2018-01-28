package engine.messageing;

import java.util.HashMap;
import java.util.Map;

public class MessageQueueNames {
    public static Map<String, Integer> QUEUES = new HashMap<String, Integer>();

    {
        QUEUES.put("KEYBOARD", 0);
        QUEUES.put("MOUSE", 1);
    }

}
