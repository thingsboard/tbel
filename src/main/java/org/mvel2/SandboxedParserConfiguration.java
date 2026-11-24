// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2;

import org.mvel2.compiler.AbstractParser;
import org.mvel2.util.MethodStub;
import org.mvel2.util.TriFunction;

import org.mvel2.integration.Interceptor;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The parser configuration used by all scripts in sandboxed mode. A single instance is shared by every script
 * compiled in the JVM, so it is configured once by the embedding application and is read-only afterwards.
 * <p>
 * Life cycle:
 * <ol>
 * <li><b>Setup.</b> The thread that created the configuration (the one that called
 * {@link ParserContext#enableSandboxedMode()}) registers imports, data types and so on.</li>
 * <li><b>Read-only for everyone else</b> as soon as any thread compiles a script. Only the setup thread can
 * still change the configuration, so a script that another thread compiles while the application is still
 * starting cannot interrupt the registration, and cannot change the configuration either.</li>
 * <li><b>Read-only for everyone</b> once the setup thread itself compiles or executes a script, or
 * {@link #freeze()} is called.</li>
 * </ol>
 * An application that builds the configuration before any script can run may skip the setup phase altogether with
 * {@link ParserContext#enableSandboxedMode(SandboxedParserConfiguration)}.
 */
public class SandboxedParserConfiguration extends ParserConfiguration {

    // The setup thread may still register while other threads already compile and run scripts, so everything
    // that is read during compilation or execution is safe for concurrent use.
    private final Map<Class<?>, Function<Object, Long>> additionalDataTypes = new ConcurrentHashMap<>();

    private final Map<Method, TriFunction<ExecutionContext, Object, Object[], Object[]>> invocationCheckers = new ConcurrentHashMap<>();

    private final SandboxedClassLoader sanboxedClassLoader = new SandboxedClassLoader();

    private final Map<String, Object> readOnlyImports = Collections.unmodifiableMap(imports);

    /**
     * Set as soon as a script is compiled or executed anywhere: from then on only the setup thread may change
     * the configuration. On its own this does not mean the configuration is closed, see {@link #isFrozen()}.
     */
    private volatile boolean scriptsStarted;

    /**
     * The thread that is setting this configuration up, or {@code null} once setup is over.
     */
    private volatile Thread setupThread = Thread.currentThread();

    protected static final Map<String, Object> literals = AbstractParser.LITERALS
            .entrySet().stream().filter(entry -> !SandboxedClassLoader.forbiddenClassLiterals.contains(entry.getKey()))
            .collect(HashMap::new, (m, v)->m.put(v.getKey(), v.getValue()), HashMap::putAll);

    public SandboxedParserConfiguration() {
        sanboxedClassLoader.setMutationGuard(this::checkMutable);
        setClassLoader(sanboxedClassLoader);
        setImports(AbstractParser.CLASS_LITERALS
                .entrySet().stream().filter(entry -> !SandboxedClassLoader.forbiddenClassLiterals.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        this.registerDefaultInvocationCheckers();
    }

    private void registerDefaultInvocationCheckers() {
        try {
            registerMethodInvocationChecker(String.class.getMethod("repeat", int.class), (ctx, value, args) -> {
                if (ctx.getMaxAllowedMemory() > 0) {
                    if (args != null && args.length > 0 && args[0] instanceof Integer) {
                        int count = (Integer)args[0];
                        long stringSize = (long) ((String) value).length() * count;
                        if (stringSize > ctx.getMaxAllowedMemory() / 2) {
                            throw new ScriptMemoryOverflowException("Max string length overflow (" + stringSize + " > " + ctx.getMaxAllowedMemory() / 2 + ")!");
                        }
                    }
                }
                return args;
            });
            registerMethodInvocationChecker(String.class.getMethod("concat", String.class), (ctx, value, args) -> {
                if (ctx.getMaxAllowedMemory() > 0) {
                    if (args != null && args.length > 0 && args[0] instanceof String) {
                        String str = (String)args[0];
                        int stringSize = ((String) value).length() + str.length();
                        if (stringSize > ctx.getMaxAllowedMemory() / 2) {
                            throw new ScriptMemoryOverflowException("Max string length overflow (" + stringSize + " > " + ctx.getMaxAllowedMemory() / 2 + ")!");
                        }
                    }
                }
                return args;
            });
            registerMethodInvocationChecker(String.class.getMethod("replace", CharSequence.class, CharSequence.class), (ctx, value, args) -> {
                if (ctx.getMaxAllowedMemory() > 0) {
                    if (args != null && args.length > 1 && args[1] instanceof CharSequence) {
                        CharSequence replacement = (CharSequence)args[1];
                        int stringSize = replacement.length();
                        if (stringSize > ctx.getMaxAllowedMemory() / 100) {
                            throw new ScriptMemoryOverflowException("Max replacement length overflow (" + stringSize + " > " + ctx.getMaxAllowedMemory() / 10 + ")!");
                        }
                    }
                }
                return args;
            });
            registerMethodInvocationChecker(String.class.getMethod("replaceAll", String.class, String.class), (ctx, value, args) -> {
                if (ctx.getMaxAllowedMemory() > 0) {
                    if (args != null && args.length > 1 && args[1] instanceof String) {
                        String replacement = (String)args[1];
                        int stringSize = replacement.length();
                        if (stringSize > ctx.getMaxAllowedMemory() / 100) {
                            throw new ScriptMemoryOverflowException("Max replacement length overflow (" + stringSize + " > " + ctx.getMaxAllowedMemory() / 10 + ")!");
                        }
                    }
                }
                return args;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to register default invocation checkers!", e);
        }
    }

    /**
     * Makes this configuration read-only for every thread: every later attempt to change imports, allowed
     * packages, data types, invocation checkers, the class loader or any other setting fails with an
     * {@link IllegalStateException}. Cannot be undone; {@link ParserContext#enableSandboxedMode()} creates a new
     * configuration.
     * <p>
     * Calling it is optional: the configuration closes by itself, see the class description.
     */
    public void freeze() {
        scriptsStarted = true;
        setupThread = null;
        sealMethodStubs();
    }

    /**
     * Registered helpers are shared by all scripts like the configuration itself: they are resolved and made
     * immutable when scripts start to use the configuration.
     */
    private void sealMethodStubs() {
        for (Object value : imports.values()) {
            if (value instanceof MethodStub) {
                ((MethodStub) value).seal();
            }
        }
    }

    /**
     * Called by the engine whenever a sandboxed script is about to be compiled or executed by the current thread.
     */
    void onScriptUse() {
        if (!scriptsStarted) {
            scriptsStarted = true;
            sealMethodStubs();
        }
        if (setupThread == Thread.currentThread()) {
            setupThread = null;
            sealMethodStubs();
        }
    }

    /**
     * @return {@code true} once no thread can change this configuration any more
     */
    public boolean isFrozen() {
        return scriptsStarted && setupThread == null;
    }

    private boolean isReadOnlyForCurrentThread() {
        return scriptsStarted && setupThread != Thread.currentThread();
    }

    @Override
    protected void checkMutable() {
        if (!scriptsStarted) {
            return;
        }
        Thread setup = setupThread;
        if (setup == null) {
            throw new IllegalStateException("Sandboxed parser configuration is frozen: its setup has ended"
                    + " (the setup thread compiled or executed a script, or freeze() was called)."
                    + " Register imports and data types before that, or enable sandboxed mode again.");
        }
        if (setup != Thread.currentThread()) {
            throw new IllegalStateException("Sandboxed parser configuration is being set up by thread '" + setup.getName()
                    + "': once a script has been compiled or executed, only the thread that enabled sandboxed mode"
                    + " may change the configuration, until its own first script.");
        }
    }

    @Override
    public Map<String, Object> getImports() {
        return isReadOnlyForCurrentThread() ? readOnlyImports : registeringImports;
    }

    @Override
    public synchronized HashSet<String> getPackageImports() {
        // always a copy: callers (e.g. import resolver factories) add to the returned set
        return packageImports == null ? null : new HashSet<>(packageImports);
    }

    @Override
    public Map<String, Interceptor> getInterceptors() {
        return isReadOnlyForCurrentThread() && interceptors != null ? Collections.unmodifiableMap(interceptors) : interceptors;
    }

    /**
     * The compiler switches this optimization off for the whole configuration when it meets certain expressions.
     * A configuration shared by all scripts must not change as a side effect of compiling one of them, so in
     * sandboxed mode the optimization is always off.
     */
    @Override
    public boolean isAllowBootstrapBypass() {
        return false;
    }

    @Override
    public void setAllowBootstrapBypass(boolean allowBootstrapBypass) {
        // fixed, see isAllowBootstrapBypass()
    }

    @Override
    public boolean hasImport(String name) {
        return imports.containsKey(name);
    }

    @Override
    public Class getImport(String name) {
        if (imports.containsKey(name) && imports.get(name) instanceof Class) {
            return (Class) imports.get(name);
        }
        return null;
    }

    @Override
    public void addImport(String name, Class cls) {
        super.addImport(name, cls);
        sanboxedClassLoader.addAllowedClass(cls);
    }

    @Override
    public void addImport(String name, MethodStub method) {
        super.addImport(name, method);
        registered(method);
    }

    @Override
    public void addAllImports(Map<String, Object> imports) {
        super.addAllImports(imports);
        registeredAll();
    }

    @Override
    public void setAllImports(Map<String, Object> imports) {
        super.setAllImports(imports);
        registeredAll();
    }

    /**
     * The one place every way of registering an import ends in ({@code addImport}, {@code addAllImports},
     * {@code setAllImports}, a {@code put} or {@code putAll} on {@link #getImports()}, which offers no other way
     * to put something in): a helper that is registered by the setup
     * thread while other threads already run scripts is shared with them at once, so it is made immutable at
     * once. Helpers registered before that are made immutable when scripts start, see {@link #onScriptUse()}.
     */
    private Object registered(Object value) {
        if (scriptsStarted && value instanceof MethodStub) {
            ((MethodStub) value).seal();
        }
        return value;
    }

    private void registeredAll() {
        // also during the construction of the base class, before the fields of this one are set
        if (scriptsStarted && imports != null) {
            sealMethodStubs();
        }
    }

    // The imports as the setup thread sees them: a change through this map registers like addImport() does.
    private final Map<String, Object> registeringImports = new java.util.AbstractMap<String, Object>() {
        // A view to read: an entry cannot be replaced or removed through it, nor through the views of keys and
        // values that are made of it, so every change comes through put(), remove() or clear() below.
        @Override
        public Set<Entry<String, Object>> entrySet() {
            return Collections.unmodifiableMap(imports).entrySet();
        }

        @Override
        public void clear() {
            checkMutable();
            imports.clear();
        }

        @Override
        public Object put(String name, Object value) {
            checkMutable();
            return imports.put(name, registered(value));
        }

        @Override
        public Object get(Object name) {
            return imports.get(name);
        }

        @Override
        public boolean containsKey(Object name) {
            return imports.containsKey(name);
        }

        @Override
        public Object remove(Object name) {
            checkMutable();
            return imports.remove(name);
        }
    };

    @Override
    public Object getStaticOrClassImport(String name) {
        return imports.getOrDefault(name, null);
    }

    public void addAllowedPackage(String packageName) {
        checkMutable();
        addPackageImport(packageName);
        this.sanboxedClassLoader.addAllowedPackage(packageName);
    }

    // The package import set is a plain set owned by the base class: writes and the copying read are serialized.

    @Override
    public synchronized void addPackageImport(String packageName) {
        super.addPackageImport(packageName);
    }

    @Override
    public synchronized void setPackageImports(HashSet<String> packageImports) {
        // a copy: the caller keeps its set and could change it behind the back of this configuration
        super.setPackageImports(packageImports == null ? null : new HashSet<>(packageImports));
    }

    @Override
    public void setInterceptors(Map<String, Interceptor> interceptors) {
        // a copy that other threads can read while the setup thread still adds to it
        super.setInterceptors(interceptors == null ? null : new ConcurrentHashMap<>(interceptors));
    }

    @SuppressWarnings("unchecked")
    public <T> void registerDataType(String name, Class<T> cls, Function<T, Long> valueSizeFunction) {
        checkMutable();
        this.addImport(name, cls);
        if (valueSizeFunction != null) {
            this.additionalDataTypes.put(cls, (Function<Object, Long>) valueSizeFunction);
        } else {
            this.additionalDataTypes.remove(cls);
        }
    }

    public void registerMethodInvocationChecker(Method method, TriFunction<ExecutionContext, Object, Object[], Object[]> methodInvocationCheckerFunction) {
        checkMutable();
        if (methodInvocationCheckerFunction != null) {
            this.invocationCheckers.put(method, methodInvocationCheckerFunction);
        } else {
            this.invocationCheckers.remove(method);
        }
    }

    public Function<Object, Long> getValueSizeFunction(Class<?> cls) {
        return cls == null ? null : this.additionalDataTypes.get(cls);
    }

    public TriFunction<ExecutionContext, Object, Object[], Object[]> getMethodInvocationChecker(Method method) {
        return method == null ? null : this.invocationCheckers.get(method);
    }
}
