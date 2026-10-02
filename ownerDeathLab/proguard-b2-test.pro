# Preserve all fixture members and names; shrink only unused compiler output.
-dontoptimize
-dontobfuscate
-keep class com.gccwebos.android.ipcqa.b2.OwnerDeathIpcTest { *; }
-keep class com.gccwebos.android.ipcqa.b2.OwnerDeathIpcTest$* { *; }
-keep class com.gccwebos.android.ipcqa.b2.OwnerDeathRunner { *; }
-keep class com.gccwebos.android.ipcqa.b2.OwnerDeathRunner$* { *; }

