package be.zvz.sony.launcherexceptionfixer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class MainModule extends XposedModule {

    private static final String TARGET_PACKAGE = "com.sonymobile.launcher";

    private static final String CLASS_PAGE_INDICATOR = "com.android.launcher3.pageindicators.PageIndicatorDots";

    private static final String CLASS_ALL_APPS = "com.android.launcher3.allapps.ActivityAllAppsContainerView";

    private static XposedModule module;

    private static final ThreadLocal<Context> currentContext = new ThreadLocal<>();
    private static int resIdStart = 0;
    private static int resIdEnd = 0;

    private static final WeakHashMap<View, ViewState> appsContainerStateMap = new WeakHashMap<>();

    private static final class ViewState {
        final int visibility;
        final boolean enabled;
        final boolean clickable;
        final boolean focusable;
        final int importantForA11y;

        ViewState(View v) {
            this.visibility = v.getVisibility();
            this.enabled = v.isEnabled();
            this.clickable = v.isClickable();
            this.focusable = v.isFocusable();
            this.importantForA11y = v.getImportantForAccessibility();
        }

        void restore(View v) {
            v.setVisibility(visibility);
            v.setEnabled(enabled);
            v.setClickable(clickable);
            v.setFocusable(focusable);
            v.setImportantForAccessibility(importantForA11y);
        }
    }

    public MainModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
        this.log("Init module");
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);

        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader cl = param.getClassLoader();

            Class<?> indicatorClass = cl.loadClass(CLASS_PAGE_INDICATOR);
            Constructor<?> targetConstructor = indicatorClass.getDeclaredConstructor(Context.class, android.util.AttributeSet.class, int.class);
            targetConstructor.setAccessible(true);
            hook(targetConstructor, ConstructorHooker.class);

            Method getDrawableMethod = Resources.class.getDeclaredMethod("getDrawable", int.class);
            getDrawableMethod.setAccessible(true);
            hook(getDrawableMethod, ResourcesHooker.class);

            Class<?> allAppsClass = cl.loadClass(CLASS_ALL_APPS);

            Method setSearchResultsMethod = findMethod(allAppsClass, "setSearchResults", ArrayList.class);
            hook(setSearchResultsMethod, SetSearchResultsHooker.class);

            Method onClearSearchResultMethod = findMethod(allAppsClass, "onClearSearchResult");
            hook(onClearSearchResultMethod, ClearSearchHooker.class);

            Method animateToSearchStateMethod = findMethod(allAppsClass, "animateToSearchState", boolean.class, long.class);
            hook(animateToSearchStateMethod, AnimateToSearchStateHooker.class);

            this.log("Hooks registered successfully.");

        } catch (Throwable t) {
            this.log("Failed to register hooks", t);
        }
    }

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

    @XposedHooker
    private static class SetSearchResultsHooker implements Hooker {
        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) {
            Object listObj = callback.getArgs()[0];
            boolean enteringSearch = listObj != null;
            applySearchTouchFix(callback.getThisObject(), enteringSearch);
        }
    }

    @XposedHooker
    private static class ClearSearchHooker implements Hooker {
        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) {
            applySearchTouchFix(callback.getThisObject(), false);
        }
    }

    @XposedHooker
    private static class AnimateToSearchStateHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            boolean enteringSearch = (boolean) callback.getArgs()[0];
            applySearchTouchFix(callback.getThisObject(), enteringSearch);
        }
    }

    private static void applySearchTouchFix(Object allAppsViewObj, boolean searchMode) {
        if (allAppsViewObj == null) return;

        try {
            Method getSearchRecyclerView = findMethod(allAppsViewObj.getClass(), "getSearchRecyclerView");
            Method getAppsRecyclerViewContainer = findMethod(allAppsViewObj.getClass(), "getAppsRecyclerViewContainer");

            View searchRecyclerView = (View) getSearchRecyclerView.invoke(allAppsViewObj);
            View appsContainer = (View) getAppsRecyclerViewContainer.invoke(allAppsViewObj);

            if (searchRecyclerView == null || appsContainer == null) return;

            if (searchMode) {
                if (!appsContainerStateMap.containsKey(appsContainer)) {
                    appsContainerStateMap.put(appsContainer, new ViewState(appsContainer));
                }

                searchRecyclerView.bringToFront();
                searchRecyclerView.setTranslationZ(20f);
                appsContainer.setTranslationZ(0f);

                appsContainer.setEnabled(false);
                appsContainer.setClickable(false);
                appsContainer.setFocusable(false);
                appsContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                appsContainer.setVisibility(View.INVISIBLE);
            } else {
                ViewState state = appsContainerStateMap.remove(appsContainer);
                if (state != null) {
                    state.restore(appsContainer);
                } else {
                    appsContainer.setVisibility(View.VISIBLE);
                    appsContainer.setEnabled(true);
                    appsContainer.setClickable(true);
                    appsContainer.setFocusable(true);
                    appsContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                }

                searchRecyclerView.setTranslationZ(0f);
                appsContainer.setTranslationZ(0f);
            }

            ViewParent parent = searchRecyclerView.getParent();
            if (parent instanceof ViewGroup vg) {
                vg.requestLayout();
                vg.invalidate();
            }
        } catch (Throwable t) {
            module.log("applySearchTouchFix failed", t);
        }
    }

    private static Method findMethod(Class<?> startClass, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> c = startClass;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }
}
