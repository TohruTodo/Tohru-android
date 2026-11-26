-keep class io.github.tohrutodo.tohru.task.** { *; }
-keep class io.github.tohrutodo.tohru.sync.** { *; }

# JSch resolves cipher, MAC and key implementations by class name.
-keep class com.jcraft.jsch.** { *; }

# JGit references desktop-only APIs that never run on Android.
-dontwarn java.lang.ProcessHandle
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.jcraft.jsch.bc.**
-dontwarn com.jcraft.jsch.jna.**
-dontwarn com.jcraft.jsch.agentproxy.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.commons.**
-dontwarn java.lang.management.**
-dontwarn com.sun.jna.**

# JGit loads translation bundles by reflection. R8 must retain the no-arg
# constructors or release builds fail with NoSuchMethodException on JGitText.
-keep class org.eclipse.jgit.nls.** { *; }
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { *; }
