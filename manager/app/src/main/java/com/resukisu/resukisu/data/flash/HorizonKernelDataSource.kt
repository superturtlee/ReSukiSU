package com.resukisu.resukisu.data.flash

import android.content.Context
import android.net.Uri
import android.util.Log
import com.resukisu.resukisu.R
import com.resukisu.resukisu.data.shell.KsuCliRepository
import com.resukisu.resukisu.domain.model.FlashProgress
import com.resukisu.resukisu.utils.AssetsUtil
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * @author ShirkNeko
 * @date 2025/5/31.
 */
class HorizonKernelState {
    private val _state = MutableStateFlow(FlashProgress())
    private val fullLogs = ConcurrentLinkedQueue<String>()
    val state: StateFlow<FlashProgress> = _state.asStateFlow()

    fun updateProgress(progress: Float) {
        _state.update { it.copy(progress = progress) }
    }

    fun updateStep(step: String) {
        _state.update { it.copy(currentStep = step) }
    }

    fun addLog(log: String) {
        fullLogs.add(log)
        _state.update {
            it.copy(logs = it.logs + log)
        }
    }

    fun addConsoleLog(log: String) {
        fullLogs.add(log)
    }

    fun getFullLog(): String = fullLogs.joinToString("\n")

    fun setError(error: String) {
        _state.update { it.copy(isFlashing = false, error = error) }
    }

    fun startFlashing() {
        fullLogs.clear()
        _state.update {
            it.copy(
                isFlashing = true,
                isCompleted = false,
                progress = 0f,
                currentStep = "",
                logs = emptyList(),
                error = ""
            )
        }
    }

    fun completeFlashing() {
        _state.update {
            it.copy(
                isFlashing = false,
                isCompleted = true,
                progress = 1f
            )
        }
    }

    fun reset() {
        fullLogs.clear()
        _state.value = FlashProgress()
    }
}

class HorizonKernelWorker(
    private val context: Context,
    private val state: HorizonKernelState,
    private val ksuCliRepository: KsuCliRepository,
    private val slot: String? = null,
    private val kpmPatchEnabled: Boolean = false,
    private val kpmUndoPatch: Boolean = false
) : Thread() {
    var uri: Uri? = null
    private val workDir = "${context.cacheDir.absolutePath}/kpm_work"

    override fun run() {
        state.startFlashing()
        state.updateStep(context.getString(R.string.horizon_preparing))

        val zipFile = File(context.cacheDir, "anykernel3.zip")
        try {
            if (!ksuCliRepository.rootAvailable()) {
                state.setError(context.getString(R.string.root_required))
                return
            }

            state.updateStep(context.getString(R.string.horizon_copying_files))
            state.updateProgress(0.2f)
            copyToCache(zipFile)

            // KPM 修补(fork 特性)
            if (kpmPatchEnabled || kpmUndoPatch) {
                state.updateStep(context.getString(R.string.kpm_preparing_tools))
                state.updateProgress(0.5f)
                prepareKpmTools()

                state.updateStep(
                    if (kpmUndoPatch) context.getString(R.string.kpm_undoing_patch)
                    else context.getString(R.string.kpm_applying_patch)
                )
                state.updateProgress(0.55f)
                performKpmPatch(zipFile)
            }

            state.updateStep(context.getString(R.string.horizon_flashing))
            state.updateProgress(0.7f)
            val succeeded = ksuCliRepository.flashAnyKernel(
                zipFile = zipFile,
                slot = slot,
                onStdout = ::handleOutput,
                onStderr = ::handleConsoleOutput
            )
            if (!succeeded) {
                state.setError(context.getString(R.string.flash_failed_message))
                return
            }

            runCatching { ksuCliRepository.install() }.onFailure { error ->
                Log.w(TAG, "Failed to refresh ksud after a successful kernel flash", error)
            }
            state.updateStep(context.getString(R.string.horizon_flash_complete_status))
            state.completeFlashing()
        } catch (error: Exception) {
            state.setError(
                error.message ?: context.getString(R.string.horizon_unknown_error)
            )
        } finally {
            if (zipFile.exists()) {
                zipFile.delete()
            }
        }
    }

    private fun copyToCache(zipFile: File) {
        zipFile.delete()
        val source = uri
            ?: throw IOException(context.getString(R.string.horizon_copy_failed))
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException(context.getString(R.string.horizon_copy_failed))
        input.use {
            zipFile.outputStream().use { output ->
                it.copyTo(output)
            }
        }
        if (!zipFile.isFile) {
            throw IOException(context.getString(R.string.horizon_copy_failed))
        }
    }

    private fun handleOutput(line: String) {
        Log.i(TAG, line)
        state.addLog(line)

        when {
            line.contains("extracting", ignoreCase = true) -> {
                state.updateProgress(0.75f)
            }

            line.contains("installing", ignoreCase = true) -> {
                state.updateProgress(0.85f)
            }

            line.contains("complete", ignoreCase = true) -> {
                state.updateProgress(0.95f)
            }
        }
    }

    private fun handleConsoleOutput(line: String) {
        Log.i(TAG, line)
        state.addConsoleLog(line)
    }

    private fun prepareKpmTools() {
        File(workDir).mkdirs()

        val kptoolsPath = "$workDir/kptools"
        val kpimgPath = "$workDir/kpimg"

        AssetsUtil.exportFiles(context, "kptools", kptoolsPath)
        if (!File(kptoolsPath).exists()) {
            throw IOException("Local kptools file extraction failed")
        }

        AssetsUtil.exportFiles(context, "kpimg", kpimgPath)
        if (!File(kpimgPath).exists()) {
            throw IOException("Local kpimg file extraction failed")
        }

        runCommand("chmod a+rx $kptoolsPath")
    }

    /**
     * 执行KPM修补操作
     */
    private fun performKpmPatch(zipFile: File) {
        try {
            // 创建临时解压目录
            val extractDir = "$workDir/extracted"
            File(extractDir).mkdirs()

            // 解压压缩包到临时目录
            val unzipResult = runCommand("cd $extractDir && unzip -o \"${zipFile.absolutePath}\"")
            if (unzipResult != 0) {
                throw IOException(context.getString(R.string.kpm_extract_zip_failed))
            }

            // 查找Image文件
            val findImageResult = runCommandGetOutput("find $extractDir -name '*Image*' -type f")
            if (findImageResult.isBlank()) {
                throw IOException(context.getString(R.string.kpm_image_file_not_found))
            }

            val imageFile = findImageResult.lines().first().trim()
            val imageDir = File(imageFile).parent
            val imageName = File(imageFile).name

            state.addLog(context.getString(R.string.kpm_found_image_file, imageFile))

            // 复制KPM工具到Image文件所在目录
            runCommand("cp $workDir/kptools $imageDir/")
            runCommand("cp $workDir/kpimg $imageDir/")

            // 执行KPM修补命令
            val patchCommand = if (kpmUndoPatch) {
                "cd $imageDir && chmod a+rx kptools && ./kptools -u -s 123 -i $imageName -k kpimg -o oImage && mv oImage $imageName"
            } else {
                "cd $imageDir && chmod a+rx kptools && ./kptools -p -s 123 -i $imageName -k kpimg -o oImage && mv oImage $imageName"
            }

            val patchResult = runCommand(patchCommand)
            if (patchResult != 0) {
                throw IOException(
                    if (kpmUndoPatch) context.getString(R.string.kpm_undo_patch_failed)
                    else context.getString(R.string.kpm_patch_failed)
                )
            }

            state.addLog(
                if (kpmUndoPatch) context.getString(R.string.kpm_undo_patch_success)
                else context.getString(R.string.kpm_patch_success)
            )

            // 清理KPM工具文件
            runCommand("rm -f $imageDir/kptools $imageDir/kpimg $imageDir/oImage")

            // 重新打包ZIP文件
            val patchedFilePath = "$workDir/patched_${zipFile.name}"

            repackZipFolder(extractDir, patchedFilePath)

            // 替换原始文件
            runCommand("mv \"$patchedFilePath\" \"${zipFile.absolutePath}\"")

            state.addLog(context.getString(R.string.kpm_file_repacked))
        } catch (e: Exception) {
            state.addLog(context.getString(R.string.kpm_patch_operation_failed, e.message))
            throw e
        } finally {
            // 清理临时文件
            runCommand("rm -rf $workDir")
        }
    }

    private fun repackZipFolder(sourceDir: String, zipFilePath: String) {
        try {
            val buffer = ByteArray(1024)
            val sourceFolder = File(sourceDir)

            FileOutputStream(zipFilePath).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    sourceFolder.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(sourceFolder).path
                            val zipEntry = ZipEntry(relativePath)
                            zos.putNextEntry(zipEntry)

                            file.inputStream().use { fis ->
                                var length: Int
                                while (fis.read(buffer).also { length = it } > 0) {
                                    zos.write(buffer, 0, length)
                                }
                            }

                            zos.closeEntry()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to create zip file: ${e.message}", e)
        }
    }

    private fun runCommand(cmd: String): Int {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))

        return try {
            process.waitFor()
        } finally {
            process.destroy()
        }
    }

    private fun runCommandGetOutput(cmd: String): String {
        return Shell.cmd(cmd).exec().out.joinToString("\n").trim()
    }

    private companion object {
        const val TAG = "HorizonKernelWorker"
    }
}
