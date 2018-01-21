package engine.ecs;

import java.util.ArrayList;
import java.util.List;

import engine.geometry.Point;
import engine.render.interfaces.IDrawable;

public class Entity {
    private int id;
    private Point position = new Point(0f, 0f, 0f);
    private List<IComponent> components = new ArrayList<IComponent>();

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public List<IComponent> getComponents() {
        return this.components;
    }

    public void setComponents(List<IComponent> components) {
        this.components = components;
    }

    public void addComponent(IComponent component) {
        this.components.add(component);
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
}
