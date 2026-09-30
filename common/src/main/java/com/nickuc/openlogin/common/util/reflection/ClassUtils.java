/*
 * The MIT License (MIT)
 *
 * Copyright © 2026 - OpenLogin Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.nickuc.openlogin.common.util.reflection;

import javax.annotation.Nullable;

public class ClassUtils {

    public static boolean exists(String clasz, String... classes) {
        return getClass(clasz, classes) != null;
    }

    public static boolean methodExists(Class<?> clasz, String methodName, Class<?>... classes) {
        try {
            clasz.getDeclaredMethod(methodName, classes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static boolean fieldExists(Class<?> clasz, String fieldName) {
        try {
            clasz.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @Nullable
    public static Class<?> getClass(String clasz, String... classes) {
        try {
            return Class.forName(clasz);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            for (String tmp : classes) {
                try {
                    return Class.forName(tmp);
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
            return null;
        }
    }
}
