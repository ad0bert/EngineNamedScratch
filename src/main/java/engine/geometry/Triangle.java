package engine.geometry;

public class Triangle {
    private final Point edge1;
    private final Point edge2;
    private final Point edge3;

    public Triangle(Point edge1, Point edge2, Point edge3) {
        this.edge1 = edge1;
        this.edge2 = edge2;
        this.edge3 = edge3;
    }

    public Point getEdge1() {
        return this.edge1;
    }

    public Point getEdge2() {
        return this.edge2;
    }

    public Point getEdge3() {
        return this.edge3;
    }

    public void scale(float scale) {
        this.edge1.scale(scale);
        this.edge2.scale(scale);
        this.edge3.scale(scale);
    }

    public void moveX(float dist) {
        this.edge1.moveX(dist);
        this.edge2.moveX(dist);
        this.edge3.moveX(dist);
    }

    public void moveY(float dist) {
        this.edge1.moveY(dist);
        this.edge2.moveY(dist);
        this.edge3.moveY(dist);
    }

    public void moveZ(float dist) {
        this.edge1.moveZ(dist);
        this.edge2.moveZ(dist);
        this.edge3.moveZ(dist);
    }
}
