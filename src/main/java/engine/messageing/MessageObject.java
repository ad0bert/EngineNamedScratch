package engine.messageing;

public class MessageObject {
    private Object sender;
    private Integer reciverId;
    private Integer queueId;
    private Object transportedObject;

    public MessageObject(Object sender, Integer reciverId, Integer queueId, Object transportedObject) {
        this.sender = sender;
        this.reciverId = reciverId;
        this.queueId = queueId;
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

    public Integer getQueueId() {
        return this.queueId;
    }

    public void setQueueId(Integer queueId) {
        this.queueId = queueId;
    }

    public Object getTransportedObject() {
        return this.transportedObject;
    }

    public void setTransportedObject(Object transportedObject) {
        this.transportedObject = transportedObject;
    }
}
