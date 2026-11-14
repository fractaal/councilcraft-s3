package startupfix;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Bytecode edits. Each returns null when the class doesn't contain what it expects. */
final class Patches implements Opcodes {
    private Patches() {}

    /**
     * Renames Bytecode.getOpcodeName(int) to getOpcodeName$uncached and adds a getOpcodeName(int) that keeps
     * the answers for opcodes 0..255 in an array. The answer for an opcode never changes.
     */
    static byte[] cacheOpcodeNames(byte[] bytes) {
        final String owner = Agent.BYTECODE, desc = "(I)Ljava/lang/String;", cache = "startupfix$names", orig = "getOpcodeName$uncached";
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] found = {false};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                if (name.equals("getOpcodeName") && d.equals(desc) && (access & ACC_STATIC) != 0) {
                    found[0] = true;
                    return super.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, orig, d, sig, ex);
                }
                return super.visitMethod(access, name, d, sig, ex);
            }

            @Override
            public void visitEnd() {
                if (found[0]) {
                    super.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, cache, "[Ljava/lang/String;", null, null).visitEnd();
                    MethodVisitor m = super.visitMethod(ACC_PUBLIC | ACC_STATIC, "getOpcodeName", desc, null, null);
                    Label ready = new Label(), miss = new Label(), uncached = new Label();
                    m.visitCode();
                    m.visitFieldInsn(GETSTATIC, owner, cache, "[Ljava/lang/String;");
                    m.visitJumpInsn(IFNONNULL, ready);
                    m.visitIntInsn(SIPUSH, 256);
                    m.visitTypeInsn(ANEWARRAY, "java/lang/String");
                    m.visitFieldInsn(PUTSTATIC, owner, cache, "[Ljava/lang/String;");
                    m.visitLabel(ready);
                    m.visitFrame(F_SAME, 0, null, 0, null);
                    m.visitVarInsn(ILOAD, 0);
                    m.visitJumpInsn(IFLT, uncached);
                    m.visitVarInsn(ILOAD, 0);
                    m.visitIntInsn(SIPUSH, 256);
                    m.visitJumpInsn(IF_ICMPGE, uncached);
                    m.visitFieldInsn(GETSTATIC, owner, cache, "[Ljava/lang/String;");
                    m.visitVarInsn(ILOAD, 0);
                    m.visitInsn(AALOAD);
                    m.visitJumpInsn(IFNULL, miss);
                    m.visitFieldInsn(GETSTATIC, owner, cache, "[Ljava/lang/String;");
                    m.visitVarInsn(ILOAD, 0);
                    m.visitInsn(AALOAD);
                    m.visitInsn(ARETURN);
                    m.visitLabel(miss);
                    m.visitFrame(F_SAME, 0, null, 0, null);
                    m.visitFieldInsn(GETSTATIC, owner, cache, "[Ljava/lang/String;");
                    m.visitVarInsn(ILOAD, 0);
                    m.visitVarInsn(ILOAD, 0);
                    m.visitMethodInsn(INVOKESTATIC, owner, orig, desc, false);
                    m.visitInsn(DUP_X2);
                    m.visitInsn(AASTORE);
                    m.visitInsn(ARETURN);
                    m.visitLabel(uncached);
                    m.visitFrame(F_SAME, 0, null, 0, null);
                    m.visitVarInsn(ILOAD, 0);
                    m.visitMethodInsn(INVOKESTATIC, owner, orig, desc, false);
                    m.visitInsn(ARETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return found[0] ? writer.toByteArray() : null;
    }

    /**
     * Wraps a private static method so it first asks a function the agent left in the system properties.
     * With {@code put} (RoxyFabricModReader.patchJar): return the function's answer if it has one, otherwise run
     * the original and hand its result to the second function. Without it (containsForgifiedFabricLoader):
     * return the function's Boolean if it has one, otherwise run the original.
     */
    static byte[] askAgentFirst(byte[] bytes, String owner, String name, String desc, String getKey, String putKey) {
        final String orig = name + "$uncached";
        final boolean returnsPath = desc.endsWith(")Ljava/nio/file/Path;");
        final int args = org.objectweb.asm.Type.getArgumentTypes(desc).length;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] found = {false};
        reader.accept(new ClassVisitor(ASM9, writer) {
            private String[] thrown;

            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                if (n.equals(name) && d.equals(desc) && (access & ACC_STATIC) != 0) {
                    found[0] = true;
                    thrown = ex;
                    return super.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, orig, d, sig, ex);
                }
                return super.visitMethod(access, n, d, sig, ex);
            }

            @Override
            public void visitEnd() {
                if (found[0]) {
                    MethodVisitor m = super.visitMethod(ACC_PRIVATE | ACC_STATIC, name, desc, null, thrown);
                    Label miss = new Label();
                    m.visitCode();
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                    m.visitLdcInsn(getKey);
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    m.visitTypeInsn(CHECKCAST, "java/util/function/Function");
                    m.visitVarInsn(ALOAD, 0);
                    m.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                    m.visitVarInsn(ASTORE, args);
                    m.visitVarInsn(ALOAD, args);
                    m.visitJumpInsn(IFNULL, miss);
                    m.visitVarInsn(ALOAD, args);
                    if (returnsPath) {
                        m.visitTypeInsn(CHECKCAST, "java/nio/file/Path");
                        m.visitInsn(ARETURN);
                    } else {
                        m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                        m.visitInsn(IRETURN);
                    }
                    m.visitLabel(miss);
                    m.visitFrame(F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null);
                    if (putKey != null) {
                        m.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                        m.visitLdcInsn(putKey);
                        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        m.visitTypeInsn(CHECKCAST, "java/util/function/BiFunction");
                        m.visitVarInsn(ALOAD, 0);
                    }
                    for (int i = 0; i < args; i++) m.visitVarInsn(ALOAD, i);
                    m.visitMethodInsn(INVOKESTATIC, owner, orig, desc, false);
                    if (putKey != null) {
                        m.visitMethodInsn(INVOKEINTERFACE, "java/util/function/BiFunction", "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                        m.visitTypeInsn(CHECKCAST, "java/nio/file/Path");
                    }
                    m.visitInsn(returnsPath ? ARETURN : IRETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return found[0] ? writer.toByteArray() : null;
    }

    /**
     * ClassTransformer.transform(byte[], String, String) becomes transform$uncached, and a new transform asks
     * the agent's cache first and tells it the result afterwards. See TransformCache.
     */
    static byte[] cacheTransform(byte[] bytes) {
        final String owner = Agent.CLASS_TRANSFORMER, name = "transform", orig = "transform$uncached";
        final String desc = "([BLjava/lang/String;Ljava/lang/String;)[B";
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final boolean[] found = {false};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                if (n.equals(name) && d.equals(desc) && (access & ACC_STATIC) == 0) {
                    found[0] = true;
                    return super.visitMethod(ACC_PRIVATE | ACC_SYNTHETIC, orig, d, sig, ex);
                }
                return super.visitMethod(access, n, d, sig, ex);
            }

            private void cacheCall(MethodVisitor m, boolean withResult) {
                m.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                m.visitLdcInsn(TransformCache.KEY);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                m.visitTypeInsn(CHECKCAST, "java/util/function/Function");
                m.visitInsn(withResult ? ICONST_5 : ICONST_4);
                m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                for (int i = 0; i < (withResult ? 5 : 4); i++) {
                    m.visitInsn(DUP);
                    m.visitInsn(ICONST_0 + i);
                    m.visitVarInsn(ALOAD, i);
                    m.visitInsn(AASTORE);
                }
                m.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            }

            @Override
            public void visitEnd() {
                if (found[0]) {
                    // locals: 0 this, 1 input, 2 name, 3 reason, 4 the cache's answer and then the result
                    MethodVisitor m = super.visitMethod(0, name, desc, null, null);
                    Label unchanged = new Label(), miss = new Label();
                    m.visitCode();
                    cacheCall(m, false);
                    m.visitVarInsn(ASTORE, 4);
                    m.visitVarInsn(ALOAD, 4);
                    m.visitJumpInsn(IFNULL, miss);
                    m.visitVarInsn(ALOAD, 4);
                    m.visitTypeInsn(INSTANCEOF, "[B");
                    m.visitJumpInsn(IFEQ, unchanged);
                    m.visitVarInsn(ALOAD, 4);
                    m.visitTypeInsn(CHECKCAST, "[B");
                    m.visitInsn(ARETURN);
                    m.visitLabel(unchanged);
                    m.visitFrame(F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null);
                    m.visitVarInsn(ALOAD, 1);
                    m.visitInsn(ARETURN);
                    m.visitLabel(miss);
                    m.visitFrame(F_SAME, 0, null, 0, null);
                    m.visitVarInsn(ALOAD, 0);
                    m.visitVarInsn(ALOAD, 1);
                    m.visitVarInsn(ALOAD, 2);
                    m.visitVarInsn(ALOAD, 3);
                    m.visitMethodInsn(INVOKEVIRTUAL, owner, orig, desc, false);
                    m.visitVarInsn(ASTORE, 4);
                    cacheCall(m, true);
                    m.visitInsn(POP);
                    m.visitVarInsn(ALOAD, 4);
                    m.visitTypeInsn(CHECKCAST, "[B");
                    m.visitInsn(ARETURN);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return found[0] ? writer.toByteArray() : null;
    }

    /**
     * Drippy's early loading window only learns its size from GLFW's resize callbacks, and those only run when
     * the main thread polls for events, which it doesn't do for the first seconds of startup. A compositor that
     * resizes the window right after it opens (any tiling one) leaves the picture drawn at the old size in a
     * corner until then. This makes drawFrame() ask GLFW for the window and framebuffer size on every frame.
     * It also hands the provider to the agent's banner (EarlyBanner), if there is one, when a frame is drawn.
     */
    static byte[] trackEarlyWindowSize(byte[] bytes, String owner) {
        ClassReader reader = new ClassReader(bytes);
        int[] seen = new int[1];
        reader.accept(new ClassVisitor(ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String n, String d, String sig, Object v) {
                if ((n.equals("windowWidth") || n.equals("windowHeight")) && d.equals("I")) seen[0]++;
                if (n.equals("window") && d.equals("J")) seen[0]++;
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                if ((n.equals("drawFrame") || n.equals("updateFramebufferFromWindow")) && d.equals("()V")) seen[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        if (seen[0] != 5) return null;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                MethodVisitor m = super.visitMethod(access, n, d, sig, ex);
                if (!n.equals("drawFrame") || !d.equals("()V")) return m;
                return new MethodVisitor(ASM9, m) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitVarInsn(ALOAD, 0);
                        visitMethodInsn(INVOKEVIRTUAL, owner, "startupfix$trackSize", "()V", false);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == RETURN) {
                            visitVarInsn(ALOAD, 0);
                            visitMethodInsn(INVOKEVIRTUAL, owner, "startupfix$frameDrawn", "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }

            @Override
            public void visitEnd() {
                MethodVisitor m = super.visitMethod(ACC_PRIVATE | ACC_SYNTHETIC, "startupfix$trackSize", "()V", null, null);
                m.visitCode();
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEVIRTUAL, owner, "updateFramebufferFromWindow", "()V", false);
                for (int local = 1; local <= 2; local++) {
                    m.visitInsn(ICONST_1);
                    m.visitIntInsn(NEWARRAY, T_INT);
                    m.visitVarInsn(ASTORE, local);
                }
                m.visitVarInsn(ALOAD, 0);
                m.visitFieldInsn(GETFIELD, owner, "window", "J");
                m.visitVarInsn(ALOAD, 1);
                m.visitVarInsn(ALOAD, 2);
                m.visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwGetWindowSize", "(J[I[I)V", false);
                for (int local = 1; local <= 2; local++) {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitInsn(ICONST_1);
                    m.visitVarInsn(ALOAD, local);
                    m.visitInsn(ICONST_0);
                    m.visitInsn(IALOAD);
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
                    m.visitFieldInsn(PUTFIELD, owner, local == 1 ? "windowWidth" : "windowHeight", "I");
                }
                m.visitInsn(RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();

                // Object hook = System.getProperties().get(KEY); if (hook instanceof Consumer) hook.accept(this);
                m = super.visitMethod(ACC_PRIVATE | ACC_SYNTHETIC, "startupfix$frameDrawn", "()V", null, null);
                m.visitCode();
                m.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                m.visitLdcInsn(EarlyBanner.KEY);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                m.visitVarInsn(ASTORE, 1);
                m.visitVarInsn(ALOAD, 1);
                m.visitTypeInsn(INSTANCEOF, "java/util/function/Consumer");
                Label none = new Label();
                m.visitJumpInsn(IFEQ, none);
                m.visitVarInsn(ALOAD, 1);
                m.visitTypeInsn(CHECKCAST, "java/util/function/Consumer");
                m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                m.visitLabel(none);
                m.visitFrame(F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null);
                m.visitInsn(RETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}
