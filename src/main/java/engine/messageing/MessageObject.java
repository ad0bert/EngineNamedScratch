package engine.messageing;

public class MessageObject {
    private Object sender;
    private Integer reciverId;
    private Object transportedObject;

    public MessageObject(Object sender, Integer reciverId, Object transportedObject) {
        this.sender = sender;
        this.reciverId = reciverId;
        this.transportedObject = transportedObject;
    }

    public Object getSender() {
        return this.sender;
    }

    public void setSender(Object sender) {
        this.sender = sender;
    }

    public Integer getReciverId() {
        return this.reciverId;
    }

    public void setReciverId(Integer reciverId) {
        this.reciverId = reciverId;
    }

    public Object getTransportedObject() {
        return this.transportedObject;
    }

    public void setTransportedObject(Object transportedObject) {
        this.transportedObject = transportedObject;
    }
}
