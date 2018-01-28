package engine.ecs.components;

import engine.ecs.Entity;

public abstract class AbstractComponent implements IComponent {

    private boolean isActive;
    private Entity entity;

    public AbstractComponent() {
        this.isActive = false;
    }

    @Override
    public void setActive(boolean active) {
        this.isActive = active;
    }

    @Override
    public boolean isActive() {
        return this.isActive;
    }

    public Entity getEntity() {
        return this.entity;
    }

    @Override
    public void setEntity(Entity entity) {
        this.entity = entity;
    }

}
