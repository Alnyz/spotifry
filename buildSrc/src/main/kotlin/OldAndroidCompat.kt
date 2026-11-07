import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The YouTube reader (NewPipe) calls URLEncoder.encode / URLDecoder.decode with a Charset, which Android only has from
 * version 13 and the build tool does not cover. On older phones that crashed every search. This points those two calls
 * at [com.spotifry.music.Compat], which does the same with the charset's name.
 */
abstract class OldAndroidCompat : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className.startsWith("org.schabi.newpipe.")

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        val charsetCall = opcode == Opcodes.INVOKESTATIC && descriptor == "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
                        when {
                            charsetCall && owner == "java/net/URLEncoder" && name == "encode" ->
                                super.visitMethodInsn(opcode, "com/spotifry/music/Compat", "urlEncode", descriptor, false)
                            charsetCall && owner == "java/net/URLDecoder" && name == "decode" ->
                                super.visitMethodInsn(opcode, "com/spotifry/music/Compat", "urlDecode", descriptor, false)
                            else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
        }
}
