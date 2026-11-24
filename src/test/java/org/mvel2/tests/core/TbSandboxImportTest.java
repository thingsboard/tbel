// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.CompileException;
import org.mvel2.ExecutionContext;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.SandboxedParserConfiguration;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.mvel2.MVEL.compileExpression;
import static org.mvel2.MVEL.executeTbExpression;

/**
 * A sandboxed script must not be able to import anything: the set of visible classes and helpers is defined
 * by the embedding application only, and the parser configuration is shared by every script in the JVM.
 */
public class TbSandboxImportTest extends TestCase {

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

    public void testClassImportIsForbidden() {
        assertImportForbidden("import java.util.HashMap; var m = new HashMap(); m");
    }

    public void testStaticImportIsForbidden() {
        assertImportForbidden("import_static java.lang.Math.abs; abs(-1)");
    }

    public void testPackageImportIsForbidden() {
        assertImportForbidden("import java.util.*; var m = new HashMap(); m");
        assertImportForbidden("import java.lang.annotation.*; var r = \"\" + RetentionPolicy.RUNTIME; r");
    }

    public void testClassStaticsImportIsForbidden() {
        assertImportForbidden("import java.lang.Thread.*; var r = MAX_PRIORITY; r");
        assertImportForbidden("import java.lang.Integer.*; var r = MAX_VALUE; r");
    }

    public void testEnumConstantsImportIsForbidden() {
        assertImportForbidden("import java.lang.annotation.RetentionPolicy.*; var r = \"\" + RUNTIME; r");
        assertImportForbidden("import java.util.concurrent.TimeUnit.*; var r = \"\" + SECONDS; r");
    }

    public void testImportIsForbiddenInInterpretedMode() {
        Map<String, Object> importsBefore = new HashMap<>(parserConfig.getImports());
        try {
            Object result = MVEL.eval("import java.lang.Thread.*; MAX_PRIORITY", new HashMap<String, Object>());
            fail("Import should be forbidden but script returned: " + result);
        } catch (RuntimeException expected) {
            // expected
        }
        assertEquals(importsBefore, parserConfig.getImports());
    }

    public void testFailedImportLeavesNothingBehindForLaterScripts() {
        assertImportForbidden("import java.lang.Thread.*; var r = MAX_PRIORITY; r");
        assertImportForbidden("import java.lang.annotation.RetentionPolicy.*; var r = \"\" + RUNTIME; r");

        // a second, unrelated compilation in the same JVM must not see anything from the scripts above
        assertUnresolved("var r = MAX_PRIORITY; r");
        assertUnresolved("var r = \"\" + RUNTIME; r");
        assertFalse(parserConfig.hasImport("MAX_PRIORITY"));
        assertFalse(parserConfig.hasImport("RUNTIME"));
    }

    public void testImportRefusalIsAnErrorOfTheScript() {
        // An application tells a script that cannot be compiled from one that failed while it ran, by the type
        // of the error. The word in a place where it is no statement is a compile error as well, as it was.
        for (String script : new String[]{"import java.util.List; 1", "import java.util.*; 1", "import_static java.lang.Math.*; 1",
                "import_static java.lang.Math.abs; abs(-1)", "var m = {import: 1}; m"}) {
            try {
                Object result = executeScript(script);
                fail("Should not compile, but returned " + result + ": " + script);
            } catch (CompileException expected) {
                // the type is the point
            }
        }
    }

    public void testParserContextImportApiIsForbidden() {
        ParserContext pCtx = new ParserContext();
        try {
            pCtx.addPackageImport("java.lang.Thread");
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("Import is forbidden!"));
        }
        assertFalse(parserConfig.hasImport("MAX_PRIORITY"));
    }

    public void testImportsStillWorkWithoutSandbox() {
        ParserContext.disableSandboxedMode();
        assertEquals(Thread.MAX_PRIORITY,
                MVEL.executeExpression(compileExpression("import java.lang.Thread.*; MAX_PRIORITY", new ParserContext()), new HashMap<String, Object>()));
        assertEquals(1,
                MVEL.executeExpression(compileExpression("import java.util.HashMap; m = new HashMap(); m.put('a', 1); m.size()", new ParserContext()), new HashMap<String, Object>()));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void assertImportForbidden(String script) {
        Map<String, Object> importsBefore = new HashMap<>(parserConfig.getImports());
        Set<String> packageImportsBefore = parserConfig.getPackageImports() == null ? null : new HashSet<>(parserConfig.getPackageImports());
        try {
            Object result = executeScript(script);
            fail("Import should be forbidden but script returned: " + result + "\n" + script);
        } catch (CompileException e) {
            assertTrue(e.getMessage().contains("Import is forbidden!"));
        }
        assertEquals("imports must not change", importsBefore, parserConfig.getImports());
        assertEquals("package imports must not change", packageImportsBefore,
                parserConfig.getPackageImports() == null ? null : new HashSet<>(parserConfig.getPackageImports()));
    }

    private void assertUnresolved(String script) {
        Object result;
        try {
            result = executeScript(script);
        } catch (RuntimeException expected) {
            return;
        }
        fail("Script should not resolve but it returned: " + result + "\n" + script);
    }

    private Object executeScript(String script) {
        Serializable compiled = compileExpression(script, new ParserContext());
        return executeTbExpression(compiled, new ExecutionContext(parserConfig), new HashMap<>());
    }
}
