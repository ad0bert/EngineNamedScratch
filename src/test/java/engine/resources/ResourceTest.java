package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.ImageData;
import engine.render.MeshData;
import engine.render.MeshRenderer;
import engine.render.Overlay;
import engine.render.RenderBackend;
import engine.render.RenderStats;

class ResourceTest {

  /** Records what the resource manager asks a backend to create; proves resources work without OpenGL. */
  static class FakeBackend implements RenderBackend {
    final List<MeshData> meshes = new ArrayList<>();
    final List<ImageData> images = new ArrayList<>();
    final GpuTexture white = () -> {
    };
    int disposed;
    int nextId;

    @Override
    public void init() {
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public GpuMesh createMesh(MeshData data) {
      this.meshes.add(data);
      return new GpuMesh() {
        @Override
        public int id() {
          return ++FakeBackend.this.nextId;
        }

        @Override
        public MeshData data() {
          return data;
        }

        @Override
        public void dispose() {
          FakeBackend.this.disposed++;
        }
      };
    }

    @Override
    public GpuTexture createTexture(ImageData image) {
      this.images.add(image);
      return () -> this.disposed++;
    }

    @Override
    public GpuTexture whiteTexture() {
      return this.white;
    }

    @Override
    public void render(Scene scene, float alpha, int width, int height, List<Overlay> overlays) {
    }

    @Override
    public RenderStats stats() {
      return new RenderStats();
    }

    @Override
    public void dispose() {
    }
  }

  private static ObjLoader.ObjModel obj(String text, boolean split) throws IOException {
    return ObjLoader.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "t", split);
  }

  @Test
  void objIsSplitIntoPartsPerMaterial() throws IOException {
    final var text = """
        mtllib stuff.mtl
        v 0 0 0
        v 1 0 0
        v 0 1 0
        v 1 1 0
        usemtl red
        f 1 2 3
        usemtl blue
        f 2 4 3
        usemtl red
        f 1 3 4
        """;
    final var split = obj(text, true);
    assertEquals("stuff.mtl", split.mtllib());
    assertEquals(List.of("red", "blue"), split.parts().stream().map(ObjLoader.Part::material).toList());
    assertEquals(6, split.parts().get(0).mesh().indices().length);
    assertEquals(3, split.parts().get(1).mesh().indices().length);

    final var merged = obj(text, false);
    assertEquals(1, merged.parts().size());
    assertEquals(9, merged.parts().get(0).mesh().indices().length);
  }

  @Test
  void mtlParsingReadsTheSupportedKeys() throws IOException {
    final var mats = MtlLoader.parse(new ByteArrayInputStream("""
        # comment
        newmtl shiny metal
        Kd 0.5 0.25 0.125
        Ks 0.9 0.9 0.9
        Ns 5000
        d 0.5
        map_Kd -s 1 1 1 textures/metal.png
        newmtl plain
        Kd 1 0 0
        Ns 0
        Tr 1
        """.getBytes(StandardCharsets.UTF_8)));
    assertEquals(2, mats.size());
    final var shiny = mats.get("shiny metal");
    assertEquals(0.25f, shiny.kd()[1], 1e-6f);
    assertEquals(0.9f, shiny.specular(), 1e-6f);
    assertEquals(128f, shiny.shininess(), 0f); // clamped
    assertEquals(0.5f, shiny.alpha(), 0f);
    assertEquals("textures/metal.png", shiny.diffuseMap());
    final var plain = mats.get("plain");
    assertEquals(1f, plain.alpha(), 0f); // Tr is ignored
    assertEquals(1f, plain.shininess(), 0f);
    assertEquals(null, plain.diffuseMap());
  }

  @Test
  void bundledImagesDecode() throws IOException {
    try (var in = ResourceTest.class.getClassLoader().getResourceAsStream("textures/cubeFace.png")) {
      final var img = ImageLoader.load(in, "cubeFace.png");
      assertTrue(img.width() > 0 && img.height() > 0);
      assertEquals(img.width() * img.height() * 4, img.rgba().length);
    }
    try (var in = ResourceTest.class.getClassLoader().getResourceAsStream("objects/capsule/capsule0.jpg")) {
      assertNotNull(ImageLoader.load(in, "capsule0.jpg"));
    }
  }

  @Test
  void corruptImagesAreRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
        () -> ImageLoader.load(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), "junk"));
  }

  @Test
  void modelUsesMtlMaterialAndTexture() {
    final var backend = new FakeBackend();
    final var res = new ResourceManager(backend);
    final var capsule = res.model("objects/capsule/capsule.obj");
    assertEquals(1, capsule.parts().size());
    final var material = capsule.parts().get(0).material();
    assertNotNull(material.texture);
    assertNotSame(backend.white, material.texture);
    assertEquals(1, backend.images.size());
    assertSame(capsule, res.model("objects/capsule/capsule.obj")); // cached

    final var e = new Entity();
    capsule.attachTo(e);
    assertEquals(1, e.components().stream().filter(c -> c instanceof MeshRenderer).count());
    assertTrue(capsule.halfExtents().x > 0f);
  }

  @Test
  void missingTexturesFallBackToWhiteAndAreNotCached() {
    final var backend = new FakeBackend();
    final var res = new ResourceManager(backend);
    assertSame(backend.white, res.texture("textures/does-not-exist.png"));
  }

  @Test
  void texturesAndMeshesAreCachedAndDisposed() {
    final var backend = new FakeBackend();
    final var res = new ResourceManager(backend);
    assertSame(res.texture("textures/cubeFace.png"), res.texture("textures/cubeFace.png"));
    assertSame(res.mesh("objects/box.obj"), res.mesh("objects/box.obj"));
    assertEquals(1, backend.images.size());
    assertEquals(1, backend.meshes.size());
    res.dispose();
    assertEquals(2, backend.disposed);
  }

  @Test
  void normalMapStatementsAreRecognisedInAllCommonSpellings() throws IOException {
    final var mats = MtlLoader.parse(new ByteArrayInputStream("""
        newmtl a
        map_Bump -bm 0.5 normal a.png
        newmtl b
        bump -s 2 2 n/b.png
        newmtl c
        norm c.png
        newmtl d
        map_bump -clamp on -bm 2 d.png
        newmtl e
        Kd 1 1 1
        """.getBytes(StandardCharsets.UTF_8)));
    assertEquals("normal a.png", mats.get("a").normalMap()); // file names may contain spaces
    assertEquals(0.5f, mats.get("a").normalScale(), 0f);
    assertEquals("n/b.png", mats.get("b").normalMap());
    assertEquals(1f, mats.get("b").normalScale(), 0f);
    assertEquals("c.png", mats.get("c").normalMap());
    assertEquals("d.png", mats.get("d").normalMap());
    assertEquals(2f, mats.get("d").normalScale(), 0f);
    assertEquals(null, mats.get("e").normalMap());
  }

  @Test
  void modelsPickUpNormalMapAndScaleFromTheirMtl() {
    final var backend = new FakeBackend();
    final var res = new ResourceManager(backend);
    final var m = res.model("objects/bumpy/bumpy.obj");
    final var material = m.parts().get(0).material();
    assertNotNull(material.texture);
    assertNotNull(material.normalMap);
    assertNotSame(material.texture, material.normalMap);
    assertEquals(0.5f, material.normalScale, 0f);
    assertEquals(2, backend.images.size());
  }

  @Test
  void generatedTexturesAreCachedByKey() {
    final var backend = new FakeBackend();
    final var res = new ResourceManager(backend);
    final var img = ImageData.solid(1, 2, 3, 4);
    assertSame(res.texture("gen:x", img), res.texture("gen:x", ImageData.solid(9, 9, 9, 9)));
    assertEquals(1, backend.images.size());
    res.dispose();
    assertEquals(1, backend.disposed);
  }
}
