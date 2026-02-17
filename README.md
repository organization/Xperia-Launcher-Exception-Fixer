# Xperia Launcher Exception Fixer

### What does this module do?

Fix these issues:

```bash
Drawable com.sonymobile.launcher:drawable/ic_chevron_start has unresolved theme attributes! Consider using Resources.getDrawable(int, Theme) or Context.getDrawable(int). (Fix with AI)
java.lang.RuntimeException
	at android.content.res.Resources.getDrawable(Resources.java:926)
	at com.android.launcher3.pageindicators.PageIndicatorDots.<init>(PageIndicatorDots.java:190)
	at com.android.launcher3.pageindicators.PageIndicatorDots.<init>(PageIndicatorDots.java:173)
...
```

And, fixes a bug where tapping a search result can open the wrong app (often the first alphabetic app) by preventing touch overlap during search transitions.


Tested on `Xperia 1 VII (71.1.A.2.119)`