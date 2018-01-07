package engine.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import engine.render.ObjectRendering;

// modified version of the object loader from:
// https://github.com/demoscenepassivist/SocialCoding/blob/master/code_demos_jogamp/src/framework/util/WavefrontObjectLoader.java

public class ObjectLoader {

    private String modelPath; // the path to the model file
    private ArrayList<float[]> vData = new ArrayList<float[]>(); // list of
                                                                 // vertex
                                                                 // coordinates
    private ArrayList<float[]> vtData = new ArrayList<float[]>(); // list of
                                                                  // texture
                                                                  // coordinates
    private ArrayList<float[]> vnData = new ArrayList<float[]>(); // list of
                                                                  // normal
                                                                  // coordinates
    private ArrayList<int[]> fv = new ArrayList<int[]>(); // face vertex indices
    private ArrayList<int[]> ft = new ArrayList<int[]>(); // face texture
                                                          // indices
    private ArrayList<int[]> fn = new ArrayList<int[]>(); // face normal indices
    // private FloatBuffer modeldata; // buffer which will contain vertice data
    private int faceFormat; // format of the faces triangles or quads
    private int faceMultiplier; // number of possible coordinates per face
    private int polyCount = 0; // the model polygon count
    private boolean init = true;

    public ObjectLoader(String modelPath) {
        this.modelPath = modelPath;
        loadOBJModel(this.modelPath);
        setFaceRenderType();
    }

    private void loadOBJModel(String modelPath) {
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(ObjectRendering.class.getClassLoader().getResourceAsStream(modelPath)));
            String line = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#")) { // read any descriptor data in the
                } else if (line.equals("")) {
                    // Ignore whitespace data
                } else if (line.startsWith("v ")) { // read in vertex data
                    this.vData.add(getFloatValues(line));
                } else if (line.startsWith("vt ")) { // read texture coordinates
                    this.vtData.add(getFloatValues(line));
                } else if (line.startsWith("vn ")) { // read normal coordinates
                    this.vnData.add(getFloatValues(line));
                } else if (line.startsWith("f ")) { // read face data
                    processfData(line);
                } else {
                    // TODO .mtl file handling
                }
            }
            br.close();
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    private float[] getFloatValues(String read) {
        final String s[] = read.split("\\s+");
        float data[] = new float[s.length - 1];
        for (int i = 0; i < (s.length - 1); ++i) {
            data[i] = Float.parseFloat(s[i + 1]);
        }
        return data;
    }

    private void processfData(String fread) {
        this.polyCount++;
        String s[] = fread.split("\\s+");
        if (fread.contains("//")) { // pattern is present if obj has only v and
                                    // vn in face data
            for (int loop = 1; loop < s.length; loop++) {
                s[loop] = s[loop].replaceAll("//", "/0/"); // insert a zero for
                                                           // missing vt data
            }
        }
        processfIntData(s); // pass in face data
    }

    private void processfIntData(String sdata[]) {
        int vdata[] = new int[sdata.length - 1];
        int vtdata[] = new int[sdata.length - 1];
        int vndata[] = new int[sdata.length - 1];
        for (int i = 0; i < (sdata.length - 1); i++) {
            String s = sdata[i + 1];
            String[] temp = s.split("/");
            vdata[i] = Integer.valueOf(temp[0]);
            vtdata[i] = temp.length > 1 ? Integer.valueOf(temp[1]) : 0;
            vndata[i] = temp.length > 2 ? Integer.valueOf(temp[2]) : 0;
        }
        this.fv.add(vdata);
        this.ft.add(vtdata);
        this.fn.add(vndata);
    }

    private void setFaceRenderType() {
        final int length = this.fv.get(0).length;
        if (length == 3) {
            this.faceFormat = GL2.GL_TRIANGLES;
            this.faceMultiplier = length;
        } else if (length == 4) {
            this.faceFormat = GL2.GL_QUADS;
            this.faceMultiplier = length;
        } else {
            this.faceFormat = GL2.GL_POLYGON;
        }
    }

    private void constructInterleavedArray(GL2 inGL) {
        final int tv[] = this.fv.get(0);
        final int tt[] = this.ft.get(0);
        final int tn[] = this.fn.get(0);
        // if a value of zero is found that it tells us we don't have that type
        // of data
        if ((tv[0] != 0) && (tt[0] != 0) && (tn[0] != 0)) {
            inGL.glInterleavedArrays(GL2.GL_T2F_N3F_V3F, 0, constructTNV());
        } else if ((tv[0] != 0) && (tt[0] != 0) && (tn[0] == 0)) {
            inGL.glInterleavedArrays(GL2.GL_T2F_V3F, 0, constructTV());
        } else if ((tv[0] != 0) && (tt[0] == 0) && (tn[0] != 0)) {
            inGL.glInterleavedArrays(GL2.GL_N3F_V3F, 0, constructNV());
        } else if ((tv[0] != 0) && (tt[0] == 0) && (tn[0] == 0)) {
            inGL.glInterleavedArrays(GL2.GL_V3F, 0, constructV());
        }
    }

    private FloatBuffer constructTNV() {
        int[] v, t, n;
        float tcoords[] = new float[2]; // only T2F is supported in
                                        // interLeavedArrays!!
        float coords[] = new float[3];
        int fbSize = this.polyCount * (this.faceMultiplier * 8); // 3v per poly,
                                                                 // 2vt per
                                                                 // poly, 3vn
                                                                 // per poly
        FloatBuffer modeldata = GLBuffers.newDirectFloatBuffer(fbSize);
        modeldata.position(0);
        for (int oloop = 0; oloop < this.fv.size(); oloop++) {
            v = (this.fv.get(oloop));
            t = (this.ft.get(oloop));
            n = (this.fn.get(oloop));
            for (int iloop = 0; iloop < v.length; iloop++) {
                // fill in the texture coordinate data
                for (int tloop = 0; tloop < tcoords.length; tloop++) {
                    // only T2F is supported in interleavedarrays!!
                    tcoords[tloop] = this.vtData.get(t[iloop] - 1)[tloop];
                }
                modeldata.put(tcoords);
                // fill in the normal coordinate data
                for (int vnloop = 0; vnloop < coords.length; vnloop++) {
                    coords[vnloop] = this.vnData.get(n[iloop] - 1)[vnloop];
                }
                modeldata.put(coords);
                // fill in the vertex coordinate data
                for (int vloop = 0; vloop < coords.length; vloop++) {
                    coords[vloop] = this.vData.get(v[iloop] - 1)[vloop];
                }
                modeldata.put(coords);
            }
        }
        modeldata.position(0);
        return modeldata;
    }

    private FloatBuffer constructTV() {
        int[] v, t;
        float tcoords[] = new float[2]; // only T2F is supported in
                                        // interLeavedArrays!!
        float coords[] = new float[3];
        int fbSize = this.polyCount * (this.faceMultiplier * 5); // 3v per poly,
                                                                 // 2vt per poly
        FloatBuffer modeldata = GLBuffers.newDirectFloatBuffer(fbSize);
        modeldata.position(0);
        for (int oloop = 0; oloop < this.fv.size(); oloop++) {
            v = (this.fv.get(oloop));
            t = (this.ft.get(oloop));
            for (int iloop = 0; iloop < v.length; iloop++) {
                // fill in the texture coordinate data
                for (int tloop = 0; tloop < tcoords.length; tloop++) {
                    // only T2F is supported in interleavedarrays!!
                    tcoords[tloop] = this.vtData.get(t[iloop] - 1)[tloop];
                }
                modeldata.put(tcoords);
                // fill in the vertex coordinate data
                for (int vloop = 0; vloop < coords.length; vloop++) {
                    coords[vloop] = this.vData.get(v[iloop] - 1)[vloop];
                }
                modeldata.put(coords);
            }
        }
        modeldata.position(0);
        return modeldata;
    }

    private FloatBuffer constructNV() {
        int[] v, n;
        float coords[] = new float[3];
        int fbSize = this.polyCount * (this.faceMultiplier * 6); // 3v per poly,
                                                                 // 3vn per poly
        FloatBuffer modeldata = GLBuffers.newDirectFloatBuffer(fbSize);
        modeldata.position(0);
        for (int oloop = 0; oloop < this.fv.size(); oloop++) {
            v = (this.fv.get(oloop));
            n = (this.fn.get(oloop));
            for (int iloop = 0; iloop < v.length; iloop++) {
                // fill in the normal coordinate data
                for (int vnloop = 0; vnloop < coords.length; vnloop++) {
                    coords[vnloop] = this.vnData.get(n[iloop] - 1)[vnloop];
                }
                modeldata.put(coords);
                // fill in the vertex coordinate data
                for (int vloop = 0; vloop < coords.length; vloop++) {
                    coords[vloop] = this.vData.get(v[iloop] - 1)[vloop];
                }
                modeldata.put(coords);
            }
        }
        modeldata.position(0);
        return modeldata;
    }

    private FloatBuffer constructV() {
        int[] v;
        float coords[] = new float[3];
        int fbSize = this.polyCount * (this.faceMultiplier * 3); // 3v per poly
        FloatBuffer modeldata = GLBuffers.newDirectFloatBuffer(fbSize);
        modeldata.position(0);
        for (int oloop = 0; oloop < this.fv.size(); oloop++) {
            v = (this.fv.get(oloop));
            for (int element : v) {
                // fill in the vertex coordinate data
                for (int vloop = 0; vloop < coords.length; vloop++) {
                    coords[vloop] = this.vData.get(element - 1)[vloop];
                }
                modeldata.put(coords);
            }
        }
        modeldata.position(0);
        return modeldata;
    }

    public void drawModel(GL2 inGL) {
        if (this.init) {
            constructInterleavedArray(inGL);
            cleanup();
            this.init = false;
        }
        inGL.glDrawArrays(this.faceFormat, 0, this.polyCount * this.faceMultiplier);
    }

    private void cleanup() {
        this.vData.clear();
        this.vtData.clear();
        this.vnData.clear();
        this.fv.clear();
        this.ft.clear();
        this.fn.clear();
    }

}