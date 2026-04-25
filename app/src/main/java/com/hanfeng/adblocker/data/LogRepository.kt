package com.HanFeng.data

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogRepository {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "adblock.log"

    fun append(context: Context, message: String) {
        val file = logFile(context)
        file.parentFile?.mkdirs()
        file.appendText("${System.currentTimeMillis()} $message\n")
    }

    fun exportZip(context: Context): android.net.Uri {
        val shareDir = File(context.cacheDir, "shared")
        shareDir.mkdirs()
        val zipFile = File(shareDir, "hanfeng-adblock-logs.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            val entryFile = logFile(context)
            if (entryFile.exists()) {
                zip.putNextEntry(ZipEntry(entryFile.name))
                zip.write(entryFile.readBytes())
                zip.closeEntry()
            }
            val suspiciousDomainReport = RuleRepository.exportUnknownVendorSamples(context)
            zip.putNextEntry(ZipEntry(String.format(Locale.US, "%s", "suspicious-domains.txt")))
            zip.write(suspiciousDomainReport.toByteArray())
            zip.closeEntry()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    private fun logFile(context: Context): File = File(File(context.filesDir, LOG_DIR), LOG_FILE)
}
