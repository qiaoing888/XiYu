# 汐语 — 精准混淆规则（除必要系统/框架入口外，全部打碎混淆）

# 1. Xposed 框架入口
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep public class com.keyscafe.unlock.UnlockHook {
    public <init>();
}

# 2. C++ JNI 原生绑定类
-keep class com.keyscafe.unlock.XyCore {
    *;
}

# 3. Android UI 与自定义视图
-keep class com.keyscafe.unlock.MainActivity {
    *;
}
-keep class com.keyscafe.unlock.KeyboardMeltView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 4. 抹除源文件与行号信息，混淆字典单字母化
-repackageclasses ''
-allowaccessmodification
