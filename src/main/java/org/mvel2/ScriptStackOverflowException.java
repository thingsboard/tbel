// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2;

/**
 * Thrown when a script exceeds the allowed function call depth or exhausts the JVM thread stack.
 * Extends {@link ScriptMemoryOverflowException} (stack is memory) so it propagates unwrapped
 * through the same paths as other resource-limit violations.
 */
public class ScriptStackOverflowException extends ScriptMemoryOverflowException {

    public ScriptStackOverflowException(String message) {
        super(message);
    }
}
