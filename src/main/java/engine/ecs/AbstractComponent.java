package engine.ecs;

public abstract class AbstractComponent implements IComponent {

    private boolean isActive = false;

    @Override
    public void setActive(boolean active) {
        this.isActive = active;
    }

    @Override
    public boolean isActive() {
        return this.isActive;
    }

}
