package io.github.icycat.edgetts

import java.io.File

/** 先写入同目录临时文件再改名，避免读到写了一半的语音列表。 */
internal object AtomicFile {
    fun write(target: File, text: String) {
        val dir = target.parentFile ?: throw IllegalArgumentException("target has no parent")
        if (!dir.isDirectory && !dir.mkdirs()) {
            target.writeText(text)
            return
        }
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
