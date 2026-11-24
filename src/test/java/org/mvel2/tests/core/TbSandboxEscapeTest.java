// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.CompileException;
import org.mvel2.PropertyAccessException;
import org.mvel2.ExecutionContext;
import org.mvel2.ParserContext;
import org.mvel2.ScriptStackOverflowException;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.refl.ReflectiveAccessorOptimizer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * Regression tests for sandbox escapes. Each test covers one link of a known escape chain so that
 * breaking any single defence is detected, not only the end-to-end exploit.
 */
public class TbSandboxEscapeTest extends TestCase {

    /**
     * Reflection-based RCE: obtains java.lang.reflect.Method handles through MVEL's "method reference"
     * property fallback, then walks Class.forName -> Runtime.exec via Method.invoke.
     */
    private static final String METHOD_REFERENCE_RCE =
            "function pick(ms, sig, i) {\n" +
            "    if ((\"\" + ms[i]).contains(sig)) { return ms[i]; }\n" +
            "    return pick(ms, sig, i + 1);\n" +
            "}\n" +
            "function cls(cc, name) {\n" +
            "    return pick(cc.getMethods.invoke(cc), \"forName(java.lang.String)\", 0).invoke(null, name);\n" +
            "}\n" +
            "function rd(s) { return s.nextLine(); }\n" +
            "function exec(cc, rt, cmd) {\n" +
            "    return rd(new java.util.Scanner(pick(cc.getMethods.invoke(rt), \"exec(java.lang.String)\", 0).invoke(rt.getRuntime.invoke(null), cmd).getInputStream()));\n" +
            "}\n" +
            "function go(cc, cmd) {\n" +
            "    return exec(cc, cls(cc, \"java.lang.Runtime\"), cmd);\n" +
            "}\n" +
            "var r = go(String.getClass.invoke(String), \"whoami\");\n" +
            "return {msg: {whoami: r}};";

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
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        super.tearDown();
    }

    // --- end-to-end -------------------------------------------------------------------------------------------

    public void testMethodReferenceRceIsBlocked() {
        assertBlocked(METHOD_REFERENCE_RCE);
    }

    public void testMethodReferenceRceIsBlockedWithDynamicOptimizer() {
        // The ASM JIT has no sandbox checks; sandboxed mode must never use it, whatever the embedder configured.
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC);
        assertTrue(OptimizerFactory.getDefaultAccessorCompiler() instanceof ReflectiveAccessorOptimizer);
        assertTrue(OptimizerFactory.getAccessorCompiler("ASM") instanceof ReflectiveAccessorOptimizer);
        assertTrue(OptimizerFactory.getThreadAccessorOptimizer() instanceof ReflectiveAccessorOptimizer);
        assertBlocked(METHOD_REFERENCE_RCE);
        // A call site that is used past the JIT threshold of the dynamic optimizer with a receiver it may be
        // called on, and then gets one it may not be called on: the check has to be made on that call as well.
        Map<String, Object> vars = new HashMap<>();
        vars.put("c", String.class);
        assertEquals("a", executeScript("var r = 0; for (var i = 0; i < 500; i++) { r = (i < 499 ? 'a' : 'a').toString(); } r", new HashMap<>(), new ExecutionContext(parserConfig)));
        assertBlocked("var r = 0; for (var i = 0; i < 500; i++) { r = (i < 499 ? 'a' : c).toString(); } r", vars);
    }

    // --- link 1/2: property access must not yield java.lang.reflect.Method values --------------------------

    public void testForbiddenMethodReferenceIsNotExposed() {
        assertBlocked("String.getClass");
        assertBlocked("var s = 'abc'; s.getClass");
        assertBlocked("String.getMethods");
    }

    public void testAllowedMethodReferenceIsNotExposedEither() {
        // Method values are not callable from TBEL; they only serve as a stepping stone to Method.invoke.
        assertBlocked("Math.abs");
        assertBlocked("java.lang.Math.abs");
        assertBlocked("Math.abs.invoke(null, -5)");
    }

    // --- link 3: reflection API is not callable even if a handle leaks in ---------------------------------

    public void testLeakedMethodCannotBeInvoked() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("m", Math.class.getMethod("abs", int.class));
        assertBlocked("m.invoke(null, -5)", vars);
        assertBlocked("m.getDeclaringClass()", vars);
        assertBlocked("m.setAccessible(true)", vars);
    }

    public void testLeakedClassCannotBeUsed() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("c", String.class);
        assertBlocked("c.forName('java.lang.Runtime')", vars);
        assertBlocked("c.getMethods()", vars);
        assertBlocked("c.getMethods", vars);
        assertBlocked("c.getClassLoader()", vars);
        assertBlocked("c.newInstance()", vars);
    }

    // --- link 5: dangerous runtime types are not usable however they were obtained -------------------------

    public void testLeakedRuntimeCannotExec() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("rt", Runtime.getRuntime());
        assertBlocked("rt.exec('whoami')", vars);
        assertBlocked("rt.exit(0)", vars);
    }

    public void testLeakedProcessCannotBeRead() throws Exception {
        Process p = new ProcessBuilder("true").start();
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("p", p);
            assertBlocked("p.getInputStream()", vars);
            assertBlocked("p.inputStream", vars);
            assertBlocked("p.destroy()", vars);
        } finally {
            p.destroyForcibly();
        }
    }

    public void testLeakedThreadCannotBeControlled() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("t", Thread.currentThread());
        assertBlocked("t.interrupt()", vars);
        assertBlocked("t.getContextClassLoader()", vars);
        assertBlocked("t.contextClassLoader", vars);
    }

    public void testObjectMonitorMethodsAreBlocked() {
        assertBlocked("var m = {}; m.wait(1); 1");
        assertBlocked("var m = {}; m.notifyAll(); 1");
    }

    // --- link 4: dangerous java.util classes ---------------------------------------------------------------

    public void testThreadSpawningAndClassLoadingUtilClassesAreBlocked() {
        assertBlocked("new java.util.Timer()");
        assertBlocked("java.util.ServiceLoader.load(String)");
        assertBlocked("java.util.ResourceBundle.getBundle('x')");
    }

    // --- JVM-global state ----------------------------------------------------------------------------------

    public void testJvmDefaultsCannotBeChanged() {
        TimeZone tz = TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        try {
            assertBlocked("java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone('Pacific/Kiritimati')); 1");
            assertBlocked("java.util.Locale.setDefault(java.util.Locale.CHINA); 1");
            assertEquals(tz, TimeZone.getDefault());
            assertEquals(locale, Locale.getDefault());
        } finally {
            TimeZone.setDefault(tz);
            Locale.setDefault(locale);
        }
    }

    // --- stacklang: raw reflection VM that bypasses all method checks ------------------------------------

    public void testStacklangIsForbidden() {
        assertBlocked("var r = stacklang { push new java.util.Scanner(\"\".getClass().forName(\"java.lang.Runtime\")" +
                ".getMethod(\"getRuntime\").invoke(null).exec(\"whoami\").getInputStream()).nextLine() };\n" +
                "return {msg: {whoami: r}};");
        assertBlocked("stacklang { push 1 + 2 }");
        assertBlocked("stacklang { ldtype java.lang.Integer; push 'java.specification.version'; invoke getInteger }");
        TimeZone tz = TimeZone.getDefault();
        try {
            assertBlocked("stacklang { ldtype java.util.TimeZone; push java.util.TimeZone.getTimeZone('Pacific/Kiritimati'); invoke setDefault }");
            assertEquals(tz, TimeZone.getDefault());
        } finally {
            TimeZone.setDefault(tz);
        }
    }

    public void testSystemPropertiesCannotBeRead() {
        assertBlocked("Integer.getInteger('java.specification.version')");
        assertBlocked("Long.getLong('java.specification.version')");
        assertBlocked("Boolean.getBoolean('java.awt.headless')");
    }

    // --- unbounded recursion -------------------------------------------------------------------------------

    public void testStackOverflowIsReportedAsScriptError() {
        try {
            executeScript("function f(n) { return f(n + 1); } f(0)", new HashMap<>(), new ExecutionContext(parserConfig));
            fail("Should throw ScriptStackOverflowException");
        } catch (ScriptStackOverflowException e) {
            assertTrue(e.getMessage().contains("Maximum call stack size exceeded"));
        }
    }

    public void testStackOverflowInsideCalledMethodIsReportedAsStackOverflow() throws Exception {
        // The matcher of the JDK recurses for every character of such a match. On a thread with a small stack
        // it runs out inside String.matches, and the error reaches the engine as the cause of a failed call.
        Map<String, Object> vars = new HashMap<>();
        vars.put("s", "a".repeat(200000));
        vars.put("p", "(?:a|b)*");
        for (String script : new String[]{"s.matches(p)", "function f(x, y) { return x.matches(y); } var a = f('a', 'a'); f(s, p)"}) {
            java.util.concurrent.atomic.AtomicReference<Object> outcome = new java.util.concurrent.atomic.AtomicReference<>();
            Thread thread = new Thread(null, () -> {
                try {
                    outcome.set("returned " + executeScript(script, vars, new ExecutionContext(parserConfig)));
                } catch (Throwable e) {
                    outcome.set(e);
                }
            }, "small-stack", 512 * 1024);
            thread.start();
            thread.join(60_000);
            assertTrue(script + ": " + outcome.get(), outcome.get() instanceof ScriptStackOverflowException);
        }
    }

    public void testMaxStackDepthLimit() {
        ExecutionContext ctx = new ExecutionContext(parserConfig, -1, 10, 50);
        try {
            executeScript("function f(n) { return f(n + 1); } f(0)", new HashMap<>(), ctx);
            fail("Should throw ScriptStackOverflowException");
        } catch (ScriptStackOverflowException e) {
            assertTrue(e.getMessage().contains("Maximum call stack depth exceeded (51 > 50)"));
        }
        // Recursion within the limit still works, and the context is reusable after the failure.
        Object res = executeScript("function f(n) { if (n == 0) { return 0; } return 1 + f(n - 1); } f(49)", new HashMap<>(), ctx);
        assertEquals(49, res);
    }

    // --- legitimate usage still works ----------------------------------------------------------------------

    public void testLegitimateCallsStillWork() {
        assertEquals(5, executeScript("Math.abs(-5)"));
        assertEquals(42, executeScript("Integer.parseInt('42')"));
        assertEquals("ABC", executeScript("'abc'.toUpperCase()"));
        assertEquals("a", executeScript("new java.util.Scanner('a b').next()"));
        assertEquals("UTC", executeScript("java.util.TimeZone.getTimeZone('UTC').getID()"));
        assertEquals(Integer.MAX_VALUE, executeScript("Integer.MAX_VALUE"));
        assertEquals(3, executeScript("var m = {a: 1, b: 2, c: 3}; m.size()"));
        assertEquals("abc", executeScript("var s = 'abc'; s.toString()"));
        assertEquals(6, executeScript("function f(n) { if (n == 0) { return 0; } return n + f(n - 1); } f(3)"));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static final String[] REFUSALS = {"unable to resolve method", "unresolvable property or identifier", "could not resolve class",
            "is forbidden", "; in class: ", "could not access"};

    private void assertBlocked(String script) {
        assertBlocked(script, new HashMap<>());
    }

    private void assertBlocked(String script, Map<String, Object> vars) {
        Object result;
        try {
            result = executeScript(script, vars, new ExecutionContext(parserConfig));
        } catch (RuntimeException refused) {
            // Refused by the sandbox, not failed for some other reason. A refusal is reported by the engine itself,
            // as a member or class that cannot be resolved or as something forbidden; what ran and failed has the
            // error of the code that ran as its cause.
            String message = String.valueOf(refused.getMessage());
            boolean refusal = false;
            for (String wording : REFUSALS) {
                refusal |= message.contains(wording);
            }
            for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
                refusal &= cause instanceof PropertyAccessException || cause instanceof CompileException
                        || cause instanceof ClassNotFoundException || cause instanceof NoSuchMethodException;
            }
            assertTrue("failed for another reason: " + refused.getClass().getName() + ": " + message + "\n" + script,
                    refusal && !message.contains("parent is null"));
            return;
        }
        fail("Sandbox should block script but it returned: " + result + "\n" + script);
    }

    private Object executeScript(String script) {
        return executeScript(script, new HashMap<>(), new ExecutionContext(parserConfig));
    }

    private Object executeScript(String script, Map<String, Object> vars, ExecutionContext ctx) {
        Serializable compiled = compileExpression(script, new ParserContext());
        return executeTbExpression(compiled, ctx, vars);
    }
}
