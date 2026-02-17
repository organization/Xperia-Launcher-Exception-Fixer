package be.zvz.sony.launcherexceptionfixer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class MainModule extends XposedModule {

    private static final String TARGET_PACKAGE = "com.sonymobile.launcher";

    private static final String CLASS_PAGE_INDICATOR = "com.android.launcher3.pageindicators.PageIndicatorDots";

    private static XposedModule module;

    public MainModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
        this.log("Init module");
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);

        if (!param.getPackageName().equals(TARGET_PACKAGE)) return;

        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> indicatorClass = cl.loadClass(CLASS_PAGE_INDICATOR);

            Constructor<?> targetConstructor = indicatorClass.getDeclaredConstructor(Context.class, android.util.AttributeSet.class, int.class);
            hook(targetConstructor, ConstructorHooker.class);

            Class<?> resourcesClass = Resources.class;
            Method getDrawableMethod = resourcesClass.getDeclaredMethod("getDrawable", int.class);
            hook(getDrawableMethod, ResourcesHooker.class);

            this.log("Hooks registered successfully.");

        } catch (Throwable t) {
            this.log("Failed to register hooks", t);
        }
    }
    private static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

    private static int resIdStart = 0;
    private static int resIdEnd = 0;

    @XposedHooker
    private static class ConstructorHooker implements Hooker {
        @BeforeInvocation
        @SuppressLint("DiscouragedApi")
        public static void before(@NonNull BeforeHookCallback callback) {
            Context context = (Context) callback.getArgs()[0];
            currentContext.set(context);

            if (resIdStart == 0 && context != null) {
                Resources res = context.getResources();
                String pkg = context.getPackageName();
                resIdStart = res.getIdentifier("ic_chevron_start", "drawable", pkg);
                resIdEnd = res.getIdentifier("ic_chevron_end", "drawable", pkg);
                module.log("Found Res IDs - Start: " + resIdStart + ", End: " + resIdEnd);
            }
        }

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) {
            currentContext.remove();
        }
    }

    @XposedHooker
    private static class ResourcesHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            Context ctx = currentContext.get();
            if (ctx == null) {
                return;
            }

            int requestedId = (int) callback.getArgs()[0];

            if (requestedId != 0 && (requestedId == resIdStart || requestedId == resIdEnd)) {
                try {
                    Drawable fixedDrawable = ctx.getDrawable(requestedId);

                    callback.returnAndSkip(fixedDrawable);
                } catch (Throwable t) {
                    module.log("Failed to fix drawable", t);
                    callback.throwAndSkip(t);
                }
            }
        }
    }
}