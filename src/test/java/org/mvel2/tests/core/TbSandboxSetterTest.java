// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.ExecutionContext;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.util.MethodStub;
import org.mvel2.util.PropertyTools;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * A property assignment ({@code a.b = c}) calls a setter. In sandboxed mode it must obey the same rules as a
 * direct method call, and must not reach static setters or the helpers registered by the embedding application.
 */
public class TbSandboxSetterTest extends TestCase {

    private SandboxedParserConfiguration parserConfig;
    private Locale defaultLocale;
    private TimeZone defaultTimeZone;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        this.parserConfig = ParserContext.enableSandboxedMode();
        this.defaultLocale = Locale.getDefault();
        this.defaultTimeZone = TimeZone.getDefault();
        Bean.shared = "initial";
    }

    @Override
    protected void tearDown() throws Exception {
        ParserContext.disableSandboxedMode();
        Locale.setDefault(defaultLocale);
        TimeZone.setDefault(defaultTimeZone);
        super.tearDown();
    }

    // --- static setters ----------------------------------------------------------------------------------------

    public void testAssignmentCannotChangeDefaultLocale() {
        Locale other = defaultLocale.equals(Locale.CHINA) ? Locale.ITALY : Locale.CHINA;
        assertBlocked("java.util.Locale.ROOT.default = java.util.Locale." + (other == Locale.CHINA ? "CHINA" : "ITALY") + "; 1");
        assertBlocked("function f(l, v) { l.default = v; return 1; } f(java.util.Locale.ROOT, java.util.Locale.ITALY)");
        assertEquals(defaultLocale, Locale.getDefault());
    }

    public void testAssignmentCannotChangeDefaultTimeZone() {
        assertBlocked("java.util.TimeZone.getTimeZone(\"UTC\").default = java.util.TimeZone.getTimeZone(\"Pacific/Kiritimati\"); 1");
        assertBlocked("function f(z, v) { z.default = v; return 1; } "
                + "f(java.util.TimeZone.getTimeZone(\"UTC\"), java.util.TimeZone.getTimeZone(\"Pacific/Kiritimati\"))");
        assertEquals(defaultTimeZone, TimeZone.getDefault());
    }

    public void testAssignmentCannotCallStaticSetterOfRegisteredType() {
        parserConfig.registerDataType("Bean", Bean.class, val -> 8L);
        assertBlocked("var b = new Bean(); b.shared = 'changed'; 1");
        assertBlocked("function f(b) { b.shared = 'changed'; return 1; } f(new Bean())");
        assertEquals("initial", Bean.shared);
        // the explicit static call is a normal method call and is not affected
        assertEquals("called", executeScript("Bean.setShared('called'); Bean.getShared()"));
    }

    public void testInterpretedAssignmentCannotCallStaticSetter() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("b", new Bean());
        try {
            MVEL.eval("b.shared = 'changed'", vars);
            fail("static setter must not be reachable through an assignment");
        } catch (RuntimeException expected) {
            // expected
        }
        assertEquals("initial", Bean.shared);

        MVEL.eval("b.value = 'v'", vars);
        assertEquals("v", ((Bean) vars.get("b")).getValue());
    }

    public void testSetterLookupSkipsForbiddenSetters() {
        new ParserContext();
        assertNull(PropertyTools.getSetter(Locale.class, "default"));
        assertNull(PropertyTools.getSetter(TimeZone.class, "default", TimeZone.class));
        assertNull(PropertyTools.getSetter(Bean.class, "shared"));
        assertNull(PropertyTools.getSetter(MethodStub.class, "methodName"));
        assertNull(PropertyTools.getSetter(Thread.class, "name"));
        assertNotNull(PropertyTools.getSetter(Bean.class, "value"));
        assertNotNull(PropertyTools.getSetter(TimeZone.class, "ID", String.class));
    }

    // --- registered helpers ---------------------------------------------------------------------------------

    public void testAssignmentCannotRedirectRegisteredHelper() throws Exception {
        MethodStub lazy = new MethodStub(Math.class, "abs");
        MethodStub eager = new MethodStub(Math.class.getMethod("abs", int.class));
        parserConfig.addImport("absLazy", lazy);
        parserConfig.addImport("absEager", eager);

        assertBlocked("function f(s) { s.methodName = \"negateExact\"; return 1; } f((absLazy))");
        assertBlocked("function f(s) { s.methodName = \"negateExact\"; return 1; } f((absEager))");
        assertBlocked("function f(s) { s.classReference = String; return 1; } f((absLazy))");
        assertBlocked("(absLazy).setMethodName(\"negateExact\"); 1");

        // later scripts still get the registered method
        assertEquals(5, executeScript("absLazy(5)"));
        assertEquals(5, executeScript("absEager(5)"));
        assertEquals("abs", lazy.getMethodName());
        assertEquals(Math.class, lazy.getClassReference());
    }

    public void testRegisteredHelpersAreImmutableOnceConfigurationIsFrozen() throws Exception {
        MethodStub lazy = new MethodStub(Math.class, "abs");
        parserConfig.addImport("absLazy", lazy);
        assertFalse(lazy.isSealed());

        parserConfig.freeze();
        assertTrue(lazy.isSealed());
        assertEquals("abs", lazy.getMethod().getName());
        try {
            lazy.setMethodName("negateExact");
            fail("a registered stub must be read-only");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            lazy.setClassReference(String.class);
            fail("a registered stub must be read-only");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals(5, executeScript("absLazy(-5)"));
    }

    public void testHelperRegisteredWhileOtherThreadsRunScriptsIsImmutable() throws Exception {
        MethodStub early = new MethodStub(Math.class, "abs");
        parserConfig.addImport("absEarly", early);

        Thread other = new Thread(() -> executeScript("1 + 2"));
        other.start();
        other.join(10_000);
        assertTrue(early.isSealed());

        // the setup thread is still registering
        MethodStub late = new MethodStub(Math.class, "abs");
        parserConfig.addImport("absLate", late);
        assertTrue(late.isSealed());
        assertEquals("abs", late.getMethod().getName());

        assertBlocked("function f(s) { s.methodName = \"negateExact\"; return 1; } f((absLate))");
        assertEquals(10, executeScript("absEarly(-5) + absLate(-5)"));
    }

    public void testUnregisteredStubStaysMutable() {
        MethodStub stub = new MethodStub(Math.class, "abs");
        stub.setMethodName("negateExact");
        stub.setClassReference(StrictMath.class);
        assertEquals("negateExact", stub.getMethod().getName());
        assertEquals(StrictMath.class, stub.getMethod().getDeclaringClass());
    }

    // --- legitimate assignments still work -----------------------------------------------------------------

    public void testMapAndListAssignmentStillWork() {
        assertEquals("{a=1, b=2, default=3, methodName=q}",
                executeScript("var m = {a: 1}; m.b = 2; m.default = 3; m.methodName = 'q'; m").toString());
        assertEquals("[1, 5, 3]", executeScript("var l = [1, 2, 3]; l[1] = 5; l").toString());
        assertEquals("{a={b=7}}", executeScript("var m = {a: {b: 1}}; m.a.b = 7; m").toString());
    }

    public void testInstanceSetterAssignmentStillWorks() {
        parserConfig.registerDataType("Bean", Bean.class, val -> 8L);
        assertEquals("v", executeScript("var b = new Bean(); b.value = 'v'; b.getValue()"));
        assertEquals("v2", executeScript("function f(b) { b.value = 'v2'; return b.value; } f(new Bean())"));
        // same call site, repeated
        assertEquals("c", executeScript("var b = new Bean(); foreach (v : ['a', 'b', 'c']) { b.value = v; } b.value"));
        assertEquals("x", executeScript("function f(z) { z.ID = 'x'; return z.getID(); } f(java.util.TimeZone.getTimeZone('UTC'))"));
        assertEquals(5L, executeScript("function f(c) { c.timeInMillis = 5; return c.getTimeInMillis(); } f(java.util.Calendar.getInstance())"));
    }

    public void testSetterIsResolvedAgainForAnotherReceiverClass() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("a", new First());
        vars.put("b", new Second());
        // one place in the script that assigns the property, two receivers whose classes have nothing in common
        Serializable compiled = compileExpression("function s(x, v) { x.name = v; return x.name; } s(a, 'p') + s(b, 'q')", new ParserContext());
        assertEquals("pq", executeTbExpression(compiled, new ExecutionContext(parserConfig), vars));
        assertEquals("pq", executeTbExpression(compiled, new ExecutionContext(parserConfig), vars));
        // The setter that is found for the second receiver is checked like the first one was. The first receiver
        // takes any object, so the cached setter is not the one of a thread and the thread's is looked up.
        Thread thread = new Thread("before");
        vars.put("t", thread);
        vars.put("o", new Loose());
        try {
            Object result = executeTbExpression(compileExpression("function s(x, v) { x.name = v; return 1; } s(o, 'p'); s(t, 'changed')", new ParserContext()),
                    new ExecutionContext(parserConfig), vars);
            fail("Sandbox should block the setter of a thread but the script returned: " + result);
        } catch (RuntimeException expected) {
            // refused
        }
        assertEquals("before", thread.getName());
        // a static method is no setter of the second receiver, as it is none of a first one
        vars.put("h", new Holder());
        try {
            executeTbExpression(compileExpression("h.shared = 'x'", new ParserContext()), new ExecutionContext(parserConfig), vars);
            fail("a static setter was called");
        } catch (RuntimeException expected) {
            // refused
        }
        try {
            Object result = executeTbExpression(compileExpression("function s(x, v) { x.shared = v; return 1; } s(o, 'p'); s(h, 'changed')", new ParserContext()),
                    new ExecutionContext(parserConfig), vars);
            fail("a static setter was called for the second receiver: " + result);
        } catch (RuntimeException expected) {
            // refused
        }
        assertEquals("initial", Holder.shared);
    }

    public void testInheritedSetterThatNeedsConversionIsFoundOnce() {
        // The setter is declared by a superclass and the value has to be converted for it: after the first
        // assignment the accessor converts, it does not look the setter up again every time.
        Map<String, Object> vars = new HashMap<>();
        Counted counted = new Counted();
        vars.put("c", counted);
        Serializable compiled = compileExpression("var n = 0; for (var i = 0; i < 2000; i++) { c.count = '7'; n += c.count; } n", new ParserContext());
        assertEquals(14000, executeTbExpression(compiled, new ExecutionContext(parserConfig), vars));
        assertEquals(2000, counted.sets);
        // looking the setter up again for every assignment would make an assignment many times slower than a call
        long assign = time(compileExpression("for (var i = 0; i < 20000; i++) { c.count = '7'; } 1", new ParserContext()), vars);
        long call = time(compileExpression("for (var i = 0; i < 20000; i++) { c.setCount('7'); } 1", new ParserContext()), vars);
        assertTrue("assignment " + assign + " ms, call " + call + " ms", assign < 5 * call + 50);
    }

    public static class Loose {
        private Object name;
        private Object shared;

        public Object getName() {
            return name;
        }

        public void setName(Object name) {
            this.name = name;
        }

        public Object getShared() {
            return shared;
        }

        public void setShared(Object shared) {
            this.shared = shared;
        }
    }

    public static class Holder {
        static String shared = "initial";

        public static void setShared(String value) {
            shared = value;
        }
    }

    public static class CountedBase {
        int count;
        int sets;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
            this.sets++;
        }
    }

    public static class Counted extends CountedBase {
    }

    private long time(Serializable compiled, Map<String, Object> vars) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            executeTbExpression(compiled, new ExecutionContext(parserConfig), vars);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best / 1_000_000;
    }

    public static class First {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Second {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public void testStaticSetterAssignmentIsUnchangedWithoutSandbox() {
        ParserContext.disableSandboxedMode();
        Map<String, Object> vars = new HashMap<>();
        vars.put("b", new Bean());
        MVEL.executeExpression(compileExpression("b.shared = 'changed'", new ParserContext()), vars);
        assertEquals("changed", Bean.shared);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    public static class Bean {
        static String shared = "initial";

        private String value;

        public static void setShared(String shared) {
            Bean.shared = shared;
        }

        public static String getShared() {
            return shared;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
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
