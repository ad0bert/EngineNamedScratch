package engine.ecs.components;

import engine.ecs.Entity;

public interface IComponent {
    public void setActive(boolean active);

    public boolean isActive();

    public void setEntity(Entity entity);
}
