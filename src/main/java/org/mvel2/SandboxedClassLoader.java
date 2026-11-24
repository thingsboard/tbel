// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2;

import org.mvel2.compiler.AbstractParser;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SandboxedClassLoader extends URLClassLoader {

    protected static final Set<String> forbiddenClassLiterals =
            Set.of("System",  "Runtime", "Class", "ClassLoader", "Thread", "Compiler", "ThreadLocal", "SecurityManager", "Array", "StringBuffer", "StringBuilder", "Module");
    protected static final Set<String> forbiddenMethodsClasses = forbiddenClassLiterals;

    protected static final Set<String> forbiddenClasses = Set.of(
            "java.util.Formatter",
            // spawns non-daemon threads that outlive the script
            "java.util.Timer", "java.util.TimerTask",
            // load and instantiate arbitrary classes from the classpath
            "java.util.ServiceLoader",
            "java.util.ResourceBundle", "java.util.PropertyResourceBundle", "java.util.ListResourceBundle");

    /**
     * Methods declared by (or inherited from) these types are never callable from a sandboxed script,
     * regardless of how the script obtained a reference to the target object.
     */
    protected static final Set<Class<?>> forbiddenMethodTypes = Set.of(
            Class.class, ClassLoader.class, System.class, Runtime.class,
            Process.class, ProcessBuilder.class, ProcessHandle.class,
            Thread.class, ThreadGroup.class, ThreadLocal.class, StackWalker.class,
            Module.class, ModuleLayer.class, Package.class, SecurityManager.class,
            java.util.ServiceLoader.class, java.util.Timer.class, java.util.TimerTask.class,
            java.util.ResourceBundle.class,
            // Every class in forbiddenClasses belongs here as well (a test compares the two): a script cannot
            // name such a class, and it cannot use an object of it that the application hands to it either.
            java.util.Formatter.class);

    /**
     * Methods declared in these packages are never callable from a sandboxed script. Most importantly this
     * covers {@code java.lang.reflect.Method#invoke}, which would otherwise turn any leaked {@code Method}
     * object into an arbitrary call.
     */
    protected static final Set<String> forbiddenMethodPackages = Set.of(
            "java.lang.reflect.", "java.lang.invoke.", "java.lang.instrument.", "java.lang.management.",
            "java.lang.ref.", "java.lang.module.", "java.lang.runtime.");

    /**
     * JDK implementation packages. Methods declared here are not callable either, with one exception: an
     * implementation class is what public factory methods return (a {@code java.util.TimeZone} is a
     * {@code sun.util.calendar.ZoneInfo}), and a public method that such a class implements is callable
     * through the public type, see {@link #isMethodForbidden(Method, Method)}.
     */
    protected static final Set<String> implementationPackages = Set.of("sun.", "com.sun.", "jdk.internal.");

    /**
     * The script engine itself. Objects of these types are reachable from a script (a function value, for
     * instance, leads to its AST node, compiled block, parser context and the shared parser configuration), but
     * none of their methods is part of the scripting API. Covers the root engine package (not its subpackages)
     * and every listed subpackage including nested ones.
     * <p>
     * Deliberately not listed: {@code org.mvel2.execution}, the collection types that back script lists, maps
     * and sets. Methods registered by the embedding application as static imports are allowed separately, see
     * {@link ParserContext#isMethodAllowed(Method)}.
     */
    protected static final String enginePackage = "org.mvel2";

    protected static final Set<String> forbiddenEnginePackages = Set.of(
            "org.mvel2.asm", "org.mvel2.ast", "org.mvel2.compiler", "org.mvel2.conversion", "org.mvel2.debug",
            "org.mvel2.integration", "org.mvel2.jsr223", "org.mvel2.math", "org.mvel2.optimizers", "org.mvel2.sh",
            "org.mvel2.templates", "org.mvel2.util");

    protected static final Set<Method> forbiddenMethods = Set.of(
            getMethod(Object.class, "getClass"),
            getMethod(Class.class, "getClassLoader"),
            // thread monitor operations
            getMethod(Object.class, "wait"),
            getMethod(Object.class, "wait", long.class),
            getMethod(Object.class, "wait", long.class, int.class),
            getMethod(Object.class, "notify"),
            getMethod(Object.class, "notifyAll"),
            // JVM-wide default state shared by every tenant and the platform itself
            getMethod(java.util.TimeZone.class, "setDefault", java.util.TimeZone.class),
            getMethod(java.util.Locale.class, "setDefault", java.util.Locale.class),
            getMethod(java.util.Locale.class, "setDefault", java.util.Locale.Category.class, java.util.Locale.class),
            // system property reads
            getMethod(Integer.class, "getInteger", String.class),
            getMethod(Integer.class, "getInteger", String.class, int.class),
            getMethod(Integer.class, "getInteger", String.class, Integer.class),
            getMethod(Long.class, "getLong", String.class),
            getMethod(Long.class, "getLong", String.class, long.class),
            getMethod(Long.class, "getLong", String.class, Long.class),
            getMethod(Boolean.class, "getBoolean", String.class)
    );

    /**
     * Whether a sandboxed script may invoke {@code method}, judged on its own: the same as
     * {@link #isMethodForbidden(Method, Method)} for a method that is invoked exactly as it was resolved.
     */
    public static boolean isMethodForbidden(Method method) {
        return isMethodForbidden(method, method);
    }

    /**
     * Single source of truth for whether a sandboxed script may make a call: every check in the engine, for
     * method calls, getters and setters alike, ends up here.
     * <p>
     * The call was resolved against the runtime class of the receiver ({@code resolved}); {@code target} is the
     * same method as the public type declares it (see {@code ParseTools.getWidenedTarget}). Both are checked.
     * Which of the two is invoked afterwards depends on the caller. For a getter, a setter and a setter that is
     * resolved again for another receiver it is decided by what reflection can reach, see
     * {@code ParserContext.invocationTarget}. A compiled method call invokes the widened method. An interpreted
     * method call invokes the resolved method and falls back to the widened one when that cannot be accessed.
     * <p>
     * The target must be allowed like any other method. The resolved method must be allowed as well, except that
     * being declared in a JDK implementation package does not count against it: calling
     * {@code TimeZone.getOffset} on the implementation class the JDK returned is a call to the public method.
     * A method that exists only in the implementation class has no other target and stays forbidden.
     */
    public static boolean isMethodForbidden(Method resolved, Method target) {
        if (resolved == null || target == null) {
            return true;
        }
        if (isMethodForbidden(target, false)) {
            return true;
        }
        return !resolved.equals(target) && isMethodForbidden(resolved, true);
    }

    private static boolean isMethodForbidden(Method method, boolean implementationClassAllowed) {
        if (method == null) {
            return true;
        }
        if (forbiddenMethods.contains(method)) {
            return true;
        }
        Class<?> declaringClass = method.getDeclaringClass();
        if (forbiddenMethodsClasses.contains(declaringClass.getSimpleName())) {
            return true;
        }
        String className = declaringClass.getName();
        if (isEngineInternal(declaringClass)) {
            return true;
        }
        if (startsWithAny(className, forbiddenMethodPackages)) {
            return true;
        }
        if (!implementationClassAllowed && startsWithAny(className, implementationPackages)) {
            return true;
        }
        for (Class<?> forbiddenType : forbiddenMethodTypes) {
            if (forbiddenType.isAssignableFrom(declaringClass)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String className, Set<String> packagePrefixes) {
        for (String prefix : packagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEngineInternal(Class<?> cls) {
        String packageName = cls.getPackageName();
        if (!packageName.startsWith(enginePackage)) {
            return false;
        }
        if (packageName.equals(enginePackage)) {
            return true;
        }
        for (String pkg : forbiddenEnginePackages) {
            if (packageName.equals(pkg) || packageName.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    static Method getMethod(Class<?> cls, String method, Class<?>... parameterTypes) {
        Method m = null;
        try {
            m = cls.getMethod(method, parameterTypes);
        } catch (Exception e) {
            //empty
        }
        return m;
    }

    // read by every class lookup of every script while the setup thread may still be adding to them
    private final Set<String> allowedClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> allowedPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> forbiddenPackages = ConcurrentHashMap.newKeySet();
    private volatile Runnable mutationGuard;

    public SandboxedClassLoader() {
        super(new URL[0], Thread.currentThread().getContextClassLoader());
        forbiddenPackages.add("java.util.concurrent");
        forbiddenPackages.add("java.util.logging");
        forbiddenPackages.add("java.util.zip");
        forbiddenPackages.add("java.util.jar");
        forbiddenPackages.add("java.util.prefs");
        forbiddenPackages.add("java.util.spi");
        allowedPackages.add("java.util");
        AbstractParser.CLASS_LITERALS
                .entrySet().stream().filter(entry -> !forbiddenClassLiterals.contains(entry.getKey()))
                .map(Map.Entry::getValue).forEach(val -> allowedClasses.add(((Class) val).getName()));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!classNameAllowed(name)) {
            throw new ClassNotFoundException(name);
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (!classNameAllowed(name)) {
            throw new ClassNotFoundException(name);
        }
        return super.loadClass(name);
    }

    public void addAllowedClass(Class cls) {
        checkMutable();
        this.allowedClasses.add(cls.getName());
    }

    public void addAllowedPackage(String packageName) {
        checkMutable();
        this.allowedPackages.add(packageName);
    }

    /**
     * The allow lists belong to the {@link SandboxedParserConfiguration} that owns this class loader and are
     * read-only whenever that configuration is: the guard throws if the current thread may not change it.
     */
    void setMutationGuard(Runnable mutationGuard) {
        this.mutationGuard = mutationGuard;
    }

    private void checkMutable() {
        Runnable guard = mutationGuard;
        if (guard != null) {
            guard.run();
        }
    }

    private boolean classNameAllowed(String name) {
        if (forbiddenClasses.contains(name)) {
            return false;
        }
        if (allowedClasses.contains(name)) {
            return true;
        }
        for (String pkgName : forbiddenPackages) {
            if (name.startsWith(pkgName)) {
                return false;
            }
        }
        for (String pkgName : allowedPackages) {
            if (name.startsWith(pkgName)) {
                return true;
            }
        }
        return false;
    }
}
