// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.ExecutionContext;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedClassLoader;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.ast.Function;
import org.mvel2.ast.FunctionInstance;
import org.mvel2.compiler.CompiledExpression;
import org.mvel2.compiler.ExecutableStatement;
import org.mvel2.execution.ExecutionArrayList;
import org.mvel2.execution.ExecutionHashMap;
import org.mvel2.execution.ExecutionLinkedHashSet;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.util.MethodStub;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * The sandboxed parser configuration is shared by every script in the JVM. It is set up by the embedding
 * application and must be out of reach of the scripts themselves: read-only once the first script is compiled,
 * and not reachable through the engine objects a script can get hold of.
 */
public class TbSandboxConfigurationTest extends TestCase {

    private static final String FUNCTION_MULTI_STATEMENT = "function f() { var a = 1; var b = 2; return a + b; } ";
    private static final String FUNCTION_SINGLE_STATEMENT = "function f() { return 1; } ";

    private static final String ANNOTATION_ENUM = "var r = \"\" + java.lang.annotation.RetentionPolicy.RUNTIME; r";

    private SandboxedParserConfiguration parserConfig;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        this.parserConfig = ParserContext.enableSandboxedMode();
    }

    @Override
    protected void tearDown() throws Exception {
        ParserContext.disableSandboxedMode();
        super.tearDown();
    }

    // --- scripts cannot reach the configuration ----------------------------------------------------------

    public void testScriptCannotChangeAllowedPackagesThroughFunctionValue() {
        assertBlocked(ANNOTATION_ENUM);
        Map<String, Object> importsBefore = new HashMap<>(parserConfig.getImports());

        assertBlocked(FUNCTION_MULTI_STATEMENT
                + "f.getFunction().getCompiledBlock().getParserConfiguration().addAllowedPackage(\"java.lang.annotation\"); 1");
        assertBlocked(FUNCTION_SINGLE_STATEMENT
                + "f.getFunction().getCompiledBlock().getParserConfiguration().addAllowedPackage(\"java.lang.annotation\"); 1");

        // a later, unrelated script sees no change
        assertBlocked(ANNOTATION_ENUM);
        assertEquals(importsBefore, parserConfig.getImports());
        assertNull(parserConfig.getPackageImports());
    }

    public void testScriptCannotWalkFromFunctionValueToEngineInternals() {
        for (String function : new String[]{FUNCTION_MULTI_STATEMENT, FUNCTION_SINGLE_STATEMENT}) {
            assertBlocked(function + "f.getFunction()");
            assertBlocked(function + "f.function");
            assertBlocked(function + "var x = f.getFunction(); x");
            assertBlocked(function + "f.getFunction().getCompiledBlock()");
            assertBlocked(function + "f.getFunction().getParserContext()");
            assertBlocked(function + "f.function.compiledBlock.parserConfiguration");
            assertBlocked(function + "f.getFunction().getCompiledBlock().getParserConfiguration().setClassLoader(null); 1");
            assertBlocked(function + "f.getFunction().getCompiledBlock().getParserConfiguration().getImports().clear(); 1");
            assertBlocked(function + "f.getFunction().getCompiledBlock().getParserConfiguration().addPackageImport(\"java.lang.Thread\"); 1");
            assertBlocked(function + "f.call(null, null, null, null)");
        }
        // the function itself still works
        assertEquals(3, executeScript(FUNCTION_MULTI_STATEMENT + "f()"));
        assertEquals(1, executeScript(FUNCTION_SINGLE_STATEMENT + "f()"));
    }

    public void testScriptCannotCallMethodsOfImportedStub() throws Exception {
        this.parserConfig.addImport("abs", new MethodStub(Math.class.getMethod("abs", int.class)));
        assertEquals(5, executeScript("abs(-5)"));
        // the parenthesised form yields the stub itself as a value
        assertMethodUnresolved("(abs).getMethod()");
        assertMethodUnresolved("(abs).getClassReference()");
        assertMethodUnresolved("(abs).setMethodName(\"negateExact\"); 1");
        assertMethodUnresolved("function g(s) { return s.getMethodName(); } g((abs))");
        assertEquals(5, executeScript("abs(-5)"));
    }

    // --- engine internals are not callable, the script collection types are ---------------------------------

    public void testEngineInternalMethodsAreForbidden() throws Exception {
        List<Method> forbidden = Arrays.asList(
                FunctionInstance.class.getMethod("getFunction"),
                Function.class.getMethod("getCompiledBlock"),
                CompiledExpression.class.getMethod("getParserConfiguration"),
                ExecutableStatement.class.getMethod("getKnownEgressType"),
                ParserContext.class.getMethod("getParserConfiguration"),
                ParserContext.class.getMethod("enableSandboxedMode"),
                ParserContext.class.getMethod("disableSandboxedMode"),
                ParserConfiguration.class.getMethod("addPackageImport", String.class),
                ParserConfiguration.class.getMethod("setClassLoader", ClassLoader.class),
                ParserConfiguration.class.getMethod("getImports"),
                SandboxedParserConfiguration.class.getMethod("addAllowedPackage", String.class),
                SandboxedParserConfiguration.class.getMethod("freeze"),
                SandboxedClassLoader.class.getMethod("addAllowedPackage", String.class),
                ExecutionContext.class.getMethod("stop"),
                MethodStub.class.getMethod("getMethod"),
                MVEL.class.getMethod("eval", String.class),
                OptimizerFactory.class.getMethod("setDefaultOptimizer", String.class));
        for (Method method : forbidden) {
            assertTrue("must be forbidden: " + method, SandboxedClassLoader.isMethodForbidden(method));
        }
    }

    public void testScriptCollectionMethodsStayAllowed() throws Exception {
        List<Method> allowed = Arrays.asList(
                ExecutionArrayList.class.getMethod("push", Object.class),
                ExecutionArrayList.class.getMethod("slice", int.class),
                ExecutionArrayList.class.getMethod("join", String.class),
                ExecutionArrayList.class.getMethod("length"),
                ExecutionHashMap.class.getMethod("keys"),
                ExecutionHashMap.class.getMethod("values"),
                ExecutionHashMap.class.getMethod("sortByValue"),
                ExecutionLinkedHashSet.class.getMethod("toList"),
                ExecutionLinkedHashSet.class.getMethod("toSorted"));
        for (Method method : allowed) {
            assertFalse("must stay allowed: " + method, SandboxedClassLoader.isMethodForbidden(method));
        }

        assertEquals(3, executeScript("var a = [1, 2]; a.push(3); a.length()"));
        assertEquals("2,3", executeScript("var a = [1, 2, 3]; a.slice(1).join(',')"));
        assertEquals(Arrays.asList("a", "b"), executeScript("var m = {a: 1, b: 2}; m.keys()"));
        assertEquals(Arrays.asList(1, 2), executeScript("var m = {b: 2, a: 1}; m.sortByValue(); m.values()"));
        assertEquals(Arrays.asList(1, 2), executeScript("var m = {}; var s = m.entrySet(); s.addAll([2, 1, 2]); s.toSorted().toList()"));
    }

    /**
     * Guards the package list: every package of the engine, except the script collection types, must be covered.
     * Fails when a new engine package is added without deciding whether scripts may call into it.
     */
    public void testOnlyExecutionPackageOfTheEngineIsCallable() throws Exception {
        URL location = ParserContext.class.getProtectionDomain().getCodeSource().getLocation();
        File root = new File(location.toURI());
        assertTrue("engine classes are expected in a directory: " + root, root.isDirectory());

        Set<String> callablePackages = new TreeSet<>();
        int checked = 0;
        List<File> pending = new ArrayList<>();
        pending.add(new File(root, "org/mvel2"));
        while (!pending.isEmpty()) {
            File dir = pending.remove(pending.size() - 1);
            File[] children = dir.listFiles();
            assertNotNull(children);
            for (File child : children) {
                if (child.isDirectory()) {
                    pending.add(child);
                    continue;
                }
                if (!child.getName().endsWith(".class")) {
                    continue;
                }
                String path = root.toURI().relativize(child.toURI()).getPath();
                String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
                Class<?> cls;
                Method[] methods;
                try {
                    cls = Class.forName(className, false, ParserContext.class.getClassLoader());
                    methods = cls.getDeclaredMethods();
                } catch (LinkageError e) {
                    continue; // optional dependency not on the test classpath
                }
                for (Method method : methods) {
                    if (!Modifier.isPublic(method.getModifiers())) {
                        continue;
                    }
                    checked++;
                    if (!SandboxedClassLoader.isMethodForbidden(method)) {
                        callablePackages.add(cls.getPackageName());
                    }
                }
            }
        }
        assertTrue("engine methods were inspected", checked > 1000);
        assertEquals(new TreeSet<>(Set.of("org.mvel2.execution")), callablePackages);
    }

    // --- the configuration is read-only once a script has been compiled ---------------------------------------

    public void testConfigurationIsMutableUntilFirstCompilation() throws Exception {
        assertFalse(parserConfig.isFrozen());
        parserConfig.addImport("abs", new MethodStub(Math.class.getMethod("abs", int.class)));
        parserConfig.registerDataType("TestType", TestType.class, val -> 8L);
        parserConfig.addAllowedPackage("java.time");
        assertFalse(parserConfig.isFrozen());

        assertEquals(5, executeScript("abs(-5)"));
        assertTrue(parserConfig.isFrozen());
        assertEquals("v", executeScript("var t = new TestType('v'); t.getValue()"));
        assertEquals("PT1S", executeScript("\"\" + java.time.Duration.ofSeconds(1)"));
    }

    public void testCreatingParserContextFreezesConfiguration() {
        assertFalse(parserConfig.isFrozen());
        new ParserContext();
        assertTrue(parserConfig.isFrozen());
    }

    public void testInterpretedEvaluationFreezesConfiguration() {
        assertFalse(parserConfig.isFrozen());
        assertEquals(3, MVEL.eval("1 + 2", new HashMap<String, Object>()));
        assertTrue(parserConfig.isFrozen());
    }

    public void testEveryMutatorIsRejectedOnceFrozen() throws Exception {
        parserConfig.freeze();
        Map<String, Object> importsBefore = new HashMap<>(parserConfig.getImports());

        // methods that are public but do not change what scripts can see or do
        Set<String> notMutators = Set.of("freeze", "flushCaches", "setAllowBootstrapBypass");

        int checked = 0;
        for (Method method : SandboxedParserConfiguration.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getReturnType() != void.class || notMutators.contains(method.getName())) {
                continue;
            }
            Object[] args = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = sampleValue(types[i]);
            }
            try {
                method.invoke(parserConfig, args);
                fail("must be rejected on a frozen configuration: " + method);
            } catch (InvocationTargetException e) {
                assertTrue(method + " threw " + e.getCause(), e.getCause() instanceof IllegalStateException);
            }
            checked++;
        }
        assertTrue("mutators were inspected", checked >= 15);
        assertEquals(importsBefore, parserConfig.getImports());
        assertNull(parserConfig.getPackageImports());
        assertNull(parserConfig.getInterceptors());
    }

    public void testExposedCollectionsAreReadOnlyOnceFrozen() {
        parserConfig.addAllowedPackage("java.time");
        parserConfig.setInterceptors(new HashMap<>());
        parserConfig.freeze();

        try {
            parserConfig.getImports().put("Thread", Thread.class);
            fail("imports must be read-only");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            parserConfig.getImports().clear();
            fail("imports must be read-only");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            parserConfig.getInterceptors().put("x", null);
            fail("interceptors must be read-only");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        // a copy: changing it does not change the configuration
        parserConfig.getPackageImports().add("java.lang.annotation");
        assertEquals(new HashSet<>(Set.of("java.time")), parserConfig.getPackageImports());
        assertBlocked(ANNOTATION_ENUM);
    }

    public void testClassLoaderAllowListIsReadOnlyOnceFrozen() {
        SandboxedClassLoader classLoader = (SandboxedClassLoader) parserConfig.getClassLoader();
        classLoader.addAllowedPackage("java.time");
        parserConfig.freeze();
        try {
            classLoader.addAllowedPackage("java.lang.annotation");
            fail("must be rejected on a frozen configuration");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            classLoader.addAllowedClass(Thread.class);
            fail("must be rejected on a frozen configuration");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertBlocked(ANNOTATION_ENUM);
    }

    public void testCompilationDoesNotChangeSharedSettings() {
        parserConfig.registerDataType("TestType", TestType.class, val -> 8L);
        assertFalse(parserConfig.isAllowBootstrapBypass());
        assertEquals("v", executeScript("new TestType('v').getValue()"));
        assertEquals("v", executeScript("var t = new TestType('v'); t.getValue()"));
        assertFalse(parserConfig.isAllowBootstrapBypass());
    }

    // --- setup while other threads already compile scripts ---------------------------------------------------

    public void testCompilationOnAnotherThreadDoesNotInterruptSetup() throws Exception {
        parserConfig.addImport("abs", new MethodStub(Math.class.getMethod("abs", int.class)));

        // another thread compiles and runs a script while this thread is still registering
        assertEquals(3, onOtherThread(() -> executeScript("1 + 2")));

        // the setup thread carries on
        assertFalse(parserConfig.isFrozen());
        parserConfig.addImport("negate", new MethodStub(Math.class.getMethod("negateExact", int.class)));
        parserConfig.registerDataType("TestType", TestType.class, val -> 8L);
        parserConfig.addAllowedPackage("java.time");
        parserConfig.getImports().put("max", new MethodStub(Math.class.getMethod("max", int.class, int.class)));

        // its first own compilation ends the setup
        assertEquals(-5, executeScript("negate(abs(-5))"));
        assertTrue(parserConfig.isFrozen());
        assertRejected(() -> parserConfig.addImport("TestType2", TestType.class));
        assertEquals("v", executeScript("new TestType('v').getValue()"));
        assertEquals(7, onOtherThread(() -> executeScript("max(negate(-7), 1)")));
    }

    public void testOtherThreadsCannotChangeConfigurationOnceAnyScriptIsCompiled() throws Exception {
        // before any compilation the configuration is plain mutable, as it always was
        onOtherThread(() -> {
            parserConfig.addAllowedPackage("java.time");
            return null;
        });

        assertEquals(3, onOtherThread(() -> executeScript("1 + 2")));

        Throwable rejected = onOtherThread(() -> {
            try {
                parserConfig.addAllowedPackage("java.lang.annotation");
                return null;
            } catch (IllegalStateException e) {
                return e;
            }
        });
        assertNotNull("a thread other than the setup thread must be rejected", rejected);
        assertNotNull(onOtherThread(() -> {
            try {
                parserConfig.getImports().put("Thread", Thread.class);
                return null;
            } catch (UnsupportedOperationException e) {
                return e;
            }
        }));
        assertNotNull(onOtherThread(() -> {
            try {
                ((SandboxedClassLoader) parserConfig.getClassLoader()).addAllowedPackage("java.lang.annotation");
                return null;
            } catch (IllegalStateException e) {
                return e;
            }
        }));
        // a script on another thread sees no change
        assertNotNull(onOtherThread(() -> {
            try {
                executeScript(ANNOTATION_ENUM);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }));
    }

    public void testStateReadByScriptsIsSafeWhileSetupThreadWrites() throws Exception {
        // What every script reads while the setup thread may still be adding to it. A test that races the two
        // rarely fails when one of these is a plain collection, so their kind is checked directly.
        SandboxedClassLoader loader = (SandboxedClassLoader) parserConfig.getClassLoader();
        for (String name : new String[]{"allowedClasses", "allowedPackages", "forbiddenPackages"}) {
            java.lang.reflect.Field field = SandboxedClassLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            assertConcurrent(name, field.get(loader));
        }
        java.lang.reflect.Field imports = ParserConfiguration.class.getDeclaredField("imports");
        imports.setAccessible(true);
        assertConcurrent("imports", imports.get(parserConfig));
        for (String name : new String[]{"invocationCheckers", "additionalDataTypes"}) {
            java.lang.reflect.Field field = SandboxedParserConfiguration.class.getDeclaredField(name);
            field.setAccessible(true);
            assertConcurrent(name, field.get(parserConfig));
        }
        Map<String, org.mvel2.integration.Interceptor> interceptors = new HashMap<>();
        interceptors.put("i", new org.mvel2.integration.Interceptor() {
            public int doBefore(org.mvel2.ast.ASTNode node, org.mvel2.integration.VariableResolverFactory factory) {
                return 0;
            }

            public int doAfter(Object exitStackValue, org.mvel2.ast.ASTNode node, org.mvel2.integration.VariableResolverFactory factory) {
                return 0;
            }
        });
        parserConfig.setInterceptors(interceptors);
        assertConcurrent("interceptors", parserConfig.getInterceptors());
        // the configuration has its own copy: what the caller does to its map afterwards does not reach it
        interceptors.clear();
        assertEquals(1, parserConfig.getInterceptors().size());
    }

    private static void assertConcurrent(String name, Object collection) {
        assertTrue(name + " is a " + collection.getClass().getName(), collection.getClass().getName().startsWith("java.util.concurrent."));
    }

    public void testPackageImportsOfTheCallerAreCopied() {
        HashSet<String> mine = new HashSet<>(Arrays.asList("java.util"));
        parserConfig.setPackageImports(mine);
        mine.add("java.io");
        assertEquals(new HashSet<>(Arrays.asList("java.util")), parserConfig.getPackageImports());
    }

    public void testHelperIsSealedOnEveryWayOfRegisteringIt() throws Exception {
        // another thread runs a script: from now on what the setup thread registers is shared at once
        Thread other = new Thread(() -> executeScript("1 + 2"));
        other.start();
        other.join(10_000);

        MethodStub added = new MethodStub(Math.class, "abs");
        parserConfig.addImport("added", added);
        assertTrue("addImport", added.isSealed());

        MethodStub addedWithOthers = new MethodStub(Math.class, "abs");
        Map<String, Object> more = new HashMap<>();
        more.put("addedWithOthers", addedWithOthers);
        more.put("wrapped", Math.class.getMethod("abs", int.class));
        parserConfig.addAllImports(more);
        assertTrue("addAllImports", addedWithOthers.isSealed());
        assertTrue("addAllImports, a method", ((MethodStub) parserConfig.getImports().get("wrapped")).isSealed());

        MethodStub put = new MethodStub(Math.class, "abs");
        parserConfig.getImports().put("put", put);
        assertTrue("getImports().put", put.isSealed());

        MethodStub putWithOthers = new MethodStub(Math.class, "abs");
        parserConfig.getImports().putAll(java.util.Collections.singletonMap("putWithOthers", putWithOthers));
        assertTrue("getImports().putAll", putWithOthers.isSealed());

        // there is no other way to put something into the imports
        MethodStub unregistered = new MethodStub(Math.class, "abs");
        Map<String, Object> view = parserConfig.getImports();
        for (Runnable change : new Runnable[]{() -> view.entrySet().iterator().next().setValue(unregistered), () -> view.replaceAll((name, value) -> unregistered),
                () -> view.values().remove(put), () -> view.keySet().remove("put"), () -> view.entrySet().clear()}) {
            try {
                change.run();
                fail("the imports were changed past put()");
            } catch (UnsupportedOperationException expected) {
                // a view to read
            }
        }
        assertFalse(unregistered.isSealed());
        assertSame(put, view.get("put"));

        MethodStub set = new MethodStub(Math.class, "abs");
        Map<String, Object> all = new HashMap<>(parserConfig.getImports());
        all.put("set", set);
        parserConfig.setAllImports(all);
        assertTrue("setAllImports", set.isSealed());
        assertEquals(15, executeScript("added(-5) + put(-5) + set(-5)"));
        // executing on this thread ended the setup: the view that was handed out before refuses every change
        int imports = view.size();
        for (Runnable change : new Runnable[]{view::clear, () -> view.put("late", unregistered), () -> view.remove("put")}) {
            try {
                change.run();
                fail("the imports were changed after the setup had ended");
            } catch (IllegalStateException expected) {
                // read-only
            }
        }
        assertEquals(imports, view.size());
    }

    public void testClassThatCannotBeNamedCannotBeUsedEither() throws Exception {
        // the two lists are kept by hand: a class a script may not name is one whose methods it may not call
        java.lang.reflect.Field names = SandboxedClassLoader.class.getDeclaredField("forbiddenClasses");
        names.setAccessible(true);
        java.lang.reflect.Field types = SandboxedClassLoader.class.getDeclaredField("forbiddenMethodTypes");
        types.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Class<?>> forbiddenTypes = (Set<Class<?>>) types.get(null);
        for (Object name : (Set<?>) names.get(null)) {
            Class<?> type = Class.forName((String) name);
            boolean covered = false;
            for (Class<?> forbidden : forbiddenTypes) {
                covered |= forbidden.isAssignableFrom(type);
            }
            assertTrue(name + " can be named in no script, but its methods can be called", covered);
        }
        // an object of such a class that the application hands to a script
        Map<String, Object> vars = new HashMap<>();
        vars.put("f", new java.util.Formatter());
        try {
            Object result = executeTbExpression(compileExpression("f.format('%s', 1).toString()", new ParserContext()), new ExecutionContext(parserConfig), vars);
            fail("Sandbox should block the call but it returned: " + result);
        } catch (RuntimeException expected) {
            // refused
        }
    }

    public void testConcurrentCompilationDuringRegistration() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> compilerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch compiling = new java.util.concurrent.CountDownLatch(1);
        Thread compiler = new Thread(() -> {
            try {
                while (!stop.get()) {
                    // resolves a class through the sandboxed class loader and calls a method with an invocation checker
                    assertEquals("" + java.util.Objects.hash(1) + "aa",
                            executeScript("\"\" + java.util.Objects.hash(1) + 'a'.repeat(2)"));
                    compiling.countDown();
                }
            } catch (Throwable t) {
                compilerFailure.set(t);
                compiling.countDown();
            }
        });
        compiler.start();
        try {
            assertTrue(compiling.await(10, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 500; i++) {
                parserConfig.addImport("abs" + i, new MethodStub(Math.class.getMethod("abs", int.class)));
                parserConfig.registerDataType("TestType" + i, TestType.class, val -> 8L);
                parserConfig.addAllowedPackage("org.example.pkg" + i);
                parserConfig.registerMethodInvocationChecker(Math.class.getMethod("abs", int.class), (ctx, value, args) -> args);
            }
        } finally {
            stop.set(true);
            compiler.join(10_000);
        }
        assertNull(compilerFailure.get());
        assertEquals(5, executeScript("abs499(-5)"));
        assertEquals("v", executeScript("new TestType499('v').getValue()"));
        assertTrue(parserConfig.isFrozen());
    }

    public void testExecutionOnSetupThreadEndsSetup() {
        Serializable compiled = onOtherThreadUnchecked(() -> compileExpression("1 + 2", new ParserContext()));
        assertFalse(parserConfig.isFrozen());
        assertEquals(3, executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>()));
        assertTrue(parserConfig.isFrozen());
        assertRejected(() -> parserConfig.addAllowedPackage("java.time"));
    }

    public void testExplicitFreezeEndsSetupForSetupThread() {
        parserConfig.freeze();
        assertTrue(parserConfig.isFrozen());
        assertRejected(() -> parserConfig.addAllowedPackage("java.time"));
    }

    public void testRejectionSaysWhatHappened() throws Exception {
        // another thread, while the setup thread has not finished
        onOtherThread(() -> executeScript("1 + 2"));
        String wrongThread = onOtherThread(() -> {
            try {
                parserConfig.addAllowedPackage("java.time");
                return null;
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        });
        assertNotNull(wrongThread);
        assertTrue(wrongThread, wrongThread.contains("being set up by thread '" + Thread.currentThread().getName() + "'"));
        assertTrue(wrongThread, wrongThread.contains("only the thread that enabled sandboxed mode"));

        // after the setup has ended, for any thread
        parserConfig.freeze();
        try {
            parserConfig.addAllowedPackage("java.time");
            fail("must be rejected on a frozen configuration");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("is frozen"));
            assertTrue(e.getMessage(), e.getMessage().contains("freeze() was called"));
        }
    }

    public void testNullCheckersAndSizeFunctionsAreAccepted() throws Exception {
        assertNull(parserConfig.getMethodInvocationChecker(null));
        assertNull(parserConfig.getValueSizeFunction(null));
        parserConfig.registerDataType("TestType", TestType.class, null);
        assertNull(parserConfig.getValueSizeFunction(TestType.class));
        Method abs = Math.class.getMethod("abs", int.class);
        parserConfig.registerMethodInvocationChecker(abs, (ctx, value, args) -> args);
        assertNotNull(parserConfig.getMethodInvocationChecker(abs));
        parserConfig.registerMethodInvocationChecker(abs, null);
        assertNull(parserConfig.getMethodInvocationChecker(abs));
    }

    public void testEnablingSandboxWithCompleteConfiguration() throws Exception {
        ParserContext.disableSandboxedMode();
        SandboxedParserConfiguration complete = new SandboxedParserConfiguration();
        complete.addImport("abs", new MethodStub(Math.class.getMethod("abs", int.class)));
        assertFalse(ParserContext.isSandboxedMode());

        ParserContext.enableSandboxedMode(complete);
        this.parserConfig = complete;
        assertTrue(ParserContext.isSandboxedMode());
        assertTrue(complete.isFrozen());
        assertRejected(() -> complete.addAllowedPackage("java.time"));
        assertEquals(5, executeScript("abs(-5)"));
        assertSame(complete, new ParserContext().getParserConfiguration());

        try {
            ParserContext.enableSandboxedMode(null);
            fail("a configuration is required");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public void testEnablingSandboxAgainStartsWithNewMutableConfiguration() throws Exception {
        executeScript("1 + 1");
        assertTrue(parserConfig.isFrozen());

        SandboxedParserConfiguration next = ParserContext.enableSandboxedMode();
        assertNotSame(parserConfig, next);
        assertFalse(next.isFrozen());
        next.addImport("abs", new MethodStub(Math.class.getMethod("abs", int.class)));
        this.parserConfig = next;
        assertEquals(5, executeScript("abs(-5)"));
    }

    public void testPlainConfigurationStaysMutable() {
        ParserContext.disableSandboxedMode();
        ParserConfiguration configuration = new ParserConfiguration();
        ParserContext pCtx = new ParserContext(configuration);
        assertEquals(2, MVEL.executeExpression(compileExpression("1 + 1", pCtx), new HashMap<String, Object>()));
        configuration.addImport("TestType", TestType.class);
        configuration.addPackageImport("java.util");
        assertTrue(configuration.hasImport("TestType"));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    public static class TestType {
        private final String value;

        public TestType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private static Object sampleValue(Class<?> type) throws Exception {
        if (type == boolean.class) return true;
        if (type == String.class) return "java.lang.annotation";
        if (type == Class.class) return Thread.class;
        if (type == Method.class) return Math.class.getMethod("abs", int.class);
        if (type == MethodStub.class) return new MethodStub(Math.class.getMethod("abs", int.class));
        if (type == Map.class || type == HashMap.class) return new HashMap<>();
        if (type == HashSet.class || type == Set.class) return new HashSet<>();
        if (type == ClassLoader.class) return TbSandboxConfigurationTest.class.getClassLoader();
        return null;
    }

    private static void assertRejected(Runnable change) {
        try {
            change.run();
            fail("must be rejected on a frozen configuration");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    private static <T> T onOtherThread(java.util.concurrent.Callable<T> task) throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T onOtherThreadUnchecked(java.util.concurrent.Callable<T> task) {
        try {
            return onOtherThread(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The script must be refused by the method policy itself, not fail for an unrelated reason.
     */
    private void assertMethodUnresolved(String script) {
        try {
            Object result = executeScript(script);
            fail("Sandbox should block script but it returned: " + result + "\n" + script);
        } catch (RuntimeException e) {
            assertTrue(script + "\nfailed with: " + e, String.valueOf(e.getMessage()).contains("unable to resolve method"));
        }
    }

    private void assertBlocked(String script) {
        Object result;
        try {
            result = executeScript(script);
        } catch (RuntimeException expected) {
            return;
        }
        fail("Sandbox should block script but it returned: " + result + "\n" + script);
    }

    private Object executeScript(String script) {
        Serializable compiled = compileExpression(script, new ParserContext());
        return executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>());
    }
}
