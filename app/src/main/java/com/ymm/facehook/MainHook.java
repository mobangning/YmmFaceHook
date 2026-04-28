package com.ymm.facehook;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[YmmFace]";
    private static final String TARGET_PKG = "com.xiwei.logistics";
    private static final String VIDEO_PATH = "/sdcard/DCIM/face.mp4";
    private static final String VIDEO_PATH_ALT = "/data/local/tmp/face.mp4";

    private static final Map<Integer, Surface> irSurfaceMap = new ConcurrentHashMap<>();
    private static final Map<Integer, int[]> irSizeMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Surface> dummyMap = new ConcurrentHashMap<>();
    private static final AtomicBoolean decoderRunning = new AtomicBoolean(false);
    private static volatile Thread decoderThread = null;
    private static final Set<String> hookedClasses = ConcurrentHashMap.newKeySet();
    private static final List<SurfaceTexture> dummyRefs = new ArrayList<>();
    private static volatile int colorPhase = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        log("✓ 模块已加载 进程=" + lpparam.processName);
        String vp = findVideo();
        if (vp != null) {
            log("✓ 视频就绪: " + vp);
        } else {
            log("⚠ 视频未找到, 请放置到 " + VIDEO_PATH);
        }
        hookImageReaderNew(lpparam);
        hookImageReaderClose(lpparam);
        hookCreateSessionLegacy(lpparam);
        hookCreateSessionNew(lpparam);
        hookSetRepeating(lpparam);
        hookColorFlash(lpparam);
        hookWindowBrightness(lpparam);
        hookClassLoading(lpparam);
        hookNativeLib(lpparam);
        hookSysProps(lpparam);
        hookFiles(lpparam);
        hookExec(lpparam);
    }

    private void hookImageReaderNew(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "newInstance",
                    int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                ImageReader r = (ImageReader) param.getResult();
                                if (r == null) return;
                                int w = (int) param.args[0];
                                int h = (int) param.args[1];
                                int fmt = (int) param.args[2];
                                Surface s = r.getSurface();
                                int k = System.identityHashCode(s);
                                irSurfaceMap.put(k, s);
                                irSizeMap.put(k, new int[]{w, h});
                                log("ImageReader 捕获: " + w + "x" + h + " fmt=0x" + Integer.toHexString(fmt) + " k=" + k);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " ImageReader追踪异常: " + t.getMessage());
                            }
                        }
                    });
            log("hookImageReaderNew ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookImageReaderNew 失败: " + t.getMessage());
        }
    }

    private void hookImageReaderClose(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "close",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                ImageReader r = (ImageReader) param.thisObject;
                                Surface s = r.getSurface();
                                int k = System.identityHashCode(s);
                                if (irSurfaceMap.remove(k) != null) {
                                    irSizeMap.remove(k);
                                    dummyMap.remove(k);
                                    stopDecoder();
                                    log("ImageReader 关闭: k=" + k);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " ImageReaderClose异常: " + t.getMessage());
                            }
                        }
                    });
            log("hookImageReaderClose ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookImageReaderClose 失败: " + t.getMessage());
        }
    }

    private void hookCreateSessionLegacy(XC_LoadPackage.LoadPackageParam lpparam) {
        for (String cls : new String[]{"android.hardware.camera2.impl.CameraDeviceImpl", "android.hardware.camera2.CameraDevice"}) {
            try {
                XposedHelpers.findAndHookMethod(XposedHelpers.findClass(cls, lpparam.classLoader),
                        "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                replaceInList(param);
                            }
                        });
                log("hookSessionLegacy[" + cls + "] ✓");
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookSessionLegacy[" + cls + "] 跳过: " + t.getMessage());
            }
        }
    }

    private void hookCreateSessionNew(XC_LoadPackage.LoadPackageParam lpparam) {
        for (String cls : new String[]{"android.hardware.camera2.impl.CameraDeviceImpl", "android.hardware.camera2.CameraDevice"}) {
            try {
                XposedHelpers.findAndHookMethod(XposedHelpers.findClass(cls, lpparam.classLoader),
                        "createCaptureSession", SessionConfiguration.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                replaceInConfig(param);
                            }
                        });
                log("hookSessionNew[" + cls + "] ✓");
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookSessionNew[" + cls + "] 跳过: " + t.getMessage());
            }
        }
    }

    private void hookSetRepeating(XC_LoadPackage.LoadPackageParam lpparam) {
        for (String cls : new String[]{"android.hardware.camera2.impl.CameraCaptureSessionImpl", "android.hardware.camera2.CameraCaptureSession"}) {
            try {
                Class<?> c = XposedHelpers.findClass(cls, lpparam.classLoader);
                try {
                    XposedHelpers.findAndHookMethod(c, "setRepeatingRequest",
                            CaptureRequest.class, CameraCaptureSession.CaptureCallback.class, Handler.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    patchRequest((CaptureRequest) param.args[0]);
                                }
                            });
                    log("hookSetRepeating(Handler)[" + cls + "] ✓");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hookSetRepeating(Handler)[" + cls + "] 跳过: " + t.getMessage());
                }
                try {
                    XposedHelpers.findAndHookMethod(c, "setSingleRepeatingRequest",
                            CaptureRequest.class, Executor.class, CameraCaptureSession.CaptureCallback.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    patchRequest((CaptureRequest) param.args[0]);
                                }
                            });
                    log("hookSetRepeating(Executor)[" + cls + "] ✓");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hookSetRepeating(Executor)[" + cls + "] 跳过: " + t.getMessage());
                }
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookSetRepeating[" + cls + "] 跳过: " + t.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void replaceInList(XC_MethodHook.MethodHookParam param) {
        String vp = findVideo();
        if (vp == null) return;
        try {
            List<Surface> out = (List<Surface>) param.args[0];
            if (out == null || out.isEmpty()) return;
            List<Surface> mod = new ArrayList<>(out);
            boolean ok = false;
            for (int i = 0; i < mod.size(); i++) {
                Surface s = mod.get(i);
                int k = System.identityHashCode(s);
                if (irSurfaceMap.containsKey(k)) {
                    int[] sz = irSizeMap.getOrDefault(k, new int[]{640, 480});
                    Surface dummy = makeDummy(sz[0], sz[1]);
                    dummyMap.put(k, dummy);
                    mod.set(i, dummy);
                    ok = true;
                    log("★ Session替换: k=" + k + " " + sz[0] + "x" + sz[1] + "→dummy");
                    startDecoder(s, vp);
                }
            }
            if (ok) param.args[0] = mod;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " replaceInList异常: " + t.getMessage());
        }
    }

    private void replaceInConfig(XC_MethodHook.MethodHookParam param) {
        String vp = findVideo();
        if (vp == null) return;
        try {
            SessionConfiguration cfg = (SessionConfiguration) param.args[0];
            List<OutputConfiguration> ocl = cfg.getOutputConfigurations();
            if (ocl == null || ocl.isEmpty()) return;
            List<OutputConfiguration> nl = new ArrayList<>();
            boolean ok = false;
            Surface target = null;
            for (OutputConfiguration oc : ocl) {
                Surface s = oc.getSurface();
                int k = System.identityHashCode(s);
                if (irSurfaceMap.containsKey(k)) {
                    int[] sz = irSizeMap.getOrDefault(k, new int[]{640, 480});
                    Surface dummy = makeDummy(sz[0], sz[1]);
                    dummyMap.put(k, dummy);
                    nl.add(new OutputConfiguration(dummy));
                    target = s;
                    ok = true;
                    log("★ Config替换: k=" + k + "→dummy");
                } else {
                    nl.add(oc);
                }
            }
            if (ok && target != null) {
                param.args[0] = new SessionConfiguration(cfg.getSessionType(), nl, cfg.getExecutor(), cfg.getStateCallback());
                startDecoder(target, vp);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " replaceInConfig异常: " + t.getMessage());
        }
    }

    private void patchRequest(CaptureRequest req) {
        if (dummyMap.isEmpty()) return;
        try {
            Field f = findSurfaceSetField();
            if (f == null) return;
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            HashSet<Surface> set = (HashSet<Surface>) f.get(req);
            if (set == null || set.isEmpty()) return;
            HashSet<Surface> p = new HashSet<>();
            boolean changed = false;
            for (Surface s : set) {
                int k = System.identityHashCode(s);
                Surface d = dummyMap.get(k);
                if (d != null) {
                    p.add(d);
                    changed = true;
                } else {
                    p.add(s);
                }
            }
            if (changed) {
                set.clear();
                set.addAll(p);
                log("CaptureRequest Surface 已替换");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " patchRequest异常: " + t.getMessage());
        }
    }

    private static volatile Field surfaceSetField = null;

    private Field findSurfaceSetField() {
        if (surfaceSetField != null) return surfaceSetField;
        try {
            surfaceSetField = CaptureRequest.class.getDeclaredField("mSurfaceSet");
            return surfaceSetField;
        } catch (NoSuchFieldException e) {
            try {
                for (Field ff : CaptureRequest.class.getDeclaredFields()) {
                    if (Set.class.isAssignableFrom(ff.getType())) {
                        surfaceSetField = ff;
                        return ff;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " findSurfaceSetField异常: " + t.getMessage());
            }
        }
        return null;
    }

    private Surface makeDummy(int w, int h) {
        SurfaceTexture st = new SurfaceTexture(0);
        st.setDefaultBufferSize(w, h);
        try {
            st.setOnFrameAvailableListener(tex -> {
                try { tex.updateTexImage(); } catch (Throwable ignored) {}
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable ignored) {}
        synchronized (dummyRefs) { dummyRefs.add(st); }
        return new Surface(st);
    }

    private synchronized void startDecoder(Surface target, String path) {
        if (decoderRunning.getAndSet(true)) return;
        decoderThread = new Thread(() -> {
            log("▶ 解码启动: " + path);
            MediaExtractor ext = null;
            MediaCodec codec = null;
            try {
                ext = new MediaExtractor();
                ext.setDataSource(path);
                int ti = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ext.getTrackCount(); i++) {
                    MediaFormat f = ext.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { ti = i; fmt = f; break; }
                }
                if (ti < 0 || fmt == null) { log("✗ 无视频轨"); return; }
                ext.selectTrack(ti);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                long frameUs = 33333L;
                if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    int fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE);
                    if (fps > 0) frameUs = 1000000L / fps;
                }
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, target, null, 0);
                codec.start();
                log("▶ 就绪: " + mime + " " + fmt.getInteger(MediaFormat.KEY_WIDTH) + "x" + fmt.getInteger(MediaFormat.KEY_HEIGHT));
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean eos = false;
                int fc = 0;
                while (decoderRunning.get() && target.isValid()) {
                    if (!eos) {
                        int idx = codec.dequeueInputBuffer(10000);
                        if (idx >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(idx);
                            if (buf != null) {
                                int sz = ext.readSampleData(buf, 0);
                                if (sz < 0) {
                                    codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    eos = true;
                                } else {
                                    codec.queueInputBuffer(idx, 0, sz, ext.getSampleTime(), 0);
                                    ext.advance();
                                }
                            }
                        }
                    }
                    int oi = codec.dequeueOutputBuffer(info, 10000);
                    if (oi >= 0) {
                        codec.releaseOutputBuffer(oi, true);
                        fc++;
                        if (fc % 90 == 1) log("▶ 帧=" + fc + " 色阶=" + colorPhase);
                        Thread.sleep(Math.max(frameUs / 1000, 16));
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            codec.flush();
                            ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            eos = false;
                            log("↻ 循环 帧=" + fc);
                        }
                    }
                }
                log("■ 解码结束 帧=" + fc);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 解码异常: " + t.getMessage());
            } finally {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
                try { if (ext != null) ext.release(); } catch (Throwable ignored) {}
                decoderRunning.set(false);
            }
        }, "YmmFace-Dec");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }

    private synchronized void stopDecoder() {
        decoderRunning.set(false);
        Thread t = decoderThread;
        if (t != null) { t.interrupt(); decoderThread = null; }
    }

    private void hookColorFlash(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int c = (int) param.args[0];
                                int a = Color.alpha(c);
                                int r = Color.red(c);
                                int g = Color.green(c);
                                int b = Color.blue(c);
                                if (a < 200) return;
                                if (r > 200 && g < 60 && b < 60) {
                                    colorPhase = 1;
                                    log("三色: ■ 红色阶段 #" + Integer.toHexString(c));
                                } else if (r < 60 && g > 200 && b < 60) {
                                    colorPhase = 2;
                                    log("三色: ■ 绿色阶段 #" + Integer.toHexString(c));
                                } else if (r < 60 && g < 60 && b > 200) {
                                    colorPhase = 3;
                                    log("三色: ■ 蓝色阶段 #" + Integer.toHexString(c));
                                } else if (r > 200 && g > 200 && b > 200) {
                                    colorPhase = 4;
                                    log("三色: □ 白色阶段 #" + Integer.toHexString(c));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hookColorFlash ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookColorFlash 失败: " + t.getMessage());
        }
    }

    private void hookWindowBrightness(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.view.Window", lpparam.classLoader,
                    "setAttributes", WindowManager.LayoutParams.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) param.args[0];
                                if (lp.screenBrightness >= 0.95f) {
                                    log("三色: 亮度=" + lp.screenBrightness);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hookWindowBrightness ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookWindowBrightness 失败: " + t.getMessage());
        }
    }

    private void hookClassLoading(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Class<?> r = (Class<?>) param.getResult();
                                if (r == null) return;
                                String n = (String) param.args[0];
                                if (n == null || isSysPkg(n)) return;
                                if (isTargetClass(n) && hookedClasses.add(n)) {
                                    hookLivenessClass(r);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hookClassLoading ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookClassLoading 失败: " + t.getMessage());
        }
    }

    private void hookNativeLib(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(System.class, "loadLibrary", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String lib = (String) param.args[0];
                                if (lib == null) return;
                                String lo = lib.toLowerCase(Locale.ROOT);
                                if (lo.contains("safex") || lo.contains("liveness")
                                        || lo.contains("alive") || lo.contains("antispoof")
                                        || lo.contains("facedetect") || lo.contains("faceverif")) {
                                    log("★★ 安全SO加载: lib" + lib + ".so");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hookNativeLib ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookNativeLib 失败: " + t.getMessage());
        }
    }

    private void hookLivenessClass(Class<?> clazz) {
        log("━━━ 活体类: " + clazz.getName() + " ━━━");
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                try {
                    String mn = m.getName();
                    String lo = mn.toLowerCase(Locale.ROOT);
                    Class<?> rt = m.getReturnType();
                    boolean nat = Modifier.isNative(m.getModifiers());
                    log("  " + (nat ? "[N]" : "   ") + rt.getSimpleName() + " " + mn + "(" + descP(m) + ")");
                    if (nat) {
                        force(m, rt, "N");
                    } else if (isTargetMethod(lo)) {
                        force(m, rt, "M");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "  方法异常: " + m.getName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookLivenessClass异常: " + t.getMessage());
        }
    }

    private void force(Method m, Class<?> rt, String tag) {
        try {
            String lo = m.getName().toLowerCase(Locale.ROOT);
            boolean invert = lo.contains("spoof") || lo.contains("fake") || lo.contains("attack")
                    || lo.contains("fraud") || lo.contains("hack") || lo.contains("replay")
                    || lo.contains("screen") || lo.contains("photo") || lo.contains("mask")
                    || lo.contains("recapture") || lo.contains("reprint");
            if (rt == boolean.class) {
                boolean val = !invert;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object o = p.getResult();
                        p.setResult(val);
                        log("  ★[" + tag + "] " + m.getName() + ": " + o + "→" + val);
                    }
                });
            } else if (rt == int.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object o = p.getResult();
                        p.setResult(0);
                        log("  ★[" + tag + "] " + m.getName() + ": " + o + "→0");
                    }
                });
            } else if (rt == float.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object o = p.getResult();
                        p.setResult(0.99f);
                        log("  ★[" + tag + "] " + m.getName() + ": " + o + "→0.99");
                    }
                });
            } else if (rt == double.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        p.setResult(0.99);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "  force失败: " + m.getName() + ": " + t.getMessage());
        }
    }

    private void hookSysProps(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        String k = (String) param.args[0];
                        if (k == null) return;
                        String v = spoofProp(k);
                        if (v != null) param.setResult(v);
                    } catch (Throwable ignored) {}
                }
            };
            XposedHelpers.findAndHookMethod(sp, "get", String.class, h);
            XposedHelpers.findAndHookMethod(sp, "get", String.class, String.class, h);
            log("hookSysProps ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookSysProps 失败: " + t.getMessage());
        }
    }

    private void hookFiles(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        String p = ((File) param.thisObject).getAbsolutePath();
                        if (isRootPath(p)) param.setResult(false);
                    } catch (Throwable ignored) {}
                }
            };
            XposedHelpers.findAndHookMethod(File.class, "exists", h);
            XposedHelpers.findAndHookMethod(File.class, "canRead", h);
            XposedHelpers.findAndHookMethod(File.class, "canExecute", h);
            log("hookFiles ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFiles 失败: " + t.getMessage());
        }
    }

    private void hookExec(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String c = (String) param.args[0];
                                if (c != null && isBadCmd(c)) {
                                    log("拦截: " + c);
                                    param.setResult(null);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String[] cs = (String[]) param.args[0];
                                if (cs != null && cs.length > 0 && isBadCmd(cs[0])) {
                                    log("拦截: " + cs[0]);
                                    param.setResult(null);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hookExec ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookExec 失败: " + t.getMessage());
        }
    }

    private static String findVideo() {
        if (new File(VIDEO_PATH).exists()) return VIDEO_PATH;
        if (new File(VIDEO_PATH_ALT).exists()) return VIDEO_PATH_ALT;
        return null;
    }

    private static boolean isSysPkg(String n) {
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.")
                || n.startsWith("androidx.") || n.startsWith("com.android.") || n.startsWith("dalvik.")
                || n.startsWith("sun.") || n.startsWith("kotlin.") || n.startsWith("kotlinx.")
                || n.startsWith("org.json.") || n.startsWith("org.xmlpull.")
                || n.startsWith("com.google.android.");
    }

    private static boolean isTargetClass(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.contains("liveness") || l.contains("alive") || l.contains("safex")
                || l.contains("antispoof") || l.contains("anti_spoof") || l.contains("antifraud")
                || l.contains("faceverif") || l.contains("facecheck") || l.contains("facedetect")) {
            return true;
        }
        if (l.contains("color") && (l.contains("live") || l.contains("detect") || l.contains("reflect"))) {
            return true;
        }
        if (l.contains("rgb") && (l.contains("live") || l.contains("detect") || l.contains("check"))) {
            return true;
        }
        if (l.contains("reflect") && (l.contains("detect") || l.contains("analy") || l.contains("check"))) {
            return true;
        }
        if (l.contains("illumin") || (l.contains("flash") && l.contains("live"))) {
            return true;
        }
        if (l.contains("face") && (l.contains("live") || l.contains("verify") || l.contains("detect")
                || l.contains("auth") || l.contains("result"))) {
            return true;
        }
        return false;
    }

    private static boolean isTargetMethod(String l) {
        return l.contains("live") || l.contains("alive") || l.contains("spoof") || l.contains("fake")
                || l.contains("detect") || l.contains("verify") || l.contains("check")
                || l.contains("result") || l.contains("score") || l.contains("confid")
                || l.contains("quality") || l.contains("pass") || l.contains("attack")
                || l.contains("fraud") || l.contains("replay") || l.contains("screen")
                || l.contains("real") || l.contains("judge") || l.contains("color")
                || l.contains("rgb") || l.contains("reflect") || l.contains("phase")
                || l.contains("flash") || l.contains("illumin") || l.contains("bright")
                || l.contains("spectrum") || l.contains("infrared") || l.contains("depth")
                || l.contains("mask") || l.contains("recapture") || l.contains("reprint");
    }

    private static String spoofProp(String k) {
        if (k.contains("ro.debuggable")) return "0";
        if (k.contains("ro.build.tags")) return "release-keys";
        if (k.contains("ro.build.type")) return "user";
        if (k.contains("ro.secure")) return "1";
        if (k.contains("vbmeta.device_state")) return "locked";
        if (k.contains("warranty_bit")) return "0";
        if (k.contains("magisk") || k.contains("supersu") || k.contains("superuser")) return "";
        return null;
    }

    private static boolean isRootPath(String p) {
        return p.contains("/su") || p.contains("/magisk") || p.contains("/supersu")
                || p.contains("Superuser.apk") || p.contains("/busybox") || p.contains("daemonsu")
                || p.contains(".magisk") || p.contains("/xposed") || p.contains("/lsposed");
    }

    private static boolean isBadCmd(String c) {
        String l = c.toLowerCase(Locale.ROOT);
        return l.equals("su") || l.endsWith("/su") || l.contains("which su")
                || l.contains("magisk") || l.contains("supersu") || l.contains("busybox")
                || l.contains("xposed");
    }

    private static String descP(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(ps[i].getSimpleName());
        }
        return sb.toString();
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }
}
