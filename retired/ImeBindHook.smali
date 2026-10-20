.class public Lio/github/wangxiaobo775/pluck/ImeBindHook;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "ImeBindHook.java"

# The keyboard service is bound with the clone user's id, so Baidu starts a
# second process. Bind clone keyboards to the main user instead.
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V
    return-void
.end method

.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 6
    :try_start_0
    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;
    if-eqz v0, :out
    const-string v1, "mUserId"
    invoke-static {v0, v1}, Lde/robv/android/xposed/XposedHelpers;->getIntField(Ljava/lang/Object;Ljava/lang/String;)I
    move-result v2
    const/16 v3, 0xf
    if-lt v2, v3, :out
    const/16 v3, 0x13
    if-gt v2, v3, :out
    const/4 v2, 0x0
    invoke-static {v0, v1, v2}, Lde/robv/android/xposed/XposedHelpers;->setIntField(Ljava/lang/Object;Ljava/lang/String;I)V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
    :out
    return-void
    :catch_0
    move-exception v0
    invoke-static {v0}, Lde/robv/android/xposed/XposedBridge;->log(Ljava/lang/Throwable;)V
    return-void
.end method
