// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.tests.core;

import junit.framework.TestCase;
import org.mvel2.MVEL;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.dynamic.DynamicOptimizer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test that post-increment expressions inside array/collection access
 * work correctly under the DynamicOptimizer (ASM JIT tenuring).
 *
 * Regression test for double-evaluation bug where i++ in input[i++]
 * could be evaluated twice when ASM optimization triggers or fails.
 */
public class DynamicOptimizerPostIncrementTest extends TestCase {

    /**
     * Verify that input[i++] in a loop produces correct results
     * when executed enough times to trigger DynamicOptimizer tenuring.
     * Before the fix, after the tenuring threshold was reached, the ASM
     * optimizer could double-evaluate i++, skipping bytes.
     */
    public void testPostIncrementInArrayAccessWithDynamicOptimizer() {
        int oldThreshold = DynamicOptimizer.tenuringThreshold;
        long oldTimeSpan = DynamicOptimizer.timeSpan;

        try {
            // Force DynamicOptimizer with low tenuring threshold
            DynamicOptimizer.tenuringThreshold = 1;
            DynamicOptimizer.timeSpan = 1000 * 60 * 60L; // 1 hour
            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC);

            String script =
                "var input = [0x02, 0x75, 45, 0x01, 0x75, 55, 0x03, 0x76, 75];\n" +
                "var result = [];\n" +
                "for (var i = 0; i < input.size;) {\n" +
                "    var channel_id = input[i++];\n" +
                "    var channel_type = input[i++];\n" +
                "    result.add(channel_id);\n" +
                "    result.add(channel_type);\n" +
                "    i += 1;\n" +
                "}\n" +
                "return result;";

            Serializable compiled = MVEL.compileExpression(script);

            // Execute many times to trigger tenuring (threshold is 1,
            // so optimization triggers on the 2nd call)
            for (int i = 0; i < 10; i++) {
                Object result = MVEL.executeExpression(compiled, new HashMap<>());
                assertNotNull("Run " + i + ": result should not be null", result);
                // Expected: [2, 117, 1, 117, 3, 118]
                // channel pairs: (0x02,0x75), (0x01,0x75), (0x03,0x76)
                assertEquals("Run " + i + ": wrong result",
                    "[2, 117, 1, 117, 3, 118]", result.toString());
            }
        } finally {
            DynamicOptimizer.tenuringThreshold = oldThreshold;
            DynamicOptimizer.timeSpan = oldTimeSpan;
            OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
        }
    }

    /**
     * Test that volatile defaultOptimizer ensures cross-thread visibility.
     * One thread sets SAFE_REFLECTIVE, worker threads must see it.
     */
    public void testOptimizerVisibilityAcrossThreads() throws Exception {
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);

        final int threadCount = 20;
        final int iterations = 100;
        final AtomicInteger errors = new AtomicInteger(0);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        var optimizer = OptimizerFactory.getDefaultAccessorCompiler();
                        if (optimizer instanceof DynamicOptimizer) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        assertEquals("Some threads saw DynamicOptimizer instead of SAFE_REFLECTIVE",
            0, errors.get());
    }
}
