package com.niftysignal.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Small, isolated document-picker activity for the Testing Lab.
 * The selected workbook is read locally and is never uploaded or copied to the live feed.
 */
class OptionChainImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, "Select NIFTY Option Chain XLSX")
        }
        try {
            startActivityForResult(picker, REQUEST_PICK)
        } catch (_: Exception) {
            TestingLabScreen.displayOptionChainAnalysis("Could not open Android file picker. Please try again.")
            finish()
        }
    }

    @Deprecated("Uses the platform document picker result for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        if (resultCode != RESULT_OK || data?.data == null) {
            finish()
            return
        }
        val report = try {
            val uri = data.data!!
            val analysis = contentResolver.openInputStream(uri)?.use { OptionChainXlsxParser.parse(it) }
                ?: throw IllegalArgumentException("The selected file could not be opened.")
            "FILE: ${uri.lastPathSegment ?: "Selected XLSX"}\n\n" + analysis.asReport()
        } catch (error: Exception) {
            "IMPORT FAILED\n${error.message ?: "The workbook could not be read."}\n\nPlease select an .xlsx NIFTY option-chain workbook."
        }
        TestingLabScreen.displayOptionChainAnalysis(report)
        finish()
    }

    companion object {
        private const val REQUEST_PICK = 7412
    }
}
