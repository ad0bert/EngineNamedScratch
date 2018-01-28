package engine.ecs;

import java.util.ArrayList;
import java.util.List;

import engine.ecs.components.IComponent;
import engine.geometry.Point;
import engine.render.interfaces.IDrawable;
import engine.util.IdFactory;

public abstract class Entity {
    private long id = IdFactory.getNextId();
    private Point position = new Point(0f, 0f, 0f);
    private Point rotation = new Point(0f, 0f, 0f);

    private List<IComponent> components = new ArrayList<IComponent>();

    public long getId() {
        return this.id;
    }

    public List<IComponent> getComponents() {
        return this.components;
    }

    public void setComponents(List<IComponent> components) {
        this.components = components;
    }

    public void addComponent(IComponent component) {
        this.components.add(component);
        component.setEntity(this);
    }

    public void activateComponent(Class<?> clazz) {
        for (IComponent comp : this.components) {
            if (clazz.isAssignableFrom(comp.getClass())) {
                comp.setActive(true);
            }
        }
    }

    public void deactivateComponent(Class<IDrawable> clazz) {
        for (IComponent comp : this.components) {
            if (clazz.isAssignableFrom(comp.getClass())) {
                comp.setActive(false);
            }
        }
    }

    public Point getPosition() {
        return this.position;
    }

    public void setPosition(Point position) {
        this.position = position;
    }

    public Point getRotation() {
        return this.rotation;
    }

    public void setRotation(Point rotation) {
        this.rotation = rotation;
    }
}
