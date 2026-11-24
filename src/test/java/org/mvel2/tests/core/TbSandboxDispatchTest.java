// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.ExecutionContext;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedClassLoader;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.util.ParseTools;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * The sandbox method policy is applied to the method that is actually invoked, wherever it is resolved:
 * <ul>
 * <li>a method found on the runtime class of the receiver is judged as the public method it implements, so the
 * objects the JDK returns for public types stay usable;</li>
 * <li>a cached call site that meets a receiver of another class resolves the method again, and that method is
 * subject to the same policy as on first use.</li>
 * </ul>
 */
public class TbSandboxDispatchTest extends TestCase {

    private static final String UTC = "java.util.TimeZone.getTimeZone(\"UTC\")";

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

    // --- public methods of JDK implementation objects ------------------------------------------------------

    public void testPublicMethodsWorkOnValuePassedToFunction() {
        assertEquals(0, executeScript("function off(tz, ts) { return tz.getOffset(ts); } off(" + UTC + ", 0)"));
        assertEquals(0, executeScript("function f(tz) { return tz.getRawOffset(); } f(" + UTC + ")"));
        assertEquals(false, executeScript("function f(tz) { return tz.useDaylightTime(); } f(" + UTC + ")"));
        assertEquals("UTC", executeScript("function f(tz) { return tz.getID(); } f(" + UTC + ")"));
        assertEquals("UTC", executeScript("function f(tz) { return tz.ID; } f(" + UTC + ")"));
        assertTrue(String.valueOf(executeScript("function f(tz) { return tz.toString(); } f(" + UTC + ")")).contains("UTC"));
        assertEquals(TimeZone.getTimeZone("UTC").hashCode(), executeScript("function f(tz) { return tz.hashCode(); } f(" + UTC + ")"));
    }

    public void testSameResultWithAndWithoutFunction() {
        assertEquals(executeScript(UTC + ".getOffset(0)"),
                executeScript("function off(tz, ts) { return tz.getOffset(ts); } off(" + UTC + ", 0)"));
    }

    public void testForbiddenMethodsStayForbiddenOnValuePassedToFunction() {
        // each of these is refused by the policy itself; without it they would fail later, for another reason
        assertMethodRefused("function f(tz) { return tz.getClass(); } f(" + UTC + ")");
        assertMethodRefused("function f(tz) { return tz.wait(1); } f(" + UTC + ")");
        assertMethodRefused("function f(tz) { tz.notifyAll(); return 1; } f(" + UTC + ")");
        // public in the implementation class only: not part of java.util.TimeZone
        assertMethodRefused("function f(tz) { return tz.isDirty(); } f(" + UTC + ")");
        assertMethodRefused("function f(tz) { return tz.getLastRuleInstance(); } f(java.util.TimeZone.getTimeZone(\"Europe/Kyiv\"))");
        assertRefused("function f(tz) { return tz.dirty; } f(" + UTC + ")", "could not access: dirty");
    }

    public void testPublicPropertiesWorkOnValuePassedToFunction() {
        assertEquals(0, executeScript("function f(tz) { return tz.rawOffset; } f(" + UTC + ")"));
        assertEquals(0, executeScript("function f(tz) { return tz.DSTSavings; } f(" + UTC + ")"));
        assertEquals(5, executeScript("function f(tz) { tz.rawOffset = 5; return tz.getRawOffset(); } f(" + UTC + ")"));
        assertEquals("x", executeScript("function f(tz) { tz.ID = 'x'; return tz.ID; } f(" + UTC + ")"));
    }

    public void testPolicyOnResolvedAndInvokedMethod() throws Exception {
        Class<?> zoneInfo = TimeZone.getTimeZone("UTC").getClass();
        assertTrue("test needs a JDK implementation class", zoneInfo.getName().startsWith("sun."));

        Method resolved = zoneInfo.getMethod("getOffset", long.class);
        Method target = TimeZone.class.getMethod("getOffset", long.class);
        assertEquals(zoneInfo, resolved.getDeclaringClass());

        // judged on its own, a method of an implementation class is forbidden
        assertTrue(SandboxedClassLoader.isMethodForbidden(resolved));
        // as the implementation of a public method it is allowed
        assertFalse(SandboxedClassLoader.isMethodForbidden(resolved, target));
        assertFalse(SandboxedClassLoader.isMethodForbidden(target, target));

        // no public declaration to go through
        int implementationOnly = 0;
        for (Method method : zoneInfo.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Method widened = ParseTools.getWidenedTarget(method);
            if (widened.equals(method)) {
                implementationOnly++;
                assertTrue("must be forbidden: " + method, SandboxedClassLoader.isMethodForbidden(method, widened));
            }
        }
        assertTrue("implementation-only methods were inspected", implementationOnly > 0);

        // a forbidden type stays forbidden even when the method is declared by an allowed interface
        assertTrue(SandboxedClassLoader.isMethodForbidden(Thread.class.getMethod("run"), Runnable.class.getMethod("run")));
        assertTrue(SandboxedClassLoader.isMethodForbidden(Class.class.getMethod("describeConstable"),
                java.lang.constant.Constable.class.getMethod("describeConstable")));
        // a forbidden target stays forbidden whatever it was resolved from
        assertTrue(SandboxedClassLoader.isMethodForbidden(zoneInfo.getMethod("getClass"), Object.class.getMethod("getClass")));
        assertTrue(SandboxedClassLoader.isMethodForbidden(Bean.class.getMethod("getName"), Thread.class.getMethod("getName")));

        assertTrue(SandboxedClassLoader.isMethodForbidden(null, target));
        assertTrue(SandboxedClassLoader.isMethodForbidden(resolved, null));

        // judged on its own means: invoked exactly as resolved
        assertEquals(SandboxedClassLoader.isMethodForbidden(target), SandboxedClassLoader.isMethodForbidden(target, target));
        assertEquals(SandboxedClassLoader.isMethodForbidden(resolved), SandboxedClassLoader.isMethodForbidden(resolved, resolved));
    }

    public void testPolicyForEveryImplementationPackage() throws Exception {
        // one implementation class per package prefix, each overriding a public method of an allowed type
        String[] implementationClasses = {
                TimeZone.getTimeZone("UTC").getClass().getName(),   // sun.
                "jdk.internal.math.FDBigInteger",                   // jdk.internal.
                "com.sun.crypto.provider.DHPublicKey"};             // com.sun.
        java.util.Set<String> covered = new java.util.TreeSet<>();
        for (String className : implementationClasses) {
            Class<?> cls;
            try {
                cls = Class.forName(className, false, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                continue; // not in this JDK
            }
            Method resolved = null;
            for (Method method : cls.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("toString") && method.getParameterCount() == 0) {
                    resolved = method;
                }
            }
            if (resolved == null) {
                continue;
            }
            Method target = ParseTools.getWidenedTarget(cls, resolved);
            assertEquals(Object.class, target.getDeclaringClass());
            assertTrue("on its own: " + resolved, SandboxedClassLoader.isMethodForbidden(resolved));
            assertFalse("through the public type: " + resolved, SandboxedClassLoader.isMethodForbidden(resolved, target));
            covered.add(className.startsWith("jdk.internal.") ? "jdk.internal." : className.startsWith("com.sun.") ? "com.sun." : "sun.");
        }
        assertTrue("covered: " + covered, covered.contains("sun."));
        assertTrue("covered: " + covered, covered.contains("jdk.internal."));
        assertTrue("covered: " + covered, covered.contains("com.sun."));
    }

    // --- cached call sites ---------------------------------------------------------------------------------

    public void testCallSiteWarmedOnAllowedReceiverRefusesForbiddenMethod() {
        Map<String, Object> vars = receivers();
        assertEquals("bean", executeScript("function n(x) { return x.getName(); } n(bean)", vars));

        assertBlocked("function n(x) { return x.getName(); } n(bean); n(thread)", vars);
        assertBlocked("function n(x) { return x.getName(); } n(bean); n(String)", vars);
        assertBlocked("function n(x) { return x.getName(); } n(bean); n(bean); n(bean); n(thread)", vars);
        assertBlocked("var r = null; foreach (x : [bean, thread]) { r = x.getName(); } r", vars);
    }

    public void testGetterCallSiteWarmedOnAllowedReceiverRefusesForbiddenMethod() {
        Map<String, Object> vars = receivers();
        assertEquals("bean", executeScript("function n(x) { return x.name; } n(bean)", vars));

        assertBlocked("function n(x) { return x.name; } n(bean); n(thread)", vars);
        assertBlocked("var r = null; foreach (x : [bean, thread]) { r = x.name; } r", vars);
    }

    public void testCallSiteWithArgumentsWarmedOnAllowedReceiverRefusesForbiddenMethod() {
        Map<String, Object> vars = receivers();
        assertEquals("bean", executeScript("function n(x) { x.setName('bean'); return x.getName(); } n(bean)", vars));

        String threadName = Thread.currentThread().getName();
        assertBlocked("function n(x) { x.setName('changed'); return 1; } n(bean); n(thread)", vars);
        assertBlocked("function n(x, v) { x.setName(v); return 1; } n(bean, 'bean'); n(thread, 'changed')", vars);
        assertEquals(threadName, Thread.currentThread().getName());
    }

    // --- cached call sites whose cached method also exists on the new receiver ---------------------------------

    public void testCallSiteCachedOnPublicTypeMethodRefusesForbiddenReceiver() {
        // refused on first use ...
        assertBlocked("function d(x) { return \"\" + x.describeConstable(); } d(java.util.ArrayList)");
        // ... and equally when the call site was first used with an allowed receiver of the same public type
        assertMethodRefused("function d(x) { return \"\" + x.describeConstable(); } d(\"abc\"); d(java.util.ArrayList)");
        assertMethodRefused("function d(x) { return x.toString(); } d(\"abc\"); d(java.util.ArrayList)");
    }

    public void testCallSiteCachedOnInterfaceMethodRefusesForbiddenReceiver() {
        Map<String, Object> vars = receivers();
        NamedThread namedThread = new NamedThread();
        vars.put("namedThread", namedThread);
        vars.put("named", new NamedBean());
        assertEquals("named", executeScript("function n(x) { return x.getName(); } n(named)", vars));

        // Named.getName() is cached; on the thread it would run Thread.getName()
        assertMethodRefused("function n(x) { return x.getName(); } n(named); n(namedThread)", vars);
        assertMethodRefused("function n(x) { return x.name; } n(named); n(namedThread)", vars);
        assertMethodRefused("function r(x) { x.run(); return 1; } r(named); r(namedThread)", vars);
        assertFalse(namedThread.ran);

        String before = namedThread.getName();
        assertMethodRefused("function s(x, v) { x.setName(v); return 1; } s(named, 'a'); s(namedThread, 'changed')", vars);
        assertMethodRefused("function s(x, v) { x.name = v; return 1; } s(named, 'a'); s(namedThread, 'changed')", vars);
        assertEquals(before, namedThread.getName());
    }

    public void testCachedCallSiteOnInterfaceMethodStillWorksForAllowedReceivers() {
        Map<String, Object> vars = receivers();
        vars.put("named", new NamedBean());
        vars.put("named2", new OtherNamedBean());
        assertEquals("named/other-named", executeScript("function n(x) { return x.getName(); } n(named) + '/' + n(named2)", vars));
        assertEquals("named/other-named", executeScript("function n(x) { return x.name; } n(named) + '/' + n(named2)", vars));
        assertEquals("b", executeScript("function s(x, v) { x.name = v; return x.name; } s(named, 'a'); s(named2, 'b')", vars));
        assertEquals("abc[1, 2]", executeScript("function d(x) { return x.toString(); } d(\"abc\") + d([1, 2])"));
        assertEquals("a1", executeScript("var m = {a: 1}; var r = null; foreach (e : m.entrySet()) { r = e.key + e.value; } r"));
    }

    // --- receiver that is a subtype of the declared type of the previous step -----------------------------------

    private static final String[] CHAINED_FORBIDDEN = {
            "holder.named.run(); 1",
            "holder.getNamed().run(); 1",
            "holder.named.getName()",
            "holder.getNamed().getName()",
            "holder.named.name",
            "holder.getNamed().name",
            "holder.named.setName('changed'); 1",
            "holder.getNamed().setName('changed'); 1",
            "holder.named.name = 'changed'; 1",
            "holder.getNamed().name = 'changed'; 1"};

    public void testChainedCallOnForbiddenReceiverIsRefusedOnFirstAndLaterExecutions() {
        NamedThread namedThread = new NamedThread();
        String before = namedThread.getName();
        Map<String, Object> vars = new HashMap<>();
        vars.put("holder", new Holder(namedThread));

        for (String script : CHAINED_FORBIDDEN) {
            // a new call site each time: this is its first execution
            assertRefusedByPolicy(script, vars);
            // one compiled script executed repeatedly
            Serializable compiled = compileExpression(script, new ParserContext());
            for (int i = 0; i < 3; i++) {
                assertRefusedByPolicy(script, () -> executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>(vars)));
            }
            // call site warmed on an allowed receiver first (value-returning forms only: a void call is not a
            // valid statement inside a script function)
            if (!script.endsWith("; 1")) {
                Map<String, Object> both = new HashMap<>(vars);
                both.put("allowed", new Holder(new NamedBean()));
                assertRefusedByPolicy("function f(holder) { return " + script + "; } f(allowed); f(holder)", both);
            }
        }
        assertFalse(namedThread.ran);
        assertEquals(before, namedThread.getName());
    }

    public void testInterpretedChainedCallOnForbiddenReceiverIsRefusedOnEveryExecution() {
        NamedThread namedThread = new NamedThread();
        String before = namedThread.getName();
        Map<String, Object> vars = new HashMap<>();
        vars.put("holder", new Holder(namedThread));

        for (String script : CHAINED_FORBIDDEN) {
            for (int i = 0; i < 3; i++) {
                assertRefusedByPolicy(script, () -> MVEL.eval(script, new HashMap<>(vars)));
            }
        }
        assertFalse(namedThread.ran);
        assertEquals(before, namedThread.getName());
    }

    public void testChainedCallOnAllowedReceiverStillWorks() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("holder", new Holder(new NamedBean()));

        String[] scripts = {"holder.named.getName()", "holder.getNamed().getName()", "holder.named.name", "holder.getNamed().name"};
        for (String script : scripts) {
            Serializable compiled = compileExpression(script, new ParserContext());
            for (int i = 0; i < 3; i++) {
                assertEquals(script, "named", executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>(vars)));
                assertEquals(script, "named", MVEL.eval(script, new HashMap<>(vars)));
            }
        }
        assertEquals("a", executeScript("holder.named.setName('a'); holder.named.name", vars));
        assertEquals("b", executeScript("holder.getNamed().name = 'b'; holder.getNamed().getName()", vars));
        assertEquals(1, executeScript("holder.named.run(); 1", vars));
        assertEquals("c", MVEL.eval("holder.named.setName('c'); holder.named.name", new HashMap<>(vars)));
        assertEquals("d", MVEL.eval("holder.getNamed().name = 'd'; holder.getNamed().getName()", new HashMap<>(vars)));
    }

    // --- accessors a public bean gets from a non-public type ---------------------------------------------------

    public void testAccessorsDeclaredByNonPublicInterfaceStillWork() {
        assertAccessorsWork(new VisibleBean());
    }

    public void testAccessorsInheritedFromNonPublicBaseClassStillWork() {
        assertAccessorsWork(new VisibleChild());
    }

    private void assertAccessorsWork(Object bean) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("b", bean);
        // compiled, repeated execution of the same call sites
        Serializable compiled = compileExpression("b.name = 'x'; var r = b.name; b.name = 'y'; r + b.name", new ParserContext());
        for (int i = 0; i < 3; i++) {
            assertEquals("xy", executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>(vars)));
        }
        assertEquals("z", executeScript("function f(b) { b.name = 'z'; return b.name; } f(b)", vars));
        // interpreted
        for (int i = 0; i < 3; i++) {
            assertEquals("i", MVEL.eval("b.name = 'i'; b.name", new HashMap<>(vars)));
        }
    }

    public void testFirstUseAndCachedCallSiteAgree() {
        // first use of the call site on the implementation object
        Object firstUse = executeScript("function ro(z) { return z.getRawOffset(); } ro(" + UTC + ")");
        // call site first used with another TimeZone class, then the same receiver
        Object cached = executeScript("function ro(z) { return z.getRawOffset(); } "
                + "var a = ro(new java.util.SimpleTimeZone(3600000, \"X\")); var b = ro(" + UTC + "); \"\" + a + \"/\" + b");
        assertEquals(0, firstUse);
        assertEquals("3600000/0", cached);

        Map<String, Object> vars = receivers();
        assertBlocked("function n(x) { return x.getName(); } n(thread)", vars);
        assertBlocked("function n(x) { return x.getName(); } n(bean); n(thread)", vars);
    }

    public void testCachedCallSiteStillWorksAcrossAllowedReceivers() {
        Map<String, Object> vars = receivers();
        assertEquals(3, executeScript("function n(x) { return x.size(); } n([1]) + n({a: 1, b: 2})"));
        assertEquals("bean/other", executeScript("function n(x) { return x.getName(); } n(bean) + '/' + n(other)", vars));
        assertEquals("bean/other", executeScript("function n(x) { return x.name; } n(bean) + '/' + n(other)", vars));
        assertEquals(4, executeScript("function n(x) { return x.length(); } n('abc') + n([1])"));
    }

    public void testRedispatchCheck() throws Exception {
        Class<?> zoneInfo = TimeZone.getTimeZone("UTC").getClass();

        assertEquals(TimeZone.class.getMethod("getOffset", long.class),
                ParserContext.checkRedispatch(zoneInfo.getMethod("getOffset", long.class)));
        assertEquals(Bean.class.getMethod("getName"), ParserContext.checkRedispatch(Bean.class.getMethod("getName")));
        assertNull(ParserContext.checkRedispatch(null));
        try {
            ParserContext.checkRedispatch(Thread.class.getMethod("getName"));
            fail("a forbidden method must be refused");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("java.lang.Thread.getName"));
        }

        // nothing changes without the sandbox
        ParserContext.disableSandboxedMode();
        Method threadGetName = Thread.class.getMethod("getName");
        assertSame(threadGetName, ParserContext.checkRedispatch(threadGetName));
        Method implementation = zoneInfo.getMethod("getOffset", long.class);
        assertSame(implementation, ParserContext.checkRedispatch(implementation));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    public static class Bean {
        private String name;

        public Bean(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public interface Named extends Runnable {
        String getName();

        void setName(String name);
    }

    public static class NamedBean implements Named {
        private String name = "named";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void run() {
        }
    }

    public static class OtherNamedBean implements Named {
        private String name = "other-named";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void run() {
        }
    }

    /** A forbidden type that happens to implement an allowed interface with its inherited methods. */
    public static class NamedThread extends Thread implements Named {
        volatile boolean ran;

        @Override
        public void run() {
            ran = true;
        }
    }

    public static class Holder {
        private final Named named;

        public Holder(Named named) {
            this.named = named;
        }

        /** The declared type is the allowed interface, whatever the object is. */
        public Named getNamed() {
            return named;
        }
    }

    interface HiddenNamed {
        String getName();

        void setName(String name);
    }

    public static class VisibleBean implements HiddenNamed {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class HiddenBase {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class VisibleChild extends HiddenBase {
    }

    public static class OtherBean {
        public String getName() {
            return "other";
        }
    }

    private static Map<String, Object> receivers() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("bean", new Bean("bean"));
        vars.put("other", new OtherBean());
        vars.put("thread", Thread.currentThread());
        return vars;
    }

    private void assertBlocked(String script) {
        assertBlocked(script, new HashMap<>());
    }

    private void assertBlocked(String script, Map<String, Object> vars) {
        Object result;
        try {
            result = executeScript(script, vars);
        } catch (RuntimeException expected) {
            return;
        }
        fail("Sandbox should block script but it returned: " + result + "\n" + script);
    }

    /** The script must be refused by the method policy itself, not fail for an unrelated reason. */
    private void assertMethodRefused(String script) {
        assertRefused(script, new HashMap<>(), "unable to resolve method");
    }

    private void assertMethodRefused(String script, Map<String, Object> vars) {
        assertRefused(script, vars, "unable to resolve method");
    }

    private void assertRefused(String script, String message) {
        assertRefused(script, new HashMap<>(), message);
    }

    private void assertRefused(String script, Map<String, Object> vars, String message) {
        try {
            Object result = executeScript(script, vars);
            fail("Sandbox should block script but it returned: " + result + "\n" + script);
        } catch (RuntimeException e) {
            assertTrue(script + "\nfailed with: " + e, String.valueOf(e.getMessage()).contains(message));
        }
    }

    /**
     * Refused by the method policy itself: for a call "unable to resolve method", for a property the
     * corresponding "could not access" message.
     */
    private void assertRefusedByPolicy(String script, Map<String, Object> vars) {
        assertRefusedByPolicy(script, () -> executeScript(script, vars));
    }

    private void assertRefusedByPolicy(String script, java.util.function.Supplier<Object> execution) {
        try {
            Object result = execution.get();
            fail("Sandbox should block script but it returned: " + result + "\n" + script);
        } catch (RuntimeException e) {
            // an enclosing accessor may wrap the refusal of a later step of the chain
            boolean refused = false;
            for (Throwable t = e; t != null && !refused; t = t.getCause()) {
                String message = String.valueOf(t.getMessage());
                refused = message.contains("unable to resolve method")
                        || message.contains("could not access: name") || message.contains("could not access property")
                        || message.contains("could not access/write property");
            }
            assertTrue(script + "\nfailed with: " + e, refused);
        }
    }

    private Object executeScript(String script) {
        return executeScript(script, new HashMap<>());
    }

    private Object executeScript(String script, Map<String, Object> vars) {
        Serializable compiled = compileExpression(script, new ParserContext());
        return executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>(vars));
    }
}
