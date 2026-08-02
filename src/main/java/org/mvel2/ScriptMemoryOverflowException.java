/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2;

/**
 * @author Mike Brock .
 */
public class ScriptMemoryOverflowException extends ScriptRuntimeException {

    public ScriptMemoryOverflowException(String message) {
        super(message);
    }
}
