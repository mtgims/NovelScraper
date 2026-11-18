package com.novelscraper.app.tts

import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.Os
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The free memory on the NVIDIA card, from NVML, the management library every
 * NVIDIA driver installs. A call takes microseconds and creates no CUDA
 * context, so it can be asked before every sentence. The first card in NVML's
 * order is the one `nvidia-smi` lists first, which is the card [GpuVoice]
 * detected and CUDA's device 0 on a machine with one card.
 */
internal object CardMemory {
    private const val TAG = "CardMemory"

    @Suppress("FunctionName")
    private interface Nvml : Library {
        fun nvmlInit_v2(): Int
        fun nvmlDeviceGetHandleByIndex_v2(index: Int, device: PointerByReference): Int
        /** Fills total, free and used, in bytes (`nvmlMemory_t`). */
        fun nvmlDeviceGetMemoryInfo(device: Pointer, memory: LongArray): Int
    }

    private val nvml: Nvml? by lazy {
        // Some distributions ship only the versioned file, without the
        // development link the plain name needs.
        val names = if (Os.isWindows) listOf("nvml") else listOf("nvidia-ml", "libnvidia-ml.so.1")
        names.firstNotNullOfOrNull { name ->
            runCatching { Native.load(name, Nvml::class.java) }.getOrNull()
        }?.takeIf { it.nvmlInit_v2() == 0 }
            .also { if (it == null) Log.w(TAG, "NVML isn't available: the card's memory can't be watched") }
    }

    private val device: Pointer? by lazy {
        val lib = nvml ?: return@lazy null
        val ref = PointerByReference()
        if (lib.nvmlDeviceGetHandleByIndex_v2(0, ref) == 0) ref.value else null
    }

    /** Free bytes on the card, or null when the driver can't say. */
    fun freeBytes(): Long? {
        val lib = nvml ?: return null
        val dev = device ?: return null
        val info = LongArray(3)
        return runCatching { if (lib.nvmlDeviceGetMemoryInfo(dev, info) == 0) info[1] else null }.getOrNull()
    }
}
