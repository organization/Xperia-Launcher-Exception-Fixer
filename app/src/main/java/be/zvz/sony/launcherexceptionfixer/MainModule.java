package be.zvz.sony.launcherexceptionfixer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedApi"})
public class MainModule extends XposedModule {

    private static final String TAG = "XperiaLauncherFixer";

    private static final String TARGET_PACKAGE = "com.sonymobile.launcher";

    private static final String CLASS_PAGE_INDICATOR = "com.android.launcher3.pageindicators.PageIndicatorDots";

    private static final String CLASS_ALL_APPS = "com.android.launcher3.allapps.ActivityAllAppsContainerView";

    private static MainModule module;

    private static final ThreadLocal<Context> currentContext = new ThreadLocal<>();
    private static int resIdStart = 0;
    private static int resIdEnd = 0;

    private static Method searchRecyclerViewGetter;
    private static Method appsContainerGetter;

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

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        module = this;
        this.log(Log.INFO, TAG, "Init module");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        super.onPackageReady(param);

        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader cl = param.getClassLoader();

            Class<?> indicatorClass = cl.loadClass(CLASS_PAGE_INDICATOR);
            Constructor<?> targetConstructor = indicatorClass.getDeclaredConstructor(Context.class, android.util.AttributeSet.class, int.class);
            targetConstructor.setAccessible(true);
            hook(targetConstructor)
                .intercept(chain -> {
                    Context context = (Context) chain.getArg(0);
                    currentContext.set(context);

                    if (resIdStart == 0 && context != null) {
                        Resources res = context.getResources();
                        String pkg = context.getPackageName();
                        resIdStart = res.getIdentifier("ic_chevron_start", "drawable", pkg);
                        resIdEnd = res.getIdentifier("ic_chevron_end", "drawable", pkg);
                        module.log(Log.INFO, TAG, "Found Res IDs - Start: " + resIdStart + ", End: " + resIdEnd);
                    }

                    try {
                        return chain.proceed();
                    } finally {
                        currentContext.remove();
                    }
                });

            Method getDrawableMethod = Resources.class.getDeclaredMethod("getDrawable", int.class);
            getDrawableMethod.setAccessible(true);
            hook(getDrawableMethod)
                .intercept(chain -> {
                    Context ctx = currentContext.get();
                    if (ctx == null) {
                        return chain.proceed();
                    }

                    int requestedId = (int) chain.getArg(0);

                    if (requestedId != 0 && (requestedId == resIdStart || requestedId == resIdEnd)) {
                        try {
                            return ctx.getDrawable(requestedId);
                        } catch (Throwable t) {
                            module.log(Log.ERROR, TAG, "Failed to fix drawable", t);
                            throw t;
                        }
                    }
                    return chain.proceed();
                });

            Class<?> allAppsClass = cl.loadClass(CLASS_ALL_APPS);
            searchRecyclerViewGetter = findMethod(allAppsClass, "getSearchRecyclerView");
            appsContainerGetter = findMethod(allAppsClass, "getAppsRecyclerViewContainer");

            Method setSearchResultsMethod = findMethod(allAppsClass, "setSearchResults", ArrayList.class);
            hook(setSearchResultsMethod)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object listObj = chain.getArg(0);
                    boolean enteringSearch = listObj != null;
                    applySearchTouchFix(chain.getThisObject(), enteringSearch);
                    return result;
                });

            Method onClearSearchResultMethod = findMethod(allAppsClass, "onClearSearchResult");
            hook(onClearSearchResultMethod)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applySearchTouchFix(chain.getThisObject(), false);
                    return result;
                });

            Method animateToSearchStateMethod = findMethod(allAppsClass, "animateToSearchState", boolean.class, long.class);
            hook(animateToSearchStateMethod)
                .intercept(chain -> {
                    boolean enteringSearch = (boolean) chain.getArg(0);
                    applySearchTouchFix(chain.getThisObject(), enteringSearch);
                    return chain.proceed();
                });

            this.log(Log.INFO, TAG, "Hooks registered successfully.");

        } catch (Throwable t) {
            this.log(Log.ERROR, TAG, "Failed to register hooks", t);
        }
    }

    private static void applySearchTouchFix(Object allAppsViewObj, boolean searchMode) {
        if (allAppsViewObj == null) return;

        try {
            View searchRecyclerView = (View) searchRecyclerViewGetter.invoke(allAppsViewObj);
            View appsContainer = (View) appsContainerGetter.invoke(allAppsViewObj);

            if (searchRecyclerView == null || appsContainer == null) return;

            if (searchMode) {
                appsContainerStateMap.putIfAbsent(appsContainer, new ViewState(appsContainer));

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
            module.log(Log.ERROR, TAG, "applySearchTouchFix failed", t);
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
