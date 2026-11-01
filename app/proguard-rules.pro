# LibVLC 3.7.7 does not package consumer rules. Its two native libraries resolve
# Java names at runtime; native declarations alone do not describe the callbacks.
# Keep this JNI surface, not the whole app or the entire library package.
-keepclasseswithmembers,includedescriptorclasses class org.videolan.libvlc.** {
    native <methods>;
}

# libvlcjni.so: JNI_OnLoad caches these fields and callback method descriptors.
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.VLCObject {
    long mInstance;
    void dispatchEventFromNative(int, long, long, float, java.lang.String);
}
-keepclassmembers class org.videolan.libvlc.MediaPlayer$Equalizer {
    long mInstance;
}
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.Media {
    private static *** create*FromNative(...);
}
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.MediaPlayer {
    private static *** create*FromNative(...);
}
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.MediaDiscoverer {
    private static *** createDescriptionFromNative(...);
}
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.RendererDiscoverer {
    private static *** createDescriptionFromNative(...);
    private static *** createItemFromNative(...);
}
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.Dialog {
    private static *** *FromNative(...);
}

# libvlc.so: AWindow is passed as an object; the video-output modules look up
# these methods and register nativeOnMouseEvent/nativeOnWindowSize by name.
-keepclassmembers,includedescriptorclasses class org.videolan.libvlc.AWindow {
    android.view.Surface getVideoSurface();
    android.view.Surface getSubtitlesSurface();
    int registerNative(long);
    void unregisterNative();
    boolean setBuffersGeometry(android.view.Surface, int, int, int);
    void setVideoLayout(int, int, int, int, int, int);
    boolean SurfaceTexture_attachToGLContext(int);
    void SurfaceTexture_detachFromGLContext();
    boolean SurfaceTexture_waitAndUpdateTexImage(float[]);
    android.view.Surface SurfaceTexture_getSurface();
}

# Activity saved state uses Java serialization for a pending file operation.
# Keep its stable class/field schema compatible with the unminified releases.
-keep class io.mediamaster.app.media.VideoItem {
    <fields>;
}

# Media3/AndroidX ship their reflection and Parcelable consumer rules in their
# AARs. Do not suppress missing classes globally or pin all player code for tests.
# Keep line positions useful with the separately archived R8 mapping file.
-keepattributes SourceFile,LineNumberTable
