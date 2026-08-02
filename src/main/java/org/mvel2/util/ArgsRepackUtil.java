/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2.util;

import org.mvel2.ExecutionContext;
import org.mvel2.execution.ExecutionArrayList;
import org.mvel2.execution.ExecutionHashMap;
import org.mvel2.execution.ExecutionLinkedHashSet;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArgsRepackUtil {

    public static Object repack(ExecutionContext ctx, Object value){
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            ExecutionArrayList list = new ExecutionArrayList(ctx);
            int size = Array.getLength(value);
            for (int i = 0; i < size; i++) {
                list.add(repack(ctx, Array.get(value, i)));
            }
            return list;
        } else if (value instanceof Map){
            Map src = (Map)value;
            ExecutionHashMap map = new ExecutionHashMap(src.size(), ctx);
            src.forEach((k,v) -> map.put(k, repack(ctx, v)));
            return map;
        } else if (value instanceof Set){
            ExecutionLinkedHashSet set = new ExecutionLinkedHashSet<>(ctx);
            for(Object o : (Set)value){
                set.add(repack(ctx, o));
            }
            return set;
        } else if (value instanceof Collection){
            ExecutionArrayList list = new ExecutionArrayList(ctx);
            for(Object o : (Collection)value){
                list.add(repack(ctx, o));
            }
            return list;
        } else {
            return value;
        }
    }

    public static Object unpack(Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            List list = new ArrayList();
            int size = Array.getLength(value);
            for (int i = 0; i < size; i++) {
                list.add(unpack(Array.get(value, i)));
            }
            return list;
        } else if (value instanceof Map){
            Map src = (Map)value;
            Map map = new LinkedHashMap(src.size());
            src.forEach((k,v) -> map.put(k, unpack(v)));
            return map;
        } else if (value instanceof Set){
            Set set = new LinkedHashSet();
            for(Object o : (Set)value){
                set.add(unpack(o));
            }
            return set;
        } else if (value instanceof Collection){
            List list = new ArrayList();
            for(Object o : (Collection)value){
                list.add(unpack(o));
            }
            return list;
        } else {
            return value;
        }
    }
}
