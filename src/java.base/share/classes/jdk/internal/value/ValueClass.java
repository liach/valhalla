/*
 * Copyright (c) 2023, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.value;

import jdk.internal.access.JavaLangReflectAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * Utilities to access
 */
public final class ValueClass {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final JavaLangReflectAccess JLRA = SharedSecrets.getJavaLangReflectAccess();

    /**
     * {@return true if the given {@code Class} object is implicitly constructible}
     */
    @Deprecated(forRemoval = true, since = "Valhalla")
    public static native boolean isImplicitlyConstructible(Class<?> cls);

    /**
     * {@return {@code CheckedType} representing the type of the given field}
     */
    public static CheckedType checkedType(Field f) {
        return JLRA.isNullRestrictedField(f) ? NullRestrictedCheckedType.of(f.getType())
                                             : NormalCheckedType.of(f.getType());
    }

    /**
     * {@return {@code CheckedType} representing the component type of the given array}
     */
    public static CheckedType componentCheckedType(Object array) {
        Class<?> componentType = array.getClass().getComponentType();
        return isNullRestrictedArray(array) ? NullRestrictedCheckedType.of(componentType)
                                            : NormalCheckedType.of(componentType);
    }

    /**
     * Allocate an array of a value class type with components that behave in
     * the same way as a {@link jdk.internal.vm.annotation.NullRestricted}
     * field.
     * <p>
     * Because these behaviors are not specified by Java SE, arrays created with
     * this method should only be used by internal JDK code for experimental
     * purposes and should not affect user-observable outcomes.
     *
     * @throws IllegalArgumentException if {@code componentType} is not a
     *         value class type or is not annotated with
     *         {@link jdk.internal.vm.annotation.ImplicitlyConstructible}
     */
    @SuppressWarnings("unchecked")
    public static Object[] newArrayInstance(CheckedType componentType, int length) {
        if (componentType instanceof NullRestrictedCheckedType) {
            return newNullRestrictedArray(componentType.boundingClass(), length);
        } else {
            return (Object[]) Array.newInstance(componentType.boundingClass(), length);
        }
    }

    // Temporary gap, to be removed when we have robust array construction
    private static final ClassValue<MethodHandle> DEFAULT_CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            MethodHandle mh;
            try {
                mh = SharedSecrets.getJavaLangInvokeAccess().findConstructor(type, MethodType.methodType(void.class));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException(ex);
            }
            if (mh == null)
                throw new IllegalArgumentException("No default constructor");
            return mh.asType(MethodType.genericMethodType(0));
        }
    };

    private static <T> T[] fillInArray(Object[] a, MethodHandle ctor) {
        for (int i = 0; i < a.length; i++) {
            try {
                a[i] = ctor.invokeExact();
            } catch (Throwable ex) {
                if (ex instanceof Error e) {
                    throw e;
                } else if (ex instanceof RuntimeException e) {
                    throw e;
                } else {
                    throw new UndeclaredThrowableException(ex);
                }
            }
        }

        UNSAFE.storeStoreFence(); // Final semantics?

        @SuppressWarnings("unchecked")
        T[] ret = (T[]) a;
        return ret;
    }

    /**
     * Allocate an array of a value class type with components that behave in
     * the same way as a {@link jdk.internal.vm.annotation.NullRestricted}
     * field.
     * <p>
     * Because these behaviors are not specified by Java SE, arrays created with
     * this method should only be used by internal JDK code for experimental
     * purposes and should not affect user-observable outcomes.
     *
     * @throws IllegalArgumentException if {@code componentType} does not have
     *         an accessible nullary constructor
     */
    public static <T> T[] newNullRestrictedArray(Class<T> componentType,
                                                 int length) {
        var ctor = DEFAULT_CONSTRUCTORS.get(componentType); // throw IAE early
        Object[] array = newNullRestrictedArray0(componentType, length);
        return fillInArray(array, ctor);
    }

    private static native Object[] newNullRestrictedArray0(Class<?> componentType,
                                                           int length);

    public static <T> T[] newNullRestrictedAtomicArray(Class<?> componentType,
                                                       int length) {
        var ctor = DEFAULT_CONSTRUCTORS.get(componentType); // throw IAE early
        Object[] array = newNullableAtomicArray0(componentType, length);
        return fillInArray(array, ctor);
    }

    private static native Object[] newNullRestrictedAtomicArray0(Class<?> componentType,
                                                                 int length);

    public static <T> T[] newNullableAtomicArray(Class<?> componentType,
                                                 int length) {
        // Default 0s from VM is fine
        @SuppressWarnings("unchecked")
        var ret = (T[]) newNullableAtomicArray0(componentType, length);
        return ret;
    }

    private static native Object[] newNullableAtomicArray0(Class<?> componentType,
                                                           int length);

    public static boolean isFlatArray(Object array) {
        // implicit null check
        var cl = array.getClass();
        return cl.isArray() && UNSAFE.isFlatArray(cl);
    }

    // TODO remove this
    private static native boolean isFlatArray0(Object array);

    /**
     * {@return true if the given array is a null-restricted array}
     */
    public static boolean isNullRestrictedArray(Object array) {
        // implicit null check
        return array.getClass().isArray() && isNullRestrictedArray0(array);
    }

    // TODO investigate move this to Unsafe?
    private static native boolean isNullRestrictedArray0(Object array);
}
