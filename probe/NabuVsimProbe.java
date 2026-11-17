import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class NabuVsimProbe {
    public static void main(String[] args) {
        final String descriptor = "vendor.xiaomi.hardware.vsimapp@1.0::IVSIMService";
        try {
            Class<?> cls = Class.forName("android.os.HwBinder");
            Method lookup = cls.getMethod("getService", String.class, String.class, boolean.class);
            Object binder = lookup.invoke(null, descriptor, "default", false);
            if (binder == null) {
                System.err.println("LOOKUP_NULL");
                System.exit(2);
            }
            System.out.println("LOOKUP_OK: " + binder.getClass().getName());
        } catch (InvocationTargetException e) {
            System.err.println("LOOKUP_FAILED: " + e.getCause());
            System.exit(3);
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            System.err.println("PLATFORM_API_UNAVAILABLE: " + e);
            System.exit(4);
        }
    }
}
