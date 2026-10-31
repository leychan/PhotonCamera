package com.hinnka.mycamera.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import com.hinnka.mycamera.gallery.MediaData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SystemGalleryLauncher {
    suspend fun openPhoto(context: Context, photo: MediaData?): Boolean {
        if (photo == null || photo.uri.scheme != "content" || photo.uri.authority != MediaStore.AUTHORITY) {
            return false
        }
        val intent = withContext(Dispatchers.IO) {
            try {
                // 只预览已发布、未删除的成片，不用旧照片替代仍在处理中的缩略图。
                val available = context.contentResolver.query(
                    photo.uri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED),
                    null, null, null,
                )?.use { it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == 0 } == true
                if (!available) return@withContext null
                val galleryPackage = resolveGalleryPackage(context) ?: return@withContext null
                val mimeType = context.contentResolver.getType(photo.uri) ?: photo.mimeType
                    ?: if (photo.isVideo) "video/*" else "image/*"
                // 图库首页只用于确定接收应用；VIEW 携带照片 URI，避免进入首页或本应用自身。
                Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, mimeType)
                    .setPackage(galleryPackage)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PLog.w("SystemGalleryLauncher", "Unable to prepare system photo preview", error)
                null
            }
        } ?: return false
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            PLog.w("SystemGalleryLauncher", "System gallery does not support this photo", error)
            false
        } catch (error: SecurityException) {
            PLog.w("SystemGalleryLauncher", "Unable to grant system photo preview access", error)
            false
        }
    }

    private fun resolveGalleryPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        val manager = context.packageManager
        val candidates = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.exported && it.packageName != context.packageName }
            .distinctBy { it.packageName }
        val preferred = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return candidates.firstOrNull { it.packageName == preferred }?.packageName
            ?: candidates.firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }?.packageName
            ?: candidates.singleOrNull()?.packageName
    }
}
