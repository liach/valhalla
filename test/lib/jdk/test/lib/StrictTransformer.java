/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.test.lib;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import jdk.test.lib.compiler.InMemoryJavaCompiler;

import static java.lang.classfile.ClassFile.ACC_STRICT;

/**
 * Compile a java file with InMemoryJavaCompiler, and then modify the resulting
 * class file to include strict modifier and null restriction attributes.
 */
public class StrictTransformer {
    public static final String TEST_SRC = System.getProperty("test.src", "").trim();
    public static final String TEST_CLASSES = System.getProperty("test.classes", "").trim();
    private static final ClassDesc CD_Strict = ClassDesc.of("jdk.internal.vm.annotation.Strict");
    private static final ClassDesc CD_NullRestricted = ClassDesc.of("jdk.internal.vm.annotation.NullRestricted");

    static final class NullRestrictedAttribute extends CustomAttribute<NullRestrictedAttribute> {
        static final NullRestrictedAttribute INSTANCE = new NullRestrictedAttribute();
        private enum Mapper implements AttributeMapper<NullRestrictedAttribute> {
            NullRestricted; // overrides name()

            @Override
            public NullRestrictedAttribute readAttribute(AttributedElement enclosing, ClassReader cf, int pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void writeAttribute(BufWriter buf, NullRestrictedAttribute attr) {
                buf.writeIndex(attr.attributeName());
                buf.writeInt(0);
            }

            @Override
            public AttributeStability stability() {
                return AttributeStability.STATELESS;
            }
        }

        private NullRestrictedAttribute() {
            super(Mapper.NullRestricted);
        }
    }

    /**
     * @param args source and destination
     * @throws IOException if an I/O error occurs
     */
    public static void main(String[] args) throws IOException {
        String fileName;
        if (args.length != 1 || !(fileName = args[0]).endsWith(".java")) {
            throw new IllegalArgumentException("Unexpected number of arguments for file copy");
        }
        Path src = Path.of(TEST_SRC, fileName);
        if (!Files.exists(src)) {
            throw new IOException("Can't find source " + src.toAbsolutePath().normalize());
        }
        String className = fileName.substring(0, fileName.length() - 5);
        var release = Runtime.version().feature();
        var classes = InMemoryJavaCompiler.compile(Map.of(className, Files.readString(src)),
                "--source", String.valueOf(release),
                "--enable-preview",
                "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED");
        Files.createDirectories(Path.of(TEST_CLASSES));
        for (var entry : classes.entrySet()) {
            dumpClass(entry.getKey(), entry.getValue());
        }
    }

    private static void dumpClass(String name, byte[] rawBytes) throws IOException {
        var cm = ClassFile.of().parse(rawBytes);
        var transformed = ClassFile.of().transformClass(cm, ClassTransform.transformingFields(new FieldTransform() {
            int oldAccessFlags;
            boolean nullRestricted;
            boolean strict;

            @Override
            public void accept(FieldBuilder builder, FieldElement element) {
                if (element instanceof AccessFlags af) {
                    oldAccessFlags = af.flagsMask();
                    return;
                }
                builder.with(element);
                if (element instanceof RuntimeVisibleAnnotationsAttribute rvaa) {
                    for (var anno : rvaa.annotations()) {
                        var descString = anno.className();
                        if (descString.equalsString(CD_Strict.descriptorString())) {
                            strict = true;
                        } else if (descString.equalsString(CD_NullRestricted.descriptorString())) {
                            nullRestricted = true;
                        }
                    }
                }
            }

            @Override
            public void atEnd(FieldBuilder builder) {
                if (strict) {
                    oldAccessFlags |= ACC_STRICT;
                }
                builder.withFlags(oldAccessFlags);
                if (nullRestricted) {
                    builder.with(NullRestrictedAttribute.INSTANCE);
                }
            }
        }));

        Path dst = Path.of(TEST_CLASSES, name + ".class");
        Files.write(dst, transformed);
    }
}
