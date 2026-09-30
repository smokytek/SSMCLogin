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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class ReflectionUtils {

    public static <T> T invokeStatic(Method method, Object... params) {
        if (method != null) {
            try {
                return invokeStaticUnsafely(method, params);
            } catch (InvocationTargetException | IllegalAccessException | ClassCastException ignored) {
            }
        }
        return null;
    }

    public static <T> T invokeStaticUnsafely(Method method, Object... params) throws InvocationTargetException, IllegalAccessException, ClassCastException {
        return invokeUnsafely(method, null, params);
    }

    @Nullable
    public static <T> T invoke(Method method, Object instance, Object... params) {
        if (method != null) {
            try {
                return invokeUnsafely(method, instance, params);
            } catch (InvocationTargetException | IllegalAccessException | ClassCastException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T invokeUnsafely(Method method, Object instance, Object... params) throws InvocationTargetException, IllegalAccessException, ClassCastException {
        return (T) method.invoke(instance, params);
    }

    @Nullable
    public static Method getMethod(Class<?> clazz, String methodName, Class<?>... classes) {
        try {
            return getMethodUnsafely(clazz, methodName, classes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public static Method getMethodUnsafely(Class<?> clazz, String methodName, Class<?>... classes) throws NoSuchMethodException {
        Method method = clazz.getDeclaredMethod(methodName, classes);
        method.setAccessible(true);
        return method;
    }

    @Nullable
    public static Method getTypedMethod(Class<?> clazz, @Nullable String methodName, @Nullable Class<?> returnType, Class<?>... params) {
        for (Method method : clazz.getDeclaredMethods()) {
            if ((methodName == null || method.getName().equals(methodName)) &&
                    (returnType == null || method.getReturnType().equals(returnType)) &&
                    Arrays.equals(method.getParameterTypes(), params)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    @Nullable
    public static Field getField(Class<?> clazz, int index) {
        return getField(clazz, null, index);
    }

    @Nullable
    public static Field getField(Class<?> clazz, @Nullable Class<?> fieldType, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative index! " + index);
        }

        Field[] fields = clazz.getDeclaredFields();
        if (fields.length > 0) {
            int i = 0;
            for (Field field : fields) {
                if (fieldType != null) {
                    Class<?> currentFieldType = field.getType();
                    if (fieldType == Object.class) {
                        if (currentFieldType != Object.class) {
                            continue;
                        }
                    } else if (currentFieldType == Object.class || !fieldType.isAssignableFrom(currentFieldType)) {
                        continue;
                    }
                }

                if (i == index) {
                    field.setAccessible(true);
                    return field;
                }
                i++;
            }
        }
        return null;
    }

    @Nullable
    public static Field getField(Class<?> clazz, String... fields) {
        if (fields.length == 0) {
            throw new IllegalArgumentException("Fields cannot be empty!");
        }

        for (String field : fields) {
            try {
                return getFieldUnsafely(clazz, field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static Field getFieldUnsafely(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    @Nullable
    public static <T> Constructor<T> getConstructor(Class<?> clazz, Class<?>... classes) {
        try {
            return getConstructorUnsafely(clazz, classes);
        } catch (NoSuchMethodException | ClassCastException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Constructor<T> getConstructorUnsafely(Class<?> clazz, Class<?>... classes) throws NoSuchMethodException, ClassCastException {
        Constructor<?> constructor = clazz.getDeclaredConstructor(classes);
        constructor.setAccessible(true);
        return (Constructor<T>) constructor;
    }
}